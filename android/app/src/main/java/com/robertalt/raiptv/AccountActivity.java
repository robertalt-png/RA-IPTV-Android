package com.nenotv.player;

import android.app.*;
import android.os.*;
import android.content.*;
import android.graphics.Typeface;
import android.net.Uri;
import android.view.*;
import android.widget.*;
import com.nenotv.player.entitlement.EntitlementClient;
import com.nenotv.player.storage.AccountLinkStore;
import com.nenotv.player.storage.EntitlementStore;
import com.nenotv.player.storage.SettingsStore;
import java.util.concurrent.*;

/** Account overview as blocks: status on top, four tiles, legal links at the bottom. */
public class AccountActivity extends Activity {
    LinearLayout box;
    TextView statusText,detailText,deviceText,serverText;
    EditText email,activationCode;
    Button refresh,trial,link;
    boolean requestRunning;
    long lastAutoRefresh;
    ExecutorService exec=Executors.newSingleThreadExecutor();
    EntitlementStore ent;

    int dp(int v){return Math.round(v*getResources().getDisplayMetrics().density);}
    String T(String k){return UiText.t(this,k);}
    String text(String nl,String en,String de){return FamilyUi.text(this,nl,en,de);}
    Button b(String s){Button v=new Button(this);v.setText(s);v.setAllCaps(false);v.setTextColor(Tiles.TEXT);v.setBackground(Tiles.background(this,false));v.setMinHeight(dp(52));return v;}

    @Override public void onCreate(Bundle x){
        super.onCreate(x);
        if(com.nenotv.player.storage.FamilyStore.active(this)){FamilyUi.blocked(this);finish();return;}
        ent=new EntitlementStore(this);
        createFields();
        build();
        handleIntent(getIntent());
    }
    @Override protected void onNewIntent(Intent i){super.onNewIntent(i);if(com.nenotv.player.storage.FamilyStore.active(this)){finish();return;}setIntent(i);handleIntent(i);}
    @Override protected void onResume(){
        super.onResume();if(com.nenotv.player.storage.FamilyStore.active(this)){finish();return;}
        build();ProModuleInstaller.syncEntitlement(this);
        // The status refreshes itself when the screen opens; no separate button needed.
        long now=SystemClock.elapsedRealtime();
        if(!requestRunning&&(lastAutoRefresh==0||now-lastAutoRefresh>60000)){lastAutoRefresh=now;refreshServer(true);
            // A paid purchase the server has not confirmed yet (network, app closed) is sent again; silent when there is none.
            if(ent.level()!=EntitlementStore.Level.PRO)try{play().restore();}catch(RuntimeException noPlay){}}
    }

