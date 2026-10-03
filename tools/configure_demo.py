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

playlist = (repo / 'demo/nenotv-demo.m3u').read_text()
if playlist.count('#EXTINF:') != 2:
    raise SystemExit('reviewed demo playlist must contain two entries')
(java / 'DemoSource.java').write_text('''package com.nenotv.player;
public final class DemoSource {
    public static final String URL="nenotv://demo/v1";
    public static final String PLAYLIST=''' + json.dumps(playlist) + ''';
    private DemoSource(){}
}
''')
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
        if(DemoPolicy.blockPlayback(PlayerActivity.this)){finish();return;}
        demoHandler.postDelayed(this,1000);
    }};
''' + anchor, 1)
s = s.replace('if(DemoPolicy.blockPlayback(this)){finish();return;}', 'if(DemoPolicy.blockPlayback(this)){finish();return;}demoHandler.post(demoExpiryCheck);', 1)
player.write_text(s)

# Existing import tests stay intact. This additional phase exercises the real one-tap UI,
# provider/parser, and Media3 audio/video decoding against every packaged stream.
instr = root / 'app/src/androidTest/java/com/nenotv/player/ImportInstrumentation.java'
replace(instr, '    @Override public void onStart() {', (repo / 'tools/demo_instrumentation.txt').read_text() + '\n    @Override public void onStart() {')
replace(instr, 'if ("prepare_resume".equals(phase))', 'if ("demo".equals(phase)) demoSuite(result);\n            else if ("prepare_resume".equals(phase))')
replace(instr, 'String key="prepare_resume".equals(phase)?', 'String key="demo".equals(phase)?"NENOTV_DEMO_TESTS":"prepare_resume".equals(phase)?')

mode = sys.argv[1]
vc = 87 if mode == 'light' else 88
vn = '0.13.9-light-test' if mode == 'light' else '0.13.9-play1'
s = gradle.read_text()
s = re.sub(r'versionCode\s+\d+', f'versionCode {vc}', s, count=1)
s = re.sub(r"versionName\s+'[^']+'", f"versionName '{vn}'", s, count=1)
gradle.write_text(s)
arch = root / 'NENOTV_ARCHITECTURE.txt'
s = arch.read_text().replace('v0.13.8', 'v0.13.9')
s = re.sub(r'versionCode=\d+', f'versionCode={vc}', s, count=1)
s = re.sub(r'versionName=.*', f'versionName={vn}', s, count=1)
arch.write_text(s + 'demo_playlist=packaged_reviewed_cc_by_3_0\ndemo_network=playback_only\n')
print(f'Demo configured: {mode}, {vn}, vc{vc}')
