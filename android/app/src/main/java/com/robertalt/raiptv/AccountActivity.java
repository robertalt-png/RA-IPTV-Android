package com.nenotv.player;

import android.app.*;
import android.os.*;
import android.content.*;
import android.graphics.Typeface;
import android.net.Uri;
import android.view.*;
import android.widget.*;
import com.nenotv.player.entitlement.EntitlementClient;
import com.nenotv.player.storage.EntitlementStore;
import com.nenotv.player.storage.SettingsStore;
import java.util.concurrent.*;

public class AccountActivity extends Activity {
    LinearLayout box;
    TextView statusText,detailText,deviceText,serverText;
    EditText email,activationCode;
    Button refresh,trial,link;
    boolean requestRunning;
    ExecutorService exec=Executors.newSingleThreadExecutor();
    EntitlementStore ent;

    int dp(int v){return Math.round(v*getResources().getDisplayMetrics().density);}
    String T(String k){return UiText.t(this,k);}
    TextView t(String s,int z){TextView v=new TextView(this);v.setText(s);v.setTextColor(0xFFF7F8FA);v.setTextSize(z);return v;}
    Button b(String s){Button v=new Button(this);v.setText(s);v.setAllCaps(false);v.setTextColor(0xFFF7F8FA);v.setBackgroundTintList(android.content.res.ColorStateList.valueOf(0xFF1B2028));return v;}

    @Override public void onCreate(Bundle x){
        super.onCreate(x);
        if(com.nenotv.player.storage.FamilyStore.active(this)){FamilyUi.blocked(this);finish();return;}
        ent=new EntitlementStore(this);
        build();
        UiText.applyDirection(this);
        handleIntent(getIntent());
    }
    @Override protected void onNewIntent(Intent i){super.onNewIntent(i);if(com.nenotv.player.storage.FamilyStore.active(this)){finish();return;}setIntent(i);handleIntent(i);}
    @Override protected void onResume(){super.onResume();if(com.nenotv.player.storage.FamilyStore.active(this)){finish();return;}refreshUi();ProModuleInstaller.syncEntitlement(this);}

    void build(){
        ScrollView sv=new ScrollView(this);
        sv.setBackgroundColor(0xFF07090D);
        box=new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(18),dp(18),dp(18),dp(34));
        sv.addView(box);

        LinearLayout h=new LinearLayout(this);
        h.setGravity(Gravity.CENTER_VERTICAL);
        ImageView title=new ImageView(this);
        title.setImageResource(R.drawable.sunnyiptv_logo);
        title.setContentDescription(getString(R.string.app_name));
        title.setScaleType(ImageView.ScaleType.FIT_START);
        h.addView(title,new LinearLayout.LayoutParams(0,dp(64),1));
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

