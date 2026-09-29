from pathlib import Path
import re, runpy, shutil

root = Path(".")
app = root / "app"
java = app / "src/main/java/com/robertalt/raiptv"
res = app / "src/main/res"

# Capture the full donor implementation BEFORE the proven Light/Free stripping pass.
# The upstream workflow has already applied the NenoTV Pro gates and all v0.10.x-v0.12.x fixes.
full_player = (java / "PlayerActivity.java").read_text()
full_player_layout = (res / "layout/activity_player.xml").read_text()
full_translator = (java / "InfoTranslator.java").read_text()

# Reuse the existing proven light transformation to make the base application small:
# - removes VLC from base
# - removes ML Kit translate/language-id from base
# - installs a Media3-only base PlayerActivity
# - creates the dynamic feature shell
runpy.run_path(str(Path(__file__).with_name("prepare_light.py")), run_name="__main__")

# This is the SAME NenoTV application/update line as Free, not a second package.
gradle = app / "build.gradle"
s = gradle.read_text()
s = s.replace("applicationId 'com.robertalt.raiptv.light'", "applicationId 'com.robertalt.raiptv'")
s = re.sub(r"versionCode\s+\d+", "versionCode 65", s, count=1)
s = re.sub(r"versionName\s+'[^']+'", "versionName '0.12.6.6-pro-dev1'", s, count=1)
s = s.replace("buildConfigField 'boolean', 'LIGHT_BUILD', 'true'",
              "buildConfigField 'boolean', 'LIGHT_BUILD', 'false'")
gradle.write_text(s)

strings = res / "values/strings.xml"
x = strings.read_text()
x = re.sub(r'<string name="app_name">.*?</string>',
           '<string name="app_name">NenoTV</string>', x)
strings.write_text(x)

# Lightweight Pro features remain in the base app and are entitlement-gated.
# Do NOT force a 40-60 MB media pack download merely to use parental controls,
# advanced EPG or other code-only features.
pg = java / "ProGate.java"
x = pg.read_text()
x = x.replace(
    'if(allowed(a)){ if(!ProModuleInstaller.isInstalled(a)){ProModuleInstaller.request(a);return false;} return true; }',
    'if(allowed(a))return true;'
)
pg.write_text(x)

# The heavy module contains VLC and ML Kit only. The base app compiles without them.
mod = root / "proextras"
(mod / "src/main/java/com/robertalt/raiptv/proextras").mkdir(parents=True, exist_ok=True)
(mod / "src/main/res/layout").mkdir(parents=True, exist_ok=True)
(mod / "src/main/res/values").mkdir(parents=True, exist_ok=True)

(mod / "build.gradle").write_text("""plugins { id 'com.android.dynamic-feature' }

android {
    namespace 'com.robertalt.raiptv.proextras'
    compileSdk 36
    defaultConfig { minSdk 26 }
    compileOptions {
        sourceCompatibility JavaVersion.VERSION_17
        targetCompatibility JavaVersion.VERSION_17
    }
}

dependencies {
    implementation project(':app')
    implementation 'androidx.fragment:fragment:1.8.9'
    implementation 'androidx.media3:media3-exoplayer:1.11.1'
    implementation 'androidx.media3:media3-exoplayer-hls:1.11.1'
    implementation 'androidx.media3:media3-ui:1.11.1'
    implementation 'com.google.android.gms:play-services-cast-framework:22.3.1'
    implementation 'org.videolan.android:libvlc-all:3.7.6'
    implementation 'com.google.mlkit:translate:17.0.3'
    implementation 'com.google.mlkit:language-id:17.0.6'
}
""")

(mod / "src/main/AndroidManifest.xml").write_text("""<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android"
          xmlns:dist="http://schemas.android.com/apk/distribution">
  <dist:module dist:instant="false" dist:title="@string/title_proextras">
    <dist:delivery><dist:on-demand/></dist:delivery>
    <dist:fusing dist:include="true"/>
  </dist:module>
  <application>
    <activity
      android:name="com.robertalt.raiptv.proextras.ProPlayerActivity"
      android:exported="false"
      android:configChanges="keyboardHidden|orientation|screenSize"
      android:supportsPictureInPicture="true"/>
  </application>
</manifest>
""")

(mod / "src/main/res/values/strings.xml").write_text(
    '<resources><string name="title_proextras">NenoTV Pro Media Pack</string></resources>\n'
)
(mod / "src/main/res/layout/activity_pro_player.xml").write_text(full_player_layout)

