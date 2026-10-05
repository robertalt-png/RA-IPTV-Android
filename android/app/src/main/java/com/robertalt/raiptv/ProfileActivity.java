package com.nenotv.player;
import android.content.Intent;

import android.app.*; import android.os.*; import android.view.*; import android.widget.*;
import com.nenotv.player.model.Profile; import com.nenotv.player.provider.*; import com.nenotv.player.storage.*;
import java.util.concurrent.*;

public class ProfileActivity extends Activity {
    interface LocalConnection { void authenticate(Profile profile,String language)throws Exception; }
    static LocalConnection localConnection=(profile,language)->{
        Provider provider=profile.type==Profile.Type.XTREAM?new XtreamProvider(profile):new M3uProvider(profile,language);
        provider.authenticate();
    };
    EditText name,server,user,pass,m3u,epg; RadioButton xtream,m3uRadio,demoRadio; TextView status;
    LinearLayout xtreamFields,m3uFields,advancedFields; SecureProfileStore store; SourceStore sources; String sourceId=""; boolean newSource=false,websiteSetup=false; Profile editingProfile; ExecutorService exec=Executors.newSingleThreadExecutor();

    boolean websiteFirst,foreground,polling,pairLaunched;
    long setupBackgroundUntil;
    final Handler setupHandler=new Handler(Looper.getMainLooper());
    final Runnable sourcePoll=()->checkWebsiteSource();
    String accountUrl(){String l=SettingsStore.language(this);return "https://sunnyiptv.com"+("nl".equals(l)?"/language/nl/mijn-account/":("de".equals(l)?"/language/de/mein-konto/":"/my-account/"))+"?nenotv_setup=1#nenotv-sources";}
    void openWebsite(){try{startActivity(new android.content.Intent(android.content.Intent.ACTION_VIEW,android.net.Uri.parse(accountUrl())));}catch(Exception ignored){status.setText(T("Open My SunnyIPTV on your phone or computer.","Open Mijn SunnyIPTV op uw telefoon of computer.","Öffne Mein SunnyIPTV auf deinem Telefon oder Computer."));}}
    void startAccountSetup(){if(pairLaunched)return;pairLaunched=true;startActivityForResult(new android.content.Intent(this,PairingActivity.class).putExtra("setup",true),31);}
    void chooseOffer(){
        if(store.exists())return;
        new AlertDialog.Builder(this).setTitle(T("Choose your TV source","Kies uw tv-aanbod","TV-Angebot auswählen"))
            .setMessage(T("My SunnyIPTV prepares your media package. Enter your provider here or on the website.","Mijn SunnyIPTV bereidt uw mediapakket voor. Vul uw aanbieder hier of op de website in.","Mein SunnyIPTV bereitet dein Medienpaket vor. Gib den Anbieter hier oder auf der Website ein."))
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
        super.onCreate(b);if(FamilyStore.active(this)){FamilyUi.blocked(this);finish();return;}if(!new com.nenotv.player.storage.AccountLinkStore(this).recent()){startActivity(new Intent(this,AccountCheckActivity.class));finish();return;} setContentView(R.layout.activity_profile); UiText.applyDirection(this); store=new SecureProfileStore(this); sources=new SourceStore(this); sourceId=getIntent().getStringExtra("source_id"); if(sourceId==null)sourceId=""; newSource=getIntent().getBooleanExtra("new_source",false);
        ScreenInsets.browsing(this);
        name=findViewById(R.id.nameField); server=findViewById(R.id.serverField); user=findViewById(R.id.userField); pass=findViewById(R.id.passField);
        m3u=findViewById(R.id.m3uField); epg=findViewById(R.id.epgField); xtream=findViewById(R.id.xtreamRadio); m3uRadio=findViewById(R.id.m3uRadio);
        demoRadio=findViewById(R.id.demoRadio); status=findViewById(R.id.profileStatus); xtreamFields=findViewById(R.id.xtreamFields);
        m3uFields=findViewById(R.id.m3uFields); advancedFields=findViewById(R.id.advancedFields);
        websiteSetup=false;websiteFirst=false;
        applyLanguage(); load(); updateMode();
        Button website=new Button(this);website.setText(T("Link devices (optional)","Apparaten koppelen (optioneel)","Geräte verbinden (optional)"));website.setTag("optional_device_link");website.setAllCaps(false);website.setMinHeight(Math.round(56*getResources().getDisplayMetrics().density));website.setTextColor(0xFFF7F8FA);website.setBackgroundTintList(android.content.res.ColorStateList.valueOf(0xFF1B2028));
        website.setOnClickListener(v->{websiteSetup=true;if(new EntitlementStore(this).isPro())openWebsite();else startAccountSetup();});
        LinearLayout container=(LinearLayout)findViewById(R.id.profileIntro).getParent();container.addView(website,container.getChildCount()-1);

        findViewById(R.id.typeGroup).setOnClickListener(v->updateMode());
        xtream.setOnClickListener(v->updateMode()); m3uRadio.setOnClickListener(v->updateMode()); demoRadio.setOnClickListener(v->{updateMode();if(demoRadio.isEnabled())connectAndSave();});
        findViewById(R.id.advancedButton).setOnClickListener(v->advancedFields.setVisibility(advancedFields.getVisibility()==View.VISIBLE?View.GONE:View.VISIBLE));
        findViewById(R.id.saveButton).setOnClickListener(v->connectAndSave());
    }

