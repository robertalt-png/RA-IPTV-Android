from pathlib import Path
import re, shutil, sys
from repair_account_manifest import repair_manifest
from repair_responsiveness import repair_main
from repair_streaming_import import repair_project

if len(sys.argv)!=2 or sys.argv[1] not in {'light','modular'}:
    raise SystemExit('usage: prepare_v0132_architecture.py light|modular')
mode=sys.argv[1]
root=Path('.')
app=root/'app'
java=app/'src/main/java/com/robertalt/raiptv'
pro=root/'proextras'

repair_manifest(app/'src/main/AndroidManifest.xml')

for p in [app/'build.gradle', java/'MainActivity.java', java/'storage/SearchIndexStore.java']:
    if not p.exists(): raise SystemExit(f'missing {p}')

version_code=71 if mode=='light' else 73
version_name='0.13.2-light-test' if mode=='light' else '0.13.2-play2'

gradle=app/'build.gradle'
s=gradle.read_text()
s=re.sub(r"applicationId\s+'[^']+'", "applicationId 'com.nenotv.player'", s, count=1)
s=re.sub(r"namespace\s+'[^']+'", "namespace 'com.nenotv.player'", s, count=1)
s=re.sub(r'versionCode\s+\d+', f'versionCode {version_code}', s, count=1)
s=re.sub(r"versionName\s+'[^']+'", f"versionName '{version_name}'", s, count=1)
s=re.sub(r"buildConfigField 'boolean', 'LIGHT_BUILD', '(?:true|false)'", f"buildConfigField 'boolean', 'LIGHT_BUILD', '{'true' if mode=='light' else 'false'}'", s, count=1)
if mode=='light':
    s=re.sub(r"\n\s*dynamicFeatures\s*=\s*\[':proextras'\]\s*", "\n", s)
    s=s.replace("    implementation 'com.google.android.play:feature-delivery:2.1.0'\n","")
else:
    if "dynamicFeatures = [':proextras']" not in s:
        s=s.replace('    buildFeatures { buildConfig true }', "    buildFeatures { buildConfig true }\n    dynamicFeatures = [':proextras']")
    if 'feature-delivery:2.1.0' not in s:
        s=s.replace('dependencies {', "dependencies {\n    implementation 'com.google.android.play:feature-delivery:2.1.0'")
gradle.write_text(s)

strings=app/'src/main/res/values/strings.xml'
x=strings.read_text()
x=re.sub(r'<string name="app_name">.*?</string>', '<string name="app_name">NenoTV Light</string>' if mode=='light' else '<string name="app_name">NenoTV</string>', x)
strings.write_text(x)

# Light owns provider authentication/fetch/cache and preserves provider order.
main=java/'MainActivity.java'
s=main.read_text()
s=s.replace('final List<Category>cats=new ArrayList<>(currentCategories);final String pref=SettingsStore.contentLanguage(this);cats.sort((a,b)->Integer.compare(ContentLanguage.rankText(a.name,pref),ContentLanguage.rankText(b.name,pref)));',
            'final List<Category>cats=new ArrayList<>(currentCategories);')
s=s.replace('List<Category> cats=new ArrayList<>(provider.categories(type));\n                        cats.sort((ca,cb)->{int z=Integer.compare(ContentLanguage.rankText(ca.name,preferred),ContentLanguage.rankText(cb.name,preferred));return z!=0?z:safe(ca.name).compareToIgnoreCase(safe(cb.name));});',
            'List<Category> cats=new ArrayList<>(provider.categories(type));')
s=re.sub(r'Category chooseLiveCategory\(List<Category>cats\)\{[^\n]*\}',
         'Category chooseLiveCategory(List<Category>cats){return cats==null||cats.isEmpty()?null:cats.get(0);}',s,count=1)

# Base keeps only explicit basic sorts. Automatic optimization is delegated to Pro.
start=s.find('    boolean isMainDutchTv(MediaEntry e){')
end=s.find('    void showSortChooser(){', start)
if start<0 or end<0: raise SystemExit('sort block markers missing')
sort_block='''    List<MediaEntry> sortItems(List<MediaEntry>src){
        List<MediaEntry>o=new ArrayList<>(src==null?Collections.emptyList():src);
        String mode=SettingsStore.sort(this);
        Comparator<MediaEntry>az=(a,b)->safe(a.name).compareToIgnoreCase(safe(b.name));
        if("az".equals(mode))o.sort(az);
        else if("za".equals(mode))o.sort(az.reversed());
        else if("favorites".equals(mode))o.sort((a,b)->{int x=Boolean.compare(library.isFavorite(b),library.isFavorite(a));return x!=0?x:az.compare(a,b);});
        else if("recent".equals(mode))o.sort((a,b)->{int x=Integer.compare(library.recentRank(a),library.recentRank(b));return x!=0?x:az.compare(a,b);});
        if("provider".equals(mode))o=ProLibraryBridge.optimize(this,o,section,SettingsStore.contentLanguage(this));
        return o;
    }
'''
s=s[:start]+sort_block+s[end:]
s=s.replace('void showSortChooser(){String[]labels={T("provider"),"A–Z","Z–A",T("favorites_first"),T("recent_first")};',
            'void showSortChooser(){String[]labels={ProLibraryBridge.isActive(this)?"Pro · "+T("recommended"):T("provider"),"A–Z","Z–A",T("favorites_first"),T("recent_first")};')