# Full donor player becomes the Pro media player in the dynamic feature.
pro_player = full_player
pro_player = pro_player.replace(
    "package com.robertalt.raiptv;",
    """package com.robertalt.raiptv.proextras;

import com.robertalt.raiptv.DisplayText;
import com.robertalt.raiptv.NivaroCastOptionsProvider;
import com.robertalt.raiptv.ProGate;
import com.robertalt.raiptv.UiText;"""
    , 1
)
pro_player = pro_player.replace("public class PlayerActivity extends", "public class ProPlayerActivity extends", 1)
pro_player = pro_player.replace("PlayerActivity.this", "ProPlayerActivity.this")
pro_player = pro_player.replace("R.layout.activity_player", "R.layout.activity_pro_player")
# Base colors are owned by the application module; keep feature-local ids/layout in feature R.
pro_player = pro_player.replace("R.color.", "com.robertalt.raiptv.R.color.")
# Feature module owns its R class; all shared models/storage/cast/subtitle imports remain valid.
(mod / "src/main/java/com/robertalt/raiptv/proextras/ProPlayerActivity.java").write_text(pro_player)

# Restore local metadata translation through the on-demand module.
# Base InfoTranslator uses reflection, so it has zero compile dependency on ML Kit.
(java / "InfoTranslator.java").write_text("""package com.robertalt.raiptv;

public final class InfoTranslator {
    public interface Callback { void done(String text); }
    private InfoTranslator(){}

    public static void translate(String text,String target,Callback cb){
        String fallback=text==null?"":text;
        try{
            Class<?> impl=Class.forName("com.robertalt.raiptv.proextras.ProInfoTranslator");
            java.lang.reflect.Method m=impl.getMethod(
                "translate",String.class,String.class,InfoTranslator.Callback.class);
            m.invoke(null,text,target,cb);
        }catch(Throwable ignored){
            if(cb!=null)cb.done(fallback);
        }
    }
}
""")

pro_translator = full_translator
pro_translator = pro_translator.replace(
    "package com.robertalt.raiptv;",
    """package com.robertalt.raiptv.proextras;

import com.robertalt.raiptv.InfoTranslator;""",
    1
)
pro_translator = pro_translator.replace("public final class InfoTranslator", "public final class ProInfoTranslator", 1)
pro_translator = pro_translator.replace("private InfoTranslator()", "private ProInfoTranslator()")
pro_translator = re.sub(
    r"public interface Callback\s*\{\s*void done\(String text\);\s*\}\s*",
    "",
    pro_translator,
    count=1
)
pro_translator = pro_translator.replace(
    "public static void translate(String text, String target, Callback cb)",
    "public static void translate(String text, String target, InfoTranslator.Callback cb)"
)
(mod / "src/main/java/com/robertalt/raiptv/proextras/ProInfoTranslator.java").write_text(pro_translator)

# Module installer: lightweight Pro is immediate. Heavy playback/translation is on demand.
installer = java / "ProModuleInstaller.java"
installer.write_text("""package com.robertalt.raiptv;

import android.app.Activity;
import android.content.Intent;
import android.widget.Toast;
import com.google.android.play.core.splitinstall.*;
import com.robertalt.raiptv.storage.EntitlementStore;

public final class ProModuleInstaller {
    private static final String MODULE="proextras";
    private static final String PRO_PLAYER="com.robertalt.raiptv.proextras.ProPlayerActivity";
    private ProModuleInstaller(){}

    public static boolean isInstalled(Activity a){
        try{return SplitInstallManagerFactory.create(a).getInstalledModules().contains(MODULE);}
        catch(Throwable t){return false;}
    }

    public static void request(Activity a){
        try{
            SplitInstallManager m=SplitInstallManagerFactory.create(a);
            if(m.getInstalledModules().contains(MODULE))return;
            SplitInstallRequest r=SplitInstallRequest.newBuilder().addModule(MODULE).build();
            m.startInstall(r)
              .addOnSuccessListener(id->Toast.makeText(a,"NenoTV Pro Media Pack wordt gedownload…",Toast.LENGTH_LONG).show())
              .addOnFailureListener(e->Toast.makeText(a,"Pro Media Pack kon niet worden gestart.",Toast.LENGTH_LONG).show());
        }catch(Throwable t){
            Toast.makeText(a,"Pro Media Pack is beschikbaar via Google Play.",Toast.LENGTH_LONG).show();
        }
    }

    public static Intent playerIntent(Activity a){
        boolean pro=new EntitlementStore(a).isPro();
        if(pro&&isInstalled(a)){
            Intent i=new Intent();
            i.setClassName(a,PRO_PLAYER);
            return i;
        }
        if(pro&&!isInstalled(a))request(a);
        return new Intent(a,PlayerActivity.class);
    }
}
""")

# Route playback through the Pro media player only when entitlement + module are both present.
main = java / "MainActivity.java"
m = main.read_text()
m = m.replace("new Intent(this,PlayerActivity.class)", "ProModuleInstaller.playerIntent(this)")
main.write_text(m)

# Machine-readable marker for CI/audit.
(root / "proextras/PRO_MEDIA_PACK.txt").write_text(
    "NenoTV Pro Media Pack\n"
    "delivery=on-demand\n"
    "heavy_dependencies=libvlc-all,mlkit-translate,mlkit-language-id\n"
    "base_application_id=com.robertalt.raiptv\n"
    "dev_version=0.12.6.6-pro-dev1\n"
)

print("Prepared NenoTV modular Pro dev build: lean base + on-demand Pro Media Pack")
