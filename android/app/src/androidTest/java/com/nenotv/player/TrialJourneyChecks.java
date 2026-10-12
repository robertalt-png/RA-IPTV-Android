package com.nenotv.player;

import android.app.*;
import android.content.*;
import android.os.*;
import android.graphics.Bitmap;
import android.widget.Button;
import com.nenotv.player.storage.*;
import com.nenotv.player.model.Profile;
import java.io.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;

final class TrialJourneyChecks {
    static void check(boolean ok,String message){if(!ok)throw new AssertionError(message);}
    static void run(Instrumentation test)throws Exception{
        Context c=test.getTargetContext();SecureProfileStore profiles=new SecureProfileStore(c);
        Profile oldProfile=profiles.exists()?profiles.load():null;
        SharedPreferences settings=SettingsStore.prefs(c),ent=c.getSharedPreferences("nenotv_entitlement",0),account=c.getSharedPreferences("nenotv_account_link_v1",0);
        Map<String,?> oldSettings=new HashMap<>(settings.getAll()),oldEnt=new HashMap<>(ent.getAll()),oldAccount=new HashMap<>(account.getAll());
        MainActivity main=null;
        try(UiInstrumentation.XtreamFixture fixture=new UiInstrumentation.XtreamFixture("QA")){
            SettingsStore.setPrimaryLanguage(c,"nl");settings.edit().putBoolean("trial_invitation_shown",false).putString("start_screen","home").commit();
            ent.edit().putString("level","FREE").commit();account.edit().clear().commit();new AccountLinkStore(c).startGuest();ExtraPrivacyStore.choose(c,"unknown");
            Profile p=new Profile();p.name="QA source";p.type=Profile.Type.XTREAM;p.server=fixture.url();p.username="qa-trial";p.password="qa";profiles.save(p);
            main=(MainActivity)test.startActivitySync(new Intent(c,MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));final MainActivity a=main;
            AtomicBoolean shown=new AtomicBoolean();long until=SystemClock.elapsedRealtime()+30000;
            while(!shown.get()&&SystemClock.elapsedRealtime()<until){test.runOnMainSync(()->shown.set(a.trialInvitation!=null&&a.trialInvitation.isShowing()));Thread.sleep(100);}
            check(shown.get(),"No invitation after successful list download");
            test.runOnMainSync(()->{
                check(new EntitlementStore(c).level()==EntitlementStore.Level.FREE,"Import silently activated Pro");
                check(a.searchIndex.isComplete(a.profileKey(),"live")&&a.searchIndex.isComplete(a.profileKey(),"vod")&&a.searchIndex.isComplete(a.profileKey(),"series"),"Invitation before complete playlist");
                check(a.trialInvitation.getButton(AlertDialog.BUTTON_POSITIVE).getText().toString().equals("Start gratis proef"),"Trial action missing");
            });
            Bitmap image=test.getUiAutomation().takeScreenshot();File dir=new File(c.getExternalFilesDir(null),"qa");dir.mkdirs();
            try(FileOutputStream out=new FileOutputStream(new File(dir,"trial-invitation.png"))){image.compress(Bitmap.CompressFormat.PNG,100,out);}image.recycle();
            test.runOnMainSync(()->a.trialInvitation.getButton(AlertDialog.BUTTON_NEGATIVE).performClick());test.waitForIdleSync();
            test.runOnMainSync(()->{a.maybeInviteTrial();check(!a.trialInvitation.isShowing(),"Later caused repeated invitation");});
            check(profiles.load().server.equals(p.server)&&profiles.load().username.equals(p.username),"Invitation changed customer's source");
            android.app.Instrumentation.ActivityMonitor monitor=test.addMonitor(PairingActivity.class.getName(),null,false);
            try{
                test.runOnMainSync(()->{Button button=a.browseContainer.findViewWithTag("start_pro_trial");check(button!=null,"Home trial action missing");button.performClick();});
                Activity next=test.waitForMonitorWithTimeout(monitor,5000);check(next instanceof PairingActivity,"Trial routed through account menu");
                check(next.getIntent().getBooleanExtra("trial_activation",false)&&next.getIntent().getBooleanExtra("auto_web",false),"Trial did not open account page directly");
                test.runOnMainSync(next::finish);
            }finally{test.removeMonitor(monitor);}
        }finally{
            if(main!=null){final MainActivity a=main;test.runOnMainSync(a::finish);test.waitForIdleSync();}
            if(oldProfile!=null)profiles.save(oldProfile);else profiles.clear();
            FamilyChecks.restore(settings,oldSettings);FamilyChecks.restore(ent,oldEnt);FamilyChecks.restore(account,oldAccount);
        }
    }
}
