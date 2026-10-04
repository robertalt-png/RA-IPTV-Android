package com.nenotv.player;

import android.app.*; import android.os.*; import android.view.*; import android.widget.*;
import com.nenotv.player.model.Profile; import com.nenotv.player.provider.*; import com.nenotv.player.storage.*;
import java.util.concurrent.*;

public class ProfileActivity extends Activity {
    EditText name,server,user,pass,m3u,epg; RadioButton xtream,m3uRadio,demoRadio; TextView status;
    LinearLayout xtreamFields,m3uFields,advancedFields; SecureProfileStore store; SourceStore sources; String sourceId=""; boolean newSource=false,websiteSetup=false; Profile editingProfile; ExecutorService exec=Executors.newSingleThreadExecutor();

    String T(String en,String nl,String de){
        String l=SettingsStore.language(this); if("nl".equals(l))return nl; if("de".equals(l))return de; return en;
    }

    @Override public void onCreate(Bundle b){
        super.onCreate(b); setContentView(R.layout.activity_profile); UiText.applyDirection(this); store=new SecureProfileStore(this); sources=new SourceStore(this); sourceId=getIntent().getStringExtra("source_id"); if(sourceId==null)sourceId=""; newSource=getIntent().getBooleanExtra("new_source",false);
        ScreenInsets.browsing(this);
        name=findViewById(R.id.nameField); server=findViewById(R.id.serverField); user=findViewById(R.id.userField); pass=findViewById(R.id.passField);
        m3u=findViewById(R.id.m3uField); epg=findViewById(R.id.epgField); xtream=findViewById(R.id.xtreamRadio); m3uRadio=findViewById(R.id.m3uRadio);
        demoRadio=findViewById(R.id.demoRadio); status=findViewById(R.id.profileStatus); xtreamFields=findViewById(R.id.xtreamFields);
        m3uFields=findViewById(R.id.m3uFields); advancedFields=findViewById(R.id.advancedFields);
        websiteSetup=!store.exists();
        applyLanguage(); load(); updateMode();
        Button website=new Button(this);website.setText(T("Set up via My NenoTV","Instellen via Mijn NenoTV","Über Mein NenoTV einrichten"));website.setAllCaps(false);website.setTextColor(0xFF07090D);website.setBackgroundTintList(android.content.res.ColorStateList.valueOf(0xFFFFD400));
        website.setOnClickListener(v->{websiteSetup=true;startActivity(new android.content.Intent(this,PairingActivity.class));});
        LinearLayout container=(LinearLayout)findViewById(R.id.profileIntro).getParent();container.addView(website,2);

        findViewById(R.id.typeGroup).setOnClickListener(v->updateMode());
        xtream.setOnClickListener(v->updateMode()); m3uRadio.setOnClickListener(v->updateMode()); demoRadio.setOnClickListener(v->{updateMode();if(demoRadio.isEnabled())connectAndSave();});
        findViewById(R.id.advancedButton).setOnClickListener(v->advancedFields.setVisibility(advancedFields.getVisibility()==View.VISIBLE?View.GONE:View.VISIBLE));
        findViewById(R.id.saveButton).setOnClickListener(v->connectAndSave());
    }

    @Override protected void onResume(){
        super.onResume();
        if(new EntitlementStore(this).isPro()&&websiteSetup&&!newSource&&sourceId.isEmpty()){
            com.nenotv.player.entitlement.AutomaticSourceDownload.check(this,()->runOnUiThread(()->{
                if(!isFinishing()&&!isDestroyed()&&store.exists()){setResult(RESULT_OK);finish();}
            }));
            if(store.exists()&&new SourceStore(this).cloudRevision()>0&&!new SourceStore(this).syncDirty()){setResult(RESULT_OK);finish();}
        }
    }

