from pathlib import Path
r=Path("source/RA_IPTV_Android_v0.1")

def R(path,old,new,label=""):
    p=r/path;s=p.read_text()
    if old not in s: raise SystemExit("missing "+(label or str(path)))
    p.write_text(s.replace(old,new))

R(Path("app/build.gradle"),"versionCode 33","versionCode 34")
R(Path("app/build.gradle"),"versionName '0.9.1'","versionName '0.9.2'")
R(Path("app/src/main/java/com/robertalt/raiptv/SettingsActivity.java"),"Nivaro IPTV Player 0.9.1","Nivaro IPTV Player 0.9.2")

# Allow index transactions to stop quickly and roll back if playback requests memory.
p=r/"app/src/main/java/com/robertalt/raiptv/storage/SearchIndexStore.java";s=p.read_text()
old='''    public synchronized void upsert(String profile,List<MediaEntry> items){
        SQLiteDatabase db=getWritableDatabase(); db.beginTransaction();
        try{ for(MediaEntry e:items) upsertInternal(db,profile,e); db.setTransactionSuccessful(); }finally{db.endTransaction();}
    }
'''
new='''    public synchronized void upsert(String profile,List<MediaEntry> items){
        SQLiteDatabase db=getWritableDatabase(); db.beginTransaction();boolean complete=true;
        try{ for(MediaEntry e:items){if(Thread.currentThread().isInterrupted()){complete=false;break;}upsertInternal(db,profile,e);} if(complete)db.setTransactionSuccessful(); }finally{db.endTransaction();}
    }
'''
if old not in s: raise SystemExit("missing SearchIndexStore upsert")
p.write_text(s.replace(old,new))

# Abort background HTTP reads as soon as their worker is cancelled.
p=r/"app/src/main/java/com/robertalt/raiptv/net/HttpText.java";s=p.read_text()
s=s.replace("import java.io.InputStream;","import java.io.InputStream;\nimport java.io.InterruptedIOException;")
s=s.replace('''        IOException last = null;
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            try {
''','''        IOException last = null;
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            if(Thread.currentThread().isInterrupted())throw new InterruptedIOException("index_cancelled");
            try {
''')
s=s.replace('''            while ((n = input.read(buf)) >= 0) {
                b.write(buf, 0, n);
''','''            while ((n = input.read(buf)) >= 0) {
                if(Thread.currentThread().isInterrupted())throw new InterruptedIOException("index_cancelled");
                b.write(buf, 0, n);
''')
s=s.replace("Nivaro/0.9.1","Nivaro/0.9.2")
p.write_text(s)

# Do not retain large category result lists while VLC/Media3 needs memory.
p=r/"app/src/main/java/com/robertalt/raiptv/provider/XtreamProvider.java";s=p.read_text()
s=s.replace("if(itemCache.size()<=4)return;","if(itemCache.size()<=2)return;")
s=s.replace("int remove=Math.max(0,entries.size()-4);","int remove=Math.max(0,entries.size()-2);")
anchor='''    private void trimItemCache(){
        if(itemCache.size()<=2)return;
        ArrayList<Map.Entry<String,Timed<List<MediaEntry>>>> entries=new ArrayList<>(itemCache.entrySet());
        entries.sort(Comparator.comparingLong(e->e.getValue().at));
        int remove=Math.max(0,entries.size()-2);
        for(int i=0;i<remove;i++)itemCache.remove(entries.get(i).getKey(),entries.get(i).getValue());
    }
'''
if anchor not in s: raise SystemExit("missing Xtream cache anchor")
s=s.replace(anchor,anchor+'''    public void clearTransientItemCache(){itemCache.clear();backdropCache.clear();}

''')
p.write_text(s)

p=r/"app/src/main/java/com/robertalt/raiptv/MainActivity.java";s=p.read_text()
s=s.replace(
'boolean seriesEpisodeMode=false,settingCategories=false,activityPaused=false,autoDefaultGroup=true; volatile boolean indexRefreshRunning=false; volatile int fullLibraryToken=0; String appliedLanguage="",appliedContentLanguage="";',
'boolean seriesEpisodeMode=false,settingCategories=false,activityPaused=false,autoDefaultGroup=true; volatile boolean indexRefreshRunning=false,resumeIndexAfterPlayback=false,indexCategoryBusy=false; volatile Future<?> indexFuture=null; volatile int fullLibraryToken=0; String appliedLanguage="",appliedContentLanguage="";')

s=s.replace("        indexExec.execute(()->{","        indexFuture=indexExec.submit(()->{",1)