    @Override protected void onResume(){super.onResume();foreground=true;if(websiteSetup)setupHandler.post(sourcePoll);}
    @Override protected void onPause(){foreground=false;setupHandler.removeCallbacks(sourcePoll);if(websiteFirst){setupBackgroundUntil=SystemClock.elapsedRealtime()+300000;setupHandler.postDelayed(sourcePoll,5000);}super.onPause();}
    @Override protected void onActivityResult(int request,int result,android.content.Intent data){super.onActivityResult(request,result,data);if(request==31){pairLaunched=false;if(result==RESULT_OK){chooseOffer();setupHandler.post(sourcePoll);}}}

    void applyLanguage(){
        ((TextView)findViewById(R.id.profileTitle)).setText("SunnyIPTV");
        ((TextView)findViewById(R.id.profileIntro)).setText(T("Choose your TV source","Kies uw tv-bron","TV-Quelle waehlen"));
        xtream.setText(T("Own provider · Xtream Codes","Eigen aanbieder · Xtream Codes","Eigener Anbieter · Xtream Codes"));
        m3uRadio.setText(T("Own playlist · M3U","Eigen afspeellijst · M3U","Eigene Wiedergabeliste · M3U"));
        String demo=BuildConfig.NENOTV_DEMO_M3U_URL;
        boolean configured=demo!=null&&!demo.trim().isEmpty();
        boolean expired=DemoPolicy.expired(this);
        demoRadio.setText(!configured
            ?T("SunnyIPTV TV & films · unavailable","SunnyIPTV tv & films · niet beschikbaar","SunnyIPTV TV & Filme · nicht verfügbar")
            :expired
                ?T("30-day demo ended · add your own TV source","30 dagen demo afgelopen · voeg uw eigen TV-bron toe","30-Tage-Demo beendet · eigene TV-Quelle hinzufügen")
                :T("SunnyIPTV TV & films · 30 days free","SunnyIPTV tv & films · 30 dagen gratis","SunnyIPTV TV & Filme · 30 Tage kostenlos"));
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
        ((Button)findViewById(R.id.saveButton)).setText(isDemo?T("Start watching","Start kijken","Jetzt ansehen"):T("Connect","Verbinden","Verbinden"));
        if(isDemo)advancedFields.setVisibility(View.GONE);
    }

