from pathlib import Path

PROFILE = r'''package com.nenotv.player;

import android.app.*; import android.os.*; import android.view.*; import android.widget.*;
import com.nenotv.player.model.Profile; import com.nenotv.player.provider.*; import com.nenotv.player.storage.*;
import java.util.concurrent.*;

public class ProfileActivity extends Activity {
    EditText name,server,user,pass,m3u,epg; RadioButton xtream,m3uRadio,demoRadio; TextView status;
    LinearLayout xtreamFields,m3uFields,advancedFields; SecureProfileStore store; ExecutorService exec=Executors.newSingleThreadExecutor();

    String T(String en,String nl,String de){
        String l=SettingsStore.language(this); if("nl".equals(l))return nl; if("de".equals(l))return de; return en;
    }

    @Override public void onCreate(Bundle b){
        super.onCreate(b); setContentView(R.layout.activity_profile); UiText.applyDirection(this); store=new SecureProfileStore(this);
        name=findViewById(R.id.nameField); server=findViewById(R.id.serverField); user=findViewById(R.id.userField); pass=findViewById(R.id.passField);
        m3u=findViewById(R.id.m3uField); epg=findViewById(R.id.epgField); xtream=findViewById(R.id.xtreamRadio); m3uRadio=findViewById(R.id.m3uRadio);
        demoRadio=findViewById(R.id.demoRadio); status=findViewById(R.id.profileStatus); xtreamFields=findViewById(R.id.xtreamFields);
        m3uFields=findViewById(R.id.m3uFields); advancedFields=findViewById(R.id.advancedFields);
        applyLanguage(); load(); updateMode();
        findViewById(R.id.typeGroup).setOnClickListener(v->updateMode());
        xtream.setOnClickListener(v->updateMode()); m3uRadio.setOnClickListener(v->updateMode()); demoRadio.setOnClickListener(v->updateMode());
        findViewById(R.id.advancedButton).setOnClickListener(v->advancedFields.setVisibility(advancedFields.getVisibility()==View.VISIBLE?View.GONE:View.VISIBLE));
        findViewById(R.id.saveButton).setOnClickListener(v->connectAndSave());
    }

    void applyLanguage(){
        ((TextView)findViewById(R.id.profileTitle)).setText(T("TV source","TV-bron","TV-Quelle"));
        ((TextView)findViewById(R.id.profileIntro)).setText(T("How would you like to start?","Hoe wilt u NenoTV gebruiken?","Wie möchten Sie NenoTV verwenden?"));
        xtream.setText("Xtream Codes"); m3uRadio.setText("M3U");
        String demo=BuildConfig.NENOTV_DEMO_M3U_URL;
        demoRadio.setText(demo==null||demo.trim().isEmpty()
            ?T("30-day free demo · not configured yet","30 dagen gratis demo · nog niet geconfigureerd","30 Tage kostenlose Demo · noch nicht eingerichtet")
            :T("Try NenoTV free for 30 days","NenoTV 30 dagen gratis proberen","NenoTV 30 Tage kostenlos testen"));
        demoRadio.setEnabled(demo!=null&&!demo.trim().isEmpty());
        server.setHint(T("Server address","Serveradres","Serveradresse")); user.setHint(T("Username","Gebruikersnaam","Benutzername"));
        pass.setHint(T("Password","Wachtwoord","Passwort")); m3u.setHint("M3U-URL");
        name.setHint(T("Profile name (optional)","Profielnaam (optioneel)","Profilname (optional)"));
        epg.setHint(T("EPG URL (optional)","EPG-URL (optioneel)","EPG-URL (optional)"));
        ((Button)findViewById(R.id.advancedButton)).setText(T("Advanced settings","Geavanceerde instellingen","Erweiterte Einstellungen"));
        ((Button)findViewById(R.id.saveButton)).setText(T("Connect and continue","Verbinden en doorgaan","Verbinden und fortfahren"));
    }

    void load(){
        if(!store.exists()){xtream.setChecked(true);return;}
        Profile p=store.load(); xtream.setChecked(p.type==Profile.Type.XTREAM); m3uRadio.setChecked(p.type==Profile.Type.M3U);
        name.setText(p.name); server.setText(p.server); user.setText(p.username); pass.setText(p.password); m3u.setText(p.m3uUrl); epg.setText(p.epgUrl);
    }

    void updateMode(){
        boolean isDemo=demoRadio.isChecked(), isM3u=m3uRadio.isChecked();
        xtreamFields.setVisibility(!isDemo&&!isM3u?View.VISIBLE:View.GONE);
        m3uFields.setVisibility(isM3u?View.VISIBLE:View.GONE);
        if(isDemo)advancedFields.setVisibility(View.GONE);
    }

    Profile collect(){
        Profile old=store.exists()?store.load():new Profile(); Profile p=new Profile();
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

    void connectAndSave(){
        final Profile p=collect();
        if(demoRadio.isChecked()&&(p.m3uUrl==null||p.m3uUrl.trim().isEmpty())){status.setText(T("Demo source is not configured yet.","Demo-bron is nog niet geconfigureerd.","Demo-Quelle ist noch nicht eingerichtet."));return;}
        status.setText(T("Connecting…","Verbinden…","Verbindung wird hergestellt…"));
        findViewById(R.id.saveButton).setEnabled(false);
        exec.execute(()->{
            try{
                provider(p).authenticate();
                if(demoRadio.isChecked()){
                    long now=System.currentTimeMillis();
                    SettingsStore.prefs(this).edit().putLong("demo_started_at",now).putLong("demo_expires_at",now+30L*24L*60L*60L*1000L).apply();
                }
                store.save(p);
                runOnUiThread(()->{setResult(RESULT_OK);finish();});
            }catch(Exception e){
                runOnUiThread(()->{findViewById(R.id.saveButton).setEnabled(true);status.setText(T("Could not connect: ","Kan geen verbinding maken: ","Verbindung fehlgeschlagen: ")+friendly(e));});
            }
        });
    }

    String friendly(Exception e){String m=e.getMessage();return m==null||m.trim().isEmpty()?T("Unknown error","Onbekende fout","Unbekannter Fehler"):m.replace("LOGIN_FAILED",T("Login failed","Inloggen mislukt","Anmeldung fehlgeschlagen"));}
    @Override protected void onDestroy(){super.onDestroy();exec.shutdownNow();}
}
'''

