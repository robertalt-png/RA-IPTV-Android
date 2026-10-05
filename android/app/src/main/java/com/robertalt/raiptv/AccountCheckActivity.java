package com.nenotv.player;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.view.Gravity;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import com.nenotv.player.entitlement.EntitlementClient;
import com.nenotv.player.entitlement.PairingClient;
import com.nenotv.player.storage.AccountLinkStore;
import com.nenotv.player.storage.FamilyStore;
import com.nenotv.player.storage.SettingsStore;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class AccountCheckActivity extends Activity {
    private final ExecutorService worker=Executors.newSingleThreadExecutor();
    private TextView status;private Button retry;private boolean busy,destroyed;
    private String text(String nl,String en,String de){String lang=SettingsStore.language(this);return "nl".equals(lang)?nl:"de".equals(lang)?de:en;}
    @Override public void onCreate(Bundle saved){
        super.onCreate(saved);
        LinearLayout box=new LinearLayout(this);box.setOrientation(LinearLayout.VERTICAL);box.setGravity(Gravity.CENTER);box.setPadding(32,32,32,32);box.setBackgroundColor(0xFF07090D);
        status=new TextView(this);status.setTextColor(0xFFF7F8FA);status.setTextSize(18);status.setGravity(Gravity.CENTER);box.addView(status,new LinearLayout.LayoutParams(-1,-2));
        retry=new Button(this);retry.setAllCaps(false);retry.setText(text("Opnieuw proberen","Try again","Erneut versuchen"));retry.setOnClickListener(v->check());box.addView(retry);setContentView(box);ScreenInsets.browsing(this);if(!new AccountLinkStore(this).linked())requireLogin();else check();
    }
    private void requireLogin(){
        new AccountLinkStore(this).clear();
        Runnable login=()->{startActivity(MainActivity.firstRunIntent(this));finish();};
        if(FamilyStore.active(this)){status.setText(text("Vraag een ouder om opnieuw aan te melden.","Ask a parent to sign in again.","Bitte einen Elternteil erneut anzumelden."));retry.setEnabled(true);retry.setText(text("Ouder aanmelden","Parent sign-in","Eltern anmelden"));retry.setOnClickListener(v->FamilyUi.pin(this,pin->{if(FamilyStore.setActive(this,false,pin))login.run();}));}
        else login.run();
    }
    private void check(){
        if(busy||destroyed)return;busy=true;retry.setEnabled(false);status.setText(text("Mijn SunnyIPTV controleren...","Checking My SunnyIPTV...","Mein SunnyIPTV wird geprueft..."));
        worker.execute(()->{Exception failure=null;try{new PairingClient(this).checkAccount();}catch(Exception e){failure=e;}final Exception error=failure;
            runOnUiThread(()->{if(destroyed)return;busy=false;if(error==null){startActivity(new Intent(this,MainActivity.class));finish();}
                else if(error instanceof EntitlementClient.ServiceException&&java.util.Arrays.asList("account_not_linked","invalid_device").contains(((EntitlementClient.ServiceException)error).code))requireLogin();
                else{retry.setEnabled(true);status.setText(text("Geen verbinding. Controleer uw internet en probeer opnieuw.","Could not connect. Check your internet and try again.","Keine Verbindung. Internet pruefen und erneut versuchen."));}});
        });
    }
    @Override protected void onDestroy(){destroyed=true;worker.shutdownNow();super.onDestroy();}
}
