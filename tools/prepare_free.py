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
s=re.sub(r"versionCode\s+\d+","versionCode 57",s,1)
s=re.sub(r"versionName\s+'[^']+'","versionName '0.12.8'",s,1)
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

# Free full-library sync: fetch every provider category in provider order and store locally.
# No language detection, translation, regrouping or smart sorting is performed.
a=s.find('    void refreshSearchIndex(boolean force){')
if a>=0:
    b=s.find('\n    void waitWhilePaused()',a)
    if b>0:
        fullsync='''    void refreshSearchIndex(boolean force){
        if(provider==null||profile==null||indexRefreshRunning)return;
        final String key=profileKey();final android.content.SharedPreferences sp=SettingsStore.prefs(this);
        final String doneKey="free_full_sync_done_v121_"+key;
        if(!force&&sp.getBoolean(doneKey,false)){hideIndexBanner("");return;}
        indexRefreshRunning=true;
        indexFuture=indexExec.submit(()->{
            boolean complete=false;int liveCount=sp.getInt("free_sync_live_"+key,0),vodCount=sp.getInt("free_sync_vod_"+key,0),seriesCount=sp.getInt("free_sync_series_"+key,0);
            try{
                final String[] types={"live","vod","series"};
                LinkedHashMap<String,List<Category>> map=new LinkedHashMap<>();int total=0,done=0;
                for(String type:types){List<Category> cats=new ArrayList<>(provider.categories(type));map.put(type,cats);total+=cats.size();done+=Math.min(cats.size(),sp.getInt("free_sync_cursor_"+type+"_"+key,0));}
                final int grand=Math.max(1,total);final int firstDone=done,fl=liveCount,fv=vodCount,fs=seriesCount;
                runOnUiThread(()->showFreeSyncProgress(firstDone,grand,fl,fv,fs));
                for(String type:types){
                    List<Category> cats=map.get(type);int start=Math.min(cats.size(),sp.getInt("free_sync_cursor_"+type+"_"+key,0));
                    for(int i=start;i<cats.size();i++){
                        if(Thread.currentThread().isInterrupted())return;
                        Category cat=cats.get(i);List<MediaEntry> items=provider.items(type,cat.id);
                        for(MediaEntry e:items)if(e!=null&&(e.group==null||e.group.trim().isEmpty()))e.group=cat.name;
                        searchIndex.upsert(key,items);
                        if("live".equals(type))liveCount+=items.size();else if("vod".equals(type))vodCount+=items.size();else seriesCount+=items.size();
                        done++;
                        sp.edit().putInt("free_sync_cursor_"+type+"_"+key,i+1).putInt("free_sync_live_"+key,liveCount).putInt("free_sync_vod_"+key,vodCount).putInt("free_sync_series_"+key,seriesCount).apply();
                        final int pd=done,pl=liveCount,pv=vodCount,ps=seriesCount;
                        runOnUiThread(()->showFreeSyncProgress(pd,grand,pl,pv,ps));
                        try{Thread.sleep(35);}catch(InterruptedException ie){Thread.currentThread().interrupt();return;}
                    }
                    int count=searchIndex.countSection(key,type);if(count>0)searchIndex.markSection(key,type,count);
                }
                complete=true;
                sp.edit().putBoolean(doneKey,true).remove("free_sync_cursor_live_"+key).remove("free_sync_cursor_vod_"+key).remove("free_sync_cursor_series_"+key).apply();
            }catch(Throwable ignored){}finally{
                indexRefreshRunning=false;indexFuture=null;final boolean ok=complete;
                runOnUiThread(()->{if(ok)hideIndexBanner("");else restoreFirstSyncBanner();});
            }
        });
    }
''';
        s=s[:a]+fullsync+s[b:]

# Full sync must continue while the user navigates; foreground loads use another executor.
a=s.find('    void pauseBackgroundIndexForUi(){')
if a>=0:
    b=s.find('\n    void ',a+10)
    if b>0:s=s[:a]+'''    void pauseBackgroundIndexForUi(){ restoreFirstSyncBanner(); }
'''+s[b:]
a=s.find('    void scheduleBackgroundIndex(){')
if a>=0:
    b=s.find('\n    void ',a+10)
    if b>0:s=s[:a]+'''    void scheduleBackgroundIndex(){ if(provider!=null&&profile!=null&&!indexRefreshRunning)refreshSearchIndex(false); }
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

# Compact full-library progress directly below the logo/header.
a=s.find('    void showIndexBanner(String type,int done,int cats,int titles){')
if a>=0:
    b=s.find('\n    void restoreFirstSyncBanner()',a)
    if b>0:s=s[:a]+'''    void showIndexBanner(String type,int done,int cats,int titles){showFreeSyncProgress(done,cats,0,titles,0);}
    void showFreeSyncProgress(int done,int total,int live,int films,int series){
        if(indexBanner==null||indexBannerText==null)return;
        int pct=total<=0?0:Math.max(0,Math.min(100,(done*100)/Math.max(1,total)));
        String msg=T("library")+" · "+pct+"%  ·  "+T("live")+" "+live+"  ·  "+T("movies")+" "+films+"  ·  "+T("series")+" "+series;
        indexBannerText.setText(msg);if(indexBannerProgress!=null){indexBannerProgress.setIndeterminate(false);indexBannerProgress.setProgress(pct);}indexBanner.setVisibility(View.VISIBLE);
    }
'''+s[b:]
a=s.find('    void restoreFirstSyncBanner(){')
if a>=0:
    b=s.find('\n    void hideIndexBanner(',a)
    if b>0:s=s[:a]+'''    void restoreFirstSyncBanner(){
        if(indexBanner==null||profile==null)return;String key=profileKey();android.content.SharedPreferences sp=SettingsStore.prefs(this);
        if(sp.getBoolean("free_full_sync_done_v121_"+key,false)){indexBanner.setVisibility(View.GONE);return;}
        int total=0,done=0;try{for(String type:new String[]{"live","vod","series"}){List<Category> cats=provider==null?Collections.emptyList():provider.categories(type);total+=cats.size();done+=Math.min(cats.size(),sp.getInt("free_sync_cursor_"+type+"_"+key,0));}}catch(Exception ignored){}
        showFreeSyncProgress(done,Math.max(1,total),sp.getInt("free_sync_live_"+key,0),sp.getInt("free_sync_vod_"+key,0),sp.getInt("free_sync_series_"+key,0));
    }
'''+s[b:]

# Start the complete sync as soon as a normal foreground view is usable.
s=s.replace('warmCategories();','warmCategories();scheduleBackgroundIndex();')

# Free TV-share/cast control stays locked for Pro instead of launching Cast.
s=s.replace('tvShareButton.setOnClickListener(v->showTvShareMenu());','tvShareButton.setOnClickListener(v->ProGate.require(this,T("casting")));')

# Final safety pass: Free sync helpers must exist after all upstream source rewrites.
if 'void scheduleBackgroundIndex()' not in s:
    ins='''    void scheduleBackgroundIndex(){ if(provider!=null&&profile!=null&&!indexRefreshRunning)refreshSearchIndex(false); }
'''
    pos=s.find('    void openProfile(){')
    if pos<0: pos=s.find('    void loadHome(){')
    s=s[:pos]+ins+s[pos:]
if 'void pauseBackgroundIndexForUi()' not in s:
    ins='''    void pauseBackgroundIndexForUi(){ restoreFirstSyncBanner(); }
'''
    pos=s.find('    void scheduleBackgroundIndex(){')
    s=s[:pos]+ins+s[pos:]
s=s.replace('scheduleBackgroundIndex();scheduleBackgroundIndex();','scheduleBackgroundIndex();')
s=s.replace(';planBadge=findViewById(R.id.planBadge)','')

# Free UI: no plan badge and no scattered PRO controls. Keep one clear website/Pro route in the menu.
layout=app/"src/main/res/layout/activity_main.xml"
x=layout.read_text()
x=re.sub(r'<Button android:id="@\+id/planBadge"[^>]*/>','',x)
layout.write_text(x)

# Hide the advanced EPG grid control in Free; list guide remains available.
s=s.replace('epgModeBar.setVisibility(View.VISIBLE);','epgModeBar.setVisibility(View.VISIBLE);if(epgGridButton!=null)epgGridButton.setVisibility(View.GONE);')
s=s.replace('if(planBadge!=null)planBadge.setOnClickListener(v->startActivity(new Intent(this,AccountActivity.class)));','')
s=s.replace('if(planBadge!=null){EntitlementStore e=new EntitlementStore(this);String b=e.shortBadge();if(e.isTrial())b="TRIAL · "+e.trialDaysRemaining()+"d";planBadge.setText(b);boolean premium=e.isPro();planBadge.setTextColor(premium?0xFF0A0A0A:0xFFA7AFBC);planBadge.setBackgroundTintList(android.content.res.ColorStateList.valueOf(premium?0xFFFFD400:0xFF1B2028));}','')

# Replace bottom-sheet menu with a clean Free menu and a clear website entry.
ma=s.find('    void showNenoMenu(){')
mb=s.find('\n    void addNenoMenuItem(',ma)
if ma>=0 and mb>ma:
    menu='''    void showNenoMenu(){
        final Dialog d=new Dialog(this);LinearLayout box=new LinearLayout(this);box.setOrientation(LinearLayout.VERTICAL);box.setPadding(dp(18),dp(14),dp(18),dp(22));
        android.graphics.drawable.GradientDrawable bg=new android.graphics.drawable.GradientDrawable();bg.setColor(0xFF10141A);bg.setCornerRadii(new float[]{dp(22),dp(22),dp(22),dp(22),0,0,0,0});box.setBackground(bg);
        TextView head=new TextView(this);head.setText("NenoTV");head.setTextColor(0xFFFFD400);head.setTextSize(22);head.setTypeface(null,Typeface.BOLD);head.setPadding(0,0,0,dp(10));box.addView(head);
        addNenoMenuItem(d,box,T("search_everywhere"),()->toggleSearch());
        addNenoMenuItem(d,box,T("manage_source"),()->startActivityForResult(new Intent(this,ProfileActivity.class),10));
        addNenoMenuItem(d,box,T("settings"),()->startActivity(new Intent(this,SettingsActivity.class)));
        addNenoMenuItem(d,box,"🌐 NenoTV.com",()->openNenoWebsite("https://nenotv.com"));
        addNenoMenuItem(d,box,"NenoTV Pro",()->openNenoWebsite("https://nenotv.com"));
        addNenoMenuItem(d,box,T("close"),()->{});
        d.setContentView(box);Window w=d.getWindow();if(w!=null){w.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));w.setGravity(Gravity.BOTTOM);w.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);w.setDimAmount(0.45f);}d.show();if(w!=null)w.setLayout(ViewGroup.LayoutParams.MATCH_PARENT,ViewGroup.LayoutParams.WRAP_CONTENT);
    }
    void openNenoWebsite(String url){try{startActivity(new Intent(Intent.ACTION_VIEW,android.net.Uri.parse(url)));}catch(Exception ignored){}}
'''
    s=s[:ma]+menu+s[mb:]

# Settings: remove promotional PRO labels from the Free app. Keep only actual Free settings.
settingsFile=app/"src/main/java/com/robertalt/raiptv/SettingsActivity.java"
sx=settingsFile.read_text()
sx=sx.replace('sec("🔒 PRO · "+T("parental_controls"));','sec(T("parental"));')
sx=sx.replace('parental.setText("🔒 "+T("hide_adult"));','parental.setText(T("hide_adult"));')
sx=re.sub(r'if\(on&&!ProGate\.require\(this,T\("parental_controls"\)\)\)\{v\.setChecked\(false\);return;\}','',sx)
sx=re.sub(r'TextView proHint=t\("🔒 PRO · "\+T\("picture_in_picture"\)\+" · "\+T\("advanced_subtitles"\),12\);proHint\.setTextColor\(0xFFFFD400\);box\.addView\(proHint\);','',sx)
settingsFile.write_text(sx)


# v0.12.2 final pass: resilient full sync, clean localized Free labels, subtle Pro discovery.
def replace_java_method(src, start_sig, next_sig, body):
    a=src.find(start_sig)
    if a<0: return src
    b=src.find(next_sig,a)
    if b<0: return src
    return src[:a]+body+src[b:]

# Local labels used by the Free shell. Never expose internal translation keys.
free_ui = r'''    String freeUi(String key){
        String l=SettingsStore.language(this);if(l==null)l="en";l=l.toLowerCase(Locale.ROOT);
        java.util.HashMap<String,String> m=new java.util.HashMap<>();
        if("nl".equals(l)){m.put("library","Bibliotheek");m.put("search","Zoeken");m.put("source","TV-bron beheren");m.put("settings","Instellingen");m.put("close","Sluiten");}
        else if("de".equals(l)){m.put("library","Bibliothek");m.put("search","Suchen");m.put("source","TV-Quelle verwalten");m.put("settings","Einstellungen");m.put("close","Schließen");}
        else if("fr".equals(l)){m.put("library","Bibliothèque");m.put("search","Rechercher");m.put("source","Gérer la source TV");m.put("settings","Paramètres");m.put("close","Fermer");}
        else if("es".equals(l)){m.put("library","Biblioteca");m.put("search","Buscar");m.put("source","Gestionar fuente de TV");m.put("settings","Ajustes");m.put("close","Cerrar");}
        else if("it".equals(l)){m.put("library","Libreria");m.put("search","Cerca");m.put("source","Gestisci sorgente TV");m.put("settings","Impostazioni");m.put("close","Chiudi");}
        else if("pt".equals(l)){m.put("library","Biblioteca");m.put("search","Pesquisar");m.put("source","Gerir fonte de TV");m.put("settings","Definições");m.put("close","Fechar");}
        else if("tr".equals(l)){m.put("library","Kütüphane");m.put("search","Ara");m.put("source","TV kaynağını yönet");m.put("settings","Ayarlar");m.put("close","Kapat");}
        else if("pl".equals(l)){m.put("library","Biblioteka");m.put("search","Szukaj");m.put("source","Zarządzaj źródłem TV");m.put("settings","Ustawienia");m.put("close","Zamknij");}
        else if("ar".equals(l)){m.put("library","المكتبة");m.put("search","بحث");m.put("source","إدارة مصدر التلفاز");m.put("settings","الإعدادات");m.put("close","إغلاق");}
        else {m.put("library","Library");m.put("search","Search");m.put("source","Manage TV source");m.put("settings","Settings");m.put("close","Close");}
        String v=m.get(key);return v==null?key:v;
    }

'''
if 'String freeUi(String key)' not in s:
    pos=s.find('    void openProfile(){')
    if pos<0: pos=s.find('    void loadHome(){')
    if pos>=0:s=s[:pos]+free_ui+s[pos:]

# Full sync: each provider category is independent. One bad category can never stop the rest.
sync_method = r'''    void refreshSearchIndex(boolean force){
        if(provider==null||profile==null||indexRefreshRunning)return;
        final String key=profileKey();final android.content.SharedPreferences sp=SettingsStore.prefs(this);
        final String completeKey="free_full_sync_done_v122_"+key;
        if(!force&&sp.getBoolean(completeKey,false)){hideIndexBanner("");return;}
        indexRefreshRunning=true;
        indexFuture=indexExec.submit(()->{
            boolean allOk=false;
            try{
                final String[] types={"live","vod","series"};
                LinkedHashMap<String,List<Category>> catMap=new LinkedHashMap<>();
                int total=0;
                for(String type:types){List<Category> cats=new ArrayList<>(provider.categories(type));catMap.put(type,cats);total+=cats.size();}
                final int grand=Math.max(1,total);
                int liveCount=sp.getInt("free_sync_live_"+key,0),filmCount=sp.getInt("free_sync_vod_"+key,0),seriesCount=sp.getInt("free_sync_series_"+key,0);
                int done=0;
                for(String type:types){
                    java.util.Set<String> saved=sp.getStringSet("free_sync_donecats_"+type+"_"+key,java.util.Collections.emptySet());
                    java.util.HashSet<String> completed=new java.util.HashSet<>(saved);
                    for(Category c:catMap.get(type))if(completed.contains(safe(c.id)))done++;
                }
                final int startDone=done,sl=liveCount,sf=filmCount,ss=seriesCount;
                runOnUiThread(()->showFreeSyncProgress(startDone,grand,sl,sf,ss));
                for(String type:types){
                    java.util.Set<String> saved=sp.getStringSet("free_sync_donecats_"+type+"_"+key,java.util.Collections.emptySet());
                    java.util.HashSet<String> completed=new java.util.HashSet<>(saved);
                    for(Category cat:catMap.get(type)){
                        if(Thread.currentThread().isInterrupted())return;
                        String cid=safe(cat.id);if(completed.contains(cid))continue;
                        List<MediaEntry> items=null;Throwable last=null;
                        for(int attempt=0;attempt<2&&items==null;attempt++){
                            try{items=provider.items(type,cat.id);}catch(Throwable ex){last=ex;try{Thread.sleep(250L*(attempt+1));}catch(InterruptedException ie){Thread.currentThread().interrupt();return;}}
                        }
                        if(items==null)continue;
                        try{
                            for(MediaEntry e:items)if(e!=null&&(e.group==null||e.group.trim().isEmpty()))e.group=cat.name;
                            searchIndex.upsert(key,items);
                            completed.add(cid);
                            sp.edit().putStringSet("free_sync_donecats_"+type+"_"+key,new java.util.HashSet<>(completed)).apply();
                            if("live".equals(type))liveCount+=items.size();else if("vod".equals(type))filmCount+=items.size();else seriesCount+=items.size();
                            sp.edit().putInt("free_sync_live_"+key,liveCount).putInt("free_sync_vod_"+key,filmCount).putInt("free_sync_series_"+key,seriesCount).apply();
                            done++;final int pd=done,pl=liveCount,pf=filmCount,ps=seriesCount;
                            runOnUiThread(()->showFreeSyncProgress(pd,grand,pl,pf,ps));
                        }catch(Throwable ignored){}
                        try{Thread.sleep(35);}catch(InterruptedException ie){Thread.currentThread().interrupt();return;}
                    }
                    int sectionCount=searchIndex.countSection(key,type);if(sectionCount>0)searchIndex.markSection(key,type,sectionCount);
                }
                int finalDone=0;
                for(String type:types){
                    java.util.Set<String> saved=sp.getStringSet("free_sync_donecats_"+type+"_"+key,java.util.Collections.emptySet());
                    for(Category c:catMap.get(type))if(saved.contains(safe(c.id)))finalDone++;
                }
                allOk=finalDone>=grand;
                if(allOk)sp.edit().putBoolean(completeKey,true).apply();
            }catch(Throwable ignored){}finally{
                indexRefreshRunning=false;indexFuture=null;final boolean ok=allOk;
                runOnUiThread(()->{
                    if(ok)hideIndexBanner("");
                    else{restoreFirstSyncBanner();ui.postDelayed(()->scheduleBackgroundIndex(),4000);}
                });
            }
        });
    }
