from pathlib import Path
import re

root=Path('.')
store=root/'app/src/main/java/com/robertalt/raiptv/storage/SearchIndexStore.java'
main=root/'app/src/main/java/com/robertalt/raiptv/MainActivity.java'
for p in (store,main):
    if not p.exists(): raise SystemExit(f'missing {p}')

s=store.read_text(encoding='utf-8')
if 'import com.nenotv.player.model.Category;' not in s:
    s=s.replace('import com.nenotv.player.model.MediaEntry;','import com.nenotv.player.model.MediaEntry;\nimport com.nenotv.player.model.Category;')
s=s.replace('private static final int VERSION=2;','private static final int VERSION=3;')
create_marker='        db.execSQL("CREATE TABLE meta(profile TEXT NOT NULL, section TEXT NOT NULL, updated INTEGER NOT NULL, item_count INTEGER NOT NULL, PRIMARY KEY(profile,section))");'
if create_marker not in s: raise SystemExit('create marker missing')
if 'CREATE TABLE category_cache' not in s:
    s=s.replace(create_marker,create_marker+'\n        db.execSQL("CREATE TABLE category_cache(profile TEXT NOT NULL, section TEXT NOT NULL, category_id TEXT NOT NULL, name TEXT NOT NULL, updated INTEGER NOT NULL, PRIMARY KEY(profile,section,category_id))");\n        db.execSQL("CREATE TABLE import_progress(profile TEXT NOT NULL, section TEXT NOT NULL, session TEXT NOT NULL, cursor TEXT NOT NULL DEFAULT \'\', item_count INTEGER NOT NULL DEFAULT 0, updated INTEGER NOT NULL, PRIMARY KEY(profile,section))");')
upgrade_marker='''        if(oldV<2){
            try{db.execSQL("ALTER TABLE entries ADD COLUMN lang_tag TEXT NOT NULL DEFAULT ''");}catch(Exception ignored){}
            try{db.execSQL("ALTER TABLE entries ADD COLUMN lang_scanned INTEGER NOT NULL DEFAULT 0");}catch(Exception ignored){}
            try{db.execSQL("CREATE INDEX IF NOT EXISTS idx_entries_lang ON entries(profile,type,lang_tag)");}catch(Exception ignored){}
        }'''
if upgrade_marker not in s: raise SystemExit('upgrade marker missing')
if 'oldV<3' not in s:
    s=s.replace(upgrade_marker,upgrade_marker+'''
        if(oldV<3){
            db.execSQL("CREATE TABLE IF NOT EXISTS category_cache(profile TEXT NOT NULL, section TEXT NOT NULL, category_id TEXT NOT NULL, name TEXT NOT NULL, updated INTEGER NOT NULL, PRIMARY KEY(profile,section,category_id))");
            db.execSQL("CREATE TABLE IF NOT EXISTS import_progress(profile TEXT NOT NULL, section TEXT NOT NULL, session TEXT NOT NULL, cursor TEXT NOT NULL DEFAULT '', item_count INTEGER NOT NULL DEFAULT 0, updated INTEGER NOT NULL, PRIMARY KEY(profile,section))");
        }''')

old_begin='''    public String beginSectionImport() {
        SQLiteDatabase db=getWritableDatabase();
        db.execSQL("CREATE TABLE IF NOT EXISTS import_entries(session TEXT NOT NULL,profile TEXT NOT NULL,item_key TEXT NOT NULL,type TEXT NOT NULL,name TEXT,name_norm TEXT,hay_norm TEXT,lang_tag TEXT NOT NULL DEFAULT '',lang_scanned INTEGER NOT NULL DEFAULT 1,payload TEXT NOT NULL,started INTEGER NOT NULL,PRIMARY KEY(session,item_key))");
        db.delete("import_entries","started<?",new String[]{Long.toString(System.currentTimeMillis()-86400000L)});
        return java.util.UUID.randomUUID().toString();
    }'''
new_begin='''    public String beginSectionImport() { return beginSectionImport(null); }

    public String beginSectionImport(String resumeSession) {
        SQLiteDatabase db=getWritableDatabase();
        db.execSQL("CREATE TABLE IF NOT EXISTS import_entries(session TEXT NOT NULL,profile TEXT NOT NULL,item_key TEXT NOT NULL,type TEXT NOT NULL,name TEXT,name_norm TEXT,hay_norm TEXT,lang_tag TEXT NOT NULL DEFAULT '',lang_scanned INTEGER NOT NULL DEFAULT 1,payload TEXT NOT NULL,started INTEGER NOT NULL,PRIMARY KEY(session,item_key))");
        db.delete("import_entries","started<?",new String[]{Long.toString(System.currentTimeMillis()-86400000L)});
        String x=resumeSession==null?"":resumeSession.trim();
        return x.isEmpty()?java.util.UUID.randomUUID().toString():x;
    }'''
if old_begin not in s: raise SystemExit('begin marker missing')
s=s.replace(old_begin,new_begin)

insert_before='    public void importBatch(String session,String profile,String section,List<MediaEntry> items) throws java.io.InterruptedIOException {'
if insert_before not in s: raise SystemExit('importBatch marker missing')
if 'class ImportProgress' not in s:
    methods='''    public static final class ImportProgress {
        public final String session,cursor; public final int itemCount;
        ImportProgress(String s,String c,int n){session=s;cursor=c;itemCount=n;}
    }

    public synchronized void replaceCategories(String profile,String section,List<Category> categories){
        SQLiteDatabase db=getWritableDatabase();db.beginTransaction();
        try{
            db.delete("category_cache","profile=? AND section=?",new String[]{profile,section});
            long now=System.currentTimeMillis();
            if(categories!=null)for(Category c:categories){if(c==null)continue;ContentValues v=new ContentValues();v.put("profile",profile);v.put("section",section);v.put("category_id",safe(c.id));v.put("name",safe(c.name));v.put("updated",now);db.insertWithOnConflict("category_cache",null,v,SQLiteDatabase.CONFLICT_REPLACE);}
            db.setTransactionSuccessful();
        }finally{db.endTransaction();}
    }

    public synchronized List<Category> cachedCategories(String profile,String section){
        ArrayList<Category> out=new ArrayList<>();
        try(Cursor c=getReadableDatabase().rawQuery("SELECT category_id,name FROM category_cache WHERE profile=? AND section=? ORDER BY rowid ASC",new String[]{profile,section})){
            while(c.moveToNext())out.add(new Category(c.getString(0),c.getString(1),section));
        }catch(Exception ignored){}
        return out;
    }

    public synchronized void checkpointImport(String profile,String section,String session,String cursor,int itemCount){
        ContentValues v=new ContentValues();v.put("profile",profile);v.put("section",section);v.put("session",session);v.put("cursor",cursor==null?"":cursor);v.put("item_count",Math.max(0,itemCount));v.put("updated",System.currentTimeMillis());
        getWritableDatabase().insertWithOnConflict("import_progress",null,v,SQLiteDatabase.CONFLICT_REPLACE);
    }

    public synchronized ImportProgress importProgress(String profile,String section){
        try(Cursor c=getReadableDatabase().rawQuery("SELECT session,cursor,item_count FROM import_progress WHERE profile=? AND section=?",new String[]{profile,section})){
            if(c.moveToFirst())return new ImportProgress(c.getString(0),c.getString(1),c.getInt(2));
        }catch(Exception ignored){}
        return null;
    }

    public synchronized void clearImportProgress(String profile,String section){getWritableDatabase().delete("import_progress","profile=? AND section=?",new String[]{profile,section});}

    public synchronized int importCount(String session,String profile,String section){
        try(Cursor c=getReadableDatabase().rawQuery("SELECT COUNT(*) FROM import_entries WHERE session=? AND profile=? AND type=?",new String[]{session,profile,section})){return c.moveToFirst()?c.getInt(0):0;}catch(Exception e){return 0;}
    }

'''
    s=s.replace(insert_before,methods+insert_before)
store.write_text(s,encoding='utf-8')

m=main.read_text(encoding='utf-8')
old='''    void warmCategories(){if(provider==null||profile==null||profile.type!=Profile.Type.XTREAM)return;for(String s:new String[]{"live","vod","series"})exec.execute(()->{try{provider.categories(s);}catch(Exception ignored){}});}'''
new='''    void warmCategories(){if(provider==null||profile==null||profile.type!=Profile.Type.XTREAM)return;final String key=profileKey();for(String s:new String[]{"live","vod","series"})exec.execute(()->{try{List<Category> c=new ArrayList<>(provider.categories(s));searchIndex.replaceCategories(key,s,c);}catch(Exception ignored){}});}'''
if old not in m: raise SystemExit('warmCategories marker missing')
m=m.replace(old,new)

old='''        exec.execute(()->{try{List<Category>loaded=new ArrayList<>(provider.categories(s));List<Category>raw=visibleCategories(loaded);runOnUiThread(()->{if(!current(token)||!section.equals(s))return;currentCategories=raw;setCategorySpinner(raw,true);String start=defaultGroupId(raw);selectSpinner(start);currentCategoryId=start;currentCategoryName=groupLabel(start);if(start.startsWith("lang:"))loadLanguageGroup(start.substring(5),false);else if("multi".equals(start))loadLanguageGroup("multi",false);else loadItems("all");scheduleBackgroundIndex();});}catch(Exception e){runOnUiThread(()->{if(current(token))busy(false,T("error_prefix")+": "+friendly(e));});}});'''
new='''        exec.execute(()->{try{List<Category>loaded=searchIndex.cachedCategories(profileKey(),s);if(loaded.isEmpty()){loaded=new ArrayList<>(provider.categories(s));searchIndex.replaceCategories(profileKey(),s,loaded);}List<Category>raw=visibleCategories(loaded);runOnUiThread(()->{if(!current(token)||!section.equals(s))return;currentCategories=raw;setCategorySpinner(raw,true);String start=defaultGroupId(raw);selectSpinner(start);currentCategoryId=start;currentCategoryName=groupLabel(start);if(start.startsWith("lang:"))loadLanguageGroup(start.substring(5),false);else if("multi".equals(start))loadLanguageGroup("multi",false);else loadItems("all");scheduleBackgroundIndex();});}catch(Exception e){runOnUiThread(()->{if(current(token))busy(false,T("error_prefix")+": "+friendly(e));});}});'''
if old not in m: raise SystemExit('loadSection categories marker missing')
m=m.replace(old,new)

old='''        exec.execute(()->{try{List<Category>raw=visibleCategories(new ArrayList<>(provider.categories("live")));runOnUiThread(()->{if(!current(token)||!section.equals("epg"))return;currentCategories=raw;setCategorySpinner(raw,true);if(raw.isEmpty()){busy(false,T("no_live_categories"));return;}String start=defaultGroupId(raw);selectSpinner(start);currentCategoryId=start;currentCategoryName=groupLabel(start);if(start.startsWith("lang:"))loadLanguageGroup(start.substring(5),false);else if("multi".equals(start))loadLanguageGroup("multi",false);else loadEpgChannels("all");scheduleBackgroundIndex();});}catch(Exception e){runOnUiThread(()->{if(current(token))busy(false,T("epg_error")+": "+friendly(e));});}});'''
new='''        exec.execute(()->{try{List<Category>loaded=searchIndex.cachedCategories(profileKey(),"live");if(loaded.isEmpty()){loaded=new ArrayList<>(provider.categories("live"));searchIndex.replaceCategories(profileKey(),"live",loaded);}List<Category>raw=visibleCategories(loaded);runOnUiThread(()->{if(!current(token)||!section.equals("epg"))return;currentCategories=raw;setCategorySpinner(raw,true);if(raw.isEmpty()){busy(false,T("no_live_categories"));return;}String start=defaultGroupId(raw);selectSpinner(start);currentCategoryId=start;currentCategoryName=groupLabel(start);if(start.startsWith("lang:"))loadLanguageGroup(start.substring(5),false);else if("multi".equals(start))loadLanguageGroup("multi",false);else loadEpgChannels("all");scheduleBackgroundIndex();});}catch(Exception e){runOnUiThread(()->{if(current(token))busy(false,T("epg_error")+": "+friendly(e));});}});'''
if old not in m: raise SystemExit('loadEpg categories marker missing')
m=m.replace(old,new)

old='''                        List<Category> cats=new ArrayList<>(indexProvider.categories(type));
                        catMap.put(type,cats);globalTotal+=cats.size();'''
new='''                        List<Category> cats=new ArrayList<>(indexProvider.categories(type));
                        searchIndex.replaceCategories(key,type,cats);
                        catMap.put(type,cats);globalTotal+=cats.size();'''
if old not in m: raise SystemExit('catMap marker missing')
m=m.replace(old,new)

old='''                                    searchIndex.importBatch(importSession,key,type,batch);
                                    received[0]+=batch.size();'''
new='''                                    searchIndex.importBatch(importSession,key,type,batch);
                                    if(!searchIndex.isComplete(key,type))searchIndex.upsert(key,batch);
                                    received[0]+=batch.size();
                                    int persisted=searchIndex.countSection(key,type);
                                    SettingsStore.prefs(this).edit().putInt("first_sync_titles_"+key,searchIndex.count(key)).apply();
                                    searchIndex.checkpointImport(key,type,importSession,"",Math.max(received[0],persisted));'''
if old not in m: raise SystemExit('bulk batch marker missing')
m=m.replace(old,new)
old='''                                searchIndex.finishSectionImport(importSession,key,type);session=null;
                                SettingsStore.prefs(this).edit().remove(cacheCursorKey(key,type)).apply();'''
new='''                                searchIndex.finishSectionImport(importSession,key,type);session=null;
                                searchIndex.clearImportProgress(key,type);
                                SettingsStore.prefs(this).edit().remove(cacheCursorKey(key,type)).apply();'''
if old not in m: raise SystemExit('bulk finish marker missing')
m=m.replace(old,new,1)

start=m.find('                        String fallbackSession=searchIndex.beginSectionImport();')
end=m.find('                    }\n\n                    allComplete=',start)
if start<0 or end<0: raise SystemExit('fallback block markers missing')
block='''                        SearchIndexStore.ImportProgress progress=searchIndex.importProgress(key,type);
                        String fallbackSession=searchIndex.beginSectionImport(progress==null?null:progress.session);
                        try{
                            final String importSession=fallbackSession;
                            int resumeAt=startAt;
                            String resumeCursor=progress==null?"":safe(progress.cursor);
                            if(!resumeCursor.isEmpty()){for(int ci=0;ci<cats.size();ci++)if(resumeCursor.equals(cats.get(ci).id)){resumeAt=Math.max(resumeAt,ci+1);break;}}
                            for(int ci=resumeAt;ci<cats.size();ci++){
                                Category c=cats.get(ci);
                                waitWhilePaused();waitForLibraryLoad();
                                com.nenotv.player.net.StreamingJsonArray.checkCancelled();
                                ((XtreamProvider)indexProvider).streamCategory(type,c.id,batch->{
                                    waitWhilePaused();waitForLibraryLoad();
                                    for(MediaEntry e:batch)e.group=c.name;
                                    searchIndex.importBatch(importSession,key,type,batch);
                                    if(!searchIndex.isComplete(key,type))searchIndex.upsert(key,batch);
                                });
                                globalDone++;
                                int staged=searchIndex.importCount(importSession,key,type);
                                searchIndex.checkpointImport(key,type,importSession,c.id,staged);
                                SettingsStore.prefs(this).edit().putString(cacheCursorKey(key,type),c.id).putInt("first_sync_done_count_"+key,globalDone).putInt("first_sync_titles_"+key,searchIndex.count(key)).apply();
                                final int gd=globalDone,gt=grandTotal,ti=searchIndex.count(key);
                                runOnUiThread(()->{if(isUiAlive()&&key.equals(profileKey()))showIndexBanner("",gd,gt,ti);});
                            }
                            searchIndex.finishSectionImport(importSession,key,type);fallbackSession=null;
                            searchIndex.clearImportProgress(key,type);
                            SettingsStore.prefs(this).edit().remove(cacheCursorKey(key,type)).apply();
                            if(key.equals(profileKey())){publishIndexedTop(type);reloadIndexedSectionWhenReady(type);}
                        }finally{
                            // Deliberately keep an unfinished staging session and checkpoint after process death/cancellation.
                        }
'''
m=m[:start]+block+m[end:]

old='''        int titles=sp.getInt("first_sync_titles_"+key,0);'''
new='''        int titles=Math.max(sp.getInt("first_sync_titles_"+key,0),searchIndex.count(key));'''
if old not in m: raise SystemExit('banner titles marker missing')
m=m.replace(old,new)

main.write_text(m,encoding='utf-8')
print('Applied explicit v0.13.3 durable resume/cache layer')
