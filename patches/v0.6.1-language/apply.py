from pathlib import Path
import re
root=Path('/mnt/data/nivaro061test/RA_IPTV_Android_v0.1')

# UiText: dynamic full language packs for every supported non-English language.
p=root/'app/src/main/java/com/robertalt/raiptv/UiText.java'
s=p.read_text()
s=s.replace('import android.content.Context;\nimport com.robertalt.raiptv.storage.SettingsStore;\nimport java.util.Locale;', '''import android.content.Context;\nimport android.content.SharedPreferences;\nimport android.app.Activity;\nimport android.view.View;\nimport com.robertalt.raiptv.storage.SettingsStore;\nimport com.google.mlkit.nl.translate.*;\nimport com.google.mlkit.common.model.DownloadConditions;\nimport java.util.*;\nimport java.util.concurrent.atomic.*;''')
old='''    private UiText(){}\n    public static String t(Context c,String key){return t(SettingsStore.language(c),key);}\n    public static String t(String lang,String key){'''
new='''    private UiText(){}\n    static final int PACK_VERSION=4;\n    static final Set<String> PREPARING=Collections.synchronizedSet(new HashSet<>());\n    static String ck(String lang,String key){return "ui4_"+lang+"_"+key;}\n    public static String t(Context c,String key){String lang=SettingsStore.language(c);if(!"en".equals(lang)){String v=SettingsStore.prefs(c).getString(ck(lang,key),"");if(v!=null&&!v.trim().isEmpty())return v;}return t(lang,key);}\n    public static boolean packReady(Context c,String lang){return lang==null||"en".equals(lang)||SettingsStore.prefs(c).getInt("ui_pack_"+lang,0)>=PACK_VERSION;}\n    public static String preparingMessage(String lang){if("nl".equals(lang))return "Taal voorbereiden…";if("de".equals(lang))return "Sprache wird vorbereitet…";if("fr".equals(lang))return "Préparation de la langue…";if("es".equals(lang))return "Preparando idioma…";if("it".equals(lang))return "Preparazione della lingua…";if("pt".equals(lang))return "A preparar o idioma…";if("tr".equals(lang))return "Dil hazırlanıyor…";if("pl".equals(lang))return "Przygotowywanie języka…";if("ar".equals(lang))return "جارٍ إعداد اللغة…";return "Preparing language…";}\n    public static void applyDirection(Activity a){boolean rtl="ar".equals(SettingsStore.language(a));View v=a.getWindow().getDecorView();v.setLayoutDirection(rtl?View.LAYOUT_DIRECTION_RTL:View.LAYOUT_DIRECTION_LTR);v.setTextDirection(rtl?View.TEXT_DIRECTION_RTL:View.TEXT_DIRECTION_LTR);}\n    public static void prepareLanguage(Context c,String lang,Runnable done){\n        if(lang==null||"en".equals(lang)||packReady(c,lang)){if(done!=null)done.run();return;}\n        if(!PREPARING.add(lang))return;\n        String dest=TranslateLanguage.fromLanguageTag(lang);if(dest==null){PREPARING.remove(lang);if(done!=null)done.run();return;}\n        ArrayList<String> need=new ArrayList<>();for(String k:ALL_KEYS){String direct=t(lang,k);String base=en(k);if(direct.equals(base))need.add(k);}\n        if(need.isEmpty()){SettingsStore.prefs(c).edit().putInt("ui_pack_"+lang,PACK_VERSION).apply();PREPARING.remove(lang);if(done!=null)done.run();return;}\n        Translator tr=Translation.getClient(new TranslatorOptions.Builder().setSourceLanguage("en").setTargetLanguage(dest).build());\n        tr.downloadModelIfNeeded(new DownloadConditions.Builder().build()).addOnSuccessListener(v->{\n            Map<String,String> out=Collections.synchronizedMap(new HashMap<>());AtomicInteger left=new AtomicInteger(need.size());AtomicBoolean failed=new AtomicBoolean(false);\n            for(String k:need){String src=en(k);tr.translate(src).addOnSuccessListener(x->{if(x!=null&&!x.trim().isEmpty())out.put(k,x);else failed.set(true);}).addOnFailureListener(e->failed.set(true)).addOnCompleteListener(task->{if(left.decrementAndGet()==0){SharedPreferences.Editor ed=SettingsStore.prefs(c).edit();for(Map.Entry<String,String> e:out.entrySet())ed.putString(ck(lang,e.getKey()),e.getValue());if(!failed.get()&&out.size()==need.size())ed.putInt("ui_pack_"+lang,PACK_VERSION);ed.apply();tr.close();PREPARING.remove(lang);if(done!=null)done.run();}});}\n        }).addOnFailureListener(e->{tr.close();PREPARING.remove(lang);if(done!=null)done.run();});\n    }\n    public static String t(String lang,String key){'''
assert old in s
s=s.replace(old,new,1)
# Add English keys before English default.
add='''case "preparing_language":return "Preparing language…";case "profile_intro":return "Add your own IPTV details. Nivaro does not provide a TV service.";case "profile_name":return "Profile name";case "server_hint":return "Server, e.g. http://example:8080";case "username":return "Username";case "password":return "Password";case "m3u_url":return "M3U URL";case "epg_optional":return "EPG URL (optional)";case "external_subtitle_profile":return "External subtitles (optional NAS bridge)";case "bridge_url":return "Bridge URL";case "bridge_token_optional":return "Bridge token (optional)";case "playback_defaults":return "Automatic: player, audio and subtitles follow your settings";case "test_connection":return "Test connection";case "connection_testing":return "Testing connection…";case "connection_ok":return "Connection OK";case "failed":return "Failed";case "error":return "Error";case "sign_in_failed":return "Sign-in failed";case "m3u_live_only":return "Only live TV is available for this M3U profile";case "cache":return "Cache";case "pip":return "Picture-in-Picture";case "about_text":return "Persistent library, vertical grids, complete language profiles and improved memory handling.";'''
# English default is first occurrence after static en.
en_start=s.index('static String en(')
en_end=s.index('static String nl(',en_start)
segment=s[en_start:en_end]
needle='default:return k;'
if needle not in segment:
    needle='default:return en(k);'
