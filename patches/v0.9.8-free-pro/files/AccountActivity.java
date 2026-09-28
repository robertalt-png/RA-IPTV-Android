package com.robertalt.raiptv;

import android.app.*;
import android.os.*;
import android.content.*;
import android.graphics.Typeface;
import android.net.Uri;
import android.view.*;
import android.widget.*;
import com.robertalt.raiptv.entitlement.EntitlementClient;
import com.robertalt.raiptv.storage.EntitlementStore;
import java.util.concurrent.*;

public class AccountActivity extends Activity {
    LinearLayout box;
    TextView statusText,detailText,deviceText,serverText;
    EditText email,order;
    Button claim,refresh;
    ExecutorService exec=Executors.newSingleThreadExecutor();
    EntitlementStore ent;

    int dp(int v){return Math.round(v*getResources().getDisplayMetrics().density);}
    String T(String k){return UiText.t(this,k);}
    TextView t(String s,int z){TextView v=new TextView(this);v.setText(s);v.setTextColor(0xFFF7F8FA);v.setTextSize(z);return v;}
    Button b(String s){Button v=new Button(this);v.setText(s);v.setAllCaps(false);v.setTextColor(0xFFF7F8FA);v.setBackgroundTintList(android.content.res.ColorStateList.valueOf(0xFF1B2028));return v;}

    @Override public void onCreate(Bundle x){
        super.onCreate(x);
        ent=new EntitlementStore(this);
        build();
        UiText.applyDirection(this);
        handleIntent(getIntent());
    }
    @Override protected void onNewIntent(Intent i){super.onNewIntent(i);setIntent(i);handleIntent(i);}
    @Override protected void onResume(){super.onResume();refreshUi();}

    void build(){
        ScrollView sv=new ScrollView(this);
        sv.setBackgroundColor(0xFF07090D);
        box=new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(18),dp(18),dp(18),dp(34));
        sv.addView(box);

        LinearLayout h=new LinearLayout(this);
        h.setGravity(Gravity.CENTER_VERTICAL);
        TextView title=t("NenoTV",28);
        title.setTypeface(null,Typeface.BOLD);
        h.addView(title,new LinearLayout.LayoutParams(0,-2,1));
        Button close=b(T("close"));
        close.setOnClickListener(v->finish());
        h.addView(close);
        box.addView(h);

        TextView sub=t(T("account_and_pro"),14);
        sub.setTextColor(0xFFA7AFBC);
        sub.setPadding(0,dp(4),0,dp(18));
        box.addView(sub);

        statusText=t("",24);
        statusText.setTypeface(null,Typeface.BOLD);
        statusText.setPadding(dp(14),dp(15),dp(14),dp(5));
        statusText.setBackgroundColor(0xFF151A21);
        box.addView(statusText,new LinearLayout.LayoutParams(-1,-2));

        detailText=t("",13);
        detailText.setTextColor(0xFFA7AFBC);
        detailText.setPadding(dp(14),0,dp(14),dp(15));
        detailText.setBackgroundColor(0xFF151A21);
        box.addView(detailText,new LinearLayout.LayoutParams(-1,-2));

        sec(T("activate_restore"));
        email=input(T("email_address"),android.text.InputType.TYPE_CLASS_TEXT|android.text.InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS);
        email.setText(ent.accountEmail());
        order=input(T("order_id_optional"),android.text.InputType.TYPE_CLASS_TEXT);
        claim=b(T("activate_pro"));
        claim.setOnClickListener(v->claim());
        box.addView(claim,new LinearLayout.LayoutParams(-1,dp(52)));

        Button trial=b(T("request_trial"));
        trial.setOnClickListener(v->openWeb("https://nenotv.com/proefperiode?device="+Uri.encode(ent.publicDeviceId())));
        box.addView(trial,new LinearLayout.LayoutParams(-1,dp(52)));

        Button pro=b(T("view_pro"));
        pro.setOnClickListener(v->openWeb("https://nenotv.com/pro?device="+Uri.encode(ent.publicDeviceId())));
        box.addView(pro,new LinearLayout.LayoutParams(-1,dp(52)));

