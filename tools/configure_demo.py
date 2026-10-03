"""Install the reviewed demo into reconstructed Light and modular source."""
from pathlib import Path
import json
import re
import sys

repo = Path(__file__).resolve().parent.parent
root = Path.cwd()
java = root / 'app/src/main/java/com/robertalt/raiptv'

def replace(path, old, new):
    text = path.read_text()
    if old not in text:
        raise SystemExit(f'demo patch anchor missing: {path}: {old[:80]}')
    path.write_text(text.replace(old, new, 1))

catalogue = json.loads((repo / 'demo/catalogue.json').read_text())
playlist = (repo / 'demo/nenotv-demo.m3u').read_text()
if playlist.count('#EXTINF:') != len(catalogue) or len({e['id'] for e in catalogue}) != len(catalogue):
    raise SystemExit('demo catalogue and playlist disagree')
statements = []
for e in catalogue:
    values = dict(type=e['type'], year=e['year'], plot=e['credit']+' | '+e['license']+' | '+e['license_url']+' | Source: '+e['source']+' | Unmodified film/official live feed.')
    statements.append('if('+json.dumps(e['id'])+'.equals(e.tvgId)){'+''.join('e.'+k+'='+json.dumps(v)+';' for k,v in values.items())+'return;}')
(java / 'DemoSource.java').write_text('package com.nenotv.player;\npublic final class DemoSource {\n'
    +'public static final String URL="nenotv://demo/v1";\n'
    +'public static final int LIVE_COUNT='+str(sum(e['type']=='live' for e in catalogue))+';\n'
    +'public static final int FILM_COUNT='+str(sum(e['type']=='vod' for e in catalogue))+';\n'
    +'public static final String PLAYLIST='+json.dumps(playlist)+';\n'
    +'public static void decorate(com.nenotv.player.model.MediaEntry e){'+''.join(statements)+'}\nprivate DemoSource(){}\n}\n')
provider = java / 'provider/M3uProvider.java'
replace(provider, 'all=new ArrayList<>(r.items);', 'all=new ArrayList<>(r.items);if(com.nenotv.player.DemoSource.URL.equals(p.m3uUrl))for(MediaEntry e:all)com.nenotv.player.DemoSource.decorate(e);')
s = provider.read_text()
s = s.replace('if(!type.equals("live"))return Collections.emptyList();','')
s = s.replace('for(MediaEntry e:all)s.add(e.group);','for(MediaEntry e:all)if(type.equals(e.type))s.add(e.group);')
s = s.replace('new Category(g,g,"live")','new Category(g,g,type)')
s = s.replace('if(cat==null||cat.isEmpty()||cat.equals("all"))return new ArrayList<>(all);','')
s = s.replace('if(cat.equals(e.group))o.add(e);','if(type.equals(e.type)&&(cat==null||cat.isEmpty()||cat.equals("all")||cat.equals(e.group)))o.add(e);')
provider.write_text(s)
replace(java / 'net/HttpText.java', 'return execute("GET", url, null, Collections.emptyMap());',
        'if(com.nenotv.player.DemoSource.URL.equals(url))return com.nenotv.player.DemoSource.PLAYLIST;\n        return execute("GET", url, null, Collections.emptyMap());')
gradle = root / 'app/build.gradle'
replace(gradle, "System.getenv('NENOTV_DEMO_M3U_URL') ?: ''", "System.getenv('NENOTV_DEMO_M3U_URL') ?: 'nenotv://demo/v1'")

# Capture the selected mode before leaving the UI thread; repeated taps cannot queue imports.
profile = java / 'ProfileActivity.java'
replace(profile, 'void connectAndSave(){\n        final Profile p=collect();',
        'boolean connecting=false;\n    void connectAndSave(){\n        if(connecting)return;\n        final boolean demoSelected=demoRadio.isChecked();\n        final Profile p=collect();')
replace(profile, 'findViewById(R.id.saveButton).setEnabled(false);',
        'connecting=true;xtream.setEnabled(false);m3uRadio.setEnabled(false);demoRadio.setEnabled(false);\n        findViewById(R.id.saveButton).setEnabled(false);')
replace(profile, 'if(demoRadio.isChecked()){\n                    long expiry=',
        'if(demoSelected){\n                    long expiry=')
replace(profile, 'findViewById(R.id.saveButton).setEnabled(true);status.setText(',
        'connecting=false;xtream.setEnabled(true);m3uRadio.setEnabled(true);applyLanguage();findViewById(R.id.saveButton).setEnabled(true);status.setText(')

# Identify demo by its source, so editing the display name cannot avoid expiry.
policy = java / 'DemoPolicy.java'
replace(policy, 'sp.edit().putBoolean("demo_consumed",true).putLong("demo_started_at",now).putLong("demo_expires_at",next).apply();',
        'if(!sp.edit().putBoolean("demo_consumed",true).putLong("demo_started_at",now).putLong("demo_expires_at",next).commit())throw new IllegalStateException("DEMO_SAVE_FAILED");')
