from pathlib import Path
import re

root=Path('source/RA_IPTV_Android_v0.1')
ui=root/'app/src/main/java/com/robertalt/raiptv/UiText.java'
orig=ui.read_text()

langs_order=['en','nl','es','de','fr','it','pt','tr','pl','ar']
existing={}
for idx,lang in enumerate(['en','nl','es']):
    start=orig.index(f'static String {lang}')
    end=orig.find('static String ',start+10)
    if end<0:end=len(orig)
    seg=orig[start:end]
    existing[lang]={k:v for k,v in re.findall(r'case "([^"]+)":return "([^"]*)";',seg)}
    assert len(existing[lang])==214,(lang,len(existing[lang]))

rows={}
for line in Path('patches/v0.6.1-localization/translations_7.tsv').read_text().splitlines():
    parts=line.split('\t'); assert len(parts)==8,parts[0]
    k=parts[0]; rows[k]=dict(zip(['de','fr','it','pt','tr','pl','ar'],parts[1:]))
assert len(rows)==214
for lang in ['de','fr','it','pt','tr','pl','ar']:
    existing[lang]={k:rows[k][lang] for k in existing['en']}

for line in Path('patches/v0.6.1-localization/translations_extra.tsv').read_text().splitlines():
    parts=line.split('\t'); assert len(parts)==11,parts[0]
    k=parts[0]
    for lang,val in zip(langs_order,parts[1:]): existing[lang][k]=val

keys=list(existing['en'].keys())
assert len(keys)==len(set(keys))
for lang in langs_order:
    assert set(existing[lang])==set(keys),(lang,len(existing[lang]),len(keys),set(keys)-set(existing[lang]))

def esc(s):
    return s.replace('\\','\\\\').replace('"','\\"').replace('\n','\\n')

out=[]
out.append('package com.robertalt.raiptv;\n')
out.append('import android.app.Activity;\nimport android.content.Context;\nimport android.view.View;\nimport com.robertalt.raiptv.storage.SettingsStore;\nimport java.util.*;\n\n')
out.append('/** Complete runtime UI dictionary for every supported app language. */\npublic final class UiText {\n    private UiText(){}\n')
out.append('    private static Map<String,String> map(String[][] rows){LinkedHashMap<String,String> m=new LinkedHashMap<>();for(String[] r:rows)m.put(r[0],r[1]);return Collections.unmodifiableMap(m);}\n')
for lang in langs_order:
    out.append(f'    private static final Map<String,String> {lang.upper()}=map(new String[][]{{\n')
    for k in keys:
        out.append(f'        {{"{esc(k)}","{esc(existing[lang][k])}"}},\n')
    out.append('    });\n')
out.append('    public static String t(Context c,String key){return t(SettingsStore.language(c),key);}\n')
out.append('    public static String t(String lang,String key){Map<String,String> m;switch(lang==null?"en":lang){case "nl":m=NL;break;case "es":m=ES;break;case "de":m=DE;break;case "fr":m=FR;break;case "it":m=IT;break;case "pt":m=PT;break;case "tr":m=TR;break;case "pl":m=PL;break;case "ar":m=AR;break;default:m=EN;}String v=m.get(key);if(v==null)v=EN.get(key);return v==null?key:v;}\n')
out.append('    public static void applyDirection(Activity a){if(a==null)return;boolean rtl="ar".equals(SettingsStore.language(a));View v=a.getWindow().getDecorView();v.setLayoutDirection(rtl?View.LAYOUT_DIRECTION_RTL:View.LAYOUT_DIRECTION_LTR);v.setTextDirection(View.TEXT_DIRECTION_LOCALE);}\n')
out.append('}\n')
ui.write_text(''.join(out))

