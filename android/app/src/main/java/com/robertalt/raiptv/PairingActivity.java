package com.nenotv.player;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.*;
import android.view.Gravity;
import android.widget.*;
import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;
import com.nenotv.player.entitlement.EntitlementClient;
import com.nenotv.player.entitlement.PairingClient;
import java.util.Collections;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class PairingActivity extends Activity {
    interface Access {
        PairingClient.Session start()throws Exception;
        String status(PairingClient.Session session)throws Exception;
        void cancel(PairingClient.Session session)throws Exception;
    }
    interface BrowserOpener {void open(Activity activity,String url);}
    static BrowserOpener browserOpener=(activity,url)->activity.startActivity(new Intent(Intent.ACTION_VIEW,Uri.parse(url)));
    interface Factory {Access create(android.content.Context context);}
    static Factory factory=context->{
        PairingClient network=new PairingClient(context);
        return new Access(){
            public PairingClient.Session start()throws Exception{return network.start();}
            public String status(PairingClient.Session session)throws Exception{return network.status(session);}
            public void cancel(PairingClient.Session session)throws Exception{network.cancel(session);}
        };
    };
    final Handler handler=new Handler(Looper.getMainLooper());
    final ExecutorService worker=Executors.newSingleThreadExecutor();
    Access client;PairingClient.Session session;
    TextView code,status;ImageView qr;Button retry,open;
    boolean resumed,busy,complete,destroyed,openedWebsite,browserHandoff;
    int generation;
    Bitmap bitmap;
    final Runnable poll=()->pollStatus();

    int dp(int value){return Math.round(value*getResources().getDisplayMetrics().density);}
    String text(String key){return UiText.t(this,key);}
    TextView label(String value,int size){TextView view=new TextView(this);view.setText(value);view.setTextSize(size);view.setTextColor(0xFFF7F8FA);view.setGravity(Gravity.CENTER);return view;}
    Button button(String key){Button view=new Button(this);view.setText(text(key));view.setAllCaps(false);view.setTextColor(0xFFF7F8FA);view.setBackgroundTintList(android.content.res.ColorStateList.valueOf(0xFF1B2028));view.setMinHeight(dp(52));return view;}

    @Override public void onCreate(Bundle saved){
        super.onCreate(saved);client=factory.create(this);
        ScrollView scroll=new ScrollView(this);scroll.setBackgroundColor(0xFF07090D);
        LinearLayout box=new LinearLayout(this);box.setOrientation(LinearLayout.VERTICAL);box.setGravity(Gravity.CENTER_HORIZONTAL);box.setPadding(dp(18),dp(18),dp(18),dp(24));scroll.addView(box);
        TextView title=label(text("link_my_nenotv"),24);box.addView(title,new LinearLayout.LayoutParams(-1,-2));
        qr=new ImageView(this);qr.setContentDescription(text("pair_qr"));qr.setVisibility(android.view.View.GONE);
        LinearLayout.LayoutParams imageParams=new LinearLayout.LayoutParams(dp(220),dp(220));imageParams.topMargin=dp(18);imageParams.bottomMargin=dp(12);box.addView(qr,imageParams);
        TextView instructions=label("nl".equals(com.nenotv.player.storage.SettingsStore.language(this))?"Scan deze QR met uw telefooncamera. Of vul de code hieronder in op Mijn SunnyIPTV.":"de".equals(com.nenotv.player.storage.SettingsStore.language(this))?"Scanne den QR-Code mit deiner Handykamera oder gib den Code in Mein SunnyIPTV ein.":"Scan this QR with your phone camera, or enter the code in My SunnyIPTV.",18);box.addView(instructions,new LinearLayout.LayoutParams(-1,-2));
        code=label("",48);code.setSaveEnabled(false);box.addView(code,new LinearLayout.LayoutParams(-1,-2));
        status=label(text("checking_status"),16);status.setPadding(0,dp(12),0,dp(12));status.setAccessibilityLiveRegion(android.view.View.ACCESSIBILITY_LIVE_REGION_POLITE);box.addView(status,new LinearLayout.LayoutParams(-1,-2));
        open=button("open_my_nenotv");open.setText("nl".equals(com.nenotv.player.storage.SettingsStore.language(this))?"Koppel dit apparaat":"de".equals(com.nenotv.player.storage.SettingsStore.language(this))?"Dieses Gerät verbinden":"Link this device");open.setEnabled(false);open.setOnClickListener(v->{if(session!=null)try{browserHandoff=true;browserOpener.open(this,session.url);}catch(Exception e){status.setText(text("server_unavailable"));}});box.addView(open,new LinearLayout.LayoutParams(-1,-2));
        retry=button("pair_new_code");retry.setOnClickListener(v->startPairing());box.addView(retry,new LinearLayout.LayoutParams(-1,-2));
        Button close=button("close");close.setOnClickListener(v->finish());box.addView(close,new LinearLayout.LayoutParams(-1,-2));
        setContentView(scroll);ScreenInsets.browsing(this);UiText.applyDirection(this);
    }

    boolean backgroundAllowed(){return browserHandoff||getIntent().getBooleanExtra("auto_web",false);}

    @Override protected void onResume(){
        super.onResume();resumed=true;
        if(complete)return;
        if(session!=null)handler.post(poll);
        else if(!busy)startPairing();
    }
    @Override protected void onPause(){resumed=false;if(!backgroundAllowed())handler.removeCallbacks(poll);super.onPause();}

    static Bitmap qrBitmap(String url)throws Exception{
        BitMatrix matrix=new QRCodeWriter().encode(url,BarcodeFormat.QR_CODE,512,512,Collections.singletonMap(EncodeHintType.MARGIN,4));
        int[] pixels=new int[512*512];
        for(int y=0;y<512;y++)for(int x=0;x<512;x++)pixels[y*512+x]=matrix.get(x,y)?0xFF000000:0xFFFFFFFF;
        return Bitmap.createBitmap(pixels,512,512,Bitmap.Config.ARGB_8888);
    }

    void clearCode(){
        qr.setImageDrawable(null);qr.setVisibility(android.view.View.GONE);code.setText("");
        if(bitmap!=null){bitmap.recycle();bitmap=null;}
        open.setEnabled(false);
    }
    void startPairing(){
        if(busy||destroyed)return;
        handler.removeCallbacks(poll);generation++;int current=generation;
        PairingClient.Session previous=session;session=null;complete=false;busy=true;
        clearCode();retry.setEnabled(false);status.setText(text("checking_status"));
        worker.execute(()->{
            if(previous!=null)try{client.cancel(previous);}catch(Exception ignored){}
            try{
                PairingClient.Session created=client.start();Bitmap picture=qrBitmap(created.url);
                runOnUiThread(()->{
                    if(destroyed||current!=generation){picture.recycle();return;}
                    session=created;bitmap=picture;busy=false;qr.setImageBitmap(picture);qr.setVisibility(android.view.View.VISIBLE);
                    code.setText(created.displayCode());status.setText(text("pair_waiting"));open.setEnabled(true);retry.setEnabled(true);
                    handler.postDelayed(()->{if(!destroyed&&!complete&&current==generation)expire();},Math.max(0,created.deadline-SystemClock.elapsedRealtime()));
                    if(getIntent().getBooleanExtra("auto_web",false)&&!openedWebsite){openedWebsite=true;try{browserOpener.open(this,created.url);}catch(Exception ignored){/* Android TV retains QR and the short code. */}}
                    if(resumed||backgroundAllowed())handler.postDelayed(poll,created.pollSeconds*1000L);
                });
            }catch(Exception error){runOnUiThread(()->{if(destroyed||current!=generation)return;busy=false;retry.setEnabled(true);status.setText(failureMessage(error));});}
        });
    }

    void pollStatus(){
        if((!resumed&&!backgroundAllowed())||destroyed||busy||complete||session==null)return;
        PairingClient.Session active=session;int current=generation;
        if(active.expired()){expire();return;}
        busy=true;retry.setEnabled(false);
        worker.execute(()->{
            try{
                String state=client.status(active);
                if("complete".equals(state)&&new com.nenotv.player.storage.EntitlementStore(this).isPro()){
                    try{new com.nenotv.player.entitlement.SourceSyncClient(this).pull();}catch(Exception ignored){/* Source retrieval can retry when the source screen resumes. */}
                }
                runOnUiThread(()->{
                    if(destroyed||current!=generation)return;
                    busy=false;retry.setEnabled(true);
                    if("complete".equals(state)){complete=true;clearCode();status.setText(text("pair_complete"));retry.setVisibility(android.view.View.GONE);ProModuleInstaller.syncEntitlement(this);if(getIntent().getBooleanExtra("setup",false)){setResult(RESULT_OK);finish();}else if(!new com.nenotv.player.storage.SecureProfileStore(this).exists()){startActivity(new Intent(this,ProfileActivity.class).putExtra("website_first",true));finish();}}
                    else if("expired".equals(state)||"cancelled".equals(state))expire();
                    else{status.setText(text("pair_waiting"));schedule(active);}
                });
            }catch(Exception error){runOnUiThread(()->{
                if(destroyed||current!=generation)return;
                busy=false;retry.setEnabled(true);status.setText(failureMessage(error));
                // Network failures may hide a completed bind, so status polling remains idempotent.
                if(!(error instanceof EntitlementClient.ServiceException))schedule(active);
            });}
        });
    }
    void schedule(PairingClient.Session active){if((resumed||backgroundAllowed())&&!active.expired())handler.postDelayed(poll,active.pollSeconds*1000L);else if(active.expired())expire();}
    void expire(){busy=false;session=null;clearCode();retry.setEnabled(true);status.setText(text("pair_expired"));}
    String failureMessage(Exception error){
        if(error instanceof EntitlementClient.ServiceException){
            String reason=((EntitlementClient.ServiceException)error).code;
            if("pro_not_live".equals(reason)||"pro_inactive".equals(reason))return text("pair_unavailable");
            if("device_limit".equals(reason))return text("pair_device_limit");
        }
        return text("server_unavailable");
    }
    @Override protected void onDestroy(){
        destroyed=true;generation++;handler.removeCallbacksAndMessages(null);clearCode();
        PairingClient.Session active=session;session=null;
        if(active!=null)worker.execute(()->{try{client.cancel(active);}catch(Exception ignored){}});
        worker.shutdown();super.onDestroy();
    }
}
