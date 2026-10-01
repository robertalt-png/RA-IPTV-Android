from pathlib import Path
import re

root = Path(".")
app = root / "app"
pro = root / "proextras"
OLD_PKG = "com.robertalt.raiptv"
NEW_PKG = "com.nenotv.player"
VERSION_CODE = 70
VERSION_NAME = "0.13.1-play2"

def must(path: Path):
    if not path.exists():
        raise SystemExit(f"Missing required path: {path}")
    return path

# --- Trial API: Light stays usable; a 30-day Pro trial only upgrades entitlement. ---
client = must(app / "src/main/java/com/robertalt/raiptv/entitlement/EntitlementClient.java")
s = client.read_text()
if "public JSONObject startTrial(" not in s:
    anchor = '    public JSONObject refresh() throws Exception {return post("entitlement/refresh",new JSONObject());}\n'
    if anchor not in s:
        raise SystemExit("EntitlementClient refresh anchor missing")
    addition = anchor + '''    public JSONObject startTrial(String email) throws Exception {
        JSONObject body=new JSONObject();
        body.put("email",email==null?"":email.trim());
        return post("entitlement/trial",body);
    }
'''
    s = s.replace(anchor, addition, 1)
client.write_text(s)

store = must(app / "src/main/java/com/robertalt/raiptv/storage/EntitlementStore.java")
s = store.read_text()
a = s.find("    public void applyServer(JSONObject o){")
b = s.find("\n    private long readEpoch", a)
if a < 0 or b < 0:
    raise SystemExit("EntitlementStore applyServer markers missing")
new_apply = '''    public void applyServer(JSONObject o){
        if(o==null)return;
        String levelRaw=o.optString("level","").trim().toLowerCase(Locale.ROOT);
        String status=o.optString("status","").trim().toLowerCase(Locale.ROOT);
        boolean active=status.isEmpty()||"active".equals(status)||"trial_active".equals(status);
        Level level;
        if(active&&"pro".equals(levelRaw))level=Level.PRO;
        else if(active&&("pro_trial".equals(levelRaw)||"trial".equals(levelRaw)))level=Level.PRO_TRIAL;
        else level=Level.FREE;
        long expiry=readEpoch(o,"expires_at_ms",1L);
        if(expiry<=0)expiry=readEpoch(o,"expires_at",1000L);
        SharedPreferences.Editor ed=prefs.edit().putString("level",level.name()).putLong("expires_at",expiry);
        if(o.has("email"))ed.putString("account_email",o.optString("email",""));
        if(o.has("account_email"))ed.putString("account_email",o.optString("account_email",""));
        if(o.has("max_devices"))ed.putInt("max_devices",Math.max(1,o.optInt("max_devices",1)));
        ed.apply();
    }
'''
s = s[:a] + new_apply + s[b:]
store.write_text(s)

# --- Dynamic-feature lifecycle: install for Trial/Pro, remove after fallback to Light. ---
installer = must(app / "src/main/java/com/robertalt/raiptv/ProModuleInstaller.java")
installer.write_text(r'''package com.robertalt.raiptv;

import android.app.Activity;
import android.content.Intent;
import android.widget.Toast;
import com.google.android.play.core.splitinstall.*;
import com.robertalt.raiptv.storage.EntitlementStore;
import java.util.Collections;

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
              .addOnFailureListener(e->Toast.makeText(a,"NenoTV Pro Media Pack kon niet worden gestart.",Toast.LENGTH_LONG).show());
        }catch(Throwable t){
            Toast.makeText(a,"NenoTV Pro Media Pack is beschikbaar via Google Play.",Toast.LENGTH_LONG).show();
        }
    }

    public static void syncEntitlement(Activity a){
        try{
            SplitInstallManager m=SplitInstallManagerFactory.create(a);
            boolean entitled=new EntitlementStore(a).isPro();
            boolean installed=m.getInstalledModules().contains(MODULE);
            if(entitled&&!installed){
                request(a);
            }else if(!entitled&&installed){
                m.deferredUninstall(Collections.singletonList(MODULE));
            }
        }catch(Throwable ignored){}
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
''')

account = must(app / "src/main/java/com/robertalt/raiptv/AccountActivity.java")
s = account.read_text()
s = s.replace(
    'trial.setOnClickListener(v->openWeb("https://nenotv.com/proefperiode?device="+Uri.encode(ent.publicDeviceId())));',
    'trial.setOnClickListener(v->startTrial());'
)
if "void startTrial(){" not in s:
    anchor = "    void claim(){\n"
    if anchor not in s:
        raise SystemExit("AccountActivity claim anchor missing")
    method = '''    void startTrial(){
        String e=email.getText().toString().trim();
        if(e.isEmpty()){email.setError(T("email_required"));return;}
        busy(true);
        exec.execute(()->{
            try{
                new EntitlementClient(this).startTrial(e);
                runOnUiThread(()->{busy(false);serverText.setText(T("status_updated"));refreshUi();ProModuleInstaller.syncEntitlement(this);});
            }catch(Exception ex){
                runOnUiThread(()->{busy(false);serverText.setText(T("activation_failed"));});
            }
        });
    }

'''
    s = s.replace(anchor, method + anchor, 1)