        sec(T("link_my_nenotv"));
        Button phone=b(T("pair_by_phone"));
        phone.setOnClickListener(v->startActivity(new Intent(this,PairingActivity.class)));
        addButton(phone);
        activationCode=input(T("activation_code"),android.text.InputType.TYPE_CLASS_TEXT|android.text.InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS);
        activationCode.setSaveEnabled(false);
        if(Build.VERSION.SDK_INT>=26)activationCode.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO);
        activationCode.setFilters(new android.text.InputFilter[]{new android.text.InputFilter.LengthFilter(128)});
        link=b(T("link_device"));
        link.setOnClickListener(v->redeemCode());
        addButton(link);
        serverText=t("",12);
        serverText.setTextColor(0xFFA7AFBC);
        serverText.setPadding(0,dp(8),0,0);
        serverText.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        box.addView(serverText);

        Button my=b(myNenoLabel());
        my.setOnClickListener(v->openWeb(myNenoUrl()));
        addButton(my);

        String language=SettingsStore.language(this);
        Button privacy=b("nl".equals(language)?"Privacyverklaring":"de".equals(language)?"Datenschutzerklärung":"Privacy policy");
        privacy.setTag("privacy_policy");
        privacy.setOnClickListener(v->openWeb(SiteEndpoints.privacyUrl(SettingsStore.language(this))));
        addButton(privacy);

        Button deletion=b("nl".equals(language)?"Account verwijderen aanvragen":"de".equals(language)?"Kontolöschung beantragen":"Request account deletion");
        deletion.setTag("account_deletion");
        deletion.setOnClickListener(v->openWeb(SiteEndpoints.accountDeletionUrl(SettingsStore.language(this))));
        addButton(deletion);

        sec(T("request_trial"));
        email=input(T("email_address"),android.text.InputType.TYPE_CLASS_TEXT|android.text.InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS);
        email.setText(ent.accountEmail());

        trial=b(T("request_trial"));
        trial.setOnClickListener(v->startTrial());
        addButton(trial);

        Button pro=b(T("view_pro"));
        pro.setOnClickListener(v->openWeb("https://sunnyiptv.com/pro?device="+Uri.encode(ent.publicDeviceId())));
        addButton(pro);

        refresh=b(T("refresh_status"));
        refresh.setOnClickListener(v->refreshServer());
        addButton(refresh);

        sec(T("this_device"));
        deviceText=t("",14);
        deviceText.setTextColor(0xFFA7AFBC);
        box.addView(deviceText);
        setContentView(sv);
        ScreenInsets.browsing(this);
        refreshUi();
    }

    void addButton(Button button){
        button.setMinHeight(dp(52));
        box.addView(button,new LinearLayout.LayoutParams(-1,-2));
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
        String places="";
        if(ent.isPro()){
            places="\n"+(l==EntitlementStore.Level.PRO_TRIAL?T("trial_plan"):ent.maxDevices()==1?"Solo":"Multi")
                    +" · "+T("devices")+": "+ent.maxDevices();
            if(ent.usedDevices()>=0)places+="\n"+T("device_places_used")+": "+ent.usedDevices()+" · "+T("device_places_free")+": "+ent.freeDevices()
                    +"\n"+T("device_status_checked")+": "+android.text.format.DateFormat.getDateFormat(this).format(new java.util.Date(ent.deviceStatusAt()))
                    +" "+android.text.format.DateFormat.getTimeFormat(this).format(new java.util.Date(ent.deviceStatusAt()));
        }
        if(l==EntitlementStore.Level.PRO_TRIAL)detailText.setText(T("trial_remaining")+": "+ent.trialDaysRemaining()+" "+T("days")+places);
        else if(l==EntitlementStore.Level.PRO)detailText.setText(T("pro_active")+places);
        else detailText.setText(T("free_description"));
        deviceText.setText(T("device_code")+": "+ent.publicDeviceId());
    }

    void busy(boolean on){
        requestRunning=on;
        refresh.setEnabled(!on);
        trial.setEnabled(!on);
        link.setEnabled(!on);
        activationCode.setEnabled(!on);
        email.setEnabled(!on);
        if(on)serverText.setText(T("checking_status"));
    }

    void startTrial(){
        if(requestRunning)return;
        String e=email.getText().toString().trim();
        if(e.isEmpty()){email.setError(T("email_required"));return;}
        busy(true);
        exec.execute(()->{
            try{
                new EntitlementClient(this).startTrial(e);
                accountResult("status_updated",true);
            }catch(Exception ex){
                accountResult("activation_failed",false);
            }
        });
    }

    void refreshServer(){
        if(requestRunning)return;
        busy(true);
        exec.execute(()->{
            try{
                new EntitlementClient(this).refresh();
                accountResult("status_updated",true);
            }catch(Exception ex){
                accountResult("server_unavailable",false);
            }
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
        if(!token.matches("NENO-[A-Z0-9]{4}-[A-Z0-9]{4}-[A-Z0-9]{4}-[A-Z0-9]{4}")){
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
            try{
                new EntitlementClient(this).redeemToken(token);
                accountResult("activation_success",true);
            }catch(Exception ex){
                accountResult("activation_failed",false);
            }
        });
    }

    void accountResult(String message,boolean updated){
        runOnUiThread(()->{
            if(isFinishing()||isDestroyed())return;
            busy(false);serverText.setText(T(message));
            if(updated){email.setText(ent.accountEmail());refreshUi();ProModuleInstaller.syncEntitlement(this);}
        });
    }

    String myNenoLabel(){
        String l=SettingsStore.language(this);
        return "nl".equals(l)?"Open Mijn SunnyIPTV":"de".equals(l)?"Mein SunnyIPTV öffnen":"Open My SunnyIPTV";
    }
    String myNenoUrl(){
        String l=SettingsStore.language(this);
        return "nl".equals(l)?"https://sunnyiptv.com/language/nl/mijn-account/":"de".equals(l)?"https://sunnyiptv.com/language/de/mein-konto/":"https://sunnyiptv.com/my-account/";
    }

    void openWeb(String url){
        try{startActivity(new Intent(Intent.ACTION_VIEW,Uri.parse(url)));}
        catch(Exception e){Toast.makeText(this,T("server_unavailable"),Toast.LENGTH_SHORT).show();}
    }
    @Override protected void onDestroy(){exec.shutdownNow();super.onDestroy();}
}
