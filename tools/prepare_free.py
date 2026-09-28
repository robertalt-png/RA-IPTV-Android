from pathlib import Path
import re, runpy, shutil

# Reuse the proven light-player stripping, then turn it into the single Free core.
runpy.run_path(str(Path(__file__).with_name("prepare_light.py")), run_name="__main__")

root=Path(".")
app=root/"app"

# One real NenoTV package: this Free core is the upgrade line.
gradle=app/"build.gradle"
s=gradle.read_text()
s=s.replace("applicationId 'com.robertalt.raiptv.light'","applicationId 'com.robertalt.raiptv'")
s=re.sub(r"versionCode\s+\d+","versionCode 49",s,1)
s=re.sub(r"versionName\s+'[^']+'","versionName '0.12.0'",s,1)
s=s.replace("    dynamicFeatures = [':proextras']\n","")
s=s.replace("    implementation 'com.google.android.play:feature-delivery:2.1.0'\n","")
gradle.write_text(s)

settings=root/"settings.gradle"
x=settings.read_text().replace("\ninclude ':proextras'\n","\n")
settings.write_text(x)
shutil.rmtree(root/"proextras",ignore_errors=True)

# Main app name, not a separate Light product.
strings=app/"src/main/res/values/strings.xml"
x=strings.read_text()
x=re.sub(r'<string name="app_name">.*?</string>','<string name="app_name">NenoTV</string>',x)
strings.write_text(x)
try:(app/"src/main/res/values/feature_strings.xml").unlink()
except FileNotFoundError:pass

# No module installer in Free yet. Locked Pro controls stay visible through ProGate.
installer=app/"src/main/java/com/robertalt/raiptv/ProModuleInstaller.java"
try:installer.unlink()
except FileNotFoundError:pass
pg=app/"src/main/java/com/robertalt/raiptv/ProGate.java"
x=pg.read_text()
x=x.replace('if(allowed(a)){ if(!ProModuleInstaller.isInstalled(a)){ProModuleInstaller.request(a);return false;} return true; }','if(allowed(a))return true;')
pg.write_text(x)

# Free core = provider order + on-demand category loading only. No full-library indexing.
main=app/"src/main/java/com/robertalt/raiptv/MainActivity.java"
s=main.read_text()

def replace_method(src, signature, next_signature, body):
    a=src.find(signature)
    if a<0: raise RuntimeError("Missing method: "+signature)
    b=src.find(next_signature,a)
    if b<0: raise RuntimeError("Missing next method after: "+signature)
    return src[:a]+body+src[b:]

# No background indexer in Free.
a=s.find('    void refreshSearchIndex(boolean force){')
if a>=0:
    b=s.find('\n    void waitWhilePaused()',a)
    if b>0:
        s=s[:a]+'''    void refreshSearchIndex(boolean force){ indexRefreshRunning=false; if(indexBanner!=null)indexBanner.setVisibility(View.GONE); }
'''+s[b:]

# Never schedule any full-library background work in Free.
a=s.find('    void scheduleBackgroundIndex(){')
if a>=0:
    b=s.find('\n    void ',a+10)
    if b>0:
        s=s[:a]+'''    void scheduleBackgroundIndex(){ if(indexBanner!=null)indexBanner.setVisibility(View.GONE); }
'''+s[b:]
a=s.find('    void pauseBackgroundIndexForUi(){')
if a>=0:
    b=s.find('\n    void ',a+10)
    if b>0:
        s=s[:a]+'''    void pauseBackgroundIndexForUi(){ if(delayedIndexResume!=null)ui.removeCallbacks(delayedIndexResume); Future<?> f=indexFuture;if(f!=null&&!f.isDone())f.cancel(true); if(indexBanner!=null)indexBanner.setVisibility(View.GONE); }
'''+s[b:]