# MainActivity localization cleanup
p=root/'app/src/main/java/com/robertalt/raiptv/MainActivity.java'; s=p.read_text()
s=s.replace('super.onCreate(b);CrashGuard.install(this);setContentView(R.layout.activity_main);','super.onCreate(b);CrashGuard.install(this);setContentView(R.layout.activity_main);UiText.applyDirection(this);',1)
s=s.replace('busy(false,"Inloggen mislukt: "+friendly(e))','busy(false,T("login_failed_prefix")+": "+friendly(e))')
s=s.replace('showLocal(Collections.emptyList(),"Alleen live tv is beschikbaar voor dit M3U-profiel")','showLocal(Collections.emptyList(),T("m3u_live_only"))')
s=s.replace('busy(false,"Fout: "+friendly(e))','busy(false,T("error_prefix")+": "+friendly(e))')
s=s.replace('busy(false,"Cache: "+friendlyThrowable(e))','busy(false,T("cache_label")+": "+friendlyThrowable(e))')
s=s.replace('?" · 18+ verborgen":""','?" · "+T("hidden_adult"):""')
s=s.replace('busy(false,"Laden onderbroken: "+friendlyThrowable(e))','busy(false,T("loading_interrupted")+": "+friendlyThrowable(e))')
s=s.replace('f.add(T("genre_label")+" "+d.genre)','f.add(T("genre_label")+" "+localizeGenreText(d.genre))')
marker='    void openWeb(String url){try{startActivity(new Intent(Intent.ACTION_VIEW,android.net.Uri.parse(url)));}catch(Exception e){status.setText(T("info_failed"));}}\n'
helper='''    String localizeGenreText(String raw){if(raw==null||raw.trim().isEmpty())return raw;String x=raw;String[][]g={{"science fiction",T("genre_scifi")},{"sci-fi",T("genre_scifi")},{"documentary",T("genre_documentary")},{"animation",T("genre_animation")},{"comedy",T("genre_comedy")},{"horror",T("genre_horror")},{"romance",T("genre_romance")},{"fantasy",T("genre_fantasy")},{"family",T("genre_family")},{"crime",T("genre_crime")},{"thriller",T("genre_thriller")},{"drama",T("genre_drama")},{"action",T("genre_action").replace(" / ","/").split("/")[0].trim()}};for(String[]a:g)x=x.replaceAll("(?i)\\\\b"+java.util.regex.Pattern.quote(a[0])+"\\\\b",java.util.regex.Matcher.quoteReplacement(a[1]));return x;}\n'''
assert marker in s
s=s.replace(marker,helper+marker,1)
p.write_text(s)

# SettingsActivity: localized language names and all labels
p=root/'app/src/main/java/com/robertalt/raiptv/SettingsActivity.java'; s=p.read_text()
s=s.replace('@Override public void onCreate(Bundle x){super.onCreate(x);p=SettingsStore.prefs(this);langAtOpen=SettingsStore.language(this);build();}','@Override public void onCreate(Bundle x){super.onCreate(x);p=SettingsStore.prefs(this);langAtOpen=SettingsStore.language(this);build();UiText.applyDirection(this);}',1)
s=s.replace('spin(T("app_language"),"language",new String[]{T("auto_device"),"Nederlands","English","Deutsch","Français","Español","Italiano","Português","Türkçe","Polski","العربية"},new String[]{"auto","nl","en","de","fr","es","it","pt","tr","pl","ar"})','spin(T("app_language"),"language",appLanguageLabels(),new String[]{"auto","nl","en","de","fr","es","it","pt","tr","pl","ar"})')
s=s.replace('spin(T("audio_pref"),"audio",new String[]{T("automatic"),T("original"),"Nederlands","English","Deutsch","Français","Español","Italiano","Português","Türkçe","Polski","العربية"},new String[]{"auto","original","nl","en","de","fr","es","it","pt","tr","pl","ar"})','spin(T("audio_pref"),"audio",audioLanguageLabels(),new String[]{"auto","original","nl","en","de","fr","es","it","pt","tr","pl","ar"})')
s=s.replace('spin(T("subtitle_pref"),"subtitles",new String[]{T("automatic"),T("off"),"Nederlands","English","Deutsch","Français","Español","Italiano","Português","Türkçe","Polski","العربية"},new String[]{"auto","off","nl","en","de","fr","es","it","pt","tr","pl","ar"})','spin(T("subtitle_pref"),"subtitles",subtitleLanguageLabels(),new String[]{"auto","off","nl","en","de","fr","es","it","pt","tr","pl","ar"})')
s=s.replace('toggle("Picture-in-Picture","pip",true)','toggle(T("picture_in_picture"),"pip",true)')
s=s.replace('Toast.makeText(this,T("reindex"),Toast.LENGTH_SHORT).show()','Toast.makeText(this,T("reindex_started"),Toast.LENGTH_SHORT).show()')
s=s.replace('Toast.makeText(this,T("clear_cache"),Toast.LENGTH_SHORT).show()','Toast.makeText(this,T("cache_cleared"),Toast.LENGTH_SHORT).show()')
s=re.sub(r'TextView a=t\("Nivaro IPTV Player 0\.5\.6\\nLanguage persistence, localized interface, cleaner titles and improved memory recovery\."\s*,13\);',lambda m:'TextView a=t("Nivaro IPTV Player 0.6.1\\\\n"+T("about_version_notes"),13);',s)
insert='''  String languageName(String code){return SettingsStore.displayLanguage(this,code);}\n  String[] appLanguageLabels(){return new String[]{T("auto_device"),languageName("nl"),languageName("en"),languageName("de"),languageName("fr"),languageName("es"),languageName("it"),languageName("pt"),languageName("tr"),languageName("pl"),languageName("ar")};}\n  String[] audioLanguageLabels(){return new String[]{T("automatic"),T("original"),languageName("nl"),languageName("en"),languageName("de"),languageName("fr"),languageName("es"),languageName("it"),languageName("pt"),languageName("tr"),languageName("pl"),languageName("ar")};}\n  String[] subtitleLanguageLabels(){return new String[]{T("automatic"),T("off"),languageName("nl"),languageName("en"),languageName("de"),languageName("fr"),languageName("es"),languageName("it"),languageName("pt"),languageName("tr"),languageName("pl"),languageName("ar")};}\n'''
marker='  interface Done{void ok(boolean ok);}\n'
assert marker in s;s=s.replace(marker,insert+marker,1)
p.write_text(s)

