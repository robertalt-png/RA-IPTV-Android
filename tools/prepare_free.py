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
s=re.sub(r"versionCode\s+\d+","versionCode 52",s,1)
s=re.sub(r"versionName\s+'[^']+'","versionName '0.12.3'",s,1)
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
print("Prepared NenoTV Free v0.12.3: resilient full sync + clean language")
main.write_text(s)
print("Prepared NenoTV Free v0.12.3: provider-order + full background sync")