    void applyLanguage(){
        ((TextView)findViewById(R.id.profileTitle)).setText("NenoTV");
        ((TextView)findViewById(R.id.profileIntro)).setText(T("Choose your TV source","Kies uw tv-aanbod","TV-Angebot auswählen"));
        xtream.setText(T("Own provider · Xtream Codes","Eigen aanbieder · Xtream Codes","Eigener Anbieter · Xtream Codes"));
        m3uRadio.setText(T("Own playlist · M3U","Eigen afspeellijst · M3U","Eigene Wiedergabeliste · M3U"));
        String demo=BuildConfig.NENOTV_DEMO_M3U_URL;
        boolean configured=demo!=null&&!demo.trim().isEmpty();
        boolean expired=DemoPolicy.expired(this);
        demoRadio.setText(!configured
            ?T("NenoTV TV & films · unavailable","NenoTV tv & films · niet beschikbaar","NenoTV TV & Filme · nicht verfügbar")
            :expired
                ?T("30-day demo ended · add your own TV source","30 dagen demo afgelopen · voeg uw eigen TV-bron toe","30-Tage-Demo beendet · eigene TV-Quelle hinzufügen")
                :T("NenoTV TV & films · 30 days free","NenoTV tv & films · 30 dagen gratis","NenoTV TV & Filme · 30 Tage kostenlos"));
        demoRadio.setEnabled(configured&&!expired);
        server.setHint(T("Server address","Serveradres","Serveradresse")); user.setHint(T("Username","Gebruikersnaam","Benutzername"));
        pass.setHint(T("Password","Wachtwoord","Passwort")); m3u.setHint("M3U-URL");
        name.setHint(T("Profile name (optional)","Profielnaam (optioneel)","Profilname (optional)"));
        epg.setHint(T("EPG URL (optional)","EPG-URL (optioneel)","EPG-URL (optional)"));
        ((Button)findViewById(R.id.advancedButton)).setText(T("Advanced settings","Geavanceerde instellingen","Erweiterte Einstellungen"));
        ((Button)findViewById(R.id.saveButton)).setText(T("Connect and continue","Verbinden en doorgaan","Verbinden und fortfahren"));
    }

    void load(){
        if(newSource){xtream.setChecked(true);editingProfile=null;return;}
        Profile p=null;
        if(!sourceId.isEmpty())for(SourceStore.Entry e:sources.list())if(sourceId.equals(e.id)){p=e.profile;break;}
        if(p==null&&store.exists())p=store.load();
        if(p==null){if(demoRadio.isEnabled())demoRadio.setChecked(true);else xtream.setChecked(true);return;}
        editingProfile=p;
        boolean ownDemo=p.type==Profile.Type.M3U&&BuildConfig.NENOTV_DEMO_M3U_URL.equals(p.m3uUrl)&&demoRadio.isEnabled();
        if(ownDemo)demoRadio.setChecked(true);else if(p.type==Profile.Type.M3U)m3uRadio.setChecked(true);else xtream.setChecked(true);
        name.setText(p.name); server.setText(p.server); user.setText(p.username); pass.setText(p.password); m3u.setText(p.m3uUrl); epg.setText(p.epgUrl);
    }

    void updateMode(){
        boolean isDemo=demoRadio.isChecked(), isM3u=m3uRadio.isChecked();
        xtreamFields.setVisibility(!isDemo&&!isM3u?View.VISIBLE:View.GONE);
        m3uFields.setVisibility(isM3u?View.VISIBLE:View.GONE);
        findViewById(R.id.nenoOffer).setVisibility(isDemo?View.VISIBLE:View.GONE);
        findViewById(R.id.advancedButton).setVisibility(isDemo?View.GONE:View.VISIBLE);
        ((TextView)findViewById(R.id.nenoOffer)).setText(T("Europe by Satellite · Europe by Satellite +\nOpen films: Sintel, Spring, Tears of Steel and more","Europe by Satellite · Europe by Satellite +\nOpen films: Sintel, Spring, Tears of Steel en meer","Europe by Satellite · Europe by Satellite +\nOpen Movies: Sintel, Spring, Tears of Steel und mehr"));
        ((Button)findViewById(R.id.saveButton)).setText(isDemo?T("Start watching","Start kijken","Jetzt ansehen"):T("Connect and continue","Verbinden en doorgaan","Verbinden und fortfahren"));
        if(isDemo)advancedFields.setVisibility(View.GONE);
    }

    Profile collect(){
        Profile old=editingProfile!=null?editingProfile:(store.exists()?store.load():new Profile()); Profile p=new Profile();
        if(demoRadio.isChecked()){
            p.type=Profile.Type.M3U; p.name="NenoTV Demo"; p.m3uUrl=BuildConfig.NENOTV_DEMO_M3U_URL; p.epgUrl="";
        }else if(m3uRadio.isChecked()){
            p.type=Profile.Type.M3U; p.name=valueOr(name,"M3U"); p.m3uUrl=m3u.getText().toString().trim(); p.epgUrl=epg.getText().toString().trim();
        }else{
            p.type=Profile.Type.XTREAM; p.name=valueOr(name,"Xtream"); p.server=server.getText().toString().trim();
            p.username=user.getText().toString().trim(); p.password=pass.getText().toString(); p.epgUrl=epg.getText().toString().trim();
        }
        p.bridgeUrl=old.bridgeUrl; p.bridgeToken=old.bridgeToken; return p;
    }

    String valueOr(EditText e,String fallback){String x=e.getText().toString().trim();return x.isEmpty()?fallback:x;}
    Provider provider(Profile p){return p.type==Profile.Type.XTREAM?new XtreamProvider(p):new M3uProvider(p,SettingsStore.primaryLanguage(this));}

    boolean connecting=false;
    void connectAndSave(){
        if(connecting)return;
        final boolean demoSelected=demoRadio.isChecked();
        final Profile p=collect();
        if(demoRadio.isChecked()&&(p.m3uUrl==null||p.m3uUrl.trim().isEmpty())){status.setText(T("Demo source is not configured yet.","Demo-bron is nog niet geconfigureerd.","Demo-Quelle ist noch nicht eingerichtet."));return;}
        if(demoRadio.isChecked()&&DemoPolicy.expired(this)){status.setText(T("Your 30-day demo has ended. Add your own M3U or Xtream source.","Uw 30 dagen demo is afgelopen. Voeg uw eigen M3U- of Xtream-bron toe.","Ihre 30-Tage-Demo ist beendet. Fügen Sie eine eigene M3U- oder Xtream-Quelle hinzu."));return;}
        status.setText(T("Connecting…","Verbinden…","Verbindung wird hergestellt…"));
        connecting=true;xtream.setEnabled(false);m3uRadio.setEnabled(false);demoRadio.setEnabled(false);
        findViewById(R.id.saveButton).setEnabled(false);
        exec.execute(()->{
            try{
                provider(p).authenticate();
                if(demoSelected){
                    long expiry=DemoPolicy.startOrKeep(this,System.currentTimeMillis());
                    if(expiry<0L)throw new IllegalStateException(T("Your 30-day demo has ended.","Uw 30 dagen demo is afgelopen.","Ihre 30-Tage-Demo ist beendet."));
                }
                store.save(p);
                sourceId=sources.upsert(sourceId,p,true);
                runOnUiThread(()->{setResult(RESULT_OK);finish();});
            }catch(Exception e){
                runOnUiThread(()->{connecting=false;xtream.setEnabled(true);m3uRadio.setEnabled(true);applyLanguage();updateMode();findViewById(R.id.saveButton).setEnabled(true);status.setText(T("Could not connect: ","Kan geen verbinding maken: ","Verbindung fehlgeschlagen: ")+friendly(e));});
            }
        });
    }

    String friendly(Exception e){String m=e.getMessage();return m==null||m.trim().isEmpty()?T("Unknown error","Onbekende fout","Unbekannter Fehler"):m.replace("LOGIN_FAILED",T("Login failed","Inloggen mislukt","Anmeldung fehlgeschlagen"));}
    @Override protected void onDestroy(){super.onDestroy();exec.shutdownNow();}
}
