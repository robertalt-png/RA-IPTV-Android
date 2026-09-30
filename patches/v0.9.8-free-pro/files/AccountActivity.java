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
import com.robertalt.raiptv.storage.SettingsStore;
import java.util.concurrent.*;

public class AccountActivity extends Activity {
    LinearLayout box;
    TextView statusText,detailText,deviceText,serverText;
    EditText email,activationCode;
    Button startTrial,activate,refresh,viewPro;
    ExecutorService exec=Executors.newSingleThreadExecutor();
    EntitlementStore ent;

    int dp(int v){return Math.round(v*getResources().getDisplayMetrics().density);}
    String T(String k){return UiText.t(this,k);}
    String L(String en,String nl,String de){
        String l=SettingsStore.language(this);
        if("nl".equals(l))return nl;
        if("de".equals(l))return de;
        return en;
    }
    TextView t(String s,int z){TextView v=new TextView(this);v.setText(s);v.setTextColor(0xFFF7F8FA);v.setTextSize(z);return v;}
    Button b(String s){Button v=new Button(this);v.setText(s);v.setAllCaps(false);v.setTextColor(0xFFF7F8FA);v.setBackgroundTintList(android.content.res.ColorStateList.valueOf(0xFF1B2028));return v;}

    @Override public void onCreate(Bundle x){
        super.onCreate(x);
        ent=new EntitlementStore(this);
        build();
        UiText.applyDirection(this);
        handleIntent(getIntent());
        refreshServerQuietly();
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

        TextView sub=t(L("Trial & NenoTV Pro","Proefperiode & NenoTV Pro","Testphase & NenoTV Pro"),14);
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

        sec(L("30-day trial","30 dagen proberen","30 Tage testen"));
        TextView trialNote=t(L(
            "Start the 30-day trial with your email address. No automatic payment follows.",
            "Start de proefperiode van 30 dagen met je e-mailadres. Er volgt geen automatische betaling.",
            "Starte die 30-tägige Testphase mit deiner E-Mail-Adresse. Es erfolgt keine automatische Zahlung."
        ),13);
        trialNote.setTextColor(0xFFA7AFBC);
        trialNote.setPadding(0,0,0,dp(8));
        box.addView(trialNote);

        email=input(L("Email address","E-mailadres","E-Mail-Adresse"),
            android.text.InputType.TYPE_CLASS_TEXT|android.text.InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS);
        email.setText(ent.accountEmail());
        startTrial=b(L("Start 30-day trial","Start 30 dagen proefperiode","30-tägige Testphase starten"));
        startTrial.setOnClickListener(v->startTrial());
        box.addView(startTrial,new LinearLayout.LayoutParams(-1,dp(52)));

        sec(L("Activate NenoTV Pro","NenoTV Pro activeren","NenoTV Pro aktivieren"));
        activationCode=input(L("Activation code","Activatiecode","Aktivierungscode"),android.text.InputType.TYPE_CLASS_TEXT);
        activate=b(L("Activate Pro","Pro activeren","Pro aktivieren"));
        activate.setOnClickListener(v->activatePro());
        box.addView(activate,new LinearLayout.LayoutParams(-1,dp(52)));

        viewPro=b(L("View NenoTV Pro","Bekijk NenoTV Pro","NenoTV Pro ansehen"));
        viewPro.setOnClickListener(v->openPro());
        box.addView(viewPro,new LinearLayout.LayoutParams(-1,dp(52)));

        refresh=b(L("Refresh account status","Accountstatus vernieuwen","Kontostatus aktualisieren"));
        refresh.setOnClickListener(v->refreshServer());
        box.addView(refresh,new LinearLayout.LayoutParams(-1,dp(52)));

        sec(L("This device","Dit apparaat","Dieses Gerät"));
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
        if(l==EntitlementStore.Level.PRO){
            statusText.setText("NenoTV Pro");
            detailText.setText(L("Pro is active","Pro is actief","Pro ist aktiv")+" · "+L("devices","apparaten","Geräte")+": "+ent.maxDevices());
            startTrial.setEnabled(false);
        }else if(l==EntitlementStore.Level.PRO_TRIAL){
            statusText.setText(L("NenoTV Trial","NenoTV Proefperiode","NenoTV Testphase"));
            if(ent.needsServerValidation()){
                detailText.setText(L(
                    "Reconnect to NenoTV to validate the trial before playback.",
                    "Maak opnieuw verbinding met NenoTV om de proefperiode te controleren voordat je afspeelt.",
                    "Verbinde dich erneut mit NenoTV, um die Testphase vor der Wiedergabe zu prüfen."
                ));
            }else{
                long days=ent.trialDaysRemaining();
                detailText.setText(days+" "+L(days==1?"day remaining":"days remaining",days==1?"dag resterend":"dagen resterend",days==1?"Tag verbleibend":"Tage verbleibend"));
            }
            startTrial.setEnabled(false);
        }else if(l==EntitlementStore.Level.TRIAL_EXPIRED||ent.isTrialExpired()){
            statusText.setText(L("Trial ended","Proefperiode afgelopen","Testphase beendet"));
            detailText.setText(L(
                "Your NenoTV settings remain on this device. Activate Pro to continue playback.",
                "Je NenoTV-instellingen blijven op dit apparaat staan. Activeer Pro om verder te kijken.",
                "Deine NenoTV-Einstellungen bleiben auf diesem Gerät. Aktiviere Pro, um weiterzuschauen."
            ));
            startTrial.setEnabled(false);
        }else{
            statusText.setText(L("30-day NenoTV trial","NenoTV 30 dagen proberen","NenoTV 30 Tage testen"));
            detailText.setText(L(
                "Start your trial to unlock NenoTV playback and Pro features.",
                "Start je proefperiode om NenoTV-afspelen en Pro-functies te ontgrendelen.",
                "Starte deine Testphase, um NenoTV-Wiedergabe und Pro-Funktionen freizuschalten."
            ));
            startTrial.setEnabled(true);
        }
        deviceText.setText(L("Device code","Apparaatcode","Gerätecode")+": "+ent.publicDeviceId());
    }