# Profile layout IDs
p=root/'app/src/main/res/layout/activity_profile.xml'; x=p.read_text()
x=x.replace('<TextView android:layout_width="match_parent" android:layout_height="wrap_content" android:text="Profiel"','<TextView android:id="@+id/profileTitle" android:layout_width="match_parent" android:layout_height="wrap_content" android:text="Profiel"',1)
x=x.replace('<TextView android:layout_width="match_parent" android:layout_height="wrap_content" android:text="Voeg je eigen IPTV-gegevens toe. Nivaro levert zelf geen tv-dienst."','<TextView android:id="@+id/profileIntro" android:layout_width="match_parent" android:layout_height="wrap_content" android:text="Voeg je eigen IPTV-gegevens toe. Nivaro levert zelf geen tv-dienst."',1)
x=x.replace('<TextView android:layout_width="match_parent" android:layout_height="wrap_content" android:text="Externe ondertiteling (optionele NAS bridge)"','<TextView android:id="@+id/bridgeLabel" android:layout_width="match_parent" android:layout_height="wrap_content" android:text="Externe ondertiteling (optionele NAS bridge)"',1)
x=x.replace('<TextView android:layout_width="match_parent" android:layout_height="wrap_content" android:text="Standaard: VLC · Engelse audio · Nederlandse ondertiteling"','<TextView android:id="@+id/profileDefaults" android:layout_width="match_parent" android:layout_height="wrap_content" android:text="Standaard: VLC · Engelse audio · Nederlandse ondertiteling"',1)
p.write_text(x)