# Actual en uses default:return k likely.
assert needle in segment, needle
segment=segment.replace(needle,add+needle,1)
s=s[:en_start]+segment+s[en_end:]
# Generate key array after English cases plus new keys.
en_seg=s[s.index('static String en('):s.index('static String nl(')]
keys=[]
for k in re.findall(r'case "([^"]+)"',en_seg):
    if k not in keys:keys.append(k)
arr='    static final String[] ALL_KEYS=new String[]{'+','.join('"'+k+'"' for k in keys)+'};\n'
pos=s.index('    static String en(')
s=s[:pos]+arr+s[pos:]
p.write_text(s)

# Fix description translation when language detector cannot identify English and add a tiny cache.
p=root/'app/src/main/java/com/robertalt/raiptv/InfoTranslator.java'
s=p.read_text()
s=s.replace('''                String source = TranslateLanguage.fromLanguageTag(code);\n                String dest = TranslateLanguage.fromLanguageTag(target);\n                if (source == null || dest == null || source.equals(dest) || "und".equals(code)) {\n                    cb.done(text);\n                    id.close();\n                    return;\n                }''','''                String source = TranslateLanguage.fromLanguageTag(code);\n                String dest = TranslateLanguage.fromLanguageTag(target);\n                if (dest == null) { cb.done(text); id.close(); return; }\n                if (source == null || "und".equals(code)) source = "en";\n                if (source.equals(dest)) { cb.done(text); id.close(); return; }''')
p.write_text(s)

