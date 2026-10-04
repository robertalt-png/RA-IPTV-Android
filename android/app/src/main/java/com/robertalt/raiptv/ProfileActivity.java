package com.nenotv.player;

import android.app.*; import android.os.*; import android.view.*; import android.widget.*;
import com.nenotv.player.model.Profile; import com.nenotv.player.provider.*; import com.nenotv.player.storage.*;
import java.util.concurrent.*;

public class ProfileActivity extends Activity {
    EditText name,server,user,pass,m3u,epg; RadioButton xtream,m3uRadio,demoRadio; TextView status;
    LinearLayout xtreamFields,m3uFields,advancedFields; SecureProfileStore store; SourceStore sources; String sourceId=""; boolean newSource=false,websiteSetup=false; Profile editingProfile; ExecutorService exec=Executors.newSingleThreadExecutor();

    boolean websiteFirst,foreground,polling,pairLaunched;
    long setupBackgroundUntil;
    final Handler setupHandler=new Handler(Looper.getMainLooper());
    final Runnable sourcePoll=()->checkWebsiteSource();
    String accountUrl(){String l=SettingsStore.language(this);return "https://nenotv.com"+("nl".equals(l)?"/language/nl/mijn-account/":("de".equals(l)?"/language/de/mein-konto/":"/my-account/"))+"?nenotv_setup=1#nenotv-sources";}
    void openWebsite(){try{startActivity(new android.content.Intent(android.content.Intent.ACTION_VIEW,android.net.Uri.parse(accountUrl())));}catch(Exception ignored){status.setText(T("Open My NenoTV on your phone or computer.","Open Mijn NenoTV op uw telefoon of computer.","Öffne Mein NenoTV auf deinem Telefon oder Computer."));}}
    void startAccountSetup(){if(pairLaunched)return;pairLaunched=true;startActivityForResult(new android.content.Intent(this,PairingActivity.class).putExtra("setup",true),31);}
    void chooseOffer(){
        if(store.exists())return;
        new AlertDialog.Builder(this).setTitle(T("Choose your TV source","Kies uw tv-aanbod","TV-Angebot auswählen"))
            .setMessage(T("My NenoTV prepares your media package. Enter your provider here or on the website.","Mijn NenoTV bereidt uw mediapakket voor. Vul uw aanbieder hier of op de website in.","Mein NenoTV bereitet dein Medienpaket vor. Gib den Anbieter hier oder auf der Website ein."))
            .setPositiveButton(T("Website","Website","Website"),(d,w)->openWebsite())
            .setNegativeButton(T("This device","Dit apparaat","Dieses Gerät"),(d,w)->{xtream.setChecked(true);updateMode();}).show();
    }
    void checkWebsiteSource(){
        if((!foreground&&(!websiteFirst||SystemClock.elapsedRealtime()>=setupBackgroundUntil))||polling||connecting||!websiteSetup||!new EntitlementStore(this).isPro())return;
        polling=true;
        exec.execute(()->{
            try{new com.nenotv.player.entitlement.SourceSyncClient(this).pullAutomatically();}catch(Exception ignored){}
            runOnUiThread(()->{polling=false;if(isFinishing()||isDestroyed())return;if(store.exists()&&!sources.syncDirty()){setResult(RESULT_OK);finish();}else if(foreground||(websiteFirst&&SystemClock.elapsedRealtime()<setupBackgroundUntil))setupHandler.postDelayed(sourcePoll,5000);});
        });
    }

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
        websiteSetup=!store.exists();websiteFirst=getIntent().getBooleanExtra("website_first",false);
        applyLanguage(); load(); updateMode();
        Button website=new Button(this);website.setText(T("Set up via My NenoTV","Instellen via Mijn NenoTV","Über Mein NenoTV einrichten"));website.setAllCaps(false);website.setTextColor(0xFF07090D);website.setBackgroundTintList(android.content.res.ColorStateList.valueOf(0xFFFFD400));
        website.setOnClickListener(v->{websiteSetup=true;if(new EntitlementStore(this).isPro())openWebsite();else startAccountSetup();});
        LinearLayout container=(LinearLayout)findViewById(R.id.profileIntro).getParent();container.addView(website,2);