'''
s=replace_java_method(s,'    void refreshSearchIndex(boolean force){','\n    void waitWhilePaused()',sync_method)

# Navigation never cancels the full Free sync.
pause_body=r'''    void pauseBackgroundIndexForUi(){ restoreFirstSyncBanner(); }
'''
a=s.find('    void pauseBackgroundIndexForUi(){')
if a>=0:
    b=s.find('\n    void ',a+10)
    if b>0:s=s[:a]+pause_body+s[b:]
if 'void scheduleBackgroundIndex()' not in s:
    pos=s.find('    void openProfile(){')
    if pos<0:pos=s.find('    void loadHome(){')
    s=s[:pos]+r'''    void scheduleBackgroundIndex(){if(provider!=null&&profile!=null&&!indexRefreshRunning)refreshSearchIndex(false);}
'''+s[pos:]
s=s.replace('scheduleBackgroundIndex();scheduleBackgroundIndex();','scheduleBackgroundIndex();')

# Progress text in selected app language.
s=s.replace('String msg=T("library")+" · "+pct+"%  ·  "+T("live")+" "+live+"  ·  "+T("movies")+" "+films+"  ·  "+T("series")+" "+series;',
            'String msg=freeUi("library")+" · "+pct+"%  ·  "+T("live")+" "+live+"  ·  "+T("movies")+" "+films+"  ·  "+T("series")+" "+series;')
s=s.replace('"free_full_sync_done_v121_"+key','"free_full_sync_done_v122_"+key')

# Header: keep a subtle yellow PRO discovery button next to language.
layout=app/"src/main/res/layout/activity_main.xml"
lx=layout.read_text()
if 'android:id="@+id/proHintButton"' not in lx:
    needle='<Button android:id="@+id/languageBadge"'
    p=lx.find(needle)
    if p>=0:
        e=lx.find('/>',p)
        if e>=0:
            e+=2
            pro='''\n            <Button android:id="@+id/proHintButton" android:layout_width="48dp" android:layout_height="36dp" android:layout_marginLeft="5dp" android:text="PRO" android:textAllCaps="false" android:textStyle="bold" android:textColor="#FF0A0A0A" android:textSize="10sp" android:backgroundTint="#FFFFD400" android:contentDescription="NenoTV Pro"/>'''
            lx=lx[:e]+pro+lx[e:]
layout.write_text(lx)

# MainActivity field + binding for PRO hint.
s=s.replace('languageBadge,planBadge; ProgressBar','languageBadge,planBadge,proHintButton; ProgressBar')
s=s.replace('languageBadge=findViewById(R.id.languageBadge);','languageBadge=findViewById(R.id.languageBadge);proHintButton=findViewById(R.id.proHintButton);')
wire='    void wire(){\n'
if 'proHintButton.setOnClickListener' not in s:
    s=s.replace(wire,wire+'        if(proHintButton!=null)proHintButton.setOnClickListener(v->openNenoWebsite("https://nenotv.com"));\n',1)

# Free menu: clean localized labels, website and Pro; no raw keys/account/casting rows.
ma=s.find('    void showNenoMenu(){')
me=s.find('    void showTvShareMenu(){',ma)
if ma>=0 and me>ma:
    menu=r'''    void showNenoMenu(){
        final Dialog d=new Dialog(this);LinearLayout box=new LinearLayout(this);box.setOrientation(LinearLayout.VERTICAL);box.setPadding(dp(18),dp(14),dp(18),dp(22));
        android.graphics.drawable.GradientDrawable bg=new android.graphics.drawable.GradientDrawable();bg.setColor(0xFF10141A);bg.setCornerRadii(new float[]{dp(22),dp(22),dp(22),dp(22),0,0,0,0});box.setBackground(bg);
        TextView head=new TextView(this);head.setText("NenoTV");head.setTextColor(0xFFFFD400);head.setTextSize(22);head.setTypeface(null,Typeface.BOLD);head.setPadding(0,0,0,dp(10));box.addView(head);
        addNenoMenuItem(d,box,freeUi("search"),()->toggleSearch());
        addNenoMenuItem(d,box,freeUi("source"),()->startActivityForResult(new Intent(this,ProfileActivity.class),10));
        addNenoMenuItem(d,box,freeUi("settings"),()->startActivity(new Intent(this,SettingsActivity.class)));
        addNenoMenuItem(d,box,"🌐 NenoTV.com",()->openNenoWebsite("https://nenotv.com"));
        addNenoMenuItem(d,box,"★ NenoTV Pro",()->openNenoWebsite("https://nenotv.com"));
        addNenoMenuItem(d,box,freeUi("close"),()->{});
        d.setContentView(box);Window w=d.getWindow();if(w!=null){w.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));w.setGravity(Gravity.BOTTOM);w.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);w.setDimAmount(0.45f);}d.show();if(w!=null)w.setLayout(ViewGroup.LayoutParams.MATCH_PARENT,ViewGroup.LayoutParams.WRAP_CONTENT);
    }
    void addNenoMenuItem(Dialog d,LinearLayout box,String label,Runnable action){
        Button b=new Button(this);b.setText(label);b.setAllCaps(false);b.setTextColor(0xFFF7F8FA);b.setTextSize(15);b.setGravity(Gravity.LEFT|Gravity.CENTER_VERTICAL);b.setPadding(dp(14),0,dp(14),0);b.setBackgroundTintList(android.content.res.ColorStateList.valueOf(0xFF1B2028));
        LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,dp(50));lp.bottomMargin=dp(7);box.addView(b,lp);b.setOnClickListener(v->{d.dismiss();action.run();});
    }
    void openNenoWebsite(String url){try{startActivity(new Intent(Intent.ACTION_VIEW,android.net.Uri.parse(url)));}catch(Exception ignored){}}

'''
    s=s[:ma]+menu+s[me:]

# TV guide Free: keep only the normal list view. PRO remains discoverable in header/menu.
s=s.replace('epgModeBar.setVisibility(View.VISIBLE);if(epgGridButton!=null)epgGridButton.setVisibility(View.GONE);',
            'epgModeBar.setVisibility(View.VISIBLE);if(epgGridButton!=null)epgGridButton.setVisibility(View.GONE);')

main.write_text(s)
print("Prepared NenoTV Free v0.12.8: resilient full sync + clean language")

# v0.12.4: language switching must not recreate the Activity while the library sync is active.
old='new AlertDialog.Builder(this).setTitle(T("language")).setItems(labels,(d,w)->{SettingsStore.setPrimaryLanguage(this,codes[w]);recreate();}).show();'
new='new AlertDialog.Builder(this).setTitle(T("language")).setItems(labels,(d,w)->{SettingsStore.setPrimaryLanguage(this,codes[w]);appliedLanguage=SettingsStore.language(this);appliedContentLanguage=SettingsStore.contentLanguage(this);applyStaticLanguage();updateHeaderBadges();restoreFirstSyncBanner();String cur=section;if("home".equals(cur))loadHome();else if("epg".equals(cur))loadEpg();else loadSection(cur);}).show();'
s=s.replace(old,new)

# Never show internal crash-class names to users.
s=s.replace('String lastCrash=CrashGuard.consumeLastType(this);busy(false,!lastCrash.isEmpty()?T("stability")+" · "+T("previous_error")+": "+lastCrash:(local.isEmpty()?T("watch_without_search"):""));',
            'CrashGuard.consumeLastType(this);busy(false,local.isEmpty()?T("watch_without_search"):"");')

# Ensure the EPG PRO button remains hidden in Free, header PRO remains.
s=s.replace('epgModeBar.setVisibility(View.VISIBLE);if(epgGridButton!=null){epgGridButton.setVisibility(View.VISIBLE);epgGridButton.setText("PRO");epgGridButton.setTextColor(0xFF0A0A0A);epgGridButton.setBackgroundTintList(android.content.res.ColorStateList.valueOf(0xFFFFD400));epgGridButton.setOnClickListener(v->openNenoWebsite("https://nenotv.com"));}',
            'epgModeBar.setVisibility(View.VISIBLE);if(epgGridButton!=null)epgGridButton.setVisibility(View.GONE);')


# v0.12.5: final Free cleanup + compact card/tile settings dashboard.

# Progress: title and counters on separate lines so Series never wraps awkwardly.
s=s.replace('String msg=freeUi("library")+" · "+pct+"%  ·  "+T("live")+" "+live+"  ·  "+T("movies")+" "+films+"  ·  "+T("series")+" "+series;',
            'String msg=freeUi("library")+" · "+pct+"%\\n"+T("live")+" "+live+"  ·  "+T("movies")+" "+films+"  ·  "+T("series")+" "+series;')

# If language was changed from Settings, refresh MainActivity in place; never recreate it.
old='String nowLang=SettingsStore.language(this);if(appliedLanguage!=null&&!appliedLanguage.isEmpty()&&!appliedLanguage.equals(nowLang)){recreate();return;}'
new='String nowLang=SettingsStore.language(this);if(appliedLanguage!=null&&!appliedLanguage.isEmpty()&&!appliedLanguage.equals(nowLang)){appliedLanguage=nowLang;appliedContentLanguage=SettingsStore.contentLanguage(this);applyStaticLanguage();updateHeaderBadges();restoreFirstSyncBanner();if(provider!=null){if("home".equals(section)||"local".equals(section))loadHome();else if("epg".equals(section))loadEpg();else loadSection(section);}return;}'
s=s.replace(old,new)

# A forced Free refresh must really restart all category completion state.
needle='final String completeKey="free_full_sync_done_v122_"+key;'
repl='''final String completeKey="free_full_sync_done_v122_"+key;
        if(force){sp.edit().remove(completeKey).remove("free_sync_donecats_live_"+key).remove("free_sync_donecats_vod_"+key).remove("free_sync_donecats_series_"+key).putInt("free_sync_live_"+key,0).putInt("free_sync_vod_"+key,0).putInt("free_sync_series_"+key,0).apply();}'''
s=s.replace(needle,repl)

# Free settings: 2-column cards instead of one long settings list.
settings=app/"src/main/java/com/robertalt/raiptv/SettingsActivity.java"
settings.write_text(r'''package com.robertalt.raiptv;

import android.app.*;
import android.os.*;
import android.content.*;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.view.*;
import android.widget.*;
import com.robertalt.raiptv.storage.*;

public class SettingsActivity extends Activity{
    LinearLayout box; android.content.SharedPreferences p;
    int dp(int v){return Math.round(v*getResources().getDisplayMetrics().density);}
    String T(String k){return UiText.t(this,k);}
    String lang(){String l=SettingsStore.language(this);return l==null?"en":l.toLowerCase(java.util.Locale.ROOT);}
    TextView tv(String x,int sp,int color){TextView v=new TextView(this);v.setText(x);v.setTextSize(sp);v.setTextColor(color);return v;}
    GradientDrawable bg(int color,int radius){GradientDrawable g=new GradientDrawable();g.setColor(color);g.setCornerRadius(dp(radius));g.setStroke(dp(1),0xFF282E38);return g;}
    Button btn(String x){Button b=new Button(this);b.setText(x);b.setAllCaps(false);b.setTextColor(0xFFF7F8FA);b.setBackgroundTintList(android.content.res.ColorStateList.valueOf(0xFF1B2028));return b;}

    @Override public void onCreate(Bundle b){super.onCreate(b);SettingsStore.migrateLanguagePreferences(this);p=SettingsStore.prefs(this);build();UiText.applyDirection(this);}

    void build(){
        ScrollView sv=new ScrollView(this);sv.setBackgroundColor(0xFF07090D);
        sv.setOnApplyWindowInsetsListener((v,insets)->{v.setPadding(0,insets.getSystemWindowInsetTop(),0,insets.getSystemWindowInsetBottom());return insets;});sv.requestApplyInsets();
        box=new LinearLayout(this);box.setOrientation(LinearLayout.VERTICAL);box.setPadding(dp(18),dp(14),dp(18),dp(30));sv.addView(box);
        LinearLayout head=new LinearLayout(this);head.setGravity(Gravity.CENTER_VERTICAL);
        TextView title=tv(T("settings"),28,0xFFF7F8FA);title.setTypeface(null,Typeface.BOLD);head.addView(title,new LinearLayout.LayoutParams(0,-2,1));
        Button close=btn(T("close"));close.setOnClickListener(v->finish());head.addView(close,new LinearLayout.LayoutParams(dp(96),dp(48)));box.addView(head);
        TextView intro=tv(local("Kies een blok om de Free-instellingen aan te passen.","Choose a block to change Free settings."),13,0xFF8D96A4);intro.setPadding(0,dp(10),0,dp(14));box.addView(intro);

        LinearLayout r1=row();
        r1.addView(tile(local("Taal","Language"),SettingsStore.displayLanguage(this,SettingsStore.language(this)),()->languageDialog()),tileLp(true));
        r1.addView(tile(local("Weergave","Display"),displaySummary(),()->displayDialog()),tileLp(false));
        box.addView(r1);

        LinearLayout r2=row();
        r2.addView(tile(local("Afspelen","Playback"),playbackSummary(),()->playbackDialog()),tileLp(true));
        r2.addView(tile(local("Onderhoud","Maintenance"),local("Bibliotheek en cache","Library and cache"),()->maintenanceDialog()),tileLp(false));
        box.addView(r2);

        LinearLayout about=tile(local("Over NenoTV","About NenoTV"),"NenoTV Free · 0.12.5",()->aboutDialog());
        LinearLayout.LayoutParams ap=new LinearLayout.LayoutParams(-1,dp(112));ap.topMargin=dp(10);box.addView(about,ap);
        setContentView(sv);
    }

    LinearLayout row(){LinearLayout r=new LinearLayout(this);r.setOrientation(LinearLayout.HORIZONTAL);r.setWeightSum(2f);return r;}
    LinearLayout.LayoutParams tileLp(boolean left){LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(0,dp(132),1f);lp.topMargin=dp(10);if(left)lp.rightMargin=dp(5);else lp.leftMargin=dp(5);return lp;}
    LinearLayout tile(String title,String sub,Runnable action){
        LinearLayout c=new LinearLayout(this);c.setOrientation(LinearLayout.VERTICAL);c.setGravity(Gravity.CENTER_VERTICAL);c.setPadding(dp(16),dp(14),dp(16),dp(14));c.setBackground(bg(0xFF12161D,18));c.setClickable(true);c.setFocusable(true);
        TextView t=tv(title,17,0xFFFFD400);t.setTypeface(null,Typeface.BOLD);c.addView(t);
        TextView s=tv(sub,12,0xFFA7AFBC);s.setPadding(0,dp(7),0,0);s.setMaxLines(3);c.addView(s);
        c.setOnClickListener(v->action.run());return c;
    }