    void busy(boolean on){
        startTrial.setEnabled(!on && ent.level()==EntitlementStore.Level.TRIAL_REQUIRED);
        activate.setEnabled(!on);
        refresh.setEnabled(!on);
        viewPro.setEnabled(!on);
        if(on)serverText.setText(L("Checking NenoTV account…","NenoTV-account controleren…","NenoTV-Konto wird geprüft…"));
    }

    void startTrial(){
        String e=email.getText().toString().trim();
        if(e.isEmpty()||!android.util.Patterns.EMAIL_ADDRESS.matcher(e).matches()){
            email.setError(L("Enter a valid email address","Vul een geldig e-mailadres in","Gib eine gültige E-Mail-Adresse ein"));
            return;
        }
        busy(true);
        exec.execute(()->{
            try{
                new EntitlementClient(this).startTrial(e);
                runOnUiThread(()->{busy(false);serverText.setText(L("Trial status updated","Proefperiode bijgewerkt","Testphase aktualisiert"));refreshUi();});
            }catch(Exception ex){
                runOnUiThread(()->{busy(false);serverText.setText(safe(ex));refreshUi();});
            }
        });
    }

    void activatePro(){
        String token=activationCode.getText().toString().trim();
        if(token.isEmpty()){
            activationCode.setError(L("Enter an activation code","Vul een activatiecode in","Gib einen Aktivierungscode ein"));
            return;
        }
        busy(true);
        exec.execute(()->{
            try{
                new EntitlementClient(this).redeemToken(token);
                runOnUiThread(()->{busy(false);serverText.setText(L("NenoTV Pro activated","NenoTV Pro geactiveerd","NenoTV Pro aktiviert"));refreshUi();});
            }catch(Exception ex){
                runOnUiThread(()->{busy(false);serverText.setText(L("Activation failed: ","Activeren mislukt: ","Aktivierung fehlgeschlagen: ")+safe(ex));refreshUi();});
            }
        });
    }

    void refreshServer(){
        busy(true);
        exec.execute(()->{
            try{
                new EntitlementClient(this).refresh();
                runOnUiThread(()->{busy(false);serverText.setText(L("Account status updated","Accountstatus bijgewerkt","Kontostatus aktualisiert"));refreshUi();});
            }catch(Exception ex){
                runOnUiThread(()->{busy(false);serverText.setText(L("NenoTV account service unavailable","NenoTV-accountdienst niet beschikbaar","NenoTV-Kontodienst nicht verfügbar"));refreshUi();});
            }
        });
    }

    void refreshServerQuietly(){
        exec.execute(()->{try{new EntitlementClient(this).refresh();runOnUiThread(this::refreshUi);}catch(Exception ignored){}});
    }

    void handleIntent(Intent i){
        Uri u=i==null?null:i.getData();
        if(u==null)return;
        boolean custom="nenotv".equalsIgnoreCase(u.getScheme())&&"activate".equalsIgnoreCase(u.getHost());
        boolean web="https".equalsIgnoreCase(u.getScheme())&&"nenotv.com".equalsIgnoreCase(u.getHost())&&"/activate".equalsIgnoreCase(u.getPath());
        if(!custom&&!web)return;
        String token=u.getQueryParameter("token");
        if(token==null||token.trim().isEmpty())return;
        activationCode.setText(token);
        activatePro();
    }

    String safe(Exception e){
        String m=e==null?null:e.getMessage();
        return m==null||m.trim().isEmpty()?L("Unknown error","Onbekende fout","Unbekannter Fehler"):m;
    }

    void openPro(){
        String lang=SettingsStore.language(this);
        String url="https://nenotv.com/pro/";
        if("nl".equals(lang))url="https://nenotv.com/language/nl/nenotv-pro-nl/";
        else if("de".equals(lang))url="https://nenotv.com/language/de/nenotv-pro-de/";
        openWeb(url+"?device="+Uri.encode(ent.publicDeviceId()));
    }

    void openWeb(String url){
        try{startActivity(new Intent(Intent.ACTION_VIEW,Uri.parse(url)));}
        catch(Exception e){Toast.makeText(this,L("Website unavailable","Website niet beschikbaar","Website nicht verfügbar"),Toast.LENGTH_SHORT).show();}
    }

    @Override protected void onDestroy(){
        exec.shutdownNow();
        super.onDestroy();
    }
}
