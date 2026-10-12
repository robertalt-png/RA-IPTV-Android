package com.nenotv.player;

import android.app.*;
import android.content.*;
import android.os.*;
import android.view.View;
import android.widget.*;
import android.graphics.Bitmap;
import java.io.*;
import java.util.concurrent.atomic.*;
import com.nenotv.player.storage.SettingsStore;

/** Focused phone checks, with no real login, purchase or customer data. */
public final class ProFlowInstrumentation extends Instrumentation {
    @Override public void runOnMainSync(Runnable action){AtomicReference<Throwable> failure=new AtomicReference<>();super.runOnMainSync(()->{try{action.run();}catch(Throwable e){failure.set(e);}});if(failure.get()!=null)throw new AssertionError(failure.get());}
    static void check(boolean condition,String message){if(!condition)throw new AssertionError(message);}
    @Override public void onCreate(Bundle args){super.onCreate(args);start();}
    @Override public void onStart(){
        Bundle result=new Bundle();
        PairingActivity.Factory factory=PairingActivity.factory;
        PairingActivity.BrowserOpener opener=PairingActivity.browserOpener;
        String language=SettingsStore.language(getTargetContext());
        AtomicInteger opened=new AtomicInteger(),cancelled=new AtomicInteger();
        PairingActivity.factory=context->new PairingActivity.Access(){
            public com.nenotv.player.entitlement.PairingClient.Session start()throws Exception{return com.nenotv.player.entitlement.PairingClientChecks.fixtureSession();}
            public String status(com.nenotv.player.entitlement.PairingClient.Session session){return "pending";}
            public void cancel(com.nenotv.player.entitlement.PairingClient.Session session){cancelled.incrementAndGet();}
        };
        PairingActivity.browserOpener=(activity,url)->opened.incrementAndGet();
        try{
            for(String lang:new String[]{"nl","en","de"}){
                SettingsStore.setPrimaryLanguage(getTargetContext(),lang);
                PairingActivity a=(PairingActivity)startActivitySync(new Intent(getTargetContext(),PairingActivity.class).putExtra("pro_activation",true).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                try{
                    AtomicBoolean ready=new AtomicBoolean();long until=SystemClock.elapsedRealtime()+5000;
                    while(!ready.get()&&SystemClock.elapsedRealtime()<until){runOnMainSync(()->ready.set(a.session!=null&&!a.busy));Thread.sleep(50);}
                    check(ready.get(),"Session not ready");waitForIdleSync();
                    runOnMainSync(()->{
                        check(a.qr.getVisibility()==View.GONE,"Phone asks customer to scan itself");
                        check(a.open.getText().toString().equals(a.linkLabel()),"Code leaked into phone button");
                        check(a.retry.getVisibility()==View.GONE,"Extra action before failure");
                        check(a.open.isEnabled(),"Continue disabled");
                        int before=opened.get();a.open.performClick();check(opened.get()==before+1,"Continue did not open login once");
                        check(a.open.getLayout()!=null&&a.open.getLayout().getEllipsisCount(0)==0,"Continue label clipped");
                    });
                    Bitmap bitmap=getUiAutomation().takeScreenshot();check(bitmap!=null,"Screenshot missing");
                    File dir=new File(getTargetContext().getExternalFilesDir(null),"qa");dir.mkdirs();
                    try(FileOutputStream out=new FileOutputStream(new File(dir,"simple-pro-"+lang+".png"))){bitmap.compress(Bitmap.CompressFormat.PNG,100,out);}bitmap.recycle();
                    runOnMainSync(()->{a.expire();check(a.session==null&&!a.open.isEnabled()&&a.retry.getVisibility()==View.VISIBLE,"Expired request not recoverable");});
                }finally{runOnMainSync(a::finish);waitForIdleSync();}
            }
            PairingActivity stop=(PairingActivity)startActivitySync(new Intent(getTargetContext(),PairingActivity.class).putExtra("pro_activation",true).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            Thread.sleep(500);runOnMainSync(stop::finish);Thread.sleep(300);
            check(cancelled.get()>0,"Stop did not cancel pending session");
            result.putString("SUNNYIPTV_SIMPLE_PRO","passed");finish(Activity.RESULT_OK,result);
        }catch(Throwable failure){result.putString("SUNNYIPTV_SIMPLE_PRO","failed: "+failure);finish(Activity.RESULT_CANCELED,result);}
        finally{PairingActivity.factory=factory;PairingActivity.browserOpener=opener;SettingsStore.setPrimaryLanguage(getTargetContext(),language);}
    }
}