# Main: wait for a complete language pack, localize remaining visible hardcoded text, translate provider genre metadata.
p=root/'app/src/main/java/com/robertalt/raiptv/MainActivity.java'
s=p.read_text()
s=s.replace('''adapter=new MediaRowAdapter(this,library);gridAdapter=new MediaGridAdapter(this,library);epgAdapter=new EpgAdapter(this,epgStore);list.setAdapter(adapter);grid.setAdapter(gridAdapter);appliedLanguage=SettingsStore.language(this);applyStaticLanguage();wire();\n        if(!profiles.exists())startActivityForResult(new Intent(this,ProfileActivity.class),10);else openProfile();''','''adapter=new MediaRowAdapter(this,library);gridAdapter=new MediaGridAdapter(this,library);epgAdapter=new EpgAdapter(this,epgStore);list.setAdapter(adapter);grid.setAdapter(gridAdapter);appliedLanguage=SettingsStore.language(this);UiText.applyDirection(this);applyStaticLanguage();wire();\n        if(!UiText.packReady(this,appliedLanguage)){busy(true,UiText.preparingMessage(appliedLanguage));UiText.prepareLanguage(this,appliedLanguage,()->runOnUiThread(()->{if(!isFinishing()&&!isDestroyed())recreate();}));return;}\n        if(!profiles.exists())startActivityForResult(new Intent(this,ProfileActivity.class),10);else openProfile();''',1)
s=s.replace('busy(false,"Inloggen mislukt: "+friendly(e))','busy(false,T("sign_in_failed")+": "+friendly(e))')
s=s.replace('showLocal(Collections.emptyList(),"Alleen live tv is beschikbaar voor dit M3U-profiel")','showLocal(Collections.emptyList(),T("m3u_live_only"))')
s=s.replace('busy(false,"Fout: "+friendly(e))','busy(false,T("error")+": "+friendly(e))')
s=s.replace('busy(false,"Cache: "+friendlyThrowable(e))','busy(false,T("cache")+": "+friendlyThrowable(e))')
# Replace showDetailsDialog method with translated facts/genre and description.
start=s.index('    void showDetailsDialog(MediaEntry item,MediaDetails d){')
end=s.index('\nvoid addInfoLine(',start)
method='''    void showDetailsDialog(MediaEntry item,MediaDetails d){\n    String uiLang=SettingsStore.language(this);FrameLayout root=new FrameLayout(this);root.setBackgroundColor(0xFF171A20);ImageView bg=new ImageView(this);bg.setScaleType(ImageView.ScaleType.CENTER_CROP);bg.setAlpha(.30f);root.addView(bg,new FrameLayout.LayoutParams(-1,-1));if(Build.VERSION.SDK_INT>=31)try{bg.setRenderEffect(RenderEffect.createBlurEffect(18f,18f,Shader.TileMode.CLAMP));}catch(Exception ignored){}View shade=new View(this);shade.setBackgroundColor(0xAA080A0E);root.addView(shade,new FrameLayout.LayoutParams(-1,-1));ScrollView scroll=new ScrollView(this);LinearLayout box=new LinearLayout(this);box.setOrientation(LinearLayout.VERTICAL);box.setPadding(dp(22),dp(20),dp(22),dp(18));scroll.addView(box);root.addView(scroll,new FrameLayout.LayoutParams(-1,-1));TextView h=new TextView(this);h.setText(safe(d.title).isEmpty()?DisplayText.title(item):d.title);h.setTextColor(Color.WHITE);h.setTextSize(25);h.setTypeface(null,Typeface.BOLD);box.addView(h);String score=d.scoreLabel();if(!score.isEmpty())addInfoLine(box,T("score")+": "+score,true);final TextView facts=addInfoLineReturn(box,detailsFacts(d,safe(d.genre)),false);if(!safe(d.genre).isEmpty())InfoTranslator.translate(d.genre,uiLang,out->runOnUiThread(()->{if(isUiAlive())facts.setText(detailsFacts(d,out));}));if(!safe(d.director).isEmpty())addInfoBlock(box,T("director"),d.director);if(!safe(d.cast).isEmpty())addInfoBlock(box,T("cast"),d.cast);if(!safe(d.plot).isEmpty()){TextView plot=addInfoBlock(box,T("description"),d.plot);InfoTranslator.translate(d.plot,uiLang,out->runOnUiThread(()->{if(isUiAlive())plot.setText(out);}));}LinearLayout links=new LinearLayout(this);links.setOrientation(LinearLayout.HORIZONTAL);links.setGravity(Gravity.END);links.setPadding(0,dp(12),0,0);if(d.hasImdb()){Button imdb=new Button(this);imdb.setText("IMDb");imdb.setAllCaps(false);imdb.setOnClickListener(v->openWeb("https://www.imdb.com/title/"+d.imdbId+"/"));links.addView(imdb);}if(d.hasTmdb()){Button tmdb=new Button(this);tmdb.setText("TMDb");tmdb.setAllCaps(false);tmdb.setOnClickListener(v->openWeb("https://www.themoviedb.org/"+(("series".equals(item.type)||"episode".equals(item.type))?"tv/":"movie/")+d.tmdbId));links.addView(tmdb);}Button close=new Button(this);close.setText(T("close"));close.setAllCaps(false);links.addView(close);box.addView(links);Dialog dialog=new Dialog(this);dialog.setContentView(root);close.setOnClickListener(v->dialog.dismiss());dialog.show();if(dialog.getWindow()!=null)dialog.getWindow().setLayout((int)(getResources().getDisplayMetrics().widthPixels*.93f),(int)(getResources().getDisplayMetrics().heightPixels*.80f));String u=safe(d.backdrop);if(u.isEmpty())u=safe(item.backdrop);if(u.isEmpty())u=safe(d.poster);if(u.isEmpty())u=safe(item.logo);final String url=u;if(!url.isEmpty())heroExec.execute(()->{Bitmap bm=downloadHero(url);runOnUiThread(()->{if(isUiAlive()&&bm!=null)bg.setImageBitmap(bm);});});\n}\n    String detailsFacts(MediaDetails d,String genre){ArrayList<String> f=new ArrayList<>();if(!safe(d.year).isEmpty())f.add(T("year")+" "+d.year);if(genre!=null&&!genre.trim().isEmpty())f.add(T("genre_label")+" "+genre);if(!safe(d.duration).isEmpty())f.add(T("duration")+" "+d.duration);return TextUtils.join(" · ",f);}\n'''
s=s[:start]+method+s[end:]
# addInfoLine return helper
s=s.replace('''void addInfoLine(LinearLayout b,String x,boolean strong){TextView v=new TextView(this);v.setText(x);v.setTextColor(0xFFF7F8FA);v.setTextSize(strong?16:14);if(strong)v.setTypeface(null,Typeface.BOLD);v.setPadding(0,dp(8),0,0);b.addView(v);}''','''void addInfoLine(LinearLayout b,String x,boolean strong){addInfoLineReturn(b,x,strong);} TextView addInfoLineReturn(LinearLayout b,String x,boolean strong){TextView v=new TextView(this);v.setText(x);v.setTextColor(0xFFF7F8FA);v.setTextSize(strong?16:14);if(strong)v.setTypeface(null,Typeface.BOLD);v.setPadding(0,dp(8),0,0);b.addView(v);return v;}''')
p.write_text(s)