LAYOUT = r'''<?xml version="1.0" encoding="utf-8"?>
<ScrollView xmlns:android="http://schemas.android.com/apk/res/android" android:layout_width="match_parent" android:layout_height="match_parent" android:background="@color/bg">
  <LinearLayout android:layout_width="match_parent" android:layout_height="wrap_content" android:orientation="vertical" android:padding="20dp">
    <TextView android:id="@+id/profileTitle" android:layout_width="match_parent" android:layout_height="wrap_content" android:textColor="@color/text" android:textSize="28sp" android:textStyle="bold" />
    <TextView android:id="@+id/profileIntro" android:layout_width="match_parent" android:layout_height="wrap_content" android:textColor="@color/muted" android:paddingTop="4dp" android:paddingBottom="14dp" />
    <RadioGroup android:id="@+id/typeGroup" android:layout_width="match_parent" android:layout_height="wrap_content" android:orientation="vertical">
      <RadioButton android:id="@+id/xtreamRadio" android:layout_width="match_parent" android:layout_height="48dp" android:checked="true" android:textColor="@color/text" />
      <RadioButton android:id="@+id/m3uRadio" android:layout_width="match_parent" android:layout_height="48dp" android:textColor="@color/text" />
      <RadioButton android:id="@+id/demoRadio" android:layout_width="match_parent" android:layout_height="48dp" android:textColor="@color/text" />
    </RadioGroup>
    <LinearLayout android:id="@+id/xtreamFields" android:layout_width="match_parent" android:layout_height="wrap_content" android:orientation="vertical">
      <EditText android:id="@+id/serverField" android:layout_width="match_parent" android:layout_height="52dp" android:layout_marginTop="8dp" android:inputType="textUri" android:textColor="@color/text" android:textColorHint="@color/muted" android:background="@drawable/bg_search" android:paddingLeft="14dp" android:paddingRight="14dp" />
      <EditText android:id="@+id/userField" android:layout_width="match_parent" android:layout_height="52dp" android:layout_marginTop="8dp" android:textColor="@color/text" android:textColorHint="@color/muted" android:background="@drawable/bg_search" android:paddingLeft="14dp" android:paddingRight="14dp" />
      <EditText android:id="@+id/passField" android:layout_width="match_parent" android:layout_height="52dp" android:layout_marginTop="8dp" android:inputType="textPassword" android:textColor="@color/text" android:textColorHint="@color/muted" android:background="@drawable/bg_search" android:paddingLeft="14dp" android:paddingRight="14dp" />
    </LinearLayout>
    <LinearLayout android:id="@+id/m3uFields" android:layout_width="match_parent" android:layout_height="wrap_content" android:orientation="vertical" android:visibility="gone">
      <EditText android:id="@+id/m3uField" android:layout_width="match_parent" android:layout_height="52dp" android:layout_marginTop="8dp" android:inputType="textUri" android:textColor="@color/text" android:textColorHint="@color/muted" android:background="@drawable/bg_search" android:paddingLeft="14dp" android:paddingRight="14dp" />
    </LinearLayout>
    <Button android:id="@+id/advancedButton" android:layout_width="match_parent" android:layout_height="48dp" android:layout_marginTop="12dp" android:textAllCaps="false" android:textColor="@color/text" android:backgroundTint="@color/panel2" />
    <LinearLayout android:id="@+id/advancedFields" android:layout_width="match_parent" android:layout_height="wrap_content" android:orientation="vertical" android:visibility="gone">
      <EditText android:id="@+id/nameField" android:layout_width="match_parent" android:layout_height="52dp" android:layout_marginTop="8dp" android:textColor="@color/text" android:textColorHint="@color/muted" android:background="@drawable/bg_search" android:paddingLeft="14dp" android:paddingRight="14dp" />
      <EditText android:id="@+id/epgField" android:layout_width="match_parent" android:layout_height="52dp" android:layout_marginTop="8dp" android:inputType="textUri" android:textColor="@color/text" android:textColorHint="@color/muted" android:background="@drawable/bg_search" android:paddingLeft="14dp" android:paddingRight="14dp" />
    </LinearLayout>
    <Button android:id="@+id/saveButton" android:layout_width="match_parent" android:layout_height="54dp" android:layout_marginTop="14dp" android:textAllCaps="false" android:textColor="#FFFFFF" android:backgroundTint="@color/accent" />
    <TextView android:id="@+id/profileStatus" android:layout_width="match_parent" android:layout_height="wrap_content" android:textColor="@color/muted" android:padding="8dp" />
  </LinearLayout>
</ScrollView>
'''