store = java / 'storage/SecureProfileStore.java'
replace(store, 'public void clear(){prefs.edit().clear().apply();}',
        'public void clear(){if(!prefs.edit().clear().commit())throw new IllegalStateException("PROFILE_CLEAR_FAILED");}')
replace(store, 'public void save(Profile p){prefs.edit()', 'public void save(Profile p){if(!prefs.edit()')
replace(store, '.putString("bridgeToken",crypto.encrypt(p.bridgeToken)).apply();',
        '.putString("bridgeToken",crypto.encrypt(p.bridgeToken)).commit())throw new IllegalStateException("PROFILE_SAVE_FAILED");')
replace(policy, '    public static boolean consumed(Context c)', '''    public static boolean isDemo(com.nenotv.player.model.Profile p){
        return p!=null&&p.type==com.nenotv.player.model.Profile.Type.M3U&&
            (DemoSource.URL.equals(p.m3uUrl)||BuildConfig.NENOTV_DEMO_M3U_URL.equals(p.m3uUrl));
    }
    public static boolean blockPlayback(Context c){
        com.nenotv.player.storage.SecureProfileStore store=new com.nenotv.player.storage.SecureProfileStore(c);
        return store.exists()&&isDemo(store.load())&&expired(c);
    }
    public static boolean consumed(Context c)''')
main = java / 'MainActivity.java'
replace(main, 'if(!"NenoTV Demo".equals(p.name))return false;', 'if(!DemoPolicy.isDemo(p))return false;')
replace(main, '@Override protected void onResume(){super.onResume();',
        '@Override protected void onResume(){super.onResume();if(provider!=null&&expireDemoProfileIfNeeded()){recreate();return;}')
replace(main, 'library.recent(e);Intent i=ProModuleInstaller.playerIntent(this);',
        'if(DemoPolicy.blockPlayback(this)){recreate();return;}library.recent(e);Intent i=ProModuleInstaller.playerIntent(this);')
player = java / 'PlayerActivity.java'
replace(player, 'super.onCreate(b);', 'super.onCreate(b);if(DemoPolicy.blockPlayback(this)){finish();return;}')
# Check while watching too; a session cannot stay open beyond its expiry.
s = player.read_text()
anchor = '    @Override public void onCreate(Bundle b){'
if anchor not in s:
    raise SystemExit('player creation anchor missing')
s = s.replace(anchor, '''    private final android.os.Handler demoHandler=new android.os.Handler(android.os.Looper.getMainLooper());
    private final Runnable demoExpiryCheck=new Runnable(){public void run(){
        if(isFinishing()||isDestroyed())return;
        if(DemoPolicy.expired(PlayerActivity.this)){finish();return;}
        demoHandler.postDelayed(this,1000);
    }};
''' + anchor, 1)
s = s.replace('if(DemoPolicy.blockPlayback(this)){finish();return;}', 'if(DemoPolicy.blockPlayback(this)){finish();return;}com.nenotv.player.storage.SecureProfileStore demoStore=new com.nenotv.player.storage.SecureProfileStore(this);if(demoStore.exists()&&DemoPolicy.isDemo(demoStore.load()))demoHandler.post(demoExpiryCheck);', 1)
player.write_text(s)

# Existing import tests stay intact. This additional phase exercises the real one-tap UI,
# provider/parser, and Media3 audio/video decoding against every packaged stream.
instr = root / 'app/src/androidTest/java/com/nenotv/player/ImportInstrumentation.java'
replace(instr, '    @Override public void onStart() {', (repo / 'tools/demo_instrumentation.txt').read_text() + '\n    @Override public void onStart() {')
replace(instr, 'if ("prepare_resume".equals(phase))', 'if ("demo".equals(phase)) demoSuite(result);\n            else if ("demo_resume".equals(phase)) verifyDemoResume(result);\n            else if ("prepare_resume".equals(phase))')
replace(instr, 'String key="prepare_resume".equals(phase)?', 'String key="demo_resume".equals(phase)?"NENOTV_DEMO_RESUME":"demo".equals(phase)?"NENOTV_DEMO_TESTS":"prepare_resume".equals(phase)?')

mode = sys.argv[1]
vc = 89 if mode == 'light' else 90
vn = '0.13.10-light-test' if mode == 'light' else '0.13.10-play1'
s = gradle.read_text()
s = re.sub(r'versionCode\s+\d+', f'versionCode {vc}', s, count=1)
s = re.sub(r"versionName\s+'[^']+'", f"versionName '{vn}'", s, count=1)
gradle.write_text(s)
arch = root / 'NENOTV_ARCHITECTURE.txt'
s = arch.read_text().replace('v0.13.8', 'v0.13.10')
s = re.sub(r'versionCode=\d+', f'versionCode={vc}', s, count=1)
s = re.sub(r'versionName=.*', f'versionName={vn}', s, count=1)
arch.write_text(s + 'demo_playlist=packaged_reviewed_open_films_and_eu_live\ndemo_network=playback_only\n')
print(f'Demo configured: {mode}, {vn}, vc{vc}')