    Profile collect(){
        Profile old=editingProfile!=null?editingProfile:(store.exists()?store.load():new Profile()); Profile p=new Profile();
        if(demoRadio.isChecked()){
            p.type=Profile.Type.M3U; p.name="SunnyIPTV Demo"; p.m3uUrl=BuildConfig.NENOTV_DEMO_M3U_URL; p.epgUrl="";
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
        if(connecting||FamilyStore.active(this))return;
            final Profile chosen=collect();
            if(!demoRadio.isChecked()){
                android.net.Uri address=android.net.Uri.parse(chosen.type==Profile.Type.XTREAM?chosen.server:chosen.m3uUrl);
                if(!java.util.Arrays.asList("https","http").contains(address.getScheme())||address.getHost()==null||(chosen.type==Profile.Type.XTREAM&&(chosen.username.trim().isEmpty()||chosen.password.isEmpty()))){status.setText(T("Enter a valid provider URL and login details.","Vul een geldige aanbieder-URL en inloggegevens in.","Gib eine gültige Anbieter-URL und Zugangsdaten ein."));return;}
            }
            final boolean demo=demoRadio.isChecked();
            if(demo&&DemoPolicy.expired(this)){status.setText(T("Demo ended","Demo afgelopen","Demo beendet"));return;}
            final String language=SettingsStore.language(this);
            final long sourceRevision=sources.localRevision();
            final String destination=sourceId.isEmpty()&&!newSource?sources.activeId():sourceId;
            connecting=true;findViewById(R.id.saveButton).setEnabled(false);
            status.setText(T("Connecting…","Verbinden…","Verbindung wird hergestellt…"));
            exec.execute(()->{try{
                localConnection.authenticate(chosen,language);
                if(Thread.currentThread().isInterrupted())return;
                runOnUiThread(()->{
                    if(isFinishing()||isDestroyed())return;
                    try{
                        if(FamilyStore.active(this))throw new IllegalStateException("FAMILY_ACTIVE");
                        if(sources.localRevision()!=sourceRevision)throw new IllegalStateException("LOCAL_SOURCES_CHANGED");
                        sources.upsert(destination,chosen,true);
                        sources.setAutomaticDownloadEnabled(false);
                        if(demo)DemoPolicy.startOrKeep(this,System.currentTimeMillis());
                        setResult(RESULT_OK);finish();
                    }catch(Exception error){localFailure();}
                });
            }catch(Exception error){runOnUiThread(()->{if(!isFinishing()&&!isDestroyed())localFailure();});}});
    }
    void localFailure(){connecting=false;findViewById(R.id.saveButton).setEnabled(true);status.setText(T("Could not connect. Check your provider details. Your existing library is kept.","Verbinden niet gelukt. Controleer de gegevens van uw aanbieder. Uw bestaande bibliotheek blijft behouden.","Verbindung fehlgeschlagen. Prüfe die Zugangsdaten deines Anbieters. Deine bisherige Bibliothek bleibt erhalten."));}
    void submitWebsiteSource(Profile profile,boolean demo){
        if(connecting)return;
        if(demo&&DemoPolicy.expired(this)){status.setText(T("Demo ended","Demo afgelopen","Demo beendet"));return;}
        if(demo)profile.m3uUrl="https://sunnyiptv.com/sunnyiptv-demo.m3u";
        connecting=true;findViewById(R.id.saveButton).setEnabled(false);
        status.setText(T("My SunnyIPTV is preparing your media package…","Mijn SunnyIPTV bereidt uw mediapakket voor…","Mein SunnyIPTV bereitet dein Medienpaket vor…"));
        exec.execute(()->{try{
            sourceId=new com.nenotv.player.entitlement.SourceSyncClient(this).submitInitialSource(profile,sourceId);
            if(demo)DemoPolicy.startOrKeep(this,System.currentTimeMillis());
            runOnUiThread(()->{setResult(RESULT_OK);finish();});
        }catch(Exception error){runOnUiThread(()->{connecting=false;findViewById(R.id.saveButton).setEnabled(true);status.setText(T("Could not prepare. Your previous library is kept.","Voorbereiden niet gelukt. Uw bestaande bibliotheek is behouden.","Vorbereitung fehlgeschlagen. Deine bisherige Bibliothek bleibt erhalten."));});}});
    }
    @Override protected void onDestroy(){super.onDestroy();setupHandler.removeCallbacksAndMessages(null);exec.shutdownNow();}
}