    LinearLayout panel(){LinearLayout p=new LinearLayout(this);p.setOrientation(LinearLayout.VERTICAL);p.setPadding(dp(18),dp(6),dp(18),dp(4));return p;}
    String local(String nl,String en){
        String l=lang();
        if("nl".equals(l))return nl;if("de".equals(l))return en.equals("Language")?"Sprache":en.equals("Display")?"Anzeige":en.equals("Playback")?"Wiedergabe":en.equals("Maintenance")?"Wartung":en.equals("About NenoTV")?"Über NenoTV":en;
        if("fr".equals(l))return en.equals("Language")?"Langue":en.equals("Display")?"Affichage":en.equals("Playback")?"Lecture":en.equals("Maintenance")?"Entretien":en.equals("About NenoTV")?"À propos de NenoTV":en;
        if("es".equals(l))return en.equals("Language")?"Idioma":en.equals("Display")?"Pantalla":en.equals("Playback")?"Reproducción":en.equals("Maintenance")?"Mantenimiento":en.equals("About NenoTV")?"Acerca de NenoTV":en;
        if("it".equals(l))return en.equals("Language")?"Lingua":en.equals("Display")?"Visualizzazione":en.equals("Playback")?"Riproduzione":en.equals("Maintenance")?"Manutenzione":en.equals("About NenoTV")?"Informazioni su NenoTV":en;
        if("pt".equals(l))return en.equals("Language")?"Idioma":en.equals("Display")?"Visualização":en.equals("Playback")?"Reprodução":en.equals("Maintenance")?"Manutenção":en.equals("About NenoTV")?"Sobre o NenoTV":en;
        if("tr".equals(l))return en.equals("Language")?"Dil":en.equals("Display")?"Görünüm":en.equals("Playback")?"Oynatma":en.equals("Maintenance")?"Bakım":en.equals("About NenoTV")?"NenoTV hakkında":en;
        if("pl".equals(l))return en.equals("Language")?"Język":en.equals("Display")?"Wygląd":en.equals("Playback")?"Odtwarzanie":en.equals("Maintenance")?"Konserwacja":en.equals("About NenoTV")?"O NenoTV":en;
        if("ar".equals(l))return en.equals("Language")?"اللغة":en.equals("Display")?"العرض":en.equals("Playback")?"التشغيل":en.equals("Maintenance")?"الصيانة":en.equals("About NenoTV")?"حول NenoTV":en;
        return en;
    }
    String freeText(String key){
        String l=lang();
        if("nl".equals(l)){
            if("display".equals(key))return "Startscherm en formaat";
            if("playback".equals(key))return "Buffer, audio en ondertitels";
            if("language_help".equals(key))return "De taal verandert de interface. Free houdt zenders, films en series in de volgorde van de provider.";
            if("track_help".equals(key))return "Audio en ondertitels gebruiken alleen tracks die door de provider of stream zijn meegeleverd.";
            if("resync".equals(key))return "Bibliotheek opnieuw ophalen";
            if("resync_msg".equals(key))return "De volledige providerbibliotheek wordt opnieuw opgehaald zodra je teruggaat naar NenoTV.";
            if("about".equals(key))return "NenoTV Free toont de IPTV-bibliotheek in provider-volgorde. Audio en ondertitels gebruiken alleen wat de provider of stream meelevert. Extra mogelijkheden vind je in NenoTV Pro.";
        }else if("de".equals(l)){
            if("language_help".equals(key))return "Die Sprache ändert die Oberfläche. Free behält Sender, Filme und Serien in der Reihenfolge des Anbieters.";
            if("track_help".equals(key))return "Audio und Untertitel verwenden nur Spuren, die der Anbieter oder Stream mitliefert.";
            if("resync".equals(key))return "Bibliothek neu laden";
            if("resync_msg".equals(key))return "Die vollständige Anbieterbibliothek wird nach der Rückkehr zu NenoTV neu geladen.";
            if("about".equals(key))return "NenoTV Free zeigt die IPTV-Bibliothek in Anbieterreihenfolge. Audio und Untertitel stammen nur vom Anbieter oder Stream.";
        }else if("fr".equals(l)){
            if("language_help".equals(key))return "La langue modifie l’interface. Free conserve l’ordre des chaînes, films et séries fourni par le fournisseur.";
            if("track_help".equals(key))return "L’audio et les sous-titres utilisent uniquement les pistes fournies par le fournisseur ou le flux.";
            if("resync".equals(key))return "Recharger la bibliothèque";
            if("resync_msg".equals(key))return "La bibliothèque complète du fournisseur sera rechargée au retour dans NenoTV.";
            if("about".equals(key))return "NenoTV Free affiche la bibliothèque IPTV dans l’ordre du fournisseur et utilise uniquement les pistes audio et sous-titres fournies.";
        }else if("es".equals(l)){
            if("language_help".equals(key))return "El idioma cambia la interfaz. Free mantiene canales, películas y series en el orden del proveedor.";
            if("track_help".equals(key))return "El audio y los subtítulos usan únicamente las pistas incluidas por el proveedor o el stream.";
            if("resync".equals(key))return "Volver a cargar la biblioteca";
            if("resync_msg".equals(key))return "La biblioteca completa del proveedor se volverá a cargar al regresar a NenoTV.";
            if("about".equals(key))return "NenoTV Free muestra la biblioteca IPTV en el orden del proveedor y solo usa audio y subtítulos incluidos.";
        }else if("it".equals(l)){
            if("language_help".equals(key))return "La lingua cambia l’interfaccia. Free mantiene canali, film e serie nell’ordine del provider.";
            if("track_help".equals(key))return "Audio e sottotitoli usano solo le tracce fornite dal provider o dallo stream.";
            if("resync".equals(key))return "Ricarica libreria";
            if("resync_msg".equals(key))return "L’intera libreria del provider verrà ricaricata tornando a NenoTV.";
            if("about".equals(key))return "NenoTV Free mostra la libreria IPTV nell’ordine del provider e usa solo audio e sottotitoli forniti.";
        }else if("pt".equals(l)){
            if("language_help".equals(key))return "O idioma altera a interface. Free mantém canais, filmes e séries na ordem do fornecedor.";
            if("track_help".equals(key))return "Áudio e legendas usam apenas faixas fornecidas pelo fornecedor ou stream.";
            if("resync".equals(key))return "Recarregar biblioteca";
            if("resync_msg".equals(key))return "A biblioteca completa do fornecedor será recarregada ao voltar ao NenoTV.";
            if("about".equals(key))return "NenoTV Free mostra a biblioteca IPTV na ordem do fornecedor e usa apenas áudio e legendas fornecidos.";
        }else if("tr".equals(l)){
            if("language_help".equals(key))return "Dil arayüzü değiştirir. Free kanal, film ve dizileri sağlayıcının sırasıyla tutar.";
            if("track_help".equals(key))return "Ses ve altyazılar yalnızca sağlayıcı veya yayın tarafından sunulan parçaları kullanır.";
            if("resync".equals(key))return "Kütüphaneyi yeniden yükle";
            if("resync_msg".equals(key))return "NenoTV’ye döndüğünüzde tüm sağlayıcı kütüphanesi yeniden yüklenir.";
            if("about".equals(key))return "NenoTV Free IPTV kütüphanesini sağlayıcı sırasıyla gösterir ve yalnızca sağlanan ses ve altyazıları kullanır.";
        }else if("pl".equals(l)){
            if("language_help".equals(key))return "Język zmienia interfejs. Free zachowuje kanały, filmy i seriale w kolejności dostawcy.";
            if("track_help".equals(key))return "Dźwięk i napisy korzystają wyłącznie ze ścieżek dostarczonych przez dostawcę lub strumień.";
            if("resync".equals(key))return "Pobierz bibliotekę ponownie";
            if("resync_msg".equals(key))return "Pełna biblioteka dostawcy zostanie pobrana ponownie po powrocie do NenoTV.";
            if("about".equals(key))return "NenoTV Free pokazuje bibliotekę IPTV w kolejności dostawcy i używa tylko dostarczonego dźwięku i napisów.";
        }else if("ar".equals(l)){
            if("language_help".equals(key))return "تغيّر اللغة واجهة التطبيق. يحافظ الإصدار المجاني على ترتيب القنوات والأفلام والمسلسلات كما يقدمه المزود.";
            if("track_help".equals(key))return "يستخدم الصوت والترجمة فقط المسارات التي يوفرها المزود أو البث.";
            if("resync".equals(key))return "إعادة تحميل المكتبة";
            if("resync_msg".equals(key))return "ستتم إعادة تحميل مكتبة المزود كاملة عند العودة إلى NenoTV.";
            if("about".equals(key))return "يعرض NenoTV Free مكتبة IPTV بترتيب المزود ويستخدم فقط الصوت والترجمة المتاحة من المصدر.";
        }
        if("display".equals(key))return "Start screen and layout";
        if("playback".equals(key))return "Buffer, audio and subtitles";
        if("language_help".equals(key))return "Language changes the interface. Free keeps channels, movies and series in provider order.";
        if("track_help".equals(key))return "Audio and subtitles use only tracks supplied by the provider or stream.";
        if("resync".equals(key))return "Reload library";
        if("resync_msg".equals(key))return "The complete provider library will be reloaded when you return to NenoTV.";
        if("about".equals(key))return "NenoTV Free shows the IPTV library in provider order and uses only audio and subtitle tracks supplied by the provider or stream.";
        return key;
    }
    String displaySummary(){return freeText("display");}
    String playbackSummary(){return freeText("playback");}

    void languageDialog(){
        final String[] codes={"nl","en","de","fr","es","it","pt","tr","pl","ar"};
        String[] labels=new String[codes.length];int checked=0;String cur=SettingsStore.language(this);
        for(int i=0;i<codes.length;i++){labels[i]=SettingsStore.displayLanguage(this,codes[i]);if(codes[i].equals(cur))checked=i;}
        new AlertDialog.Builder(this).setTitle(local("Taal","Language")).setSingleChoiceItems(labels,checked,(d,w)->{SettingsStore.setPrimaryLanguage(this,codes[w]);d.dismiss();build();UiText.applyDirection(this);})
          .setMessage(freeText("language_help")).setNegativeButton(T("close"),null).show();
    }

    void displayDialog(){
        LinearLayout v=panel();
        addToggle(v,T("compact"),"compact",true);
        addSpinner(v,T("hero_size"),"hero_size",new String[]{T("small"),T("normal"),T("large")},new String[]{"small","normal","large"});
        addSpinner(v,T("start_screen"),"start_screen",new String[]{T("home"),T("last_tab"),T("live_tv"),T("epg"),T("movies"),T("series")},new String[]{"home","last","live","epg","vod","series"});
        new AlertDialog.Builder(this).setTitle(local("Weergave","Display")).setView(v).setPositiveButton(T("close"),null).show();
    }

    void playbackDialog(){
        LinearLayout v=panel();
        TextView info=tv(freeText("track_help"),12,0xFF8D96A4);info.setPadding(0,0,0,dp(8));v.addView(info);
        addSpinner(v,T("buffer"),"buffer",new String[]{T("fast"),T("stable"),T("maximum")},new String[]{"normal","stable","max"});
        addSpinner(v,T("audio_pref"),"audio",audioLanguageLabels(),new String[]{"auto","original","nl","en","de","fr","es","it","pt","tr","pl","ar"});
        addSpinner(v,T("subtitle_pref"),"subtitles",subtitleLanguageLabels(),new String[]{"auto","off","nl","en","de","fr","es","it","pt","tr","pl","ar"});
        addToggle(v,T("autoplay"),"autoplay_next",true);
        new AlertDialog.Builder(this).setTitle(local("Afspelen","Playback")).setView(v).setPositiveButton(T("close"),null).show();
    }

    void maintenanceDialog(){
        LinearLayout v=panel();
        Button reload=btn(freeText("resync"));reload.setOnClickListener(x->{p.edit().putBoolean("force_reindex",true).apply();Toast.makeText(this,freeText("resync_msg"),Toast.LENGTH_LONG).show();});
        v.addView(reload,new LinearLayout.LayoutParams(-1,dp(52)));
        Button cache=btn(T("clear_cache"));LinearLayout.LayoutParams cp=new LinearLayout.LayoutParams(-1,dp(52));cp.topMargin=dp(8);v.addView(cache,cp);
        cache.setOnClickListener(x->{MediaRowAdapter.clearArtworkCache();MainActivity.clearHeroCache();Toast.makeText(this,T("cache_cleared"),Toast.LENGTH_SHORT).show();});
        new AlertDialog.Builder(this).setTitle(local("Onderhoud","Maintenance")).setView(v).setPositiveButton(T("close"),null).show();
    }

    void aboutDialog(){
        TextView v=tv("NenoTV Free 0.12.5\\n\\n"+freeText("about")+"\\n\\nnenotv.com",14,0xFFF7F8FA);v.setPadding(dp(20),dp(10),dp(20),dp(10));
        new AlertDialog.Builder(this).setTitle(local("Over NenoTV","About NenoTV")).setView(v).setPositiveButton(T("close"),null).show();
    }

    void addToggle(LinearLayout parent,String label,String key,boolean def){
        Switch s=new Switch(this);s.setText(label);s.setTextColor(0xFFF7F8FA);s.setChecked(p.getBoolean(key,def));s.setOnCheckedChangeListener((v,on)->p.edit().putBoolean(key,on).apply());parent.addView(s,new LinearLayout.LayoutParams(-1,dp(54)));
    }
    void addSpinner(LinearLayout parent,String label,String key,String[] labels,String[] vals){
        TextView l=tv(label,12,0xFFA7AFBC);l.setPadding(0,dp(8),0,0);parent.addView(l);
        Spinner sp=new Spinner(this);ArrayAdapter<String> ad=new ArrayAdapter<String>(this,android.R.layout.simple_spinner_item,labels){
            @Override public View getDropDownView(int p,View v,ViewGroup g){TextView x=(TextView)super.getDropDownView(p,v,g);x.setTextColor(0xFFF7F8FA);x.setBackgroundColor(0xFF181C22);x.setPadding(dp(16),dp(14),dp(16),dp(14));return x;}};
        ad.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);sp.setAdapter(ad);String cur=p.getString(key,vals[0]);int n=0;for(int i=0;i<vals.length;i++)if(vals[i].equals(cur))n=i;sp.setSelection(n,false);
        final boolean[] ready={false};sp.post(()->ready[0]=true);sp.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener(){public void onNothingSelected(AdapterView<?>x){}public void onItemSelected(AdapterView<?>x,View v,int q,long id){if(!ready[0])return;p.edit().putString(key,vals[q]).apply();}});
        parent.addView(sp,new LinearLayout.LayoutParams(-1,dp(48)));
    }
    String[] audioLanguageLabels(){return new String[]{T("follow_app_language"),T("original"),SettingsStore.displayLanguage(this,"nl"),SettingsStore.displayLanguage(this,"en"),SettingsStore.displayLanguage(this,"de"),SettingsStore.displayLanguage(this,"fr"),SettingsStore.displayLanguage(this,"es"),SettingsStore.displayLanguage(this,"it"),SettingsStore.displayLanguage(this,"pt"),SettingsStore.displayLanguage(this,"tr"),SettingsStore.displayLanguage(this,"pl"),SettingsStore.displayLanguage(this,"ar")};}
    String[] subtitleLanguageLabels(){return new String[]{T("follow_app_language"),T("off"),SettingsStore.displayLanguage(this,"nl"),SettingsStore.displayLanguage(this,"en"),SettingsStore.displayLanguage(this,"de"),SettingsStore.displayLanguage(this,"fr"),SettingsStore.displayLanguage(this,"es"),SettingsStore.displayLanguage(this,"it"),SettingsStore.displayLanguage(this,"pt"),SettingsStore.displayLanguage(this,"tr"),SettingsStore.displayLanguage(this,"pl"),SettingsStore.displayLanguage(this,"ar")};}
}
''')

# Free TV source screen: no NAS bridge, no "Profiel", only fields relevant to selected source type.
profile=app/"src/main/java/com/robertalt/raiptv/ProfileActivity.java"
profile.write_text(r'''package com.robertalt.raiptv;

import android.app.*;import android.os.*;import android.view.*;import android.widget.*;
import com.robertalt.raiptv.model.Profile;import com.robertalt.raiptv.provider.*;import com.robertalt.raiptv.storage.*;
import java.util.concurrent.*;

public class ProfileActivity extends Activity{
    String T(String k){return UiText.t(this,k);}int dp(int v){return Math.round(v*getResources().getDisplayMetrics().density);}
    EditText name,server,user,pass,m3u,epg,bridge,bridgeToken;RadioButton xtream,m3uRadio;TextView status;SecureProfileStore store;ExecutorService exec=Executors.newSingleThreadExecutor();
    String lang(){String l=SettingsStore.language(this);return l==null?"en":l.toLowerCase(java.util.Locale.ROOT);}
    String tx(String key){
        String l=lang();
        if("source".equals(key)){if("nl".equals(l))return "TV-bron";if("de".equals(l))return "TV-Quelle";if("fr".equals(l))return "Source TV";if("es".equals(l))return "Fuente TV";if("it".equals(l))return "Sorgente TV";if("pt".equals(l))return "Fonte TV";if("tr".equals(l))return "TV kaynağı";if("pl".equals(l))return "Źródło TV";if("ar".equals(l))return "مصدر التلفاز";return "TV source";}
        if("name".equals(key)){if("nl".equals(l))return "Naam van TV-bron";if("de".equals(l))return "Name der TV-Quelle";if("fr".equals(l))return "Nom de la source TV";if("es".equals(l))return "Nombre de la fuente TV";if("it".equals(l))return "Nome sorgente TV";if("pt".equals(l))return "Nome da fonte TV";if("tr".equals(l))return "TV kaynağı adı";if("pl".equals(l))return "Nazwa źródła TV";if("ar".equals(l))return "اسم مصدر التلفاز";return "TV source name";}
        if("defaults".equals(key)){if("nl".equals(l))return "Free: audio en ondertitels zoals aangeleverd door de provider.";if("de".equals(l))return "Free: Audio und Untertitel wie vom Anbieter geliefert.";if("fr".equals(l))return "Free : audio et sous-titres tels que fournis par le fournisseur.";if("es".equals(l))return "Free: audio y subtítulos tal como los proporciona el proveedor.";if("it".equals(l))return "Free: audio e sottotitoli come forniti dal provider.";if("pt".equals(l))return "Free: áudio e legendas tal como fornecidos pelo fornecedor.";if("tr".equals(l))return "Free: sağlayıcının sunduğu ses ve altyazılar.";if("pl".equals(l))return "Free: dźwięk i napisy dostarczone przez dostawcę.";if("ar".equals(l))return "Free: الصوت والترجمة كما يوفرهما المزود.";return "Free: audio and subtitles as supplied by the provider.";}
        return key;
    }
    @Override public void onCreate(Bundle b){super.onCreate(b);setContentView(R.layout.activity_profile);UiText.applyDirection(this);store=new SecureProfileStore(this);
        name=findViewById(R.id.nameField);server=findViewById(R.id.serverField);user=findViewById(R.id.userField);pass=findViewById(R.id.passField);m3u=findViewById(R.id.m3uField);epg=findViewById(R.id.epgField);bridge=findViewById(R.id.bridgeField);bridgeToken=findViewById(R.id.bridgeTokenField);xtream=findViewById(R.id.xtreamRadio);m3uRadio=findViewById(R.id.m3uRadio);status=findViewById(R.id.profileStatus);
        applyLanguage();load();hideProFields();updateTypeVisibility();
        ((RadioGroup)findViewById(R.id.typeGroup)).setOnCheckedChangeListener((g,id)->updateTypeVisibility());
        Button test=findViewById(R.id.testButton);test.setOnClickListener(v->test());
        Button save=findViewById(R.id.saveButton);save.setTextColor(0xFF0A0A0A);save.setBackgroundTintList(android.content.res.ColorStateList.valueOf(0xFFFFD400));save.setOnClickListener(v->{Profile p=collect();store.save(p);setResult(RESULT_OK);finish();});
    }
    void applyLanguage(){((TextView)findViewById(R.id.profileTitle)).setText(tx("source"));((TextView)findViewById(R.id.profileIntro)).setText(T("profile_intro"));name.setHint(tx("name"));server.setHint(T("server_hint"));user.setHint(T("username"));pass.setHint(T("password"));m3u.setHint("M3U-URL");epg.setHint(T("epg_url_optional"));((TextView)findViewById(R.id.profileDefaults)).setText(tx("defaults"));((Button)findViewById(R.id.testButton)).setText(T("test_connection"));((Button)findViewById(R.id.saveButton)).setText(T("save"));}
    void hideProFields(){findViewById(R.id.bridgeLabel).setVisibility(View.GONE);bridge.setVisibility(View.GONE);bridgeToken.setVisibility(View.GONE);}
    void updateTypeVisibility(){boolean x=xtream.isChecked();server.setVisibility(x?View.VISIBLE:View.GONE);user.setVisibility(x?View.VISIBLE:View.GONE);pass.setVisibility(x?View.VISIBLE:View.GONE);m3u.setVisibility(x?View.GONE:View.VISIBLE);epg.setVisibility(x?View.GONE:View.VISIBLE);}
    void load(){if(!store.exists())return;Profile p=store.load();xtream.setChecked(p.type==Profile.Type.XTREAM);m3uRadio.setChecked(p.type==Profile.Type.M3U);name.setText(p.name);server.setText(p.server);user.setText(p.username);pass.setText(p.password);m3u.setText(p.m3uUrl);epg.setText(p.epgUrl);bridge.setText(p.bridgeUrl);bridgeToken.setText(p.bridgeToken);}
    Profile collect(){Profile p=new Profile();p.type=m3uRadio.isChecked()?Profile.Type.M3U:Profile.Type.XTREAM;p.name=name.getText().toString().trim();p.server=server.getText().toString().trim();p.username=user.getText().toString().trim();p.password=pass.getText().toString();p.m3uUrl=m3u.getText().toString().trim();p.epgUrl=epg.getText().toString().trim();p.bridgeUrl=bridge.getText().toString().trim();p.bridgeToken=bridgeToken.getText().toString();return p;}
    Provider provider(Profile p){return p.type==Profile.Type.XTREAM?new XtreamProvider(p):new M3uProvider(p,SettingsStore.primaryLanguage(this));}
    void test(){status.setText(T("testing_connection"));Profile p=collect();exec.execute(()->{try{provider(p).authenticate();runOnUiThread(()->status.setText(T("connection_ok")));}catch(Exception e){runOnUiThread(()->status.setText(T("failed")+": "+friendly(e)));}});}
    String friendly(Exception e){String m=e.getMessage();if(m==null||m.trim().isEmpty())return T("unknown_error");return m.replace("LOGIN_FAILED",T("login_failed"));}
    @Override protected void onDestroy(){super.onDestroy();exec.shutdownNow();}
}
''')


# v0.12.6: IPTV Free polish — direct search, global local-index search, favorites, robust progress restore, source labels and player retry.

# Header search button: direct one-tap access, placed before hamburger menu.
layout=app/"src/main/res/layout/activity_main.xml"
lx=layout.read_text()
search_tag='''<Button android:id="@+id/searchToggle" android:layout_width="40dp" android:layout_height="36dp" android:layout_marginLeft="5dp" android:minWidth="0dp" android:minHeight="0dp" android:padding="0dp" android:text="⌕" android:textSize="22sp" android:textColor="#FFFFFFFF" android:backgroundTint="#151A21" android:visibility="visible" android:contentDescription="Search"/>'''
lx=re.sub(r'<Button android:id="@\+id/searchToggle"[^>]*/>',search_tag,lx,flags=re.S)
sm=re.search(r'<Button android:id="@\+id/searchToggle"[^>]*/>',lx,re.S)
mm=re.search(r'<Button android:id="@\+id/menuButton"[^>]*/>',lx,re.S)
if sm and mm and sm.start()>mm.start():
    tag=sm.group(0);lx=lx[:sm.start()]+lx[sm.end():]
    mm=re.search(r'<Button android:id="@\+id/menuButton"[^>]*/>',lx,re.S)
    if mm:lx=lx[:mm.start()]+tag+"\n            "+lx[mm.start():]
layout.write_text(lx)

# Search the complete local Free library that has already been synchronized.
a=s.find('    void searchEverywhere(String q){')
if a>=0:
    b=s.find('\n    String safe(',a)
    if b>0:
        method=r'''    void searchEverywhere(String q){
        String z=q==null?"":q.trim().toLowerCase(Locale.ROOT);latestSearchQuery=z;
        if("epg".equals(section)){
            filterBar.setVisibility(z.isEmpty()?View.VISIBLE:View.GONE);showEpgList();epgAdapter.configure(provider,profileKey());epgAdapter.set(all);if(!z.isEmpty())epgAdapter.filter(z);busy(false,z.isEmpty()?T("epg"):T("results"));return;
        }
        if(z.isEmpty()){
            if("home".equals(section)||"local".equals(section)){loadHome();return;}
            filterBar.setVisibility(View.VISIBLE);categories.setVisibility(View.VISIBLE);showMediaGrid("live".equals(section));gridAdapter.set(new ArrayList<>(all),"live".equals(section));busy(false,all.size()+" "+T("results"));return;
        }
        if(z.length()<2){gridAdapter.set(Collections.emptyList(),false);status.setText(T("type_2"));return;}
        categories.setVisibility(View.GONE);filterBar.setVisibility(View.GONE);showMediaGrid(false);
        exec.execute(()->{List<MediaEntry> found;try{found=visibleItems(searchIndex.search(profileKey(),z,250));}catch(Throwable e){found=Collections.emptyList();}final List<MediaEntry> result=found;runOnUiThread(()->{if(!z.equals(latestSearchQuery))return;gridAdapter.set(result,false);busy(false,result.isEmpty()?T("no_results_for")+" ‘"+z+"’":result.size()+" "+T("results"));});});
    }