        findViewById(R.id.typeGroup).setOnClickListener(v->updateMode());
        xtream.setOnClickListener(v->updateMode()); m3uRadio.setOnClickListener(v->updateMode()); demoRadio.setOnClickListener(v->{updateMode();if(demoRadio.isEnabled())connectAndSave();});
        findViewById(R.id.advancedButton).setOnClickListener(v->advancedFields.setVisibility(advancedFields.getVisibility()==View.VISIBLE?View.GONE:View.VISIBLE));
        findViewById(R.id.saveButton).setOnClickListener(v->connectAndSave());
        if(websiteFirst&&!store.exists()){
            if(!new EntitlementStore(this).isPro())startAccountSetup();
            else if(!getIntent().getBooleanExtra("device_entry",false))openWebsite();
        }
    }

    @Override protected void onResume(){super.onResume();foreground=true;if(websiteSetup)setupHandler.post(sourcePoll);}
    @Override protected void onPause(){foreground=false;setupHandler.removeCallbacks(sourcePoll);if(websiteFirst){setupBackgroundUntil=SystemClock.elapsedRealtime()+300000;setupHandler.postDelayed(sourcePoll,5000);}super.onPause();}
    @Override protected void onActivityResult(int request,int result,android.content.Intent data){super.onActivityResult(request,result,data);if(request==31){pairLaunched=false;if(result==RESULT_OK){chooseOffer();setupHandler.post(sourcePoll);}}}

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
        boolean ownDemo=DemoPolicy.isDemo(p)&&demoRadio.isEnabled();
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

    boolean connecting=false;
    void connectAndSave(){
        if(!new EntitlementStore(this).isPro()){startAccountSetup();return;}
        if(new EntitlementStore(this).isPro()){
            final Profile chosen=collect();
            if(!demoRadio.isChecked()){
                android.net.Uri address=android.net.Uri.parse(chosen.type==Profile.Type.XTREAM?chosen.server:chosen.m3uUrl);
                if(!java.util.Arrays.asList("https","http").contains(address.getScheme())||address.getHost()==null||(chosen.type==Profile.Type.XTREAM&&(chosen.username.trim().isEmpty()||chosen.password.isEmpty()))){status.setText(T("Enter a valid provider URL and login details.","Vul een geldige aanbieder-URL en inloggegevens in.","Gib eine gültige Anbieter-URL und Zugangsdaten ein."));return;}
            }
            new AlertDialog.Builder(this).setTitle(T("Prepare via My NenoTV","Voorbereiden via Mijn NenoTV","Über Mein NenoTV vorbereiten"))
                .setMessage(T("Allow My NenoTV to retrieve your list using these details, store them encrypted and prepare the media package for your linked devices?","Mag Mijn NenoTV met deze gegevens uw lijst ophalen, versleuteld opslaan en het mediapakket voor uw gekoppelde apparaten voorbereiden?","Darf Mein NenoTV mit diesen Daten deine Liste abrufen, verschlüsselt speichern und das Medienpaket für deine verbundenen Geräte vorbereiten?"))
                .setNegativeButton(android.R.string.cancel,null).setPositiveButton(T("Agree and prepare","Akkoord en voorbereiden","Zustimmen und vorbereiten"),(d,w)->submitWebsiteSource(chosen,demoRadio.isChecked())).show();return;
        }

    }
    void submitWebsiteSource(Profile profile,boolean demo){
        if(connecting)return;
        if(demo&&DemoPolicy.expired(this)){status.setText(T("Demo ended","Demo afgelopen","Demo beendet"));return;}
        if(demo)profile.m3uUrl="https://nenotv.com/nenotv-demo.m3u";
        connecting=true;findViewById(R.id.saveButton).setEnabled(false);
        status.setText(T("My NenoTV is preparing your media package…","Mijn NenoTV bereidt uw mediapakket voor…","Mein NenoTV bereitet dein Medienpaket vor…"));
        exec.execute(()->{try{
            sourceId=new com.nenotv.player.entitlement.SourceSyncClient(this).submitInitialSource(profile,sourceId);
            if(demo)DemoPolicy.startOrKeep(this,System.currentTimeMillis());
            runOnUiThread(()->{setResult(RESULT_OK);finish();});
        }catch(Exception error){runOnUiThread(()->{connecting=false;findViewById(R.id.saveButton).setEnabled(true);status.setText(T("Could not prepare. Your previous library is kept.","Voorbereiden niet gelukt. Uw bestaande bibliotheek is behouden.","Vorbereitung fehlgeschlagen. Deine bisherige Bibliothek bleibt erhalten."));});}});
    }
    @Override protected void onDestroy(){super.onDestroy();setupHandler.removeCallbacksAndMessages(null);exec.shutdownNow();}
}