# ProfileActivity localize all fixed UI
p=root/'app/src/main/java/com/robertalt/raiptv/ProfileActivity.java'; s=p.read_text()
s=s.replace('public class ProfileActivity extends Activity {','public class ProfileActivity extends Activity {\n    String T(String k){return UiText.t(this,k);}',1)
old='''@Override public void onCreate(Bundle b){super.onCreate(b);setContentView(R.layout.activity_profile);store=new SecureProfileStore(this);name=findViewById(R.id.nameField);server=findViewById(R.id.serverField);user=findViewById(R.id.userField);pass=findViewById(R.id.passField);m3u=findViewById(R.id.m3uField);epg=findViewById(R.id.epgField);bridge=findViewById(R.id.bridgeField);bridgeToken=findViewById(R.id.bridgeTokenField);xtream=findViewById(R.id.xtreamRadio);m3uRadio=findViewById(R.id.m3uRadio);status=findViewById(R.id.profileStatus);load();findViewById(R.id.testButton).setOnClickListener(v->test());findViewById(R.id.saveButton).setOnClickListener(v->{Profile p=collect();store.save(p);setResult(RESULT_OK);finish();});}'''
new='''@Override public void onCreate(Bundle b){super.onCreate(b);setContentView(R.layout.activity_profile);UiText.applyDirection(this);store=new SecureProfileStore(this);name=findViewById(R.id.nameField);server=findViewById(R.id.serverField);user=findViewById(R.id.userField);pass=findViewById(R.id.passField);m3u=findViewById(R.id.m3uField);epg=findViewById(R.id.epgField);bridge=findViewById(R.id.bridgeField);bridgeToken=findViewById(R.id.bridgeTokenField);xtream=findViewById(R.id.xtreamRadio);m3uRadio=findViewById(R.id.m3uRadio);status=findViewById(R.id.profileStatus);applyLanguage();load();findViewById(R.id.testButton).setOnClickListener(v->test());findViewById(R.id.saveButton).setOnClickListener(v->{Profile p=collect();store.save(p);setResult(RESULT_OK);finish();});}'''
assert old in s;s=s.replace(old,new,1)
marker='    void load(){if(!store.exists())return;'
apply='''    void applyLanguage(){((TextView)findViewById(R.id.profileTitle)).setText(T("profile"));((TextView)findViewById(R.id.profileIntro)).setText(T("profile_intro"));name.setHint(T("profile_name"));server.setHint(T("server_hint"));user.setHint(T("username"));pass.setHint(T("password"));m3u.setHint("M3U-URL");epg.setHint(T("epg_url_optional"));((TextView)findViewById(R.id.bridgeLabel)).setText(T("external_subtitle_bridge"));bridge.setHint(T("bridge_url"));bridgeToken.setHint(T("bridge_token_optional"));((TextView)findViewById(R.id.profileDefaults)).setText(T("profile_defaults"));((Button)findViewById(R.id.testButton)).setText(T("test_connection"));((Button)findViewById(R.id.saveButton)).setText(T("save"));}\n'''
assert marker in s;s=s.replace(marker,apply+marker,1)
s=s.replace('status.setText("Verbinding testen…")','status.setText(T("testing_connection"))')
s=s.replace('status.setText("Verbinding OK")','status.setText(T("connection_ok"))')
s=s.replace('status.setText("Mislukt: "+friendly(e))','status.setText(T("failed")+": "+friendly(e))')
s=s.replace('return "onbekende fout"','return T("unknown_error")')
s=s.replace('m.replace("LOGIN_FAILED","Gebruikersnaam, wachtwoord of server niet geaccepteerd")','m.replace("LOGIN_FAILED",T("login_failed"))')
p.write_text(s)

# PlayerActivity localization
p=root/'app/src/main/java/com/robertalt/raiptv/PlayerActivity.java'; s=p.read_text()
s=s.replace('setContentView(R.layout.activity_player);','setContentView(R.layout.activity_player);UiText.applyDirection(this);',1)
old='''root=findViewById(R.id.playerRoot);controls=findViewById(R.id.playerControls);vlcLayout=findViewById(R.id.vlcLayout);media3View=findViewById(R.id.media3View);title=findViewById(R.id.playerTitle);status=findViewById(R.id.playerStatus);timeText=findViewById(R.id.timeText);playPause=findViewById(R.id.playPauseButton);rewind=findViewById(R.id.rewindButton);forward=findViewById(R.id.forwardButton);audio=findViewById(R.id.audioButton);subtitle=findViewById(R.id.subtitleButton);pip=findViewById(R.id.pipButton);seek=findViewById(R.id.seekBar);channelPrev=findViewById(R.id.channelPrevButton);channelNext=findViewById(R.id.channelNextButton);speed=findViewById(R.id.speedButton);aspect=findViewById(R.id.aspectButton);sleep=findViewById(R.id.sleepButton);record=findViewById(R.id.recordButton);'''
new=old+'\n        audio.setText(T("audio"));subtitle.setText(T("subtitles"));aspect.setText(T("fit"));sleep.setText(T("sleep_short"));pip.setContentDescription(T("picture_in_picture"));'
assert old in s;s=s.replace(old,new,1)
s=s.replace('cleanTrackName(t[i].name,"Audio "+(i+1))','cleanTrackName(t[i].name,T("audio")+" "+(i+1))')
s=s.replace('status.setText("Audio: "+names[w])','status.setText(T("audio")+": "+names[w])')
s=s.replace('status.setText("Audio: "+names.get(w))','status.setText(T("audio")+": "+names.get(w))')
s=s.replace('formatTrack(f,"Audio "+(names.size()+1))','formatTrack(f,T("audio")+" "+(names.size()+1))')
s=s.replace('sleep.setText("Sleep")','sleep.setText(T("sleep_short"))')
s=s.replace('sleep.setText("Sleep "+minutes+"m")','sleep.setText(T("sleep_short")+" "+minutes+"m")')
s=s.replace('sleep.setText("Sleep "+Math.max(1,(left+59999)/60000)+"m")','sleep.setText(T("sleep_short")+" "+Math.max(1,(left+59999)/60000)+"m")')
s=s.replace('recordingChannel=entry.name==null?"Live tv":entry.name','recordingChannel=entry.name==null?T("live_tv"):entry.name')
s=s.replace('timeText.setText("live".equals(entry.type)?"LIVE":fmt(p))','timeText.setText("live".equals(entry.type)?T("live").toUpperCase(SettingsStore.appLocale(this)):fmt(p))')
p.write_text(s)