    /** Input fields live in dialogs, but are created once so state and checks stay in one place. */
    void createFields(){
        activationCode=new EditText(this);activationCode.setHint(T("activation_code"));activationCode.setSingleLine(true);
        activationCode.setInputType(android.text.InputType.TYPE_CLASS_TEXT|android.text.InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS);
        activationCode.setSaveEnabled(false);
        if(Build.VERSION.SDK_INT>=26)activationCode.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO);
        activationCode.setFilters(new android.text.InputFilter[]{new android.text.InputFilter.LengthFilter(128)});
        link=b(T("link_device"));link.setOnClickListener(v->redeemCode());
        email=new EditText(this);email.setHint(T("email_address"));email.setSingleLine(true);
        email.setInputType(android.text.InputType.TYPE_CLASS_TEXT|android.text.InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS);
        email.setText(ent.accountEmail());
        trial=b(T("request_trial"));trial.setOnClickListener(v->startTrial());
        refresh=b(T("refresh_status"));refresh.setOnClickListener(v->refreshServer(false));
        serverText=new TextView(this);serverText.setTextColor(Tiles.MUTED);serverText.setTextSize(13);serverText.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
    }

    void build(){
        if(ent==null)return;
        box=Tiles.page(this,T("account_and_pro"));
        LinearLayout card=new LinearLayout(this);card.setOrientation(LinearLayout.VERTICAL);card.setBackground(Tiles.background(this,false));card.setPadding(dp(16),dp(16),dp(16),dp(16));
        statusText=new TextView(this);statusText.setTextColor(Tiles.TEXT);statusText.setTextSize(22);statusText.setTypeface(null,Typeface.BOLD);card.addView(statusText);
        detailText=new TextView(this);detailText.setTextColor(Tiles.MUTED);detailText.setTextSize(14);detailText.setPadding(0,dp(6),0,0);card.addView(detailText);
        LinearLayout.LayoutParams cp=new LinearLayout.LayoutParams(-1,-2);cp.topMargin=dp(14);cp.bottomMargin=dp(6);box.addView(card,cp);
        if(serverText.getParent()!=null)((ViewGroup)serverText.getParent()).removeView(serverText);
        box.addView(serverText);
        deviceText=new TextView(this);

        Tiles.Grid grid=new Tiles.Grid(this,box);
        grid.add("🌐",myNenoLabel(),text("Apparaten, abonnement en tv-bron","Devices, subscription and TV source","Geräte, Abo und TV-Quelle"),false,v->openWeb(myNenoUrl()));
        // Google Play policy: the app sells Pro only through Google Play and never links to a payment page.
        if(ent.level()==EntitlementStore.Level.PRO)grid.add("⭐",ent.statusLabel(this),text("Bekijk je abonnement","View your subscription","Abo ansehen"),false,v->openWeb(myNenoUrl()));
        else{
            grid.add("🛒",T("buy_pro"),text("Solo of Multi, via Google Play","Solo or Multi, through Google Play","Solo oder Multi, über Google Play"),false,v->showBuyDialog());
            if(!ent.isPro())grid.add("⭐",T("request_trial"),text("Probeer alle Pro-functies gratis","Try every Pro feature for free","Alle Pro-Funktionen gratis testen"),false,v->showTrialDialog());
        }
        grid.add("🔑",T("activation_code"),text("Code uit je e-mail invoeren","Enter the code from your email","Code aus deiner E-Mail eingeben"),false,v->showCodeDialog());
        grid.add("📱",T("this_device"),T("device_code")+": "+ent.publicDeviceId(),false,v->showDeviceDialog());
        if(!new AccountLinkStore(this).linked())grid.add("📲",T("pair_by_phone"),text("Koppel dit apparaat aan je account","Link this device to your account","Dieses Gerät mit deinem Konto verbinden"),false,v->startActivity(new Intent(this,PairingActivity.class)));
        grid.finish();

        String language=SettingsStore.language(this);
        TextView privacy=Tiles.link(this,box,"nl".equals(language)?"Privacyverklaring":"de".equals(language)?"Datenschutzerklärung":"Privacy policy",v->openWeb(SiteEndpoints.privacyUrl(SettingsStore.language(this))));
        privacy.setTag("privacy_policy");
        TextView deletion=Tiles.link(this,box,"nl".equals(language)?"Account verwijderen aanvragen":"de".equals(language)?"Kontolöschung beantragen":"Request account deletion",v->openWeb(SiteEndpoints.accountDeletionUrl(SettingsStore.language(this))));
        deletion.setTag("account_deletion");
        refreshUi();
        UiText.applyDirection(this);
    }

    PlayPurchases play;boolean wantProducts;
    PlayPurchases play(){
        if(play==null)play=new PlayPurchases(this,new PlayPurchases.Listener(){
            @Override public void products(java.util.List<com.android.billingclient.api.ProductDetails> list,String error){if(wantProducts){wantProducts=false;busy(false);showProducts(list,error);}}
            @Override public void purchase(String result){
                if(isFinishing()||isDestroyed())return;
                busy(false);serverText.setText(T(result));Toast.makeText(AccountActivity.this,T(result),Toast.LENGTH_LONG).show();
                if("purchase_done".equals(result)){build();ProModuleInstaller.syncEntitlement(AccountActivity.this);}
            }
        });
        return play;
    }
    /** Asks the server whether Pro is on sale, then shows the Google Play products with their local prices. */
    void showBuyDialog(){
        if(requestRunning)return;
        busy(true);
        exec.execute(()->{
            boolean onSale=false;
            try{onSale=new EntitlementClient(this).offer().optBoolean("play_billing",false);}catch(Exception ignored){}
            final boolean sale=onSale;
            runOnUiThread(()->{
                if(isFinishing()||isDestroyed())return;
                if(!sale){busy(false);new AlertDialog.Builder(this).setTitle(T("buy_pro")).setMessage(T("pro_coming_soon")).setPositiveButton(T("close"),null).show();return;}
                wantProducts=true;play().loadProducts();
            });
        });
    }
    void showProducts(java.util.List<com.android.billingclient.api.ProductDetails> list,String error){
        if(list.isEmpty()){new AlertDialog.Builder(this).setTitle(T("buy_pro")).setMessage(T(error.isEmpty()?"pro_coming_soon":error)).setPositiveButton(T("close"),null).show();return;}
        String[] rows=new String[list.size()];
        for(int i=0;i<rows.length;i++){com.android.billingclient.api.ProductDetails d=list.get(i);int n=PlayPurchases.devices(d);
            rows[i]=(n==1?"Solo":"Multi")+" · "+n+" "+T(n==1?"device_one":"device_many")+"\n"+PlayPurchases.price(d,T("per_year"),T("lifetime"));}
        new AlertDialog.Builder(this).setTitle(T("buy_pro")).setItems(rows,(dlg,w)->{serverText.setText(T("checking_status"));play().buy(list.get(w));}).setNegativeButton(T("close"),null).show();
    }

    LinearLayout dialogBox(){LinearLayout l=new LinearLayout(this);l.setOrientation(LinearLayout.VERTICAL);l.setPadding(dp(20),dp(8),dp(20),0);return l;}
    void detach(View v){if(v.getParent()!=null)((ViewGroup)v.getParent()).removeView(v);}

    void showTrialDialog(){
        LinearLayout l=dialogBox();detach(email);detach(trial);
        TextView info=new TextView(this);info.setText(text("Vul je e-mailadres in. Je krijgt 14 dagen alle Pro-functies.","Enter your email address. You get every Pro feature for 14 days.","Gib deine E-Mail-Adresse ein. Du bekommst 14 Tage alle Pro-Funktionen."));l.addView(info);
        l.addView(email,new LinearLayout.LayoutParams(-1,-2));l.addView(trial,new LinearLayout.LayoutParams(-1,-2));
        new AlertDialog.Builder(this).setTitle(T("request_trial")).setView(l).setNegativeButton(T("close"),null).show();
    }
    void showCodeDialog(){
        LinearLayout l=dialogBox();detach(activationCode);detach(link);
        l.addView(activationCode,new LinearLayout.LayoutParams(-1,-2));l.addView(link,new LinearLayout.LayoutParams(-1,-2));
        new AlertDialog.Builder(this).setTitle(T("activation_code")).setView(l).setNegativeButton(T("close"),null).show();
    }
    void showDeviceDialog(){
        String msg=T("device_code")+": "+ent.publicDeviceId();
        if(ent.isPro()&&ent.usedDevices()>=0)msg+="\n"+T("device_places_used")+": "+ent.usedDevices()+" · "+T("device_places_free")+": "+ent.freeDevices();
        new AlertDialog.Builder(this).setTitle(T("this_device")).setMessage(msg).setPositiveButton(android.R.string.ok,null).show();
    }

    void refreshUi(){
        if(statusText==null)return;
        EntitlementStore.Level l=ent.level();
        statusText.setText(ent.statusLabel(this));
        String places="";
        if(ent.isPro()){
            places="\n"+(l==EntitlementStore.Level.PRO_TRIAL?T("trial_plan"):ent.maxDevices()==1?"Solo":"Multi")+" · "+T("devices")+": "+ent.maxDevices();
        }
        if(l==EntitlementStore.Level.PRO_TRIAL)detailText.setText(T("trial_remaining")+": "+ent.trialDaysRemaining()+" "+T("days")+places);
        else if(l==EntitlementStore.Level.PRO)detailText.setText(T("pro_active")+places);
        else detailText.setText(T("free_description"));
        deviceText.setText(T("device_code")+": "+ent.publicDeviceId());
    }

    void busy(boolean on){
        requestRunning=on;
        refresh.setEnabled(!on);trial.setEnabled(!on);link.setEnabled(!on);activationCode.setEnabled(!on);email.setEnabled(!on);
        if(on)serverText.setText(T("checking_status"));
    }

    void startTrial(){
        if(requestRunning)return;
        String e=email.getText().toString().trim();
        if(e.isEmpty()){email.setError(T("email_required"));return;}
        busy(true);
        exec.execute(()->{
            try{new EntitlementClient(this).startTrial(e);accountResult("status_updated",true);}
            catch(EntitlementClient.ServiceException ex){accountResult("activation_failed",false);}
            catch(Exception ex){accountResult("server_unavailable",false);}
        });
    }

    volatile boolean quietRefreshRunning;
    void refreshServer(boolean quiet){
        if(requestRunning||quietRefreshRunning)return;
        if(quiet){
            // Background refresh on open: never blocks the buttons or shows "checking".
            quietRefreshRunning=true;
            exec.execute(()->{
                boolean ok=false;try{new EntitlementClient(this).refresh();ok=true;}catch(Exception ignored){}
                final boolean updated=ok;
                runOnUiThread(()->{quietRefreshRunning=false;if(updated&&!isFinishing()&&!isDestroyed()&&!requestRunning){build();ProModuleInstaller.syncEntitlement(this);}});
            });
            return;
        }
        busy(true);
        exec.execute(()->{
            try{new EntitlementClient(this).refresh();accountResult("status_updated",true);}
            catch(Exception ex){accountResult("server_unavailable",false);}
        });
    }

    void handleIntent(Intent i){
        Uri u=i==null?null:i.getData();
        if(u==null||!"nenotv".equalsIgnoreCase(u.getScheme())||!"activate".equalsIgnoreCase(u.getHost()))return;
        String token=u.getQueryParameter("token");
        i.setData(null);
        if(token==null||token.trim().isEmpty())return;
        redeem(token);
    }

    void redeemCode(){
        String token=activationCode.getText().toString().trim().toUpperCase(java.util.Locale.ROOT);
        // Accept the code as the server issues it: a short prefix plus four groups of four characters.
        if(!token.matches("[A-Z]{3,8}-[A-Z0-9]{4}-[A-Z0-9]{4}-[A-Z0-9]{4}-[A-Z0-9]{4}")){
            activationCode.setError(T("activation_code_invalid"));
            return;
        }
        redeem(token);
    }

    void redeem(String token){
        if(requestRunning)return;
        activationCode.setText("");
        busy(true);
        exec.execute(()->{
            try{new EntitlementClient(this).redeemToken(token);accountResult("activation_success",true);}
            catch(EntitlementClient.ServiceException ex){accountResult("activation_failed",false);}
            catch(Exception ex){accountResult("server_unavailable",false);}
        });
    }

    void accountResult(String message,boolean updated){
        runOnUiThread(()->{
            if(isFinishing()||isDestroyed())return;
            busy(false);serverText.setText(T(message));
            if(updated){email.setText(ent.accountEmail());build();ProModuleInstaller.syncEntitlement(this);}
        });
    }

    String myNenoLabel(){
        String l=SettingsStore.language(this);
        return "nl".equals(l)?"Mijn SunnyIPTV":"de".equals(l)?"Mein SunnyIPTV":"My SunnyIPTV";
    }
    String myNenoUrl(){
        String l=SettingsStore.language(this);
        return "nl".equals(l)?"https://sunnyiptv.com/language/nl/mijn-account/":"de".equals(l)?"https://sunnyiptv.com/language/de/mein-konto/":"https://sunnyiptv.com/my-account/";
    }

    void openWeb(String url){Tiles.open(this,url);}
    @Override protected void onDestroy(){exec.shutdownNow();if(play!=null)play.close();super.onDestroy();}
}
