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
s=re.sub(r"versionCode\s+\d+","versionCode 63",s,1)
s=re.sub(r"versionName\s+'[^']+'","versionName '0.12.6.5'",s,1)
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
        addNenoMenuItem(d,box,"NenoTV Pro",()->startActivity(new Intent(this,AccountActivity.class)));
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
    s=s.replace(wire,wire+'        if(proHintButton!=null)proHintButton.setOnClickListener(v->startActivity(new Intent(this,AccountActivity.class)));\n',1)

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
        addNenoMenuItem(d,box,"★ NenoTV Pro",()->startActivity(new Intent(this,AccountActivity.class)));
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
print("Prepared NenoTV Free v0.12.6: resilient full sync + clean language")

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
sx=sx.replace('NenoTV Free · 0.12.5','NenoTV Free · 0.12.6.5').replace('NenoTV Free 0.12.5','NenoTV Free 0.12.6.5')
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


# v0.12.6.1: website/account integration maintenance rebuild.
# Pro entry opens the local account screen and entitlement status refreshes quietly
# against the existing nenotv.com App Bridge at most once every six hours.
if 'void refreshEntitlementQuietly()' not in s:
    helper=r'''    void refreshEntitlementQuietly(){
        try{
            android.content.SharedPreferences p=getSharedPreferences("nenotv_entitlement_sync",MODE_PRIVATE);
            long now=System.currentTimeMillis(),last=p.getLong("last_refresh",0L);
            if(now-last<6L*60L*60L*1000L)return;
            p.edit().putLong("last_refresh",now).apply();
            exec.execute(()->{try{new com.robertalt.raiptv.entitlement.EntitlementClient(this).refresh();}catch(Throwable ignored){}});
        }catch(Throwable ignored){}
    }

'''
    menuPos=s.find('    void showNenoMenu(){')
    if menuPos<0: raise SystemExit("showNenoMenu marker missing for entitlement helper")
    s=s[:menuPos]+helper+s[menuPos:]

wire_anchor='        wire();'
wi=s.find(wire_anchor)
if wi>=0:
    nearby=s[wi:wi+220]
    if 'refreshEntitlementQuietly();' not in nearby:
        s=s[:wi+len(wire_anchor)]+'\n        refreshEntitlementQuietly();'+s[wi+len(wire_anchor):]

# Account activation can return by custom URI now and by HTTPS later.
account=app/"src/main/java/com/robertalt/raiptv/AccountActivity.java"
ax=account.read_text()
old='if(u==null||!"nenotv".equalsIgnoreCase(u.getScheme())||!"activate".equalsIgnoreCase(u.getHost()))return;'
new='if(u==null)return;boolean custom="nenotv".equalsIgnoreCase(u.getScheme())&&"activate".equalsIgnoreCase(u.getHost());boolean web="https".equalsIgnoreCase(u.getScheme())&&"nenotv.com".equalsIgnoreCase(u.getHost())&&"/activate".equalsIgnoreCase(u.getPath());if(!custom&&!web)return;'
if old in ax: ax=ax.replace(old,new)
account.write_text(ax)

# Add HTTPS /activate alongside nenotv://activate.
manifest=app/"src/main/AndroidManifest.xml"
mx=manifest.read_text()
needle='<activity android:name=".AccountActivity" android:exported="true"><intent-filter><action android:name="android.intent.action.VIEW"/><category android:name="android.intent.category.DEFAULT"/><category android:name="android.intent.category.BROWSABLE"/><data android:scheme="nenotv" android:host="activate"/></intent-filter></activity>'
if needle in mx:
    repl='<activity android:name=".AccountActivity" android:exported="true"><intent-filter><action android:name="android.intent.action.VIEW"/><category android:name="android.intent.category.DEFAULT"/><category android:name="android.intent.category.BROWSABLE"/><data android:scheme="nenotv" android:host="activate"/></intent-filter><intent-filter><action android:name="android.intent.action.VIEW"/><category android:name="android.intent.category.DEFAULT"/><category android:name="android.intent.category.BROWSABLE"/><data android:scheme="https" android:host="nenotv.com" android:pathPrefix="/activate"/></intent-filter></activity>'
    mx=mx.replace(needle,repl)