        refresh=b(T("refresh_status"));
        refresh.setOnClickListener(v->refreshServer());
        box.addView(refresh,new LinearLayout.LayoutParams(-1,dp(52)));

        sec(T("this_device"));
        deviceText=t("",14);
        deviceText.setTextColor(0xFFA7AFBC);
        box.addView(deviceText);
        serverText=t("",12);
        serverText.setTextColor(0xFF8D96A4);
        serverText.setPadding(0,dp(8),0,0);
        box.addView(serverText);
        setContentView(sv);
        refreshUi();
    }

    EditText input(String hint,int type){
        EditText e=new EditText(this);
        e.setHint(hint);
        e.setInputType(type);
        e.setSingleLine(true);
        e.setTextColor(0xFFF7F8FA);
        e.setHintTextColor(0xFF8D96A4);
        e.setPadding(dp(14),0,dp(14),0);
        e.setBackgroundColor(0xFF151A21);
        LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,dp(52));
        lp.bottomMargin=dp(8);
        box.addView(e,lp);
        return e;
    }

    void sec(String s){
        TextView v=t(s,18);
        v.setTypeface(null,Typeface.BOLD);
        v.setPadding(0,dp(22),0,dp(8));
        box.addView(v);
    }

    void refreshUi(){
        EntitlementStore.Level l=ent.level();
        statusText.setText(ent.statusLabel(this));
        if(l==EntitlementStore.Level.PRO_TRIAL)detailText.setText(T("trial_remaining")+": "+ent.trialDaysRemaining()+" "+T("days"));
        else if(l==EntitlementStore.Level.PRO)detailText.setText(T("pro_active")+" · "+T("devices")+": "+ent.maxDevices());
        else detailText.setText(T("free_description"));
        deviceText.setText(T("device_code")+": "+ent.publicDeviceId());
    }

    void busy(boolean on){
        claim.setEnabled(!on);
        refresh.setEnabled(!on);
        if(on)serverText.setText(T("checking_status"));
    }

    void claim(){
        String e=email.getText().toString().trim();
        String o=order.getText().toString().trim();
        if(e.isEmpty()){email.setError(T("email_required"));return;}
        busy(true);
        exec.execute(()->{
            try{
                new EntitlementClient(this).claim(e,o);
                runOnUiThread(()->{busy(false);serverText.setText(T("status_updated"));refreshUi();});
            }catch(Exception ex){
                runOnUiThread(()->{busy(false);serverText.setText(T("activation_failed")+": "+safe(ex));});
            }
        });
    }

    void refreshServer(){
        busy(true);
        exec.execute(()->{
            try{
                new EntitlementClient(this).refresh();
                runOnUiThread(()->{busy(false);serverText.setText(T("status_updated"));refreshUi();});
            }catch(Exception ex){
                runOnUiThread(()->{busy(false);serverText.setText(T("server_unavailable")+" · "+safe(ex));});
            }
        });
    }

    void handleIntent(Intent i){
        Uri u=i==null?null:i.getData();
        if(u==null||!"nenotv".equalsIgnoreCase(u.getScheme())||!"activate".equalsIgnoreCase(u.getHost()))return;
        String token=u.getQueryParameter("token");
        if(token==null||token.trim().isEmpty())return;
        busy(true);
        exec.execute(()->{
            try{
                new EntitlementClient(this).redeemToken(token);
                runOnUiThread(()->{busy(false);serverText.setText(T("activation_success"));refreshUi();});
            }catch(Exception ex){
                runOnUiThread(()->{busy(false);serverText.setText(T("activation_failed")+": "+safe(ex));});
            }
        });
    }

    String safe(Exception e){
        String m=e==null?null:e.getMessage();
        return m==null||m.trim().isEmpty()?T("unknown_error"):m;
    }
    void openWeb(String url){
        try{startActivity(new Intent(Intent.ACTION_VIEW,Uri.parse(url)));}
        catch(Exception e){Toast.makeText(this,T("server_unavailable"),Toast.LENGTH_SHORT).show();}
    }
    @Override protected void onDestroy(){exec.shutdownNow();super.onDestroy();}
}