# Settings: localized language names, PiP, about text, and build selected language pack before switching UI.
p=root/'app/src/main/java/com/robertalt/raiptv/SettingsActivity.java'
s=p.read_text()
s=s.replace('@Override public void onCreate(Bundle x){super.onCreate(x);p=SettingsStore.prefs(this);langAtOpen=SettingsStore.language(this);build();}', '@Override public void onCreate(Bundle x){super.onCreate(x);p=SettingsStore.prefs(this);langAtOpen=SettingsStore.language(this);UiText.applyDirection(this);build();}')
old='''sec(T("language"));spin(T("app_language"),"language",new String[]{T("auto_device"),"Nederlands","English","Deutsch","Français","Español","Italiano","Português","Türkçe","Polski","العربية"},new String[]{"auto","nl","en","de","fr","es","it","pt","tr","pl","ar"});'''
new='''sec(T("language"));spin(T("app_language"),"language",languageNames(),new String[]{"auto","nl","en","de","fr","es","it","pt","tr","pl","ar"});'''
assert old in s;s=s.replace(old,new,1)
old='''spin(T("audio_pref"),"audio",new String[]{T("automatic"),T("original"),"Nederlands","English","Deutsch","Français","Español","Italiano","Português","Türkçe","Polski","العربية"},new String[]{"auto","original","nl","en","de","fr","es","it","pt","tr","pl","ar"});spin(T("subtitle_pref"),"subtitles",new String[]{T("automatic"),T("off"),"Nederlands","English","Deutsch","Français","Español","Italiano","Português","Türkçe","Polski","العربية"},new String[]{"auto","off","nl","en","de","fr","es","it","pt","tr","pl","ar"});toggle("Picture-in-Picture","pip",true);'''
new='''spin(T("audio_pref"),"audio",mediaLanguageNames(false),new String[]{"auto","original","nl","en","de","fr","es","it","pt","tr","pl","ar"});spin(T("subtitle_pref"),"subtitles",mediaLanguageNames(true),new String[]{"auto","off","nl","en","de","fr","es","it","pt","tr","pl","ar"});toggle(T("pip"),"pip",true);'''
assert old in s;s=s.replace(old,new,1)
s=s.replace('TextView a=t("Nivaro IPTV Player 0.5.6\\nLanguage persistence, localized interface, cleaner titles and improved memory recovery.",13);','TextView a=t("Nivaro IPTV Player 0.6.1\\n"+T("about_text"),13);')
# insert helpers before interface Done
marker='  interface Done{void ok(boolean ok);}'
helpers='''  String[] languageNames(){String[] c={"nl","en","de","fr","es","it","pt","tr","pl","ar"};String[] out=new String[c.length+1];out[0]=T("auto_device");for(int i=0;i<c.length;i++)out[i+1]=SettingsStore.displayLanguage(this,c[i]);return out;}\n  String[] mediaLanguageNames(boolean subs){String[] c={"nl","en","de","fr","es","it","pt","tr","pl","ar"};String[] out=new String[c.length+2];out[0]=T("automatic");out[1]=subs?T("off"):T("original");for(int i=0;i<c.length;i++)out[i+2]=SettingsStore.displayLanguage(this,c[i]);return out;}\n'''
assert marker in s;s=s.replace(marker,helpers+marker,1)
old='''if("language".equals(k)){p.edit().putString(k,next).commit();recreate();}else p.edit().putString(k,next).apply();'''
new='''if("language".equals(k)){p.edit().putString(k,next).commit();String target=SettingsStore.language(this);if(UiText.packReady(this,target)){recreate();}else{Toast.makeText(this,UiText.preparingMessage(target),Toast.LENGTH_LONG).show();UiText.prepareLanguage(this,target,()->runOnUiThread(()->{if(!isFinishing()&&!isDestroyed())recreate();}));}}else p.edit().putString(k,next).apply();'''
assert old in s;s=s.replace(old,new,1)
p.write_text(s)