def patch_uitext(path: Path):
    s=path.read_text(encoding="utf-8")
    pairs=[
      ('{"bridge_token_optional","Bridge token (optional)"},','{"bridge_token_optional","Bridge token (optional)"},\n        {"bridge_help","Optional advanced setting for an external NAS subtitle bridge. Not required for normal TV, M3U or Xtream use."},'),
      ('{"bridge_token_optional","Bridge-token (optioneel)"},','{"bridge_token_optional","Bridge-token (optioneel)"},\n        {"bridge_help","Optionele geavanceerde instelling voor een externe NAS-ondertitelingsbridge. Niet nodig voor normaal TV-, M3U- of Xtream-gebruik."},'),
      ('{"bridge_token_optional","Bridge-Token (optional)"},','{"bridge_token_optional","Bridge-Token (optional)"},\n        {"bridge_help","Optionale erweiterte Einstellung für eine externe NAS-Untertitel-Bridge. Für die normale Nutzung mit TV, M3U oder Xtream nicht erforderlich."},')
    ]
    for old,new in pairs:
        if old in s and '"bridge_help"' not in s[s.find(old):s.find(old)+500]:
            s=s.replace(old,new,1)
    path.write_text(s,encoding="utf-8")

def patch_settings(path: Path):
    s=path.read_text(encoding="utf-8")
    marker='    sec(T("maintenance"));'
    if marker not in s: raise SystemExit("settings maintenance marker missing")
    add='''    sec(T("advanced_subtitles"));Button bridge=b(T("external_subtitle_bridge"));bridge.setOnClickListener(v->editSubtitleBridge());box.addView(bridge,new LinearLayout.LayoutParams(-1,dp(50)));\\n'''
    s=s.replace(marker,add+marker,1)
    insert='''  void editSubtitleBridge(){SecureProfileStore store=new SecureProfileStore(this);if(!store.exists()){Toast.makeText(this,T("profile_required"),Toast.LENGTH_SHORT).show();return;}com.nenotv.player.model.Profile profile=store.load();LinearLayout wrap=new LinearLayout(this);wrap.setOrientation(LinearLayout.VERTICAL);wrap.setPadding(dp(18),0,dp(18),0);EditText url=new EditText(this);url.setHint(T("bridge_url"));url.setInputType(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_VARIATION_URI);url.setText(profile.bridgeUrl);EditText token=new EditText(this);token.setHint(T("bridge_token_optional"));token.setInputType(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_VARIATION_PASSWORD);token.setText(profile.bridgeToken);wrap.addView(url);wrap.addView(token);new AlertDialog.Builder(this).setTitle(T("external_subtitle_bridge")).setMessage(T("bridge_help")).setView(wrap).setNegativeButton(T("cancel"),null).setPositiveButton(T("save"),(d,w)->{profile.bridgeUrl=url.getText().toString().trim();profile.bridgeToken=token.getText().toString();store.save(profile);Toast.makeText(this,T("saved"),Toast.LENGTH_SHORT).show();}).show();}\\n'''
    s=s.replace('  String languageName(String code){',insert+'  String languageName(String code){',1)
    path.write_text(s,encoding="utf-8")

root=Path(".")
profile=root/"app/src/main/java/com/robertalt/raiptv/ProfileActivity.java"
layout=root/"app/src/main/res/layout/activity_profile.xml"
settings=root/"app/src/main/java/com/robertalt/raiptv/SettingsActivity.java"
ui=root/"app/src/main/java/com/robertalt/raiptv/UiText.java"
for p in (profile,layout,settings,ui):
    if not p.exists(): raise SystemExit(f"missing {p}")
profile.write_text(PROFILE,encoding="utf-8")
layout.write_text(LAYOUT,encoding="utf-8")
patch_settings(settings)
patch_uitext(ui)
print("Applied explicit v0.13.3 source onboarding")