manifest.write_text(mx)


# v0.12.6.2: Free TV-guide crash fix.
# Free must always use the lightweight EPG list. The advanced grid is a Pro path
# and was still the default mode in v0.12.6.1 even though its button was hidden.
s=s.replace('boolean cachePagingActive=false,cachePageLoading=false,autoReindexAfterConnect=false,epgGridMode=true;',
            'boolean cachePagingActive=false,cachePageLoading=false,autoReindexAfterConnect=false,epgGridMode=false;')

s=s.replace('findViewById(R.id.navEpg).setOnClickListener(v->loadEpg());',
            'findViewById(R.id.navEpg).setOnClickListener(v->openEpgSafe());')

old_show='void showEpgByMode(List<MediaEntry>channels){if(epgGridMode)renderEpgBoard(channels);else{showEpgList();epgAdapter.configure(provider,profileKey());epgAdapter.set(channels==null?Collections.emptyList():channels);}}'
new_show='void showEpgByMode(List<MediaEntry>channels){epgGridMode=false;showEpgList();epgAdapter.configure(provider,profileKey());epgAdapter.set(channels==null?Collections.emptyList():channels);}'
if old_show not in s: raise SystemExit("showEpgByMode marker missing")
s=s.replace(old_show,new_show,1)

s=s.replace('epgModeBar.setVisibility(View.VISIBLE);if(epgGridButton!=null)epgGridButton.setVisibility(View.GONE);',
            'epgModeBar.setVisibility(View.GONE);if(epgGridButton!=null)epgGridButton.setVisibility(View.GONE);if(epgListButton!=null)epgListButton.setVisibility(View.GONE);')

if 'void openEpgSafe()' not in s:
    helper=r'''    void openEpgSafe(){
        try{
            epgGridMode=false;
            loadEpg();
        }catch(Throwable e){
            epgGridMode=false;
            try{
                section="epg";
                updateBottomNav("epg");
                if(epgModeBar!=null)epgModeBar.setVisibility(View.GONE);
                if(categories!=null)categories.setVisibility(View.VISIBLE);
                showEpgList();
                if(epgAdapter!=null){epgAdapter.configure(provider,profileKey());epgAdapter.set(Collections.emptyList());}
                busy(false,T("epg_error"));
            }catch(Throwable ignored){}
            Toast.makeText(this,T("epg_error"),Toast.LENGTH_SHORT).show();
        }
    }

'''
    pos=s.find('    void loadEpg(){')
    if pos<0: raise SystemExit("loadEpg marker missing")
    s=s[:pos]+helper+s[pos:]

# Defensive null guards around EPG controls so a layout variant cannot terminate the Activity.
s=s.replace('epgGridButton.setOnClickListener(v->setEpgMode(true));epgListButton.setOnClickListener(v->setEpgMode(false));',
            'if(epgGridButton!=null)epgGridButton.setOnClickListener(v->setEpgMode(true));if(epgListButton!=null)epgListButton.setOnClickListener(v->setEpgMode(false));')

s=s.replace('void setEpgMode(boolean gridMode){epgGridMode=gridMode;epgGridButton.setBackgroundTintList',
            'void setEpgMode(boolean gridMode){epgGridMode=false;if(epgGridButton==null||epgListButton==null){showEpgByMode(all);return;}epgGridButton.setBackgroundTintList')