# Provider-order spinner helper.
helper='''    void setProviderCategorySpinner(List<Category> raw){
        List<Category> c=new ArrayList<>();if(raw!=null)c.addAll(raw);
        if(c.isEmpty())c.add(new Category("",T("choose_category"),section));
        ArrayAdapter<Category>a=new ArrayAdapter<Category>(this,android.R.layout.simple_spinner_item,c){
            @Override public View getView(int position,View convertView,ViewGroup parent){View v=super.getView(position,convertView,parent);if(v instanceof TextView){TextView t=(TextView)v;t.setTextColor(getResources().getColor(R.color.text));t.setPadding(12,10,12,10);}return v;}
            @Override public View getDropDownView(int position,View convertView,ViewGroup parent){View v=super.getDropDownView(position,convertView,parent);if(v instanceof TextView){TextView t=(TextView)v;t.setTextColor(getResources().getColor(R.color.text));t.setBackgroundColor(getResources().getColor(R.color.card));t.setPadding(24,16,24,16);}return v;}
        };a.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);settingCategories=true;categories.setAdapter(a);categories.setSelection(0,false);settingCategories=false;
    }

'''
if 'void setProviderCategorySpinner(' not in s:
    s=s.replace('    void selectSpinner(String id){',helper+'    void selectSpinner(String id){',1)

# Sections: categories exactly as provider returns them, first provider category opens immediately.
section_body='''    void loadSection(String s){
        pauseBackgroundIndexForUi();stopCachePaging();final int token=nextRequest();section=s;updateBottomNav(s);autoDefaultGroup=false;SettingsStore.setLastSection(this,s);seriesEpisodeMode=false;latestSearchQuery="";currentCategoryId="";currentCategoryName="";epgModeBar.setVisibility(View.GONE);
        if(profile!=null&&profile.type==Profile.Type.M3U&&!s.equals("live")){showLocal(Collections.emptyList(),T("m3u_live_only"));return;}
        setHeroHeight(heroHeight());collapseSearch();search.setHint(T("search_everywhere"));filterBar.setVisibility(View.VISIBLE);sortButton.setVisibility(View.GONE);genreButton.setVisibility(View.GONE);categories.setVisibility(View.VISIBLE);showMediaGrid(s.equals("live"));gridAdapter.set(Collections.emptyList(),s.equals("live"));setHeroDefault(label(s),s.equals("live")?T("all_live_sub"):s.equals("vod")?T("all_movies_sub"):T("all_series_sub"));busy(true,T("categories_loading"));
        exec.execute(()->{try{
            List<Category> raw=visibleCategories(new ArrayList<>(provider.categories(s)));
            runOnUiThread(()->{if(!current(token)||!section.equals(s))return;currentCategories=raw;setProviderCategorySpinner(raw);if(raw.isEmpty()){busy(false,T("no_categories"));return;}Category first=raw.get(0);currentCategoryId=first.id;currentCategoryName=first.name;loadItems(first.id);});
        }catch(Exception e){runOnUiThread(()->{if(current(token))busy(false,T("error_prefix")+": "+friendly(e));});}});
    }

'''
s=replace_method(s,'    void loadSection(String s){','    void stopCachePaging()',section_body)

# Items: no sorting, no indexing, preserve provider order.
items_body='''    void loadItems(String cat){
        final String requested=section;stopCachePaging();final int token=nextRequest();busy(true,T("loading"));
        exec.execute(()->{try{
            List<MediaEntry> loaded=provider.items(requested,cat);List<MediaEntry>x=visibleItems(loaded);
            runOnUiThread(()->{if(!current(token)||!section.equals(requested))return;all=new ArrayList<>(x);showMediaGrid(requested.equals("live"));gridAdapter.set(x,requested.equals("live"));if(!x.isEmpty())previewAuto(x.get(0));busy(false,x.size()+" "+T("results")+(hiddenCount(loaded,x)>0?" · "+T("hidden_adult"):""));});
        }catch(Exception e){runOnUiThread(()->{if(current(token))busy(false,T("error_prefix")+": "+friendly(e));});}});
    }

'''
s=replace_method(s,'    void loadItems(String cat){','    void loadLanguageGroup(',items_body)

# EPG: same provider order; no language grouping/sorting.
epg_body='''    void loadEpg(){
        pauseBackgroundIndexForUi();stopCachePaging();final int token=nextRequest();section="epg";updateBottomNav("epg");autoDefaultGroup=false;SettingsStore.setLastSection(this,"epg");setHeroHeight(heroHeight());collapseSearch();seriesEpisodeMode=false;latestSearchQuery="";genreButton.setVisibility(View.GONE);sortButton.setVisibility(View.GONE);filterBar.setVisibility(View.VISIBLE);categories.setVisibility(View.VISIBLE);epgModeBar.setVisibility(View.VISIBLE);search.setHint(T("search_channel"));setHeroDefault(T("epg"),T("now_next"));gridAdapter.set(Collections.emptyList(),true);showEpgByMode(Collections.emptyList());busy(true,T("epg_categories_loading"));
        exec.execute(()->{try{List<Category>raw=visibleCategories(new ArrayList<>(provider.categories("live")));runOnUiThread(()->{if(!current(token)||!section.equals("epg"))return;currentCategories=raw;setProviderCategorySpinner(raw);if(raw.isEmpty()){busy(false,T("no_live_categories"));return;}Category first=raw.get(0);currentCategoryId=first.id;currentCategoryName=first.name;loadEpgChannels(first.id);});}catch(Exception e){runOnUiThread(()->{if(current(token))busy(false,T("epg_error")+": "+friendly(e));});}});
    }
'''
s=replace_method(s,'    void loadEpg(){','    void loadEpgChannels(',epg_body)