# Sync module state after all server entitlement mutations and on resume.
s = s.replace(
    '@Override protected void onResume(){super.onResume();refreshUi();}',
    '@Override protected void onResume(){super.onResume();refreshUi();ProModuleInstaller.syncEntitlement(this);}'
)
s = s.replace(
    'runOnUiThread(()->{busy(false);serverText.setText(T("status_updated"));refreshUi();});',
    'runOnUiThread(()->{busy(false);serverText.setText(T("status_updated"));refreshUi();ProModuleInstaller.syncEntitlement(this);});'
)
s = s.replace(
    'runOnUiThread(()->{busy(false);serverText.setText(T("activation_success"));refreshUi();});',
    'runOnUiThread(()->{busy(false);serverText.setText(T("activation_success"));refreshUi();ProModuleInstaller.syncEntitlement(this);});'
)
account.write_text(s)

main = must(app / "src/main/java/com/robertalt/raiptv/MainActivity.java")
s = main.read_text()
marker = "@Override protected void onResume(){super.onResume();"
if marker in s and "ProModuleInstaller.syncEntitlement(this);" not in s[s.find(marker):s.find(marker)+300]:
    s = s.replace(marker, marker + "ProModuleInstaller.syncEntitlement(this);", 1)
main.write_text(s)

# Make the trial wording explicit where present.
for p in [app / "src/main/java/com/robertalt/raiptv/UiText.java", account]:
    if not p.exists():
        continue
    s=p.read_text()
    s=s.replace("Request free trial","Start 30-day Pro trial")
    s=s.replace("Gratis proefperiode aanvragen","Start 30 dagen Pro")
    s=s.replace("Kostenlose Testphase starten","30 Tage Pro testen")
    p.write_text(s)

# --- Definitive first-publication identity. ---
gradle = must(app / "build.gradle")
s = gradle.read_text()
s = re.sub(r"applicationId\s+'[^']+'", f"applicationId '{NEW_PKG}'", s, count=1)
s = re.sub(r"versionCode\s+\d+", f"versionCode {VERSION_CODE}", s, count=1)
s = re.sub(r"versionName\s+'[^']+'", f"versionName '{VERSION_NAME}'", s, count=1)
gradle.write_text(s)

# Replace legacy source namespace and all Nivaro branding in active compiled text.
roots = [app, pro]
text_suffixes={".java",".kt",".xml",".gradle",".properties",".txt",".json",".pro",".html",".htm",".js",".css"}
for base in roots:
    if not base.exists():
        continue
    for p in base.rglob("*"):
        if not p.is_file() or p.suffix.lower() not in text_suffixes:
            continue
        try:
            x=p.read_text()
        except UnicodeDecodeError:
            continue
        x=x.replace(OLD_PKG,NEW_PKG)
        x=x.replace("NIVARO","NENOTV").replace("Nivaro","NenoTV").replace("nivaro","nenotv")
        x=x.replace("RA IPTV","NenoTV").replace("RA-IPTV-Android","NenoTV-Android").replace("RA-IPTV","NenoTV")
        p.write_text(x)

# Rename active source/resource filenames that still carry the old brand.
for base in roots:
    if not base.exists():
        continue
    for p in sorted(list(base.rglob("*")), key=lambda q: len(q.parts), reverse=True):
        new_name=p.name.replace("NIVARO","NENOTV").replace("Nivaro","NenoTV").replace("nivaro","nenotv")
        if new_name!=p.name:
            target=p.with_name(new_name)
            if target.exists():
                raise SystemExit(f"Cannot rename {p} -> {target}: target exists")
            p.rename(target)

# Root metadata used by QA.
marker = pro / "PRO_MEDIA_PACK.txt"
if marker.exists():
    x=marker.read_text()
    x=x.replace("base_application_id=com.robertalt.raiptv",f"base_application_id={NEW_PKG}")
    x=re.sub(r"dev_version=.*",f"dev_version={VERSION_NAME}",x)
    marker.write_text(x)

# App display name must be NenoTV.
strings = must(app / "src/main/res/values/strings.xml")
x=strings.read_text()
x=re.sub(r'<string name="app_name">.*?</string>','<string name="app_name">NenoTV</string>',x)
strings.write_text(x)

# Hard release gates.
active_files=[]
for base in roots:
    if not base.exists(): continue
    for p in base.rglob("*"):
        if p.is_file() and p.suffix.lower() in text_suffixes:
            try: active_files.append((p,p.read_text()))
            except UnicodeDecodeError: pass

legacy_brand=[str(p) for p,x in active_files if re.search(r"nivaro",x,re.I)]
legacy_pkg=[str(p) for p,x in active_files if OLD_PKG in x]
if legacy_brand:
    raise SystemExit("Legacy Nivaro branding remains in active source: "+", ".join(legacy_brand[:20]))
if legacy_pkg:
    raise SystemExit("Legacy package namespace remains in active source: "+", ".join(legacy_pkg[:20]))

g=gradle.read_text()
if f"applicationId '{NEW_PKG}'" not in g:
    raise SystemExit("New applicationId missing")
if f"versionCode {VERSION_CODE}" not in g or f"versionName '{VERSION_NAME}'" not in g:
    raise SystemExit("Release version mismatch")
if not pro.exists():
    raise SystemExit("proextras dynamic feature missing")

print(f"Prepared NenoTV Play release: {NEW_PKG} vc={VERSION_CODE} version={VERSION_NAME}")
print("Nivaro branding scan: PASS")
print("Legacy package namespace scan: PASS")