# v0.12.6.3: Pro/account crash fix.
# AccountActivity exists in source but must also be registered in the final manifest.
manifest=app/"src/main/AndroidManifest.xml"
mx=manifest.read_text()
account_decl='''        <activity
            android:name=".AccountActivity"
            android:exported="true">
            <intent-filter>
                <action android:name="android.intent.action.VIEW"/>
                <category android:name="android.intent.category.DEFAULT"/>
                <category android:name="android.intent.category.BROWSABLE"/>
                <data android:scheme="nenotv" android:host="activate"/>
            </intent-filter>
            <intent-filter>
                <action android:name="android.intent.action.VIEW"/>
                <category android:name="android.intent.category.DEFAULT"/>
                <category android:name="android.intent.category.BROWSABLE"/>
                <data android:scheme="https" android:host="nenotv.com" android:pathPrefix="/activate"/>
            </intent-filter>
        </activity>
'''
if 'android:name=".AccountActivity"' not in mx:
    anchor='        <activity android:name=".ProfileActivity" android:exported="false" />'
    if anchor not in mx: raise SystemExit("ProfileActivity manifest anchor missing")
    mx=mx.replace(anchor,account_decl+anchor,1)
manifest.write_text(mx)


# v0.12.6.4: complete EN/NL localization for the Account & Pro screen.
ui=app/"src/main/java/com/robertalt/raiptv/UiText.java"
ux=ui.read_text()

en_anchor='    private static final Map<String,String> EN=map(new String[][]{'
en_rows=r'''
        {"account_and_pro","Account & NenoTV Pro"},
        {"activate_restore","Activate or restore purchase"},
        {"email_address","Email address"},
        {"order_id_optional","Order ID (optional)"},
        {"activate_pro","Activate / restore Pro"},
        {"request_trial","Request free trial"},
        {"view_pro","View NenoTV Pro"},
        {"refresh_status","Refresh account status"},
        {"this_device","This device"},
        {"device_code","Device code"},
        {"trial_remaining","Trial remaining"},
        {"days","days"},
        {"pro_active","Pro is active"},
        {"devices","devices"},
        {"free_description","Live TV, movies, series, basic TV guide, search and favorites are available in Free."},
        {"checking_status","Checking NenoTV account…"},
        {"status_updated","Account status updated"},
        {"activation_success","NenoTV Pro activated"},
        {"activation_failed","Activation failed"},
        {"server_unavailable","The NenoTV account service is not available yet"},
        {"email_required","Enter your email address"},
'''
if '{"account_and_pro","Account & NenoTV Pro"}' not in ux:
    if en_anchor not in ux: raise SystemExit("EN localization anchor missing")
    ux=ux.replace(en_anchor,en_anchor+en_rows,1)

nl_anchor='    private static final Map<String,String> NL=map(new String[][]{'
nl_rows=r'''
        {"account_and_pro","Account & NenoTV Pro"},
        {"activate_restore","Aankoop activeren of herstellen"},
        {"email_address","E-mailadres"},
        {"order_id_optional","Bestelnummer (optioneel)"},
        {"activate_pro","Pro activeren / herstellen"},
        {"request_trial","Gratis proefperiode aanvragen"},
        {"view_pro","NenoTV Pro bekijken"},
        {"refresh_status","Accountstatus vernieuwen"},
        {"this_device","Dit apparaat"},
        {"device_code","Apparaatcode"},
        {"trial_remaining","Resterende proefperiode"},
        {"days","dagen"},
        {"pro_active","Pro is actief"},
        {"devices","apparaten"},
        {"free_description","Live tv, films, series, basis TV-gids, zoeken en favorieten zijn beschikbaar in Free."},
        {"checking_status","NenoTV-account controleren…"},
        {"status_updated","Accountstatus bijgewerkt"},
        {"activation_success","NenoTV Pro geactiveerd"},
        {"activation_failed","Activeren mislukt"},
        {"server_unavailable","De NenoTV-accountdienst is nog niet beschikbaar"},
        {"email_required","Vul je e-mailadres in"},
'''
if '{"activate_restore","Aankoop activeren of herstellen"}' not in ux:
    if nl_anchor not in ux: raise SystemExit("NL localization anchor missing")
    ux=ux.replace(nl_anchor,nl_anchor+nl_rows,1)

ui.write_text(ux)

main.write_text(s)
print("Prepared NenoTV Free v0.12.6.5: REST fallback + account localization + Pro/EPG fixes")