'''
        s=s[:a]+method+s[b:]

# Favorieten are a primary Home shelf: Continue -> Favorites -> recent live.
s=s.replace('addShelf(T("continue"),cont);addShelf(T("recent_live"),live);addShelf(T("favorites"),favs);',
            'addShelf(T("continue"),cont);addShelf(T("favorites"),favs);addShelf(T("recent_live"),live);')

# Restore progress from the category completion sets used by the resilient sync.
a=s.find('    void restoreFirstSyncBanner(){')
if a>=0:
    b=s.find('\n    void hideIndexBanner(',a)
    if b>0:
        restore=r'''    void restoreFirstSyncBanner(){
        if(indexBanner==null||profile==null)return;String key=profileKey();android.content.SharedPreferences sp=SettingsStore.prefs(this);
        if(sp.getBoolean("free_full_sync_done_v122_"+key,false)){indexBanner.setVisibility(View.GONE);return;}
        int total=0,done=0;
        try{
            for(String type:new String[]{"live","vod","series"}){
                List<Category> cats=provider==null?Collections.emptyList():provider.categories(type);total+=cats.size();
                java.util.Set<String> completed=sp.getStringSet("free_sync_donecats_"+type+"_"+key,java.util.Collections.emptySet());
                for(Category cat:cats)if(completed.contains(safe(cat.id)))done++;
            }
        }catch(Exception ignored){}
        showFreeSyncProgress(done,Math.max(1,total),sp.getInt("free_sync_live_"+key,0),sp.getInt("free_sync_vod_"+key,0),sp.getInt("free_sync_series_"+key,0));
    }
'''
        s=s[:a]+restore+s[b:]

# Wire direct search explicitly.
if 'searchToggle.setOnClickListener(v->toggleSearch())' not in s:
    s=s.replace('    void wire(){\n','    void wire(){\n        if(searchToggle!=null)searchToggle.setOnClickListener(v->toggleSearch());\n',1)

# Settings microcopy/version.
settings=app/"src/main/java/com/robertalt/raiptv/SettingsActivity.java"
sx=settings.read_text()
sx=sx.replace('Kies een blok om de Free-instellingen aan te passen.','Kies een onderdeel.')
sx=sx.replace('Choose a block to change Free settings.','Choose a section.')
sx=sx.replace('NenoTV Free · 0.12.5','NenoTV Free · 0.12.6').replace('NenoTV Free 0.12.5','NenoTV Free 0.12.6')
settings.write_text(sx)

# TV source: permanent field labels + NenoTV-yellow source selector.
profile=app/"src/main/java/com/robertalt/raiptv/ProfileActivity.java"
profile.write_text(r'''package com.robertalt.raiptv;

import android.app.*;import android.os.*;import android.view.*;import android.widget.*;import android.content.res.ColorStateList;import android.graphics.Typeface;
import com.robertalt.raiptv.model.Profile;import com.robertalt.raiptv.provider.*;import com.robertalt.raiptv.storage.*;
import java.util.concurrent.*;