main.write_text(s)

# Base cache paging stays in provider insertion order unless the user explicitly asks A-Z/Z-A.
idx=java/'storage/SearchIndexStore.java'
s=idx.read_text()
method_start=s.find('    public synchronized List<MediaEntry> sectionPage(String profile,String section,int offset,int limit,String sort,String preferredLanguage){')
method_end=s.find('\n    public synchronized int countLanguage(',method_start)
if method_start<0 or method_end<0: raise SystemExit('SearchIndexStore sectionPage markers missing')
method='''    public synchronized List<MediaEntry> sectionPage(String profile,String section,int offset,int limit,String sort,String preferredLanguage){
        ArrayList<MediaEntry> out=new ArrayList<>();int safeOffset=Math.max(0,offset),safeLimit=Math.max(1,Math.min(1000,limit));
        String order="rowid ASC";if("az".equals(sort))order="name_norm COLLATE NOCASE ASC";else if("za".equals(sort))order="name_norm COLLATE NOCASE DESC";
        SQLiteDatabase db=getWritableDatabase();ensureLanguageHints(db,profile,section);
        String sql="SELECT payload FROM entries WHERE profile=? AND type=? ORDER BY "+order+" LIMIT ? OFFSET ?";
        String[] args=new String[]{profile,section,String.valueOf(safeLimit),String.valueOf(safeOffset)};
        try(Cursor c=db.rawQuery(sql,args)){while(c.moveToNext()){MediaEntry e=decode(c.getString(0));if(e!=null)out.add(e);}}catch(Exception ignored){}
        return out;
    }
'''
s=s[:method_start]+method+s[method_end:]
idx.write_text(s)

# Bridge passes already-loaded objects to the dynamic feature; it never asks a provider for data.
bridge=java/'ProLibraryBridge.java'
if mode=='modular':
    bridge.write_text('''package com.nenotv.player;

import android.app.Activity;
import com.nenotv.player.model.MediaEntry;
import java.util.*;

public final class ProLibraryBridge {
    private static final String OPTIMIZER="com.nenotv.player.proextras.ProLibraryOptimizer";
    private ProLibraryBridge(){}
    public static boolean isActive(Activity a){return a!=null&&ProGate.allowed(a)&&ProModuleInstaller.isInstalled(a);}
    @SuppressWarnings("unchecked")
    public static List<MediaEntry> optimize(Activity a,List<MediaEntry> input,String section,String preferredLanguage){
        ArrayList<MediaEntry> fallback=new ArrayList<>(input==null?Collections.emptyList():input);
        if(!isActive(a))return fallback;
        try{
            Class<?> c=Class.forName(OPTIMIZER);
            java.lang.reflect.Method m=c.getMethod("optimize",List.class,String.class,String.class);
            Object out=m.invoke(null,fallback,section,preferredLanguage);
            if(out instanceof List)return new ArrayList<>((List<MediaEntry>)out);
        }catch(Throwable ignored){}
        return fallback;
    }
}
''')
else:
    bridge.write_text('''package com.nenotv.player;

import android.app.Activity;
import com.nenotv.player.model.MediaEntry;
import java.util.*;

public final class ProLibraryBridge {
    private ProLibraryBridge(){}
    public static boolean isActive(Activity a){return false;}
    public static List<MediaEntry> optimize(Activity a,List<MediaEntry> input,String section,String preferredLanguage){return new ArrayList<>(input==null?Collections.emptyList():input);}
}
''')

gate=java/'ProGate.java'
gs=gate.read_text()
gs=gs.replace('return new EntitlementStore(a).isPro();','return !BuildConfig.LIGHT_BUILD && new EntitlementStore(a).isPro();')
gate.write_text(gs)

if mode=='light':
    installer=java/'ProModuleInstaller.java'
    installer.write_text('''package com.nenotv.player;

import android.app.Activity;
import android.content.Intent;

public final class ProModuleInstaller {
    private ProModuleInstaller(){}
    public static boolean isInstalled(Activity a){return false;}
    public static void request(Activity a){}
    public static void syncEntitlement(Activity a){}
    public static Intent playerIntent(Activity a){return new Intent(a,PlayerActivity.class);}
}
''')
    settings=root/'settings.gradle'
    st=settings.read_text();st=re.sub(r"\n\s*include\s+':proextras'\s*",'\n',st);settings.write_text(st)
    if pro.exists(): shutil.rmtree(pro)