# Profile XML: add IDs to text labels so ProfileActivity can localize them.
p=root/'app/src/main/res/layout/activity_profile.xml'
s=p.read_text()
s=s.replace('<TextView android:layout_width="match_parent" android:layout_height="wrap_content" android:text="Profiel"', '<TextView android:id="@+id/profileTitle" android:layout_width="match_parent" android:layout_height="wrap_content" android:text="Profiel"',1)
s=s.replace('<TextView android:layout_width="match_parent" android:layout_height="wrap_content" android:text="Voeg je eigen IPTV-gegevens toe. Nivaro levert zelf geen tv-dienst."', '<TextView android:id="@+id/profileIntro" android:layout_width="match_parent" android:layout_height="wrap_content" android:text="Voeg je eigen IPTV-gegevens toe. Nivaro levert zelf geen tv-dienst."',1)
s=s.replace('<TextView android:layout_width="match_parent" android:layout_height="wrap_content" android:text="Externe ondertiteling (optionele NAS bridge)"', '<TextView android:id="@+id/subtitleBridgeLabel" android:layout_width="match_parent" android:layout_height="wrap_content" android:text="Externe ondertiteling (optionele NAS bridge)"',1)
s=s.replace('<TextView android:layout_width="match_parent" android:layout_height="wrap_content" android:text="Standaard: VLC · Engelse audio · Nederlandse ondertiteling"', '<TextView android:id="@+id/profileDefaults" android:layout_width="match_parent" android:layout_height="wrap_content" android:text="Standaard: VLC · Engelse audio · Nederlandse ondertiteling"',1)
p.write_text(s)