public class ProfileActivity extends Activity{
    String T(String k){return UiText.t(this,k);}int dp(int v){return Math.round(v*getResources().getDisplayMetrics().density);}
    EditText name,server,user,pass,m3u,epg,bridge,bridgeToken;RadioButton xtream,m3uRadio;TextView status,nameLabel,serverLabel,userLabel,passLabel,m3uLabel,epgLabel;SecureProfileStore store;ExecutorService exec=Executors.newSingleThreadExecutor();
    String lang(){String l=SettingsStore.language(this);return l==null?"en":l.toLowerCase(java.util.Locale.ROOT);}
    String tx(String key){
        String l=lang();
        if("source".equals(key)){if("nl".equals(l))return "TV-bron";if("de".equals(l))return "TV-Quelle";if("fr".equals(l))return "Source TV";if("es".equals(l))return "Fuente TV";if("it".equals(l))return "Sorgente TV";if("pt".equals(l))return "Fonte TV";if("tr".equals(l))return "TV kaynağı";if("pl".equals(l))return "Źródło TV";if("ar".equals(l))return "مصدر التلفاز";return "TV source";}
        if("name".equals(key)){if("nl".equals(l))return "Naam";if("de".equals(l))return "Name";if("fr".equals(l))return "Nom";if("es".equals(l))return "Nombre";if("it".equals(l))return "Nome";if("pt".equals(l))return "Nome";if("tr".equals(l))return "Ad";if("pl".equals(l))return "Nazwa";if("ar".equals(l))return "الاسم";return "Name";}
        if("server".equals(key)){if("nl".equals(l))return "Server";if("de".equals(l))return "Server";if("fr".equals(l))return "Serveur";if("es".equals(l))return "Servidor";if("it".equals(l))return "Server";if("pt".equals(l))return "Servidor";if("tr".equals(l))return "Sunucu";if("pl".equals(l))return "Serwer";if("ar".equals(l))return "الخادم";return "Server";}
        if("user".equals(key))return T("username");if("pass".equals(key))return T("password");
        if("m3u".equals(key))return "M3U-URL";if("epg".equals(key))return "EPG-URL";
        if("defaults".equals(key)){if("nl".equals(l))return "Audio en ondertitels zoals aangeleverd door de provider.";if("de".equals(l))return "Audio und Untertitel wie vom Anbieter geliefert.";if("fr".equals(l))return "Audio et sous-titres tels que fournis par le fournisseur.";if("es".equals(l))return "Audio y subtítulos tal como los proporciona el proveedor.";if("it".equals(l))return "Audio e sottotitoli come forniti dal provider.";if("pt".equals(l))return "Áudio e legendas tal como fornecidos pelo fornecedor.";if("tr".equals(l))return "Sağlayıcının sunduğu ses ve altyazılar.";if("pl".equals(l))return "Dźwięk i napisy dostarczone przez dostawcę.";if("ar".equals(l))return "الصوت والترجمة كما يوفرهما المزود.";return "Audio and subtitles as supplied by the provider.";}
        return key;
    }
    @Override public void onCreate(Bundle b){super.onCreate(b);setContentView(R.layout.activity_profile);UiText.applyDirection(this);store=new SecureProfileStore(this);
        name=findViewById(R.id.nameField);server=findViewById(R.id.serverField);user=findViewById(R.id.userField);pass=findViewById(R.id.passField);m3u=findViewById(R.id.m3uField);epg=findViewById(R.id.epgField);bridge=findViewById(R.id.bridgeField);bridgeToken=findViewById(R.id.bridgeTokenField);xtream=findViewById(R.id.xtreamRadio);m3uRadio=findViewById(R.id.m3uRadio);status=findViewById(R.id.profileStatus);
        nameLabel=labelBefore(name,tx("name"));serverLabel=labelBefore(server,tx("server"));userLabel=labelBefore(user,tx("user"));passLabel=labelBefore(pass,tx("pass"));m3uLabel=labelBefore(m3u,tx("m3u"));epgLabel=labelBefore(epg,tx("epg"));
        ColorStateList tint=new ColorStateList(new int[][]{new int[]{android.R.attr.state_checked},new int[]{}},new int[]{0xFFFFD400,0xFFA7AFBC});xtream.setButtonTintList(tint);m3uRadio.setButtonTintList(tint);
        applyLanguage();load();hideProFields();updateTypeVisibility();
        ((RadioGroup)findViewById(R.id.typeGroup)).setOnCheckedChangeListener((g,id)->updateTypeVisibility());
        Button test=findViewById(R.id.testButton);test.setOnClickListener(v->test());
        Button save=findViewById(R.id.saveButton);save.setTextColor(0xFF0A0A0A);save.setBackgroundTintList(ColorStateList.valueOf(0xFFFFD400));save.setOnClickListener(v->{Profile p=collect();store.save(p);setResult(RESULT_OK);finish();});
    }
    TextView labelBefore(EditText field,String text){ViewGroup parent=(ViewGroup)field.getParent();TextView l=new TextView(this);l.setText(text);l.setTextColor(0xFFA7AFBC);l.setTextSize(12);l.setTypeface(null,Typeface.BOLD);l.setPadding(dp(8),dp(9),0,dp(3));int i=parent.indexOfChild(field);parent.addView(l,Math.max(0,i),new ViewGroup.LayoutParams(-1,-2));return l;}
    void applyLanguage(){((TextView)findViewById(R.id.profileTitle)).setText(tx("source"));((TextView)findViewById(R.id.profileIntro)).setText(T("profile_intro"));name.setHint("");server.setHint("");user.setHint("");pass.setHint("");m3u.setHint("");epg.setHint("");((TextView)findViewById(R.id.profileDefaults)).setText(tx("defaults"));((Button)findViewById(R.id.testButton)).setText(T("test_connection"));((Button)findViewById(R.id.saveButton)).setText(T("save"));}
    void hideProFields(){findViewById(R.id.bridgeLabel).setVisibility(View.GONE);bridge.setVisibility(View.GONE);bridgeToken.setVisibility(View.GONE);}
    void updateTypeVisibility(){boolean x=xtream.isChecked();setVisible(server,serverLabel,x);setVisible(user,userLabel,x);setVisible(pass,passLabel,x);setVisible(m3u,m3uLabel,!x);setVisible(epg,epgLabel,!x);}
    void setVisible(View field,View label,boolean yes){field.setVisibility(yes?View.VISIBLE:View.GONE);label.setVisibility(yes?View.VISIBLE:View.GONE);}
    void load(){if(!store.exists())return;Profile p=store.load();xtream.setChecked(p.type==Profile.Type.XTREAM);m3uRadio.setChecked(p.type==Profile.Type.M3U);name.setText(p.name);server.setText(p.server);user.setText(p.username);pass.setText(p.password);m3u.setText(p.m3uUrl);epg.setText(p.epgUrl);bridge.setText(p.bridgeUrl);bridgeToken.setText(p.bridgeToken);}
    Profile collect(){Profile p=new Profile();p.type=m3uRadio.isChecked()?Profile.Type.M3U:Profile.Type.XTREAM;p.name=name.getText().toString().trim();p.server=server.getText().toString().trim();p.username=user.getText().toString().trim();p.password=pass.getText().toString();p.m3uUrl=m3u.getText().toString().trim();p.epgUrl=epg.getText().toString().trim();p.bridgeUrl=bridge.getText().toString().trim();p.bridgeToken=bridgeToken.getText().toString();return p;}
    Provider provider(Profile p){return p.type==Profile.Type.XTREAM?new XtreamProvider(p):new M3uProvider(p,SettingsStore.primaryLanguage(this));}
    void test(){status.setTextColor(0xFFA7AFBC);status.setText(T("testing_connection"));Profile p=collect();exec.execute(()->{try{provider(p).authenticate();runOnUiThread(()->{status.setTextColor(0xFF7ED957);status.setText(T("connection_ok"));});}catch(Exception e){runOnUiThread(()->{status.setTextColor(0xFFFF6B6B);status.setText(T("failed")+": "+friendly(e));});}});}
    String friendly(Exception e){String m=e.getMessage();if(m==null||m.trim().isEmpty())return T("unknown_error");if(m.contains("LOGIN_FAILED"))return T("login_failed");return T("unknown_error");}
    @Override protected void onDestroy(){super.onDestroy();exec.shutdownNow();}
}
''')

# Free Media3 player: automatic retry for transient stream errors, no technical error-code text.
player=app/"src/main/java/com/robertalt/raiptv/PlayerActivity.java"
px=player.read_text()
px=px.replace('boolean userSeeking=false,destroyed=false; int aspectMode=0; float playbackSpeed=1f;',
              'boolean userSeeking=false,destroyed=false; int aspectMode=0,retryCount=0; float playbackSpeed=1f;')
old='exo.addListener(new Player.Listener(){@Override public void onPlaybackStateChanged(int state){if(state==Player.STATE_READY)status.setText("Media3 · "+T("playing"));else if(state==Player.STATE_ENDED)finish();}@Override public void onPlayerError(PlaybackException e){status.setText(T("error_prefix")+": "+e.getErrorCodeName());}});'
new='exo.addListener(new Player.Listener(){@Override public void onPlaybackStateChanged(int state){if(state==Player.STATE_READY){retryCount=0;status.setText("Media3 · "+T("playing"));}else if(state==Player.STATE_ENDED)finish();}@Override public void onPlayerError(PlaybackException e){retryStream();}});'
px=px.replace(old,new)
if 'void retryStream()' not in px:
    px=px.replace('    void updateProgress(){',r'''    void retryStream(){if(exo==null||destroyed)return;if(retryCount>=2){status.setText(streamText(false));return;}retryCount++;status.setText(streamText(true));final int attempt=retryCount;ui.postDelayed(()->{if(exo==null||destroyed)return;try{exo.seekToDefaultPosition();exo.prepare();exo.play();}catch(Throwable ignored){}},700L*attempt);}
    String streamText(boolean retry){String l=SettingsStore.language(this);if("nl".equals(l))return retry?"Stream opnieuw verbinden…":"Stream tijdelijk niet beschikbaar";if("de".equals(l))return retry?"Stream wird neu verbunden…":"Stream vorübergehend nicht verfügbar";if("fr".equals(l))return retry?"Reconnexion du flux…":"Flux temporairement indisponible";if("es".equals(l))return retry?"Reconectando stream…":"Stream temporalmente no disponible";if("it".equals(l))return retry?"Riconnessione stream…":"Stream temporaneamente non disponibile";if("pt".equals(l))return retry?"A reconectar o stream…":"Stream temporariamente indisponível";if("tr".equals(l))return retry?"Yayın yeniden bağlanıyor…":"Yayın geçici olarak kullanılamıyor";if("pl".equals(l))return retry?"Ponowne łączenie ze strumieniem…":"Strumień chwilowo niedostępny";if("ar".equals(l))return retry?"جارٍ إعادة الاتصال بالبث…":"البث غير متاح مؤقتًا";return retry?"Reconnecting stream…":"Stream temporarily unavailable";}

    void updateProgress(){''')
player.write_text(px)


# v0.12.7: finish the Free IPTV core: live zapping + Now/Next, provider catch-up,
# silent library/EPG refresh and Android TV/D-pad controls.

# Provider contract: archive/catch-up is optional and provider-driven.
providerFile=app/"src/main/java/com/robertalt/raiptv/provider/Provider.java"
pv=providerFile.read_text()
if 'archiveEntries(MediaEntry item,int limit)' not in pv:
    pv=pv.replace(
        '    default List<EpgEntry> epgEntries(MediaEntry item,int limit) throws Exception { return Collections.emptyList(); }',
        '''    default List<EpgEntry> epgEntries(MediaEntry item,int limit) throws Exception { return Collections.emptyList(); }
    default List<EpgEntry> archiveEntries(MediaEntry item,int limit) throws Exception { return Collections.emptyList(); }
    default String catchupUrl(MediaEntry item,EpgEntry programme) throws Exception { return ""; }'''
    )
providerFile.write_text(pv)

# Xtream: use archive metadata only when provider marks a channel as catch-up capable.
xt=app/"src/main/java/com/robertalt/raiptv/provider/XtreamProvider.java"
xx=xt.read_text()
if 'archiveEntries(MediaEntry item,int limit)' not in xx:
    insert=r'''
    @Override public List<EpgEntry> archiveEntries(MediaEntry item,int limit)throws Exception{
        List<EpgEntry> out=new ArrayList<>();if(item==null||!item.catchup||item.streamId==null||item.streamId.isEmpty())return out;
        String url=XtreamUrls.api(p.server,p.username,p.password,"get_simple_data_table","")+"&stream_id="+XtreamUrls.enc(item.streamId);
        JSONObject data=new JSONObject(HttpText.get(url));JSONArray rows=data.optJSONArray("epg_listings");if(rows==null)return out;
        long now=System.currentTimeMillis()/1000L;long cutoff=item.catchupDays>0?now-(item.catchupDays*86400L):now-(7L*86400L);
        ArrayList<EpgEntry> all=new ArrayList<>();
        for(int i=0;i<rows.length();i++){
            JSONObject x=rows.optJSONObject(i);if(x==null)continue;EpgEntry e=new EpgEntry();
            e.title=decodeMaybe(x.optString("title",""));if(e.title.isEmpty())e.title="Programma";
            e.description=decodeMaybe(x.optString("description",x.optString("desc","")));
            e.startRaw=x.optString("start","");e.endRaw=x.optString("end",x.optString("stop",""));
            e.startEpoch=readEpoch(x,"start_timestamp",e.startRaw);e.endEpoch=readEpoch(x,"stop_timestamp",e.endRaw);
            if(e.startEpoch>0&&e.endEpoch>e.startEpoch&&e.endEpoch<=now&&e.endEpoch>=cutoff)all.add(e);
        }
        all.sort((a,b)->Long.compare(b.startEpoch,a.startEpoch));int n=Math.max(1,Math.min(40,limit));for(int i=0;i<Math.min(n,all.size());i++)out.add(all.get(i));return out;
    }

    @Override public String catchupUrl(MediaEntry item,EpgEntry programme)throws Exception{
        if(item==null||programme==null||!item.catchup||item.streamId==null||item.streamId.isEmpty()||programme.startEpoch<=0)return "";
        long end=programme.endEpoch>programme.startEpoch?programme.endEpoch:programme.startEpoch+1800L;
        long minutes=Math.max(1L,(end-programme.startEpoch+59L)/60L);
        java.time.ZonedDateTime z=java.time.Instant.ofEpochSecond(programme.startEpoch).atZone(java.time.ZoneId.systemDefault());
        String start=z.format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd:HH-mm"));
        return XtreamUrls.base(p.server)+"/timeshift/"+XtreamUrls.enc(p.username)+"/"+XtreamUrls.enc(p.password)+"/"+minutes+"/"+start+"/"+XtreamUrls.enc(item.streamId)+".ts";
    }

'''
    marker='    private long readEpoch(JSONObject x,String field,String raw){'
    xx=xx.replace(marker,insert+marker)
xt.write_text(xx)

# EPG cache refreshes itself every 10 minutes while the guide remains open.
epg=app/"src/main/java/com/robertalt/raiptv/EpgAdapter.java"
ex=epg.read_text()
if 'autoRefresh' not in ex:
    ex=ex.replace(
        'private final ExecutorService exec=Executors.newFixedThreadPool(2);',
        'private final ExecutorService exec=Executors.newFixedThreadPool(2);'
    )
    ex=ex.replace(
        'private List<MediaEntry> all=new ArrayList<>(), shown=new ArrayList<>(); private Provider provider; private String profileKey=""; private volatile boolean disposed=false;',
        '''private List<MediaEntry> all=new ArrayList<>(), shown=new ArrayList<>(); private Provider provider; private String profileKey=""; private volatile boolean disposed=false;
    private final Runnable autoRefresh=new Runnable(){public void run(){if(disposed)return;cache.clear();loading.clear();notifyDataSetChanged();ui.postDelayed(this,10*60*1000L);}};'''
    )
    ex=ex.replace(
        'public void configure(Provider p,String key){disposed=false;provider=p;profileKey=key==null?"":key;cache.clear();loading.clear();}',
        'public void configure(Provider p,String key){disposed=false;provider=p;profileKey=key==null?"":key;cache.clear();loading.clear();ui.removeCallbacks(autoRefresh);ui.postDelayed(autoRefresh,10*60*1000L);}'
    )
    ex=ex.replace(
        'public void shutdown(){disposed=true;loading.clear();cache.clear();exec.shutdownNow();}',
        'public void shutdown(){disposed=true;ui.removeCallbacks(autoRefresh);loading.clear();cache.clear();exec.shutdownNow();}'
    )
epg.write_text(ex)

# Library: after the first complete sync, quietly refresh from the provider every 6 hours.
# The initial progress banner remains first-sync only.
if 'void refreshFreeLibrarySilent()' not in s:
    anchor='    void scheduleBackgroundIndex(){'
    a=s.find(anchor)
    if a>=0:
        b=s.find('\\n    void ',a+10)
        old=s[a:b] if b>0 else ''
        new=r'''    void scheduleBackgroundIndex(){
        if(provider==null||profile==null||indexRefreshRunning)return;String key=profileKey();android.content.SharedPreferences sp=SettingsStore.prefs(this);
        boolean complete=sp.getBoolean("free_full_sync_done_v122_"+key,false);
        if(!complete){refreshSearchIndex(false);return;}
        long last=sp.getLong("free_full_sync_at_"+key,0L);if(last<=0L)last=sp.getLong("free_library_refresh_at_"+key,0L);
        if(System.currentTimeMillis()-last>=6L*60L*60L*1000L)refreshFreeLibrarySilent();
        else if(indexBanner!=null)indexBanner.setVisibility(View.GONE);
    }

    void refreshFreeLibrarySilent(){
        if(provider==null||profile==null||indexRefreshRunning)return;indexRefreshRunning=true;final String key=profileKey();
        indexFuture=indexExec.submit(()->{
            boolean ok=true;int live=0,vod=0,series=0;
            try{
                for(String type:new String[]{"live","vod","series"}){
                    List<Category> cats=new ArrayList<>(provider.categories(type));ArrayList<MediaEntry> aggregate=new ArrayList<>();
                    boolean sectionOk=true;
                    for(Category cat:cats){
                        if(Thread.currentThread().isInterrupted())return;List<MediaEntry> rows=null;
                        for(int attempt=0;attempt<2&&rows==null;attempt++)try{rows=provider.items(type,cat.id);}catch(Throwable e){try{Thread.sleep(250L*(attempt+1));}catch(InterruptedException ie){Thread.currentThread().interrupt();return;}}
                        if(rows==null){sectionOk=false;break;}
                        for(MediaEntry e:rows)if(e!=null&&(e.group==null||e.group.trim().isEmpty()))e.group=cat.name;
                        aggregate.addAll(rows);
                    }
                    if(sectionOk){searchIndex.replaceSection(key,type,aggregate);if("live".equals(type))live=aggregate.size();else if("vod".equals(type))vod=aggregate.size();else series=aggregate.size();}
                    else ok=false;
                }
                if(ok)SettingsStore.prefs(this).edit().putLong("free_library_refresh_at_"+key,System.currentTimeMillis()).putInt("free_sync_live_"+key,live).putInt("free_sync_vod_"+key,vod).putInt("free_sync_series_"+key,series).apply();
            }catch(Throwable ignored){ok=false;}finally{indexRefreshRunning=false;indexFuture=null;}
        });
    }
'''
        if b>0:s=s[:a]+new+s[b:]

# Mark initial sync completion time.
s=s.replace(
    'if(allOk)sp.edit().putBoolean(completeKey,true).apply();',
    'if(allOk)sp.edit().putBoolean(completeKey,true).putLong("free_full_sync_at_"+key,System.currentTimeMillis()).apply();'
)

# Player layout: Now/Next, EPG progress and a provider catch-up button.
pl=app/"src/main/res/layout/activity_player.xml"
lx=pl.read_text()
if 'playerNowNext' not in lx:
    lx=lx.replace(
        '<TextView android:id="@+id/playerStatus" android:layout_width="match_parent" android:layout_height="wrap_content" android:textColor="#C7CDD6" android:textSize="12sp" android:paddingTop="3dp" />',
        '<TextView android:id="@+id/playerStatus" android:layout_width="match_parent" android:layout_height="wrap_content" android:textColor="#C7CDD6" android:textSize="12sp" android:paddingTop="3dp" />\\n'
        '            <TextView android:id="@+id/playerNowNext" android:layout_width="match_parent" android:layout_height="wrap_content" android:textColor="#FFFFFF" android:textSize="12sp" android:paddingTop="5dp" android:maxLines="2" android:ellipsize="end" android:visibility="gone"/>\\n'
        '            <ProgressBar android:id="@+id/playerEpgProgress" style="?android:attr/progressBarStyleHorizontal" android:layout_width="match_parent" android:layout_height="4dp" android:layout_marginTop="5dp" android:max="100" android:progress="0" android:progressTint="#FFD400" android:progressBackgroundTint="#333333" android:visibility="gone"/>'
    )
if 'catchupButton' not in lx:
    lx=lx.replace(
        '<Button android:id="@+id/favoriteButton"',
        '<Button android:id="@+id/catchupButton" android:layout_width="wrap_content" android:layout_height="42dp" android:text="↶ Terug" android:textAllCaps="false" android:textColor="#FFFFFF" android:backgroundTint="#55000000" android:visibility="gone" />\\n                    <Button android:id="@+id/favoriteButton"'
    )
# Remote focus should land on controls naturally.
lx=lx.replace('android:id="@+id/playerControls" android:layout_width="match_parent"', 'android:id="@+id/playerControls" android:layout_width="match_parent" android:focusable="true" android:focusableInTouchMode="true"')
pl.write_text(lx)

# Replace the lightweight PlayerActivity with an IPTV-aware Free player.
player=app/"src/main/java/com/robertalt/raiptv/PlayerActivity.java"
player.write_text(r'''package com.robertalt.raiptv;

import android.app.*;
import android.graphics.Color;
import android.os.*;
import android.view.*;
import android.widget.*;
import androidx.fragment.app.FragmentActivity;
import androidx.media3.common.*;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.ui.AspectRatioFrameLayout;
import androidx.media3.ui.PlayerView;
import com.robertalt.raiptv.model.*;
import com.robertalt.raiptv.provider.*;
import com.robertalt.raiptv.storage.*;
import java.util.*;
import java.util.concurrent.*;

public class PlayerActivity extends FragmentActivity {
    PlayerView media3View; ExoPlayer exo; MediaEntry entry; LibraryStore library; Provider provider;
    ArrayList<MediaEntry> liveChannels=new ArrayList<>();int liveIndex=-1,retryCount=0,aspectMode=0;boolean playingCatchup=false;
    EpgEntry nowProgramme,nextProgramme;
    TextView title,status,timeText,nowNext; ProgressBar epgProgress;
    Button playPause,rewind,forward,audio,subtitle,pip,speed,aspect,sleep,record,favorite,castButton,channelPrev,channelNext,catchup;
    SeekBar seek; FrameLayout controls; Handler ui=new Handler(Looper.getMainLooper()); ExecutorService bg=Executors.newSingleThreadExecutor();
    boolean userSeeking=false,destroyed=false; float playbackSpeed=1f;
    String T(String k){return UiText.t(this,k);}
    Runnable tick=new Runnable(){public void run(){if(destroyed)return;updateProgress();updateEpgProgress();ui.postDelayed(this,500);}};

    @Override public void onCreate(Bundle b){
        super.onCreate(b);CrashGuard.install(this);getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);getWindow().setStatusBarColor(Color.BLACK);
        setContentView(R.layout.activity_player);UiText.applyDirection(this);library=new LibraryStore(this);
        media3View=findViewById(R.id.media3View);controls=findViewById(R.id.playerControls);title=findViewById(R.id.playerTitle);status=findViewById(R.id.playerStatus);timeText=findViewById(R.id.timeText);
        nowNext=findViewById(R.id.playerNowNext);epgProgress=findViewById(R.id.playerEpgProgress);playPause=findViewById(R.id.playPauseButton);rewind=findViewById(R.id.rewindButton);forward=findViewById(R.id.forwardButton);audio=findViewById(R.id.audioButton);subtitle=findViewById(R.id.subtitleButton);pip=findViewById(R.id.pipButton);seek=findViewById(R.id.seekBar);speed=findViewById(R.id.speedButton);aspect=findViewById(R.id.aspectButton);sleep=findViewById(R.id.sleepButton);record=findViewById(R.id.recordButton);favorite=findViewById(R.id.favoriteButton);castButton=findViewById(R.id.castRouteButton);channelPrev=findViewById(R.id.channelPrevButton);channelNext=findViewById(R.id.channelNextButton);catchup=findViewById(R.id.catchupButton);
        entry=(MediaEntry)getIntent().getSerializableExtra("media");if(entry==null){finish();return;}title.setText(DisplayText.title(entry));
        record.setVisibility(View.GONE);castButton.setVisibility(View.GONE);pip.setVisibility(View.GONE);sleep.setVisibility(View.GONE);
        boolean live="live".equals(entry.type);channelPrev.setVisibility(live?View.VISIBLE:View.GONE);channelNext.setVisibility(live?View.VISIBLE:View.GONE);rewind.setVisibility(live?View.GONE:View.VISIBLE);forward.setVisibility(live?View.GONE:View.VISIBLE);seek.setVisibility(live?View.GONE:View.VISIBLE);timeText.setVisibility(live?View.GONE:View.VISIBLE);
        wire();startPlayer();if(live)loadLiveContext();ui.post(tick);controls.requestFocus();
    }

    void wire(){
        playPause.setOnClickListener(v->{if(exo==null)return;if(exo.isPlaying())exo.pause();else exo.play();updatePlayIcon();});
        rewind.setOnClickListener(v->{if(exo!=null)exo.seekTo(Math.max(0,exo.getCurrentPosition()-10000));});
        forward.setOnClickListener(v->{if(exo!=null)exo.seekTo(exo.getCurrentPosition()+10000);});
        channelPrev.setOnClickListener(v->switchChannel(-1));channelNext.setOnClickListener(v->switchChannel(1));
        catchup.setOnClickListener(v->{if(playingCatchup)playLiveEntry();else showCatchup();});
        seek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener(){public void onStartTrackingTouch(SeekBar b){userSeeking=true;}public void onStopTrackingTouch(SeekBar b){userSeeking=false;if(exo!=null&&exo.getDuration()>0)exo.seekTo((long)(exo.getDuration()*(b.getProgress()/1000f)));}public void onProgressChanged(SeekBar b,int p,boolean u){}});
        favorite.setOnClickListener(v->{if(entry!=null){library.toggleFavorite(entry);updateFavorite();}});
        audio.setOnClickListener(v->showTracks(C.TRACK_TYPE_AUDIO));subtitle.setOnClickListener(v->showTracks(C.TRACK_TYPE_TEXT));speed.setOnClickListener(v->showSpeed());aspect.setOnClickListener(v->cycleAspect());updateFavorite();
    }

    Provider makeProvider(){
        try{Profile p=new SecureProfileStore(this).load();if(p==null)return null;Provider q=p.type==Profile.Type.XTREAM?new XtreamProvider(p):new M3uProvider(p,SettingsStore.primaryLanguage(this));if(p.type==Profile.Type.M3U)q.authenticate();return q;}catch(Exception e){return null;}
    }

    void loadLiveContext(){
        nowNext.setVisibility(View.VISIBLE);epgProgress.setVisibility(View.VISIBLE);
        bg.execute(()->{
            provider=makeProvider();if(provider==null)return;
            try{
                List<MediaEntry> rows=provider.items("live",(entry.categoryId==null||entry.categoryId.isEmpty())?"all":entry.categoryId);liveChannels=new ArrayList<>(rows);
                for(int i=0;i<liveChannels.size();i++){MediaEntry x=liveChannels.get(i);if(x.uniqueKey().equals(entry.uniqueKey())||(!entry.id.isEmpty()&&entry.id.equals(x.id))){liveIndex=i;break;}}
            }catch(Exception ignored){}
            loadNowNext();
        });
    }

    void loadNowNext(){
        if(provider==null||entry==null||!"live".equals(entry.type))return;final String key=entry.uniqueKey();
        try{
            List<EpgEntry> rows=provider.epgEntries(entry,6);long now=System.currentTimeMillis()/1000L;EpgEntry cur=null,nxt=null;
            for(EpgEntry e:rows){if(e.startEpoch>0&&e.endEpoch>0&&e.startEpoch<=now&&now<e.endEpoch){cur=e;continue;}if(e.startEpoch>now&&(nxt==null||e.startEpoch<nxt.startEpoch))nxt=e;}
            if(cur==null)for(EpgEntry e:rows)if(e.startEpoch<=now&&(cur==null||e.startEpoch>cur.startEpoch))cur=e;
            final EpgEntry fc=cur,fn=nxt;final boolean canCatch=entry.catchup;
            runOnUiThread(()->{if(destroyed||entry==null||!key.equals(entry.uniqueKey()))return;nowProgramme=fc;nextProgramme=fn;renderNowNext();catchup.setVisibility(canCatch?View.VISIBLE:View.GONE);});
        }catch(Exception ignored){runOnUiThread(()->{if(!destroyed){nowNext.setText(T("no_epg"));epgProgress.setProgress(0);}});}
    }

    void renderNowNext(){
        if(nowNext==null)return;StringBuilder x=new StringBuilder();
        if(nowProgramme!=null){x.append(T("now")).append("  ");String r=nowProgramme.range();if(!r.isEmpty())x.append(r).append("  ");x.append(nowProgramme.title);}
        if(nextProgramme!=null){if(x.length()>0)x.append("\\n");x.append(T("next")).append("  ");String r=nextProgramme.range();if(!r.isEmpty())x.append(r).append("  ");x.append(nextProgramme.title);}
        nowNext.setText(x.length()==0?T("no_epg"):x.toString());updateEpgProgress();
    }

    void updateEpgProgress(){
        if(epgProgress==null||nowProgramme==null){if(epgProgress!=null)epgProgress.setProgress(0);return;}long n=System.currentTimeMillis()/1000L,span=nowProgramme.endEpoch-nowProgramme.startEpoch;if(span>0&&n>=nowProgramme.startEpoch){epgProgress.setProgress((int)Math.max(0,Math.min(100,((n-nowProgramme.startEpoch)*100)/span)));if(n>=nowProgramme.endEpoch&&provider!=null)bg.execute(this::loadNowNext);}
    }

    void switchChannel(int delta){
        if(liveChannels==null||liveChannels.isEmpty()||liveIndex<0)return;int n=liveChannels.size();liveIndex=(liveIndex+delta+n)%n;entry=liveChannels.get(liveIndex);playingCatchup=false;title.setText(DisplayText.title(entry));library.recent(entry);updateFavorite();playLiveEntry();if(provider!=null)bg.execute(this::loadNowNext);
    }

    ArrayList<String> streamUrls(MediaEntry e){ArrayList<String> urls=new ArrayList<>();if(e!=null&&e.candidates!=null)urls.addAll(e.candidates);if(urls.isEmpty()&&e!=null&&e.url!=null&&!e.url.isEmpty())urls.add(e.url);return urls;}

    void startPlayer(){playLiveEntry();}
    void playLiveEntry(){
        playingCatchup=false;if(catchup!=null)catchup.setText("↶ "+catchupLabel());ArrayList<String> urls=streamUrls(entry);if(urls.isEmpty()){status.setText(T("no_stream_url"));return;}playUrl(urls.get(0),!"live".equals(entry.type));library.recent(entry);
    }

    void playUrl(String url,boolean seekable){
        if(exo==null){exo=new ExoPlayer.Builder(this).build();media3View.setPlayer(exo);exo.addListener(new Player.Listener(){@Override public void onPlaybackStateChanged(int state){if(state==Player.STATE_READY){retryCount=0;status.setText(T("playing"));}else if(state==Player.STATE_ENDED&&playingCatchup){playLiveEntry();}}@Override public void onPlayerError(PlaybackException e){retryStream();}});}
        exo.setMediaItem(MediaItem.fromUri(url));exo.prepare();if(seekable&&entry!=null&&!"live".equals(entry.type)){long resume=library.progress(entry);if(resume>10000)exo.seekTo(resume);}exo.play();status.setText(T("playing"));updatePlayIcon();
    }

    void showCatchup(){
        if(provider==null||entry==null||!entry.catchup)return;status.setText(catchupLoading());
        final String key=entry.uniqueKey();bg.execute(()->{try{
            List<EpgEntry> rows=provider.archiveEntries(entry,24);ArrayList<EpgEntry> usable=new ArrayList<>();ArrayList<String> labels=new ArrayList<>();
            for(EpgEntry e:rows){String u=provider.catchupUrl(entry,e);if(u!=null&&!u.isEmpty()){usable.add(e);labels.add((e.range().isEmpty()?"":e.range()+"  ")+e.title);}}
            runOnUiThread(()->{if(destroyed||entry==null||!key.equals(entry.uniqueKey()))return;if(usable.isEmpty()){Toast.makeText(this,noCatchup(),Toast.LENGTH_SHORT).show();status.setText(T("playing"));return;}new AlertDialog.Builder(this).setTitle(catchupLabel()).setItems(labels.toArray(new String[0]),(d,w)->playArchive(usable.get(w))).setNegativeButton(T("close"),null).show();status.setText(T("playing"));});
        }catch(Exception e){runOnUiThread(()->{Toast.makeText(this,noCatchup(),Toast.LENGTH_SHORT).show();status.setText(T("playing"));});}});
    }

    void playArchive(EpgEntry p){if(provider==null)return;try{String u=provider.catchupUrl(entry,p);if(u==null||u.isEmpty())return;playingCatchup=true;title.setText(DisplayText.title(entry)+" · "+p.title);catchup.setText("● LIVE");playUrl(u,true);}catch(Exception ignored){}}

    String catchupLabel(){String l=SettingsStore.language(this);if("nl".equals(l))return "Terugkijken";if("de".equals(l))return "Nachholen";if("fr".equals(l))return "Replay";if("es".equals(l))return "Repetición";if("it".equals(l))return "Replay";if("pt".equals(l))return "Rever";if("tr".equals(l))return "Geri izle";if("pl".equals(l))return "Cofnij";if("ar".equals(l))return "إعادة";return "Catch-up";}
    String catchupLoading(){String l=SettingsStore.language(this);return "nl".equals(l)?"Terugkijkprogramma’s laden…":"Loading catch-up…";}
    String noCatchup(){String l=SettingsStore.language(this);return "nl".equals(l)?"Geen terugkijkprogramma’s beschikbaar":"No catch-up programmes available";}

    void retryStream(){if(exo==null||destroyed)return;if(retryCount>=2){status.setText(streamText(false));return;}retryCount++;status.setText(streamText(true));final int attempt=retryCount;ui.postDelayed(()->{if(exo==null||destroyed)return;try{exo.seekToDefaultPosition();exo.prepare();exo.play();}catch(Throwable ignored){}},700L*attempt);}
    String streamText(boolean retry){String l=SettingsStore.language(this);if("nl".equals(l))return retry?"Stream opnieuw verbinden…":"Stream tijdelijk niet beschikbaar";if("de".equals(l))return retry?"Stream wird neu verbunden…":"Stream vorübergehend nicht verfügbar";if("fr".equals(l))return retry?"Reconnexion du flux…":"Flux temporairement indisponible";if("es".equals(l))return retry?"Reconectando stream…":"Stream temporalmente no disponible";if("it".equals(l))return retry?"Riconnessione stream…":"Stream temporaneamente non disponibile";if("pt".equals(l))return retry?"A reconectar o stream…":"Stream temporariamente indisponível";if("tr".equals(l))return retry?"Yayın yeniden bağlanıyor…":"Yayın geçici olarak kullanılamıyor";if("pl".equals(l))return retry?"Ponowne łączenie ze strumieniem…":"Strumień chwilowo niedostępny";if("ar".equals(l))return retry?"جارٍ إعادة الاتصال بالبث…":"البث غير متاح مؤقتًا";return retry?"Reconnecting stream…":"Stream temporarily unavailable";}

    void updateProgress(){if(exo==null)return;long pos=Math.max(0,exo.getCurrentPosition()),dur=Math.max(0,exo.getDuration());if(!userSeeking&&dur>0)seek.setProgress((int)Math.min(1000,pos*1000/dur));timeText.setText(fmt(pos)+(dur>0?" / "+fmt(dur):""));if(entry!=null&&!"live".equals(entry.type)&&!playingCatchup&&pos>5000)library.saveProgress(entry,pos,dur);updatePlayIcon();}
    String fmt(long ms){long s=Math.max(0,ms/1000),m=s/60,h=m/60;return h>0?String.format(Locale.ROOT,"%d:%02d:%02d",h,m%60,s%60):String.format(Locale.ROOT,"%02d:%02d",m,s%60);}
    void updatePlayIcon(){if(playPause!=null)playPause.setText(exo!=null&&exo.isPlaying()?"❚❚":"▶");}
    void updateFavorite(){if(favorite!=null&&entry!=null)favorite.setText(library.isFavorite(entry)?"♥":"♡");}
    void showSpeed(){final float[] r={.75f,1f,1.25f,1.5f,2f};String[] l={"0.75×","1.0×","1.25×","1.5×","2.0×"};new AlertDialog.Builder(this).setTitle(T("speed_title")).setItems(l,(d,w)->{playbackSpeed=r[w];if(exo!=null)exo.setPlaybackSpeed(playbackSpeed);speed.setText(l[w]);}).show();}
    void cycleAspect(){aspectMode=(aspectMode+1)%3;media3View.setResizeMode(aspectMode==0?AspectRatioFrameLayout.RESIZE_MODE_FIT:aspectMode==1?AspectRatioFrameLayout.RESIZE_MODE_FILL:AspectRatioFrameLayout.RESIZE_MODE_ZOOM);}
    void showTracks(int type){if(exo==null)return;Tracks tr=exo.getCurrentTracks();ArrayList<String> names=new ArrayList<>();ArrayList<TrackSelectionOverride> picks=new ArrayList<>();for(Tracks.Group g:tr.getGroups()){if(g.getType()!=type)continue;for(int i=0;i<g.length;i++){Format f=g.getTrackFormat(i);String n=f.label!=null?f.label:(f.language!=null?SettingsStore.displayLanguage(this,f.language):T(type==C.TRACK_TYPE_AUDIO?"audio":"subtitles"));names.add(n);picks.add(new TrackSelectionOverride(g.getMediaTrackGroup(),Collections.singletonList(i)));}}if(names.isEmpty()){Toast.makeText(this,type==C.TRACK_TYPE_AUDIO?T("no_audio_tracks"):T("no_subtitles"),Toast.LENGTH_SHORT).show();return;}new AlertDialog.Builder(this).setTitle(type==C.TRACK_TYPE_AUDIO?T("audio_track"):T("subtitles")).setItems(names.toArray(new String[0]),(d,w)->{TrackSelectionParameters.Builder pb=exo.getTrackSelectionParameters().buildUpon();pb.setOverrideForType(picks.get(w));exo.setTrackSelectionParameters(pb.build());}).show();}

    @Override public boolean onKeyDown(int keyCode,KeyEvent event){
        if("live".equals(entry.type)){
            if(keyCode==KeyEvent.KEYCODE_DPAD_UP||keyCode==KeyEvent.KEYCODE_CHANNEL_UP){switchChannel(-1);return true;}
            if(keyCode==KeyEvent.KEYCODE_DPAD_DOWN||keyCode==KeyEvent.KEYCODE_CHANNEL_DOWN){switchChannel(1);return true;}
        }
        if(keyCode==KeyEvent.KEYCODE_DPAD_CENTER||keyCode==KeyEvent.KEYCODE_ENTER){if(exo!=null){if(exo.isPlaying())exo.pause();else exo.play();updatePlayIcon();}return true;}
        return super.onKeyDown(keyCode,event);
    }

    @Override protected void onStop(){super.onStop();if(entry!=null&&exo!=null&&!"live".equals(entry.type)&&!playingCatchup)library.saveProgress(entry,Math.max(0,exo.getCurrentPosition()),Math.max(0,exo.getDuration()));}
    @Override protected void onDestroy(){destroyed=true;ui.removeCallbacksAndMessages(null);bg.shutdownNow();if(exo!=null){exo.release();exo=null;}super.onDestroy();}
}
''')

# Settings About version.
settings=app/"src/main/java/com/robertalt/raiptv/SettingsActivity.java"
sx=settings.read_text().replace('NenoTV Free · 0.12.6','NenoTV Free · 0.12.7').replace('NenoTV Free 0.12.6','NenoTV Free 0.12.7')
settings.write_text(sx)


# v0.12.8 correctness + visual polish.

def replace_method_final(src, start_sig, next_sig, body):
    a=src.find(start_sig)
    if a<0:return src
    b=src.find(next_sig,a+len(start_sig))
    if b<0:return src
    return src[:a]+body+src[b:]

# ---------- Free EPG: list-only + one correct Now/Next algorithm ----------
s=s.replace('epgModeBar.setVisibility(View.VISIBLE);','epgModeBar.setVisibility(View.GONE);')
s=s.replace('setHeroHeight(heroHeight());collapseSearch();seriesEpisodeMode=false;latestSearchQuery="";genreButton.setVisibility(View.GONE);sortButton.setVisibility(View.VISIBLE);filterBar.setVisibility(View.VISIBLE);categories.setVisibility(View.VISIBLE);epgModeBar.setVisibility(View.GONE);',
            'setHeroHeight(dp(145));collapseSearch();seriesEpisodeMode=false;latestSearchQuery="";genreButton.setVisibility(View.GONE);sortButton.setVisibility(View.GONE);filterBar.setVisibility(View.VISIBLE);categories.setVisibility(View.VISIBLE);epgModeBar.setVisibility(View.GONE);')

s=replace_method_final(s,'    void setEpgMode(boolean gridMode){','\n    void showEpgByMode(',r'''    void setEpgMode(boolean gridMode){epgGridMode=false;if("epg".equals(section))showEpgByMode(all);}
''')
s=replace_method_final(s,'    void showEpgByMode(List<MediaEntry>channels){','\n    void renderEpgBoard(',r'''    void showEpgByMode(List<MediaEntry>channels){epgGridMode=false;epgModeBar.setVisibility(View.GONE);showEpgList();epgAdapter.configure(provider,profileKey());epgAdapter.set(channels==null?Collections.emptyList():channels);}
''')

epg=app/"src/main/java/com/robertalt/raiptv/EpgAdapter.java"
epg.write_text(r'''package com.robertalt.raiptv;