# EPG channel rows keep provider order.
a=s.find('    void loadEpgChannels(String cat){')
if a>=0:
    b=s.find('\n\n    void setEpgMode(',a)
    if b>0:
        s=s[:a]+'''    void loadEpgChannels(String cat){final int token=nextRequest();busy(true,T("channels_loading"));exec.execute(()->{try{List<MediaEntry>x=visibleItems(provider.items("live",cat));runOnUiThread(()->{if(!current(token)||!section.equals("epg"))return;all=new ArrayList<>(x);showEpgByMode(x);busy(false,x.size()+" "+T("channels")+" · "+T("visible_epg"));});}catch(Exception e){runOnUiThread(()->{if(current(token))busy(false,T("epg_channels_failed")+": "+friendly(e));});}});}
'''+s[b:]

# Search only what Free has already loaded; never start a global index.
a=s.find('    void searchEverywhere(String q){')
if a>=0:
    b=s.find('\n    String safe(',a)
    if b>0:
        search='''    void searchEverywhere(String q){
        String z=q==null?"":q.trim().toLowerCase(Locale.ROOT);latestSearchQuery=z;
        if("epg".equals(section)){filterBar.setVisibility(z.isEmpty()?View.VISIBLE:View.GONE);showEpgList();epgAdapter.configure(provider,profileKey());epgAdapter.set(all);if(!z.isEmpty())epgAdapter.filter(z);busy(false,z.isEmpty()?T("epg"):T("results"));return;}
        if(z.isEmpty()){filterBar.setVisibility(View.VISIBLE);categories.setVisibility(View.VISIBLE);showMediaGrid("live".equals(section));gridAdapter.set(new ArrayList<>(all),"live".equals(section));busy(false,all.size()+" "+T("results"));return;}
        if(z.length()<2){gridAdapter.set(Collections.emptyList(),false);status.setText(T("type_2"));return;}
        categories.setVisibility(View.GONE);filterBar.setVisibility(View.GONE);showMediaGrid(false);ArrayList<MediaEntry> found=new ArrayList<>();for(MediaEntry e:all){String hay=(safe(e.name)+" "+safe(e.group)+" "+safe(e.meta())).toLowerCase(Locale.ROOT);if(hay.contains(z))found.add(e);}gridAdapter.set(found,false);busy(false,found.isEmpty()?T("no_results_for")+" ‘"+z+"’":found.size()+" "+T("results"));
    }
'''
        s=s[:a]+search+s[b:]

# Free never schedules background indexing anywhere, including legacy helper paths.
s=s.replace('scheduleBackgroundIndex();','')

# Free never shows first-sync/index banner.
a=s.find('    void showIndexBanner(String type,int done,int cats,int titles){')
if a>=0:
    b=s.find('\n    void restoreFirstSyncBanner()',a)
    if b>0:s=s[:a]+'    void showIndexBanner(String type,int done,int cats,int titles){if(indexBanner!=null)indexBanner.setVisibility(View.GONE);}\n'+s[b:]
a=s.find('    void restoreFirstSyncBanner(){')
if a>=0:
    b=s.find('\n    void hideIndexBanner(',a)
    if b>0:s=s[:a]+'    void restoreFirstSyncBanner(){if(indexBanner!=null)indexBanner.setVisibility(View.GONE);}\n'+s[b:]

# Free TV-share/cast control stays locked for Pro instead of launching Cast.
s=s.replace('tvShareButton.setOnClickListener(v->showTvShareMenu());','tvShareButton.setOnClickListener(v->ProGate.require(this,T("casting")));')

main.write_text(s)
print("Prepared NenoTV Free v0.12.0: provider-order, on-demand, Media3-only")