else:
    if not pro.exists(): raise SystemExit('proextras missing in modular mode')
    optimizer=pro/'src/main/java/com/robertalt/raiptv/proextras/ProLibraryOptimizer.java'
    optimizer.parent.mkdir(parents=True,exist_ok=True)
    optimizer.write_text('''package com.nenotv.player.proextras;

import com.nenotv.player.ContentLanguage;
import com.nenotv.player.model.MediaEntry;
import java.util.*;

/** Pure post-load optimizer. It never authenticates, fetches categories, or calls an IPTV provider. */
public final class ProLibraryOptimizer {
    private ProLibraryOptimizer(){}
    public static List<MediaEntry> optimize(List<MediaEntry> loaded,String section,String preferredLanguage){
        LinkedHashMap<String,MediaEntry> unique=new LinkedHashMap<>();
        if(loaded!=null)for(MediaEntry e:loaded)if(e!=null)unique.putIfAbsent(e.uniqueKey(),e);
        ArrayList<MediaEntry> out=new ArrayList<>(unique.values());
        final String pref=preferredLanguage==null?"":preferredLanguage;
        Comparator<MediaEntry> language=(a,b)->Integer.compare(ContentLanguage.rank(a,pref),ContentLanguage.rank(b,pref));
        Comparator<MediaEntry> quality=(a,b)->Integer.compare(liveRank(a),liveRank(b));
        Comparator<MediaEntry> name=(a,b)->safe(a.name).compareToIgnoreCase(safe(b.name));
        if("live".equals(section))out.sort(language.thenComparing(quality).thenComparing(name));
        else if("vod".equals(section)||"series".equals(section))out.sort(language.thenComparing(name));
        else if(!out.isEmpty()&&"episode".equals(out.get(0).type))out.sort(Comparator.comparingInt((MediaEntry e)->e.season).thenComparingInt(e->e.episode));
        return out;
    }
    private static int liveRank(MediaEntry e){
        String x=(safe(e==null?"":e.name)+" "+safe(e==null?"":e.group)).toLowerCase(Locale.ROOT);
        int r=0;if(isRadio(x))r+=10000;else if(isMainNl(x))r-=600;if(e!=null&&e.catchup)r-=350;
        if(x.matches(".*(?:\\b4k\\b|\\buhd\\b).*"))r-=260;else if(x.matches(".*\\bfhd\\b.*"))r-=220;else if(x.matches(".*\\bhd\\b.*"))r-=180;else r+=40;return r;
    }
    private static boolean isRadio(String x){return x.matches(".*(?:\\bradio\\b|\\bfm\\b).* ")||x.contains(" radio ")||x.startsWith("radio ");}
    private static boolean isMainNl(String x){String z=x.replaceAll("[^a-z0-9]+"," ");return z.matches(".*\\b(?:npo ?[123]|rtl ?[4578]|sbs ?6|net ?5|veronica|rtl z|rtl lounge)\\b.*");}
    private static String safe(String s){return s==null?"":s;}
}
''')
    marker=pro/'PRO_LIBRARY_ARCHITECTURE.txt'
    marker.write_text('NenoTV v0.13.2 Pro architecture\ninput=already-loaded Light MediaEntry list\nprovider_fetch=false\ncategory_fetch=false\nauthentication=false\nfunctions=dedupe,rank,sort,metadata/player extras\n')

(root/'NENOTV_ARCHITECTURE.txt').write_text(
    f'NenoTV v0.13.2\nmode={mode}\npackage=com.nenotv.player\nversionCode={version_code}\nversionName={version_name}\n'
    'light_owns=authentication,provider_fetch,category_fetch,library_cache,search_index,basic_display\n'
    + ('proextras=absent\n' if mode=='light' else 'proextras=on-demand;uses_loaded_library_only=true\n')
)

g=gradle.read_text()
if f'versionCode {version_code}' not in g or f"versionName '{version_name}'" not in g: raise SystemExit('version gate failed')
repair_main(main)
repair_project(root)
main_text=main.read_text();idx_text=idx.read_text()
if 'cats.sort((a,b)->Integer.compare(ContentLanguage.rankText' in main_text: raise SystemExit('Light loader still ranks categories')
if 'ORDER BY CASE WHEN lang_tag=' in idx_text: raise SystemExit('Light cache still auto-ranks by language')
if 'ProLibraryBridge.optimize' not in main_text: raise SystemExit('Pro library bridge not wired')
if mode=='light':
    if pro.exists(): raise SystemExit('Light unexpectedly contains proextras')
    if "dynamicFeatures" in gradle.read_text() or "feature-delivery" in gradle.read_text(): raise SystemExit('Light still has Play feature dependency')
else:
    forbidden=('provider.items','provider.categories','XtreamProvider','M3uProvider','HttpText','XtreamUrls','com.nenotv.player.provider')
    hits=[]
    for p in pro.rglob('*.java'):
        t=p.read_text()
        for needle in forbidden:
            if needle in t: hits.append(f'{p}:{needle}')
    if hits: raise SystemExit('Pro module contains IPTV loading dependency: '+', '.join(hits))
    if 'class ProLibraryOptimizer' not in optimizer.read_text(): raise SystemExit('Pro optimizer missing')

print(f'Prepared NenoTV {mode} architecture {version_name} vc={version_code}')