import android.content.*;import android.os.*;import android.view.*;import android.widget.*;
import com.robertalt.raiptv.model.*;import com.robertalt.raiptv.provider.Provider;import com.robertalt.raiptv.storage.EpgStore;
import java.util.*;import java.util.concurrent.*;

public class EpgAdapter extends BaseAdapter {
    private final LayoutInflater in;private final EpgStore store;private final Context context;private final Handler ui=new Handler(Looper.getMainLooper());
    private final ExecutorService exec=Executors.newFixedThreadPool(2);private final Map<String,List<EpgEntry>>cache=new ConcurrentHashMap<>();private final Set<String>loading=ConcurrentHashMap.newKeySet();
    private List<MediaEntry>all=new ArrayList<>(),shown=new ArrayList<>();private Provider provider;private String profileKey="";private volatile boolean disposed=false;
    private final Runnable autoRefresh=new Runnable(){public void run(){if(disposed)return;cache.clear();loading.clear();notifyDataSetChanged();ui.postDelayed(this,10*60*1000L);}};
    static class H{ImageView logo;TextView channel,now,next;ProgressBar progress;}
    public EpgAdapter(Context c,EpgStore s){context=c;in=LayoutInflater.from(c);store=s;}
    public void configure(Provider p,String key){disposed=false;provider=p;profileKey=key==null?"":key;cache.clear();loading.clear();ui.removeCallbacks(autoRefresh);ui.postDelayed(autoRefresh,10*60*1000L);}
    public void set(List<MediaEntry>x){all=x==null?new ArrayList<>():new ArrayList<>(x);shown=new ArrayList<>(all);notifyDataSetChanged();}
    public void filter(String q){String z=q==null?"":q.toLowerCase(Locale.ROOT).trim();shown=new ArrayList<>();for(MediaEntry e:all){if(e==null)continue;String n=e.name==null?"":e.name.toLowerCase(Locale.ROOT);String g=e.group==null?"":e.group.toLowerCase(Locale.ROOT);if(z.isEmpty()||n.contains(z)||g.contains(z))shown.add(e);}notifyDataSetChanged();}
    public int getCount(){return shown.size();}public MediaEntry getItem(int i){return shown.get(i);}public long getItemId(int i){return i;}
    public View getView(int i,View v,ViewGroup p){
        H h;if(v==null){v=in.inflate(R.layout.row_epg,p,false);h=new H();h.logo=v.findViewById(R.id.epgLogo);h.channel=v.findViewById(R.id.epgChannel);h.now=v.findViewById(R.id.epgNow);h.next=v.findViewById(R.id.epgNext);h.progress=v.findViewById(R.id.epgProgress);v.setTag(h);}else h=(H)v.getTag();
        MediaEntry e=getItem(i);h.channel.setText(DisplayText.title(e));MediaRowAdapter.loadArtwork(h.logo,e.logo,e.name,180,180);String k=key(e);h.now.setTag(k);h.next.setTag(k);h.now.setText(UiText.t(context,"now")+"  "+UiText.t(context,"epg_loading"));h.next.setText("");h.progress.setProgress(0);
        List<EpgEntry>rows=cache.get(k);if(rows!=null)bind(h,rows,k);else load(e,k);return v;
    }
    private String key(MediaEntry e){return profileKey+"|"+e.uniqueKey();}
    private void load(MediaEntry e,String k){
        if(disposed||provider==null||e==null||!loading.add(k))return;
        exec.execute(()->{try{List<EpgEntry>r=store.get(k);if(r.isEmpty()){r=provider.epgEntries(e,12);if(!r.isEmpty())store.put(k,r);}cache.put(k,r);}catch(Exception ex){cache.put(k,Collections.emptyList());}finally{loading.remove(k);if(!disposed)ui.post(()->{if(!disposed)notifyDataSetChanged();});}});
    }
    private List<EpgEntry>normal(List<EpgEntry>rows){
        ArrayList<EpgEntry>x=new ArrayList<>();HashSet<String>seen=new HashSet<>();
        if(rows!=null)for(EpgEntry e:rows){if(e==null||e.startEpoch<=0||e.endEpoch<=e.startEpoch)continue;String k=e.startEpoch+"|"+e.endEpoch+"|"+(e.title==null?"":e.title.trim().toLowerCase(Locale.ROOT));if(seen.add(k))x.add(e);}
        x.sort((a,b)->{int q=Long.compare(a.startEpoch,b.startEpoch);return q!=0?q:Long.compare(a.endEpoch,b.endEpoch);});return x;
    }
    private void bind(H h,List<EpgEntry>rows,String key){
        List<EpgEntry>r=normal(rows);if(r.isEmpty()){h.now.setText(UiText.t(context,"no_epg"));h.next.setText("");h.progress.setProgress(0);return;}
        long now=System.currentTimeMillis()/1000L;EpgEntry cur=null,next=null;
        for(EpgEntry e:r)if(e.startEpoch<=now&&now<e.endEpoch&&(cur==null||e.startEpoch>cur.startEpoch))cur=e;
        if(cur!=null){
            long floor=cur.endEpoch-60L;
            for(EpgEntry e:r)if(e.startEpoch>=floor&&e.startEpoch>cur.startEpoch){next=e;break;}
        }else{
            for(EpgEntry e:r)if(e.startEpoch>now){next=e;break;}
        }
        if(cur!=null){
            h.now.setText(UiText.t(context,"now")+"  "+time(cur)+cur.title);
            long span=cur.endEpoch-cur.startEpoch,done=Math.max(0,Math.min(span,now-cur.startEpoch));h.progress.setProgress(span>0?(int)Math.min(100,(done*100)/span):0);
        }else{h.now.setText(UiText.t(context,"no_epg"));h.progress.setProgress(0);}
        if(next!=null)h.next.setText(UiText.t(context,"next")+"  "+time(next)+next.title);else h.next.setText("");
    }
    private String time(EpgEntry e){String r=e.range();return r.isEmpty()?"":r+"  ";}
    public void shutdown(){disposed=true;ui.removeCallbacks(autoRefresh);loading.clear();cache.clear();exec.shutdownNow();}
}
''')

# Taller, calmer EPG rows; yellow means active/progress, not red.
row=app/"src/main/res/layout/row_epg.xml"
row.write_text(r'''<?xml version="1.0" encoding="utf-8"?>
<LinearLayout xmlns:android="http://schemas.android.com/apk/res/android"
 android:layout_width="match_parent" android:layout_height="112dp" android:orientation="horizontal"
 android:gravity="center_vertical" android:padding="10dp" android:background="@drawable/bg_card">
 <ImageView android:id="@+id/epgLogo" android:layout_width="70dp" android:layout_height="70dp" android:scaleType="centerInside" android:background="#252A33"/>
 <LinearLayout android:layout_width="0dp" android:layout_height="match_parent" android:layout_weight="1" android:orientation="vertical" android:gravity="center_vertical" android:paddingLeft="12dp">
  <TextView android:id="@+id/epgChannel" android:layout_width="match_parent" android:layout_height="wrap_content" android:textColor="@color/text" android:textSize="15sp" android:textStyle="bold" android:maxLines="1" android:ellipsize="end"/>
  <TextView android:id="@+id/epgNow" android:layout_width="match_parent" android:layout_height="wrap_content" android:textColor="@color/text" android:textSize="13sp" android:maxLines="1" android:ellipsize="end" android:paddingTop="6dp"/>
  <ProgressBar android:id="@+id/epgProgress" style="@android:style/Widget.ProgressBar.Horizontal" android:layout_width="match_parent" android:layout_height="4dp" android:layout_marginTop="5dp" android:max="100" android:progressTint="#FFD400" android:progressBackgroundTint="#343A44"/>
  <TextView android:id="@+id/epgNext" android:layout_width="match_parent" android:layout_height="wrap_content" android:textColor="@color/muted" android:textSize="12sp" android:maxLines="1" android:ellipsize="end" android:paddingTop="5dp"/>
 </LinearLayout>