# ProfileActivity: localize all visible labels/hints/status strings.
p=root/'app/src/main/java/com/robertalt/raiptv/ProfileActivity.java'
s=p.read_text()
s=s.replace('public class ProfileActivity extends Activity {', 'public class ProfileActivity extends Activity {\n    String T(String k){return UiText.t(this,k);}')
s=s.replace('''@Override public void onCreate(Bundle b){super.onCreate(b);setContentView(R.layout.activity_profile);store=new SecureProfileStore(this);name=findViewById(R.id.nameField);server=findViewById(R.id.serverField);user=findViewById(R.id.userField);pass=findViewById(R.id.passField);m3u=findViewById(R.id.m3uField);epg=findViewById(R.id.epgField);bridge=findViewById(R.id.bridgeField);bridgeToken=findViewById(R.id.bridgeTokenField);xtream=findViewById(R.id.xtreamRadio);m3uRadio=findViewById(R.id.m3uRadio);status=findViewById(R.id.profileStatus);load();findViewById(R.id.testButton).setOnClickListener(v->test());findViewById(R.id.saveButton).setOnClickListener(v->{Profile p=collect();store.save(p);setResult(RESULT_OK);finish();});}''','''@Override public void onCreate(Bundle b){super.onCreate(b);setContentView(R.layout.activity_profile);UiText.applyDirection(this);store=new SecureProfileStore(this);name=findViewById(R.id.nameField);server=findViewById(R.id.serverField);user=findViewById(R.id.userField);pass=findViewById(R.id.passField);m3u=findViewById(R.id.m3uField);epg=findViewById(R.id.epgField);bridge=findViewById(R.id.bridgeField);bridgeToken=findViewById(R.id.bridgeTokenField);xtream=findViewById(R.id.xtreamRadio);m3uRadio=findViewById(R.id.m3uRadio);status=findViewById(R.id.profileStatus);applyLanguage();load();findViewById(R.id.testButton).setOnClickListener(v->test());findViewById(R.id.saveButton).setOnClickListener(v->{Profile p=collect();store.save(p);setResult(RESULT_OK);finish();});}''')
insert='''    void applyLanguage(){((TextView)findViewById(R.id.profileTitle)).setText(T("profile"));((TextView)findViewById(R.id.profileIntro)).setText(T("profile_intro"));name.setHint(T("profile_name"));server.setHint(T("server_hint"));user.setHint(T("username"));pass.setHint(T("password"));m3u.setHint(T("m3u_url"));epg.setHint(T("epg_optional"));((TextView)findViewById(R.id.subtitleBridgeLabel)).setText(T("external_subtitle_profile"));bridge.setHint(T("bridge_url"));bridgeToken.setHint(T("bridge_token_optional"));((TextView)findViewById(R.id.profileDefaults)).setText(T("playback_defaults"));((Button)findViewById(R.id.testButton)).setText(T("test_connection"));((Button)findViewById(R.id.saveButton)).setText(T("save"));}\n'''
marker='    void load(){'
assert marker in s;s=s.replace(marker,insert+marker,1)
s=s.replace('status.setText("Verbinding testen…")','status.setText(T("connection_testing"))')
s=s.replace('status.setText("Verbinding OK")','status.setText(T("connection_ok"))')
s=s.replace('status.setText("Mislukt: "+friendly(e))','status.setText(T("failed")+": "+friendly(e))')
s=s.replace('return "onbekende fout"','return T("unknown_error")')
s=s.replace('return m.replace("LOGIN_FAILED","Gebruikersnaam, wachtwoord of server niet geaccepteerd")','return m.replace("LOGIN_FAILED",T("login_failed"))')
p.write_text(s)

# Player: apply selected RTL direction and localize one user-facing fallback.
p=root/'app/src/main/java/com/robertalt/raiptv/PlayerActivity.java'
s=p.read_text()
s=s.replace('super.onCreate(b);setContentView(R.layout.activity_player);', 'super.onCreate(b);setContentView(R.layout.activity_player);UiText.applyDirection(this);',1)
s=s.replace('throw new IOException("Output stream ontbreekt")','throw new IOException(T("error"))')
p.write_text(s)

# Version bump.
p=root/'app/build.gradle'
s=p.read_text().replace('versionCode 19','versionCode 20').replace("versionName '0.6.0'","versionName '0.6.1'")
p.write_text(s)

# Sanity checks.
ui=(root/'app/src/main/java/com/robertalt/raiptv/UiText.java').read_text()
assert 'PACK_VERSION=4' in ui and 'ALL_KEYS=new String[]' in ui and 'profile_intro' in ui
assert "versionName '0.6.1'" in (root/'app/build.gradle').read_text()
assert 'SettingsStore.displayLanguage(this,c[i])' in (root/'app/src/main/java/com/robertalt/raiptv/SettingsActivity.java').read_text()
assert 'T("profile_intro")' in (root/'app/src/main/java/com/robertalt/raiptv/ProfileActivity.java').read_text()
print('v0.6.1 full-language consistency patch applied')