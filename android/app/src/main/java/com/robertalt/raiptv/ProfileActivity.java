package com.nenotv.player;

import android.app.*; import android.os.*; import android.view.*; import android.widget.*;
import com.nenotv.player.model.Profile; import com.nenotv.player.provider.*; import com.nenotv.player.storage.*;
import java.util.concurrent.*;

public class ProfileActivity extends Activity {
    EditText name,server,user,pass,m3u,epg; RadioButton xtream,m3uRadio,demoRadio; TextView status;
    LinearLayout xtreamFields,m3uFields,advancedFields; SecureProfileStore store; SourceStore sources; String sourceId=""; boolean newSource=false; Profile editingProfile; ExecutorService exec=Executors.newSingleThreadExecutor();

    String T(String en,String nl,String de){
        String l=SettingsStore.language(this); if("nl".equals(l))return nl; if("de".equals(l))return de; return en;
    }

    @Override public void onCreate(Bundle b){
        super.onCreate(b); setContentView(R.layout.activity_profile); UiText.applyDirection(this); store=new SecureProfileStore(this); sources=new SourceStore(this); sourceId=getIntent().getStringExtra("source_id"); if(sourceId==null)sourceId=""; newSource=getIntent().getBooleanExtra("new_source",false);
        name=findViewById(R.id.nameField); server=findViewById(R.id.serverField); user=findViewById(R.id.userField); pass=findViewById(R.id.passField);
        m3u=findViewById(R.id.m3uField); epg=findViewById(R.id.epgField); xtream=findViewById(R.id.xtreamRadio); m3uRadio=findViewById(R.id.m3uRadio);
        demoRadio=findViewById(R.id.demoRadio); status=findViewById(R.id.profileStatus); xtreamFields=findViewById(R.id.xtreamFields);
        m3uFields=findViewById(R.id.m3uFields); advancedFields=findViewById(R.id.advancedFields);
        applyLanguage(); load(); updateMode();
        findViewById(R.id.typeGroup).setOnClickListener(v->updateMode());
        xtream.setOnClickListener(v->updateMode()); m3uRadio.setOnClickListener(v->updateMode()); demoRadio.setOnClickListener(v->{updateMode();if(demoRadio.isEnabled())connectAndSave();});
        findViewById(R.id.advancedButton).setOnClickListener(v->advancedFields.setVisibility(advancedFields.getVisibility()==View.VISIBLE?View.GONE:View.VISIBLE));
        findViewById(R.id.saveButton).setOnClickListener(v->connectAndSave());
    }

    void applyLanguage(){
        ((TextView)findViewById(R.id.profileTitle)).setText(T("TV source","TV-bron","TV-Quelle"));
        ((TextView)findViewById(R.id.profileIntro)).setText(T("How would you like to start?","Hoe wilt u NenoTV gebruiken?","Wie möchten Sie NenoTV verwenden?"));
        xtream.setText("Xtream Codes"); m3uRadio.setText("M3U");
        String demo=BuildConfig.NENOTV_DEMO_M3U_URL;
        boolean configured=demo!=null&&!demo.trim().isEmpty();
        boolean expired=DemoPolicy.expired(this);
        demoRadio.setText(!configured
            ?T("30-day free demo · not configured yet","30 dagen gratis demo · nog niet geconfigureerd","30 Tage kostenlose Demo · noch nicht eingerichtet")
            :expired
                ?T("30-day demo ended · add your own TV source","30 dagen demo afgelopen · voeg uw eigen TV-bron toe","30-Tage-Demo beendet · eigene TV-Quelle hinzufügen")
                :T("Try NenoTV free for 30 days","NenoTV 30 dagen gratis proberen","NenoTV 30 Tage kostenlos testen"));
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
        if(p==null){xtream.setChecked(true);return;}
        editingProfile=p;
        xtream.setChecked(p.type==Profile.Type.XTREAM); m3uRadio.setChecked(p.type==Profile.Type.M3U);
        name.setText(p.name); server.setText(p.server); user.setText(p.username); pass.setText(p.password); m3u.setText(p.m3uUrl); epg.setText(p.epgUrl);
    }

    void updateMode(){
        boolean isDemo=demoRadio.isChecked(), isM3u=m3uRadio.isChecked();
        xtreamFields.setVisibility(!isDemo&&!isM3u?View.VISIBLE:View.GONE);
        m3uFields.setVisibility(isM3u?View.VISIBLE:View.GONE);
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
                runOnUiThread(()->{connecting=false;xtream.setEnabled(true);m3uRadio.setEnabled(true);applyLanguage();findViewById(R.id.saveButton).setEnabled(true);status.setText(T("Could not connect: ","Kan geen verbinding maken: ","Verbindung fehlgeschlagen: ")+friendly(e));});
            }
        });
    }

    String friendly(Exception e){String m=e.getMessage();return m==null||m.trim().isEmpty()?T("Unknown error","Onbekende fout","Unbekannter Fehler"):m.replace("LOGIN_FAILED",T("Login failed","Inloggen mislukt","Anmeldung fehlgeschlagen"));}
    @Override protected void onDestroy(){super.onDestroy();exec.shutdownNow();}
}