old='''                        int total=0;
                        try{
                            List<Category> cats=new ArrayList<>(provider.categories(type));final String preferred=SettingsStore.contentLanguage(this);cats.sort((a,b)->{int x=Integer.compare(ContentLanguage.rankText(a.name,preferred),ContentLanguage.rankText(b.name,preferred));return x!=0?x:safe(a.name).compareToIgnoreCase(safe(b.name));});int startAt=0;String cursor=force?"":SettingsStore.prefs(this).getString(cacheCursorKey(type),"");if(cursor!=null&&!cursor.isEmpty()){for(int ci=0;ci<cats.size();ci++)if(cursor.equals(cats.get(ci).id)){startAt=ci+1;break;}if(startAt>=cats.size())startAt=0;}
                            if(("vod".equals(type)||"series".equals(type))&&isUiAlive()){final int fc=cats.size(),fs=startAt;runOnUiThread(()->showIndexBanner(type,fs,fc,searchIndex.countSection(key,type)));}
                            for(int ci=startAt;ci<cats.size();ci++){
                                Category c=cats.get(ci);if(Thread.currentThread().isInterrupted())break;
                                waitWhilePaused();waitForLibraryLoad();
                                try{
                                    List<MediaEntry>x=provider.items(type,c.id);for(MediaEntry e:x)if(e!=null&&(e.group==null||e.group.trim().isEmpty()))e.group=c.name;
                                    searchIndex.upsert(key,x);
                                    total+=x.size();publishIndexedTop(type);if(("vod".equals(type)||"series".equals(type))&&isUiAlive()){final int fd=ci+1,fc=cats.size(),fi=searchIndex.countSection(key,type);runOnUiThread(()->showIndexBanner(type,fd,fc,fi));}
                                }catch(Exception ignored){}
                                try{SettingsStore.prefs(this).edit().putString(cacheCursorKey(type),safe(c.id)).apply();}catch(Exception ignored){}
                                try{Thread.sleep(15);}catch(InterruptedException ie){Thread.currentThread().interrupt();break;}
                                String q=latestSearchQuery;
                                if(q!=null&&!q.isEmpty()&&isUiAlive())runOnUiThread(()->{if(isUiAlive())searchEverywhere(q);});
                            }
'''
new='''                        int total=searchIndex.countSection(key,type);
                        try{
                            List<Category> cats=new ArrayList<>(provider.categories(type));final String preferred=SettingsStore.contentLanguage(this);cats.sort((a,b)->{int x=Integer.compare(ContentLanguage.rankText(a.name,preferred),ContentLanguage.rankText(b.name,preferred));return x!=0?x:safe(a.name).compareToIgnoreCase(safe(b.name));});int startAt=0;String cursor=force?"":SettingsStore.prefs(this).getString(cacheCursorKey(type),"");if(cursor!=null&&!cursor.isEmpty()){for(int ci=0;ci<cats.size();ci++)if(cursor.equals(cats.get(ci).id)){startAt=ci+1;break;}if(startAt>=cats.size())startAt=0;}
                            if(("vod".equals(type)||"series".equals(type))&&isUiAlive()){final int fc=cats.size(),fs=startAt,fi=total;runOnUiThread(()->showIndexBanner(type,fs,fc,fi));}
                            for(int ci=startAt;ci<cats.size();ci++){
                                Category c=cats.get(ci);if(Thread.currentThread().isInterrupted())break;
                                waitWhilePaused();waitForLibraryLoad();if(activityPaused||Thread.currentThread().isInterrupted())break;
                                boolean indexed=false;List<MediaEntry>x=Collections.emptyList();indexCategoryBusy=true;
                                try{
                                    x=provider.items(type,c.id);if(activityPaused||Thread.currentThread().isInterrupted())break;
                                    for(MediaEntry e:x)if(e!=null&&(e.group==null||e.group.trim().isEmpty()))e.group=c.name;
                                    searchIndex.upsert(key,x);if(Thread.currentThread().isInterrupted())break;
                                    indexed=true;total+=x.size();publishIndexedTop(type);if(("vod".equals(type)||"series".equals(type))&&isUiAlive()){final int fd=ci+1,fc=cats.size(),fi=total;runOnUiThread(()->showIndexBanner(type,fd,fc,fi));}
                                }catch(Exception ignored){}finally{indexCategoryBusy=false;}
                                if(!indexed){if(activityPaused||Thread.currentThread().isInterrupted())break;continue;}
                                try{SettingsStore.prefs(this).edit().putString(cacheCursorKey(type),safe(c.id)).apply();}catch(Exception ignored){}
                                String q=latestSearchQuery;
                                if(q!=null&&!q.isEmpty()&&isUiAlive())runOnUiThread(()->{if(isUiAlive())searchEverywhere(q);});
                            }
'''
if old not in s: raise SystemExit("missing index category loop")
s=s.replace(old,new)

old='''            }catch(Exception ignored){}finally{indexRefreshRunning=false;runOnUiThread(()->{if(isUiAlive())hideIndexBanner("");});}
        });
    }
'''
new='''            }catch(Exception ignored){}finally{indexCategoryBusy=false;indexRefreshRunning=false;indexFuture=null;runOnUiThread(()->{if(isUiAlive())hideIndexBanner("");});}
        });
    }
'''
if old not in s: raise SystemExit("missing index finally")
s=s.replace(old,new)

old='''    void play(MediaEntry e){
        library.recent(e);Intent i=new Intent(this,PlayerActivity.class);i.putExtra("media",e);i.putExtra("profileType",profile.type.name());i.putExtra("bridgeUrl",profile.bridgeUrl);i.putExtra("bridgeToken",profile.bridgeToken);
'''
new='''    void play(MediaEntry e){
        pauseIndexForPlayback();
        library.recent(e);Intent i=new Intent(this,PlayerActivity.class);i.putExtra("media",e);i.putExtra("profileType",profile.type.name());i.putExtra("bridgeUrl",profile.bridgeUrl);i.putExtra("bridgeToken",profile.bridgeToken);
'''
if old not in s: raise SystemExit("missing play start")
s=s.replace(old,new)
s=s.replace('''        activityPaused=true;
        try{startActivity(i);}catch(RuntimeException ex){activityPaused=false;throw ex;}
    }
''','''        try{startActivity(i);}catch(RuntimeException ex){activityPaused=false;resumeIndexAfterPlayback=true;resumeIndexSoon();throw ex;}
    }
    void pauseIndexForPlayback(){
        activityPaused=true;resumeIndexAfterPlayback=indexRefreshRunning;
        Future<?> f=indexFuture;if(f!=null&&!f.isDone())f.cancel(true);
        try{if(provider instanceof XtreamProvider)((XtreamProvider)provider).clearTransientItemCache();}catch(Exception ignored){}
        HERO_CACHE.evictAll();MediaRowAdapter.clearArtworkCache();if(heroImage!=null)heroImage.setImageDrawable(null);
    }
    void resumeIndexSoon(){
        if(!resumeIndexAfterPlayback||provider==null)return;
        ui.postDelayed(new Runnable(){@Override public void run(){if(!isUiAlive()||provider==null)return;if(indexRefreshRunning){ui.postDelayed(this,250);return;}resumeIndexAfterPlayback=false;refreshSearchIndex(false);}},300);
    }
''',1)

old='''    @Override protected void onResume(){super.onResume();activityPaused=false;String nowLang=SettingsStore.language(this);if(appliedLanguage!=null&&!appliedLanguage.isEmpty()&&!appliedLanguage.equals(nowLang)){recreate();return;}String nowContent=SettingsStore.contentLanguage(this);if(appliedContentLanguage!=null&&!appliedContentLanguage.isEmpty()&&!appliedContentLanguage.equals(nowContent)){appliedContentLanguage=nowContent;if(provider!=null){if("home".equals(section)||"local".equals(section))loadHome();else if("epg".equals(section))loadEpg();else loadSection(section);}return;}if(provider!=null){setHeroHeight(heroHeight());android.content.SharedPreferences sp=SettingsStore.prefs(this);if(sp.getBoolean("force_reindex",false)){sp.edit().putBoolean("force_reindex",false).apply();try{searchIndex.clearAll();}catch(Exception ignored){}refreshSearchIndex(true);}}}
'''
new='''    @Override protected void onResume(){super.onResume();activityPaused=false;resumeIndexSoon();String nowLang=SettingsStore.language(this);if(appliedLanguage!=null&&!appliedLanguage.isEmpty()&&!appliedLanguage.equals(nowLang)){recreate();return;}String nowContent=SettingsStore.contentLanguage(this);if(appliedContentLanguage!=null&&!appliedContentLanguage.isEmpty()&&!appliedContentLanguage.equals(nowContent)){appliedContentLanguage=nowContent;if(provider!=null){if("home".equals(section)||"local".equals(section))loadHome();else if("epg".equals(section))loadEpg();else loadSection(section);}return;}if(provider!=null){setHeroHeight(heroHeight());android.content.SharedPreferences sp=SettingsStore.prefs(this);if(sp.getBoolean("force_reindex",false)){sp.edit().putBoolean("force_reindex",false).apply();try{searchIndex.clearAll();}catch(Exception ignored){}refreshSearchIndex(true);}}}
'''
if old not in s: raise SystemExit("missing onResume")
s=s.replace(old,new)

s=s.replace('''    @Override protected void onDestroy(){requestSerial++;heroSerial++;if(pendingSearch!=null)ui.removeCallbacks(pendingSearch);exec.shutdownNow();heroExec.shutdownNow();indexExec.shutdownNow();if(searchIndex!=null)searchIndex.close();''',
'''    @Override protected void onDestroy(){requestSerial++;heroSerial++;if(pendingSearch!=null)ui.removeCallbacks(pendingSearch);Future<?> f=indexFuture;if(f!=null)f.cancel(true);exec.shutdownNow();heroExec.shutdownNow();indexExec.shutdownNow();if(searchIndex!=null)searchIndex.close();''')
p.write_text(s)

print("v0.9.2 playback/index isolation applied")