# EPG adapter localization
p=root/'app/src/main/java/com/robertalt/raiptv/EpgAdapter.java'; s=p.read_text()
s=s.replace('private final LayoutInflater in; private final EpgStore store;','private final LayoutInflater in; private final EpgStore store; private final Context context;',1)
s=s.replace('public EpgAdapter(Context c,EpgStore s){in=LayoutInflater.from(c);store=s;}','public EpgAdapter(Context c,EpgStore s){context=c;in=LayoutInflater.from(c);store=s;}',1)
s=s.replace('h.now.setText("Nu · EPG laden…")','h.now.setText(UiText.t(context,"now")+" · "+UiText.t(context,"epg_loading"))')
s=s.replace('h.now.setText("Geen EPG beschikbaar")','h.now.setText(UiText.t(context,"no_epg"))')
s=s.replace('h.now.setText("Nu  "+time(a)+a.title)','h.now.setText(UiText.t(context,"now")+"  "+time(a)+a.title)')
s=s.replace('h.next.setText("Hierna  "+time(b)+b.title)','h.next.setText(UiText.t(context,"next")+"  "+time(b)+b.title)')
p.write_text(s)

# MediaRowAdapter type labels
p=root/'app/src/main/java/com/robertalt/raiptv/MediaRowAdapter.java'; s=p.read_text()
old='String typeLabel(String t){return t.equals("live")?"LIVE":t.equals("vod")?"FILM":t.equals("series")?"SERIE":t.equals("episode")?"AFLEVERING":t.toUpperCase(Locale.ROOT);}'
new='String typeLabel(String t){android.content.Context c=in.getContext();String x=t.equals("live")?UiText.t(c,"live"):t.equals("vod")?UiText.t(c,"movie"):t.equals("series")?UiText.t(c,"series"):t.equals("episode")?UiText.t(c,"episode"):t;return x.toUpperCase(SettingsStore.appLocale(c));}'
assert old in s;s=s.replace(old,new,1)
# ensure SettingsStore import exists
if 'import com.robertalt.raiptv.storage.SettingsStore;' not in s:
    s=s.replace('import com.robertalt.raiptv.storage.LibraryStore;','import com.robertalt.raiptv.storage.LibraryStore;import com.robertalt.raiptv.storage.SettingsStore;')
p.write_text(s)

# Version bump
p=root/'app/build.gradle'; s=p.read_text().replace('versionCode 19','versionCode 20').replace("versionName '0.6.0'","versionName '0.6.1'"); p.write_text(s)

# validation
assert 'versionName \'0.6.1\'' in (root/'app/build.gradle').read_text()
assert 'private static final Map<String,String> DE' in ui.read_text()
assert 'profile_intro' in ui.read_text()
assert 'UiText.applyDirection(this)' in (root/'app/src/main/java/com/robertalt/raiptv/ProfileActivity.java').read_text()
assert 'InfoTranslator.translate(d.plot,uiLang' in (root/'app/src/main/java/com/robertalt/raiptv/MainActivity.java').read_text()
print('v0.6.1 localization patch applied; keys',len(keys))