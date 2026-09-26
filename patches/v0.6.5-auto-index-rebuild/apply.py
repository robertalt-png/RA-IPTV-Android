from pathlib import Path

root=Path("source/RA_IPTV_Android_v0.1")

def rw(rel, old, new, count=1):
    p=root/rel
    s=p.read_text()
    if old not in s:
        raise SystemExit("missing target in "+rel+": "+repr(old[:140]))
    p.write_text(s.replace(old,new,count))

# Version bump.
rw("app/build.gradle","versionCode 23","versionCode 24")
rw("app/build.gradle","versionName '0.6.4'","versionName '0.6.5'")
rw("app/src/main/java/com/robertalt/raiptv/SettingsActivity.java","Nivaro IPTV Player 0.6.4\\n","Nivaro IPTV Player 0.6.5\\n")

p=root/"app/src/main/java/com/robertalt/raiptv/MainActivity.java"
s=p.read_text()

# Add a one-time search-index generation marker. Increment this whenever the persistent
# index format/classification/order changes in a way that makes old cached rows unsafe.
old='''    boolean seriesEpisodeMode=false,settingCategories=false,indexRefreshRunning=false,activityPaused=false; volatile int fullLibraryToken=0; String appliedLanguage="",appliedContentLanguage="";
    boolean cachePagingActive=false,cachePageLoading=false; String cachePagingSection=""; int cachePagingOffset=0,cachePagingTotal=0; static final int CACHE_PAGE_SIZE=600;
'''
new='''    boolean seriesEpisodeMode=false,settingCategories=false,indexRefreshRunning=false,activityPaused=false; volatile int fullLibraryToken=0; String appliedLanguage="",appliedContentLanguage="";
    boolean cachePagingActive=false,cachePageLoading=false,autoReindexAfterConnect=false; String cachePagingSection=""; int cachePagingOffset=0,cachePagingTotal=0; static final int CACHE_PAGE_SIZE=600;
'''
if old not in s: raise SystemExit("field anchor missing")
s=s.replace(old,new,1)

old='''    static final long SEARCH_INDEX_TTL_MS=6*60*60*1000L;
    static final LruCache<String,Bitmap> HERO_CACHE=new LruCache<String,Bitmap>(4*1024){@Override protected int sizeOf(String k,Bitmap b){return Math.max(1,b.getByteCount()/1024);}};
'''
new='''    static final long SEARCH_INDEX_TTL_MS=6*60*60*1000L;
    static final int SEARCH_INDEX_GENERATION=3;
    static final LruCache<String,Bitmap> HERO_CACHE=new LruCache<String,Bitmap>(4*1024){@Override protected int sizeOf(String k,Bitmap b){return Math.max(1,b.getByteCount()/1024);}};
'''
if old not in s: raise SystemExit("constant anchor missing")
s=s.replace(old,new,1)

old='''        profiles=new SecureProfileStore(this);library=new LibraryStore(this);searchIndex=new SearchIndexStore(this);epgStore=new EpgStore(this);
        categories=findViewById(R.id.categorySpinner);'''
new='''        profiles=new SecureProfileStore(this);library=new LibraryStore(this);searchIndex=new SearchIndexStore(this);epgStore=new EpgStore(this);migrateSearchIndexIfNeeded();
        categories=findViewById(R.id.categorySpinner);'''
if old not in s: raise SystemExit("onCreate store anchor missing")
s=s.replace(old,new,1)

# Automatic one-time invalidation preserves account/profile/settings and only clears the
# derived search index plus its resume cursors.
anchor='''    String T(String key){return UiText.t(this,key);}
'''
method='''    void migrateSearchIndexIfNeeded(){
        android.content.SharedPreferences sp=SettingsStore.prefs(this);
        int have=sp.getInt("search_index_generation",0);
        if(have>=SEARCH_INDEX_GENERATION)return;
        try{searchIndex.clearAll();}catch(Exception ignored){}
        android.content.SharedPreferences.Editor ed=sp.edit().putInt("search_index_generation",SEARCH_INDEX_GENERATION);
        try{for(String k:sp.getAll().keySet())if(k!=null&&k.startsWith("cache_cursor_"))ed.remove(k);}catch(Exception ignored){}
        ed.apply();
        autoReindexAfterConnect=true;
    }

'''
if anchor not in s: raise SystemExit("method anchor missing")
s=s.replace(anchor,method+anchor,1)

# After successful provider authentication, perform the migration rebuild once.
old='''        exec.execute(()->{try{provider.authenticate();runOnUiThread(()->{if(current(token)){adapter.setEpg(provider,epgStore,profileKey());gridAdapter.setEpg(provider,epgStore,profileKey());epgAdapter.configure(provider,profileKey());openStart();ui.postDelayed(()->{if(provider!=null&&!indexRefreshRunning&&!isFinishing()&&!isDestroyed())refreshSearchIndex(false);},1500);}});}catch(Exception e){runOnUiThread(()->{if(current(token))busy(false,T("login_failed_prefix")+": "+friendly(e));});}});
'''
new='''        exec.execute(()->{try{provider.authenticate();runOnUiThread(()->{if(current(token)){adapter.setEpg(provider,epgStore,profileKey());gridAdapter.setEpg(provider,epgStore,profileKey());epgAdapter.configure(provider,profileKey());openStart();final boolean forceAuto=autoReindexAfterConnect;autoReindexAfterConnect=false;ui.postDelayed(()->{if(provider!=null&&!indexRefreshRunning&&!isFinishing()&&!isDestroyed())refreshSearchIndex(forceAuto);},1500);}});}catch(Exception e){runOnUiThread(()->{if(current(token))busy(false,T("login_failed_prefix")+": "+friendly(e));});}});
'''
if old not in s: raise SystemExit("openProfile refresh anchor missing")
s=s.replace(old,new,1)

p.write_text(s)

# Validation.
main=p.read_text()
assert "SEARCH_INDEX_GENERATION=3" in main
assert "migrateSearchIndexIfNeeded();" in main
assert "autoReindexAfterConnect=true" in main
assert "refreshSearchIndex(forceAuto)" in main
assert "cache_cursor_" in main
assert "versionName '0.6.5'" in (root/"app/build.gradle").read_text()
assert "Nivaro IPTV Player 0.6.5" in (root/"app/src/main/java/com/robertalt/raiptv/SettingsActivity.java").read_text()
print("v0.6.5 automatic search-index rebuild patch applied")