</LinearLayout>
''')

# ---------- Series correctness ----------
series_select=r'''    void select(MediaEntry e){
        if(e.type.equals("series")){
            stopCachePaging();final int token=nextRequest();busy(true,T("episodes_loading"));
            exec.execute(()->{try{
                List<MediaEntry>raw=provider.seriesEpisodes(e);ArrayList<MediaEntry>x=new ArrayList<>(raw==null?Collections.emptyList():raw);
                for(MediaEntry ep:x)if(ep.categoryId==null||ep.categoryId.isEmpty())ep.categoryId=e.categoryId;
                x.sort((a,b)->{int as=a.season>0?a.season:9999,bs=b.season>0?b.season:9999;if(as!=bs)return Integer.compare(as,bs);int ae=a.episode>0?a.episode:9999,be=b.episode>0?b.episode:9999;if(ae!=be)return Integer.compare(ae,be);return safe(a.name).compareToIgnoreCase(safe(b.name));});
                runOnUiThread(()->{if(current(token)){
                    seriesEpisodeMode=true;showMediaList();categories.setVisibility(View.GONE);genreButton.setVisibility(View.GONE);all=x;adapter.set(x);
                    MediaEntry next=nextEpisodeFor(e,x);heroTitle.setText(DisplayText.title(e));
                    if(next!=null){
                        selectedHero=next;boolean resume=library.progressPercent(next)>0&&library.progressPercent(next)<90;boolean history=hasSeriesHistory(e,x);
                        String action=resume?T("continue"):(history?T("next_episode"):startSeriesLabel());
                        heroSubtitle.setText(action+" · S"+Math.max(1,next.season)+"E"+Math.max(1,next.episode)+" · "+episodeShortTitle(next));
                        heroAction.setText("▶  "+action);heroAction.setVisibility(View.VISIBLE);heroInfo.setVisibility(View.VISIBLE);
                    }else{selectedHero=e;heroSubtitle.setText(T("no_episodes"));heroAction.setVisibility(View.GONE);heroInfo.setVisibility(View.VISIBLE);}
                    busy(false,x.size()+" "+T("episodes"));
                }});
            }catch(Exception ex){runOnUiThread(()->{if(current(token))busy(false,T("series_error")+": "+friendly(ex));});}});
            return;
        }
        play(e);
    }
    boolean sameSeries(MediaEntry series,MediaEntry ep){return ep!=null&&"episode".equals(ep.type)&&((series.seriesId!=null&&!series.seriesId.isEmpty()&&series.seriesId.equals(ep.seriesId))||(!safe(series.name).isEmpty()&&safe(series.name).equals(safe(ep.seriesTitle))));}
    boolean hasSeriesHistory(MediaEntry series,List<MediaEntry>episodes){for(MediaEntry ep:episodes)if(library.progressPercent(ep)>0)return true;for(MediaEntry r:library.recent())if(sameSeries(series,r))return true;return false;}
    String startSeriesLabel(){String l=SettingsStore.language(this);if("nl".equals(l))return "Start serie";if("de".equals(l))return "Serie starten";if("fr".equals(l))return "Commencer la série";if("es".equals(l))return "Iniciar serie";if("it".equals(l))return "Inizia serie";if("pt".equals(l))return "Iniciar série";if("tr".equals(l))return "Diziyi başlat";if("pl".equals(l))return "Rozpocznij serial";if("ar".equals(l))return "بدء المسلسل";return "Start series";}
    String episodeShortTitle(MediaEntry ep){String n=DisplayText.title(ep);try{String z=n.replaceFirst("(?i)^.*?S\\\\d{1,2}E\\\\d{1,3}\\\\s*[-–:]?\\\\s*","").trim();if(!z.isEmpty())return z;}catch(Exception ignored){}return n;}
'''
s=replace_method_final(s,'    void select(MediaEntry e){','\n    MediaEntry nextEpisodeFor(',series_select)

next_method=r'''    MediaEntry nextEpisodeFor(MediaEntry series,List<MediaEntry>episodes){
        if(episodes==null||episodes.isEmpty())return null;
        for(MediaEntry ep:episodes){int pct=library.progressPercent(ep);if(pct>0&&pct<90)return ep;}
        MediaEntry last=null;
        for(MediaEntry r:library.recent())if(sameSeries(series,r)){last=r;break;}
        if(last!=null){
            for(int i=0;i<episodes.size();i++){MediaEntry ep=episodes.get(i);if(ep.season==last.season&&ep.episode==last.episode){if(i+1<episodes.size())return episodes.get(i+1);return null;}}
        }
        return episodes.get(0);
    }
'''
s=replace_method_final(s,'    MediaEntry nextEpisodeFor(MediaEntry series,List<MediaEntry> episodes){','\n    void setHeroHeight(',next_method)

# Episode rows: show the episode title once; S/E metadata below it; yellow chip.
row_media=app/"src/main/res/layout/row_media.xml"
rm=row_media.read_text().replace('android:textColor="@color/accent2"','android:textColor="#FFD400"')
row_media.write_text(rm)

media=app/"src/main/java/com/robertalt/raiptv/MediaRowAdapter.java"
mx=media.read_text()
old='MediaEntry e=getItem(i);h.name.setText(DisplayText.title(e));h.fav.setText(store.isFavorite(e)?"★":"");String meta=DisplayText.shortMeta(e);'
new='MediaEntry e=getItem(i);h.name.setText("episode".equals(e.type)?episodeTitle(e):DisplayText.title(e));h.fav.setText(store.isFavorite(e)?"★":"");String meta="episode".equals(e.type)?episodeMeta(e):DisplayText.shortMeta(e);'
mx=mx.replace(old,new)
if 'String episodeTitle(MediaEntry e)' not in mx:
    mx=mx.replace('    String typeLabel(String t){',r'''    String episodeTitle(MediaEntry e){String n=DisplayText.title(e);try{String z=n.replaceFirst("(?i)^.*?S\\d{1,2}E\\d{1,3}\\s*[-–:]?\\s*","").trim();if(!z.isEmpty())return z;}catch(Exception ignored){}return n;}
    String episodeMeta(MediaEntry e){ArrayList<String>x=new ArrayList<>();if(e.season>0&&e.episode>0)x.add("S"+e.season+"E"+e.episode);if(e.year!=null&&!e.year.trim().isEmpty())x.add(e.year.trim());if(e.group!=null&&!e.group.trim().isEmpty())x.add(e.group.trim());return android.text.TextUtils.join(" · ",x);}
    String typeLabel(String t){''')
media.write_text(mx)

# ---------- Detail popup ----------
# Score 0 means unavailable, not a genuine zero rating.
s=s.replace('String score=d.scoreLabel();if(!score.isEmpty())addInfoLine(box,T("score")+": "+score,true);',
            'String score=d.scoreLabel();if(!score.isEmpty()&&!score.equals("0")&&!score.equals("0.0")&&!score.equals("0,0"))addInfoLine(box,T("score")+": "+score,true);')

# ---------- Artwork fallback ----------
grid=app/"src/main/java/com/robertalt/raiptv/MediaGridAdapter.java"
gx=grid.read_text()
gx=gx.replace('MediaRowAdapter.loadArtwork(h.poster,e.logo,e.name,isLive?220:210,isLive?130:300);',
              'String artUrl=(e.logo==null||e.logo.trim().isEmpty())?e.backdrop:e.logo;MediaRowAdapter.loadArtwork(h.poster,artUrl,e.name,isLive?220:210,isLive?130:300);')
grid.write_text(gx)

# ---------- Sync counts ----------
# Display unique indexed items instead of raw provider rows, and use locale thousands separators.
s=s.replace('if("live".equals(type))liveCount+=items.size();else if("vod".equals(type))filmCount+=items.size();else seriesCount+=items.size();',
            'int uniqueCount=searchIndex.countSection(key,type);if("live".equals(type))liveCount=uniqueCount;else if("vod".equals(type))filmCount=uniqueCount;else seriesCount=uniqueCount;')
s=s.replace('if("live".equals(type))live=aggregate.size();else if("vod".equals(type))vod=aggregate.size();else series=aggregate.size();',
            'int uniqueCount=searchIndex.countSection(key,type);if("live".equals(type))live=uniqueCount;else if("vod".equals(type))vod=uniqueCount;else series=uniqueCount;')
s=s.replace('String msg=freeUi("library")+" · "+pct+"%\\n"+T("live")+" "+live+"  ·  "+T("movies")+" "+films+"  ·  "+T("series")+" "+series;',
            'String msg=freeUi("library")+" · "+pct+"%\\n"+T("live")+" "+fmtLibraryCount(live)+"  ·  "+T("movies")+" "+fmtLibraryCount(films)+"  ·  "+T("series")+" "+fmtLibraryCount(series);')
if 'String fmtLibraryCount(int n)' not in s:
    anchor='    void showFreeSyncProgress(int done,int total,int live,int films,int series){'
    a=s.find(anchor)
    if a>=0:s=s[:a]+'    String fmtLibraryCount(int n){try{return java.text.NumberFormat.getIntegerInstance(SettingsStore.appLocale(this)).format(n);}catch(Exception e){return String.valueOf(n);}}\\n'+s[a:]

# ---------- Player: fullscreen, auto-hide, consistent yellow/dark controls ----------
pl=app/"src/main/res/layout/activity_player.xml"
pl.write_text(r'''<?xml version="1.0" encoding="utf-8"?>
<FrameLayout xmlns:android="http://schemas.android.com/apk/res/android" xmlns:app="http://schemas.android.com/apk/res-auto"
 android:id="@+id/playerRoot" android:layout_width="match_parent" android:layout_height="match_parent" android:background="#000000">
 <FrameLayout android:id="@+id/vlcLayout" android:layout_width="1dp" android:layout_height="1dp" android:visibility="gone"/>
 <androidx.media3.ui.PlayerView android:id="@+id/media3View" android:layout_width="match_parent" android:layout_height="match_parent" app:use_controller="false"/>
 <FrameLayout android:id="@+id/playerControls" android:layout_width="match_parent" android:layout_height="match_parent" android:clickable="true" android:focusable="true" android:focusableInTouchMode="true">
  <LinearLayout android:layout_width="match_parent" android:layout_height="wrap_content" android:layout_gravity="top" android:orientation="vertical" android:padding="16dp" android:background="#9A000000">
   <TextView android:id="@+id/playerTitle" android:layout_width="match_parent" android:layout_height="wrap_content" android:textColor="#FFFFFF" android:textSize="20sp" android:textStyle="bold" android:maxLines="1" android:ellipsize="end"/>
   <TextView android:id="@+id/playerNowNext" android:layout_width="match_parent" android:layout_height="wrap_content" android:textColor="#FFFFFF" android:textSize="12sp" android:paddingTop="5dp" android:maxLines="2" android:ellipsize="end" android:visibility="gone"/>
   <ProgressBar android:id="@+id/playerEpgProgress" style="?android:attr/progressBarStyleHorizontal" android:layout_width="match_parent" android:layout_height="4dp" android:layout_marginTop="5dp" android:max="100" android:progressTint="#FFD400" android:progressBackgroundTint="#3A3F48" android:visibility="gone"/>
   <TextView android:id="@+id/playerStatus" android:layout_width="match_parent" android:layout_height="wrap_content" android:textColor="#C7CDD6" android:textSize="11sp" android:paddingTop="4dp" android:visibility="gone"/>
  </LinearLayout>
  <LinearLayout android:layout_width="match_parent" android:layout_height="wrap_content" android:layout_gravity="bottom" android:orientation="vertical" android:paddingLeft="16dp" android:paddingRight="16dp" android:paddingTop="18dp" android:paddingBottom="12dp" android:background="@drawable/bg_player_controls">
   <SeekBar android:id="@+id/seekBar" android:layout_width="match_parent" android:layout_height="wrap_content" android:progressTint="#FFD400" android:thumbTint="#FFD400"/>
   <LinearLayout android:layout_width="match_parent" android:layout_height="54dp" android:gravity="center_vertical" android:orientation="horizontal">
    <Button android:id="@+id/channelPrevButton" android:layout_width="64dp" android:layout_height="44dp" android:text="CH−" android:textAllCaps="false" android:textColor="#FFFFFF" android:backgroundTint="#55151A22" android:visibility="gone"/>
    <Button android:id="@+id/rewindButton" android:layout_width="66dp" android:layout_height="44dp" android:layout_marginLeft="5dp" android:text="↶ 10" android:textAllCaps="false" android:textColor="#FFFFFF" android:backgroundTint="#55151A22"/>
    <Button android:id="@+id/playPauseButton" android:layout_width="62dp" android:layout_height="48dp" android:layout_marginLeft="5dp" android:text="❚❚" android:textSize="18sp" android:textColor="#101010" android:backgroundTint="#FFD400"/>
    <Button android:id="@+id/forwardButton" android:layout_width="66dp" android:layout_height="44dp" android:layout_marginLeft="5dp" android:text="10 ↷" android:textAllCaps="false" android:textColor="#FFFFFF" android:backgroundTint="#55151A22"/>
    <Button android:id="@+id/channelNextButton" android:layout_width="64dp" android:layout_height="44dp" android:layout_marginLeft="5dp" android:text="CH+" android:textAllCaps="false" android:textColor="#FFFFFF" android:backgroundTint="#55151A22" android:visibility="gone"/>
    <TextView android:id="@+id/timeText" android:layout_width="0dp" android:layout_height="wrap_content" android:layout_weight="1" android:text="00:00" android:textColor="#FFFFFF" android:textSize="13sp" android:paddingLeft="12dp"/>
   </LinearLayout>
   <HorizontalScrollView android:layout_width="match_parent" android:layout_height="46dp" android:scrollbars="none">
    <LinearLayout android:layout_width="wrap_content" android:layout_height="match_parent" android:gravity="center_vertical" android:orientation="horizontal">
     <Button android:id="@+id/catchupButton" android:layout_width="wrap_content" android:layout_height="40dp" android:text="↶ Terugkijken" android:textAllCaps="false" android:textColor="#FFFFFF" android:backgroundTint="#55151A22" android:visibility="gone"/>
     <Button android:id="@+id/favoriteButton" android:layout_width="48dp" android:layout_height="40dp" android:text="♡" android:textSize="20sp" android:textAllCaps="false" android:textColor="#FFFFFF" android:backgroundTint="#55151A22"/>
     <Button android:id="@+id/audioButton" android:layout_width="wrap_content" android:layout_height="40dp" android:layout_marginLeft="5dp" android:text="Audio" android:textAllCaps="false" android:textColor="#FFFFFF" android:backgroundTint="#55151A22"/>
     <Button android:id="@+id/subtitleButton" android:layout_width="wrap_content" android:layout_height="40dp" android:layout_marginLeft="5dp" android:text="CC" android:textAllCaps="false" android:textColor="#FFFFFF" android:backgroundTint="#55151A22"/>
     <Button android:id="@+id/speedButton" android:layout_width="wrap_content" android:layout_height="40dp" android:layout_marginLeft="5dp" android:text="1.0×" android:textAllCaps="false" android:textColor="#FFFFFF" android:backgroundTint="#55151A22"/>
     <Button android:id="@+id/aspectButton" android:layout_width="wrap_content" android:layout_height="40dp" android:layout_marginLeft="5dp" android:text="Fit" android:textAllCaps="false" android:textColor="#FFFFFF" android:backgroundTint="#55151A22"/>
     <Button android:id="@+id/castRouteButton" android:layout_width="1dp" android:layout_height="1dp" android:visibility="gone"/>
     <Button android:id="@+id/sleepButton" android:layout_width="1dp" android:layout_height="1dp" android:visibility="gone"/>
     <Button android:id="@+id/recordButton" android:layout_width="1dp" android:layout_height="1dp" android:visibility="gone"/>
     <Button android:id="@+id/pipButton" android:layout_width="1dp" android:layout_height="1dp" android:visibility="gone"/>
    </LinearLayout>
   </HorizontalScrollView>
  </LinearLayout>
 </FrameLayout>
</FrameLayout>
''')

player=app/"src/main/java/com/robertalt/raiptv/PlayerActivity.java"
player.write_text(r'''package com.robertalt.raiptv;

import android.app.*;import android.content.res.ColorStateList;import android.graphics.Color;import android.os.*;import android.view.*;import android.widget.*;
import androidx.fragment.app.FragmentActivity;import androidx.media3.common.*;import androidx.media3.exoplayer.ExoPlayer;import androidx.media3.ui.AspectRatioFrameLayout;import androidx.media3.ui.PlayerView;
import com.robertalt.raiptv.model.*;import com.robertalt.raiptv.provider.*;import com.robertalt.raiptv.storage.*;
import java.util.*;import java.util.concurrent.*;

public class PlayerActivity extends FragmentActivity{
 PlayerView media3View;ExoPlayer exo;MediaEntry entry;LibraryStore library;Provider provider;ArrayList<MediaEntry>liveChannels=new ArrayList<>();int liveIndex=-1,retryCount=0,aspectMode=0;boolean playingCatchup=false,controlsVisible=true,userSeeking=false,destroyed=false;float playbackSpeed=1f;
 EpgEntry nowProgramme,nextProgramme;TextView title,status,timeText,nowNext;ProgressBar epgProgress;Button playPause,rewind,forward,audio,subtitle,pip,speed,aspect,sleep,record,favorite,castButton,channelPrev,channelNext,catchup;SeekBar seek;FrameLayout controls,root;Handler ui=new Handler(Looper.getMainLooper());ExecutorService bg=Executors.newSingleThreadExecutor();
 String T(String k){return UiText.t(this,k);}Runnable tick=new Runnable(){public void run(){if(destroyed)return;updateProgress();updateEpgProgress();ui.postDelayed(this,500);}};Runnable autoHide=()->hideControls();

 @Override public void onCreate(Bundle b){super.onCreate(b);CrashGuard.install(this);getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);setContentView(R.layout.activity_player);UiText.applyDirection(this);hideSystemUi();library=new LibraryStore(this);
  root=findViewById(R.id.playerRoot);media3View=findViewById(R.id.media3View);controls=findViewById(R.id.playerControls);title=findViewById(R.id.playerTitle);status=findViewById(R.id.playerStatus);timeText=findViewById(R.id.timeText);nowNext=findViewById(R.id.playerNowNext);epgProgress=findViewById(R.id.playerEpgProgress);playPause=findViewById(R.id.playPauseButton);rewind=findViewById(R.id.rewindButton);forward=findViewById(R.id.forwardButton);audio=findViewById(R.id.audioButton);subtitle=findViewById(R.id.subtitleButton);pip=findViewById(R.id.pipButton);seek=findViewById(R.id.seekBar);speed=findViewById(R.id.speedButton);aspect=findViewById(R.id.aspectButton);sleep=findViewById(R.id.sleepButton);record=findViewById(R.id.recordButton);favorite=findViewById(R.id.favoriteButton);castButton=findViewById(R.id.castRouteButton);channelPrev=findViewById(R.id.channelPrevButton);channelNext=findViewById(R.id.channelNextButton);catchup=findViewById(R.id.catchupButton);
  entry=(MediaEntry)getIntent().getSerializableExtra("media");if(entry==null){finish();return;}title.setText(DisplayText.title(entry));wire();applyButtonFocus();updateModeUi();startPlayer();if("live".equals(entry.type))loadLiveContext();ui.post(tick);showControls();
 }

 void hideSystemUi(){getWindow().setStatusBarColor(Color.TRANSPARENT);getWindow().setNavigationBarColor(Color.BLACK);getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY|View.SYSTEM_UI_FLAG_FULLSCREEN|View.SYSTEM_UI_FLAG_HIDE_NAVIGATION|View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN|View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION|View.SYSTEM_UI_FLAG_LAYOUT_STABLE);}
 void wire(){
  root.setOnClickListener(v->toggleControls());media3View.setOnClickListener(v->toggleControls());controls.setOnClickListener(v->showControls());
  playPause.setOnClickListener(v->{showControls();if(exo==null)return;if(exo.isPlaying())exo.pause();else exo.play();updatePlayIcon();});
  rewind.setOnClickListener(v->{showControls();if(exo!=null)exo.seekTo(Math.max(0,exo.getCurrentPosition()-10000));});forward.setOnClickListener(v->{showControls();if(exo!=null)exo.seekTo(exo.getCurrentPosition()+10000);});
  channelPrev.setOnClickListener(v->{showControls();switchChannel(-1);});channelNext.setOnClickListener(v->{showControls();switchChannel(1);});catchup.setOnClickListener(v->{showControls();if(playingCatchup)playLiveEntry();else showCatchup();});
  seek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener(){public void onStartTrackingTouch(SeekBar b){userSeeking=true;showControls();}public void onStopTrackingTouch(SeekBar b){userSeeking=false;if(exo!=null&&exo.getDuration()>0)exo.seekTo((long)(exo.getDuration()*(b.getProgress()/1000f)));showControls();}public void onProgressChanged(SeekBar b,int p,boolean u){}});
  favorite.setOnClickListener(v->{showControls();if(entry!=null){library.toggleFavorite(entry);updateFavorite();}});audio.setOnClickListener(v->{showControls();showTracks(C.TRACK_TYPE_AUDIO);});subtitle.setOnClickListener(v->{showControls();showTracks(C.TRACK_TYPE_TEXT);});speed.setOnClickListener(v->{showControls();showSpeed();});aspect.setOnClickListener(v->{showControls();cycleAspect();});updateFavorite();
 }
 void applyButtonFocus(){for(Button b:new Button[]{channelPrev,channelNext,rewind,forward,audio,subtitle,speed,aspect,catchup,favorite})if(b!=null){ColorStateList c=new ColorStateList(new int[][]{new int[]{android.R.attr.state_focused},new int[]{android.R.attr.state_pressed},new int[]{}},new int[]{0xFFFFD400,0xFFFFD400,0xFF202630});b.setBackgroundTintList(c);b.setOnFocusChangeListener((v,on)->{((Button)v).setTextColor(on?0xFF101010:0xFFFFFFFF);if(on)showControls();});}playPause.setBackgroundTintList(ColorStateList.valueOf(0xFFFFD400));playPause.setTextColor(0xFF101010);}
 void updateModeUi(){boolean live="live".equals(entry.type)&&!playingCatchup;channelPrev.setVisibility(live?View.VISIBLE:View.GONE);channelNext.setVisibility(live?View.VISIBLE:View.GONE);rewind.setVisibility(live?View.GONE:View.VISIBLE);forward.setVisibility(live?View.GONE:View.VISIBLE);seek.setVisibility(live?View.GONE:View.VISIBLE);timeText.setVisibility(live?View.GONE:View.VISIBLE);speed.setVisibility(live?View.GONE:View.VISIBLE);aspect.setVisibility(live?View.GONE:View.VISIBLE);}
 void toggleControls(){if(controlsVisible)hideControls();else showControls();}
 void showControls(){controlsVisible=true;controls.setVisibility(View.VISIBLE);ui.removeCallbacks(autoHide);ui.postDelayed(autoHide,3500);hideSystemUi();}
 void hideControls(){if(userSeeking)return;controlsVisible=false;controls.setVisibility(View.GONE);hideSystemUi();}
 void showStatus(String x){if(x==null||x.trim().isEmpty()){status.setVisibility(View.GONE);return;}status.setText(x);status.setVisibility(View.VISIBLE);}

 Provider makeProvider(){try{Profile p=new SecureProfileStore(this).load();if(p==null)return null;Provider q=p.type==Profile.Type.XTREAM?new XtreamProvider(p):new M3uProvider(p,SettingsStore.primaryLanguage(this));if(p.type==Profile.Type.M3U)q.authenticate();return q;}catch(Exception e){return null;}}
 void loadLiveContext(){nowNext.setVisibility(View.VISIBLE);epgProgress.setVisibility(View.VISIBLE);bg.execute(()->{provider=makeProvider();if(provider==null)return;try{List<MediaEntry>rows=provider.items("live",(entry.categoryId==null||entry.categoryId.isEmpty())?"all":entry.categoryId);liveChannels=new ArrayList<>(rows);for(int i=0;i<liveChannels.size();i++){MediaEntry x=liveChannels.get(i);if(x.uniqueKey().equals(entry.uniqueKey())||(!entry.id.isEmpty()&&entry.id.equals(x.id))){liveIndex=i;break;}}}catch(Exception ignored){}loadNowNext();});}
 List<EpgEntry>normalizeEpg(List<EpgEntry>rows){ArrayList<EpgEntry>x=new ArrayList<>();HashSet<String>seen=new HashSet<>();if(rows!=null)for(EpgEntry e:rows){if(e==null||e.startEpoch<=0||e.endEpoch<=e.startEpoch)continue;String k=e.startEpoch+"|"+e.endEpoch+"|"+(e.title==null?"":e.title);if(seen.add(k))x.add(e);}x.sort((a,b)->Long.compare(a.startEpoch,b.startEpoch));return x;}
 void loadNowNext(){if(provider==null||entry==null||!"live".equals(entry.type))return;final String key=entry.uniqueKey();try{List<EpgEntry>rows=normalizeEpg(provider.epgEntries(entry,12));long now=System.currentTimeMillis()/1000L;EpgEntry cur=null,nxt=null;for(EpgEntry e:rows)if(e.startEpoch<=now&&now<e.endEpoch&&(cur==null||e.startEpoch>cur.startEpoch))cur=e;if(cur!=null){long floor=cur.endEpoch-60L;for(EpgEntry e:rows)if(e.startEpoch>=floor&&e.startEpoch>cur.startEpoch){nxt=e;break;}}final EpgEntry fc=cur,fn=nxt;final boolean canCatch=entry.catchup;runOnUiThread(()->{if(destroyed||entry==null||!key.equals(entry.uniqueKey()))return;nowProgramme=fc;nextProgramme=fn;renderNowNext();catchup.setVisibility(canCatch?View.VISIBLE:View.GONE);});}catch(Exception ignored){runOnUiThread(()->{if(!destroyed){nowNext.setText(T("no_epg"));epgProgress.setProgress(0);}});}}
 void renderNowNext(){StringBuilder x=new StringBuilder();if(nowProgramme!=null){x.append(T("now")).append(" · ");String r=nowProgramme.range();if(!r.isEmpty())x.append(r).append(" · ");x.append(nowProgramme.title);}if(nextProgramme!=null){if(x.length()>0)x.append("\n");x.append(T("next")).append(" · ");String r=nextProgramme.range();if(!r.isEmpty())x.append(r).append(" · ");x.append(nextProgramme.title);}nowNext.setText(x.length()==0?T("no_epg"):x.toString());updateEpgProgress();}
 void updateEpgProgress(){if(epgProgress==null||nowProgramme==null){if(epgProgress!=null)epgProgress.setProgress(0);return;}long n=System.currentTimeMillis()/1000L,span=nowProgramme.endEpoch-nowProgramme.startEpoch;if(span>0&&n>=nowProgramme.startEpoch){epgProgress.setProgress((int)Math.max(0,Math.min(100,((n-nowProgramme.startEpoch)*100)/span)));if(n>=nowProgramme.endEpoch&&provider!=null)bg.execute(this::loadNowNext);}}
 void switchChannel(int d){if(liveChannels==null||liveChannels.isEmpty()||liveIndex<0)return;int n=liveChannels.size();liveIndex=(liveIndex+d+n)%n;entry=liveChannels.get(liveIndex);playingCatchup=false;title.setText(DisplayText.title(entry));library.recent(entry);updateFavorite();updateModeUi();playLiveEntry();if(provider!=null)bg.execute(this::loadNowNext);}
 ArrayList<String>streamUrls(MediaEntry e){ArrayList<String>u=new ArrayList<>();if(e!=null&&e.candidates!=null)u.addAll(e.candidates);if(u.isEmpty()&&e!=null&&e.url!=null&&!e.url.isEmpty())u.add(e.url);return u;}
 void startPlayer(){playLiveEntry();}
 void playLiveEntry(){playingCatchup=false;updateModeUi();if(catchup!=null)catchup.setText("↶ "+catchupLabel());ArrayList<String>u=streamUrls(entry);if(u.isEmpty()){showStatus(T("no_stream_url"));return;}playUrl(u.get(0),!"live".equals(entry.type));library.recent(entry);}
 void playUrl(String url,boolean seekable){if(exo==null){exo=new ExoPlayer.Builder(this).build();media3View.setPlayer(exo);exo.addListener(new Player.Listener(){@Override public void onPlaybackStateChanged(int state){if(state==Player.STATE_READY){retryCount=0;showStatus("");ui.postDelayed(autoHide,1200);}else if(state==Player.STATE_ENDED&&playingCatchup)playLiveEntry();}@Override public void onPlayerError(PlaybackException e){retryStream();}});}exo.setMediaItem(MediaItem.fromUri(url));exo.prepare();if(seekable&&entry!=null&&!"live".equals(entry.type)){long r=library.progress(entry);if(r>10000)exo.seekTo(r);}exo.play();showStatus("");updatePlayIcon();}
 void showCatchup(){if(provider==null||entry==null||!entry.catchup)return;showStatus(catchupLoading());final String key=entry.uniqueKey();bg.execute(()->{try{List<EpgEntry>rows=provider.archiveEntries(entry,24);ArrayList<EpgEntry>usable=new ArrayList<>();ArrayList<String>labels=new ArrayList<>();for(EpgEntry e:rows){String u=provider.catchupUrl(entry,e);if(u!=null&&!u.isEmpty()){usable.add(e);labels.add((e.range().isEmpty()?"":e.range()+"  ")+e.title);}}runOnUiThread(()->{if(destroyed||entry==null||!key.equals(entry.uniqueKey()))return;showStatus("");if(usable.isEmpty()){Toast.makeText(this,noCatchup(),Toast.LENGTH_SHORT).show();return;}new AlertDialog.Builder(this).setTitle(catchupLabel()).setItems(labels.toArray(new String[0]),(d,w)->playArchive(usable.get(w))).setNegativeButton(T("close"),null).show();});}catch(Exception e){runOnUiThread(()->{showStatus("");Toast.makeText(this,noCatchup(),Toast.LENGTH_SHORT).show();});}});}
 void playArchive(EpgEntry p){if(provider==null)return;try{String u=provider.catchupUrl(entry,p);if(u==null||u.isEmpty())return;playingCatchup=true;updateModeUi();title.setText(DisplayText.title(entry)+" · "+p.title);catchup.setText("● LIVE");playUrl(u,true);}catch(Exception ignored){}}
 String catchupLabel(){String l=SettingsStore.language(this);if("nl".equals(l))return "Terugkijken";if("de".equals(l))return "Nachholen";if("fr".equals(l))return "Replay";if("es".equals(l))return "Repetición";if("it".equals(l))return "Replay";if("pt".equals(l))return "Rever";if("tr".equals(l))return "Geri izle";if("pl".equals(l))return "Cofnij";if("ar".equals(l))return "إعادة";return "Catch-up";}
 String catchupLoading(){return "nl".equals(SettingsStore.language(this))?"Terugkijkprogramma’s laden…":"Loading catch-up…";}String noCatchup(){return "nl".equals(SettingsStore.language(this))?"Geen terugkijkprogramma’s beschikbaar":"No catch-up programmes available";}
 void retryStream(){if(exo==null||destroyed)return;if(retryCount>=2){showStatus(streamText(false));return;}retryCount++;showStatus(streamText(true));showControls();final int a=retryCount;ui.postDelayed(()->{if(exo==null||destroyed)return;try{exo.seekToDefaultPosition();exo.prepare();exo.play();}catch(Throwable ignored){}},700L*a);}
 String streamText(boolean retry){String l=SettingsStore.language(this);if("nl".equals(l))return retry?"Stream opnieuw verbinden…":"Stream tijdelijk niet beschikbaar";return retry?"Reconnecting stream…":"Stream temporarily unavailable";}
 void updateProgress(){if(exo==null)return;long pos=Math.max(0,exo.getCurrentPosition()),dur=Math.max(0,exo.getDuration());if(!userSeeking&&dur>0)seek.setProgress((int)Math.min(1000,pos*1000/dur));timeText.setText(fmt(pos)+(dur>0?" / "+fmt(dur):""));if(entry!=null&&!"live".equals(entry.type)&&!playingCatchup&&pos>5000)library.saveProgress(entry,pos,dur);updatePlayIcon();}
 String fmt(long ms){long s=Math.max(0,ms/1000),m=s/60,h=m/60;return h>0?String.format(Locale.ROOT,"%d:%02d:%02d",h,m%60,s%60):String.format(Locale.ROOT,"%02d:%02d",m,s%60);}
 void updatePlayIcon(){playPause.setText(exo!=null&&exo.isPlaying()?"❚❚":"▶");}void updateFavorite(){if(favorite!=null&&entry!=null)favorite.setText(library.isFavorite(entry)?"♥":"♡");}
 void showSpeed(){final float[]r={.75f,1f,1.25f,1.5f,2f};String[]l={"0.75×","1.0×","1.25×","1.5×","2.0×"};new AlertDialog.Builder(this).setTitle(T("speed_title")).setItems(l,(d,w)->{playbackSpeed=r[w];if(exo!=null)exo.setPlaybackSpeed(playbackSpeed);speed.setText(l[w]);}).show();}
 void cycleAspect(){aspectMode=(aspectMode+1)%3;media3View.setResizeMode(aspectMode==0?AspectRatioFrameLayout.RESIZE_MODE_FIT:aspectMode==1?AspectRatioFrameLayout.RESIZE_MODE_FILL:AspectRatioFrameLayout.RESIZE_MODE_ZOOM);}
 void showTracks(int type){if(exo==null)return;Tracks tr=exo.getCurrentTracks();ArrayList<String>names=new ArrayList<>();ArrayList<TrackSelectionOverride>picks=new ArrayList<>();for(Tracks.Group g:tr.getGroups()){if(g.getType()!=type)continue;for(int i=0;i<g.length;i++){Format f=g.getTrackFormat(i);String n=f.label!=null?f.label:(f.language!=null?SettingsStore.displayLanguage(this,f.language):T(type==C.TRACK_TYPE_AUDIO?"audio":"subtitles"));names.add(n);picks.add(new TrackSelectionOverride(g.getMediaTrackGroup(),Collections.singletonList(i)));}}if(names.isEmpty()){Toast.makeText(this,type==C.TRACK_TYPE_AUDIO?T("no_audio_tracks"):T("no_subtitles"),Toast.LENGTH_SHORT).show();return;}new AlertDialog.Builder(this).setTitle(type==C.TRACK_TYPE_AUDIO?T("audio_track"):T("subtitles")).setItems(names.toArray(new String[0]),(d,w)->{TrackSelectionParameters.Builder pb=exo.getTrackSelectionParameters().buildUpon();pb.setOverrideForType(picks.get(w));exo.setTrackSelectionParameters(pb.build());}).show();}
 @Override public boolean onKeyDown(int k,KeyEvent e){showControls();if(entry!=null&&"live".equals(entry.type)&&!playingCatchup){if(k==KeyEvent.KEYCODE_DPAD_UP||k==KeyEvent.KEYCODE_CHANNEL_UP){switchChannel(-1);return true;}if(k==KeyEvent.KEYCODE_DPAD_DOWN||k==KeyEvent.KEYCODE_CHANNEL_DOWN){switchChannel(1);return true;}}return super.onKeyDown(k,e);}
 @Override public void onWindowFocusChanged(boolean h){super.onWindowFocusChanged(h);if(h)hideSystemUi();}
 @Override protected void onStop(){super.onStop();if(entry!=null&&exo!=null&&!"live".equals(entry.type)&&!playingCatchup)library.saveProgress(entry,Math.max(0,exo.getCurrentPosition()),Math.max(0,exo.getDuration()));}
 @Override protected void onDestroy(){destroyed=true;ui.removeCallbacksAndMessages(null);bg.shutdownNow();if(exo!=null){exo.release();exo=null;}super.onDestroy();}
}
''')

# Settings version.
settings=app/"src/main/java/com/robertalt/raiptv/SettingsActivity.java"
sx=settings.read_text().replace('NenoTV Free · 0.12.7','NenoTV Free · 0.12.8').replace('NenoTV Free 0.12.7','NenoTV Free 0.12.8')
settings.write_text(sx)

main.write_text(s)
print("Prepared NenoTV Free v0.12.8: provider-order + full background sync")
