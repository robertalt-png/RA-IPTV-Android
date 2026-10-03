package com.nenotv.player.storage;

import android.content.*;
import android.database.Cursor;
import android.database.sqlite.*;
import com.nenotv.player.model.MediaEntry;
import com.nenotv.player.model.Category;
import com.nenotv.player.ContentLanguage;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.*;

public class SearchIndexStore extends SQLiteOpenHelper {
    private static final String DB="nenotv_search.db";
    private static final int VERSION=3;

    public SearchIndexStore(Context c){ super(c,DB,null,VERSION); try{setWriteAheadLoggingEnabled(true);}catch(Exception ignored){} }

    @Override public void onCreate(SQLiteDatabase db){
        db.execSQL("CREATE TABLE entries(profile TEXT NOT NULL, item_key TEXT NOT NULL, type TEXT NOT NULL, name TEXT, name_norm TEXT, hay_norm TEXT, lang_tag TEXT NOT NULL DEFAULT '', lang_scanned INTEGER NOT NULL DEFAULT 1, payload TEXT NOT NULL, PRIMARY KEY(profile,item_key))");
        db.execSQL("CREATE INDEX idx_entries_name ON entries(profile,name_norm)");
        db.execSQL("CREATE INDEX idx_entries_type ON entries(profile,type)");
        db.execSQL("CREATE INDEX idx_entries_lang ON entries(profile,type,lang_tag)");
        db.execSQL("CREATE TABLE meta(profile TEXT NOT NULL, section TEXT NOT NULL, updated INTEGER NOT NULL, item_count INTEGER NOT NULL, PRIMARY KEY(profile,section))");
        db.execSQL("CREATE TABLE category_cache(profile TEXT NOT NULL, section TEXT NOT NULL, category_id TEXT NOT NULL, name TEXT NOT NULL, updated INTEGER NOT NULL, PRIMARY KEY(profile,section,category_id))");
        db.execSQL("CREATE TABLE import_progress(profile TEXT NOT NULL, section TEXT NOT NULL, session TEXT NOT NULL, cursor TEXT NOT NULL DEFAULT '', item_count INTEGER NOT NULL DEFAULT 0, updated INTEGER NOT NULL, PRIMARY KEY(profile,section))");
    }
    @Override public void onUpgrade(SQLiteDatabase db,int oldV,int newV){
        if(oldV<2){
            try{db.execSQL("ALTER TABLE entries ADD COLUMN lang_tag TEXT NOT NULL DEFAULT ''");}catch(Exception ignored){}
            try{db.execSQL("ALTER TABLE entries ADD COLUMN lang_scanned INTEGER NOT NULL DEFAULT 0");}catch(Exception ignored){}
            try{db.execSQL("CREATE INDEX IF NOT EXISTS idx_entries_lang ON entries(profile,type,lang_tag)");}catch(Exception ignored){}
        }
        if(oldV<3){
            db.execSQL("CREATE TABLE IF NOT EXISTS category_cache(profile TEXT NOT NULL, section TEXT NOT NULL, category_id TEXT NOT NULL, name TEXT NOT NULL, updated INTEGER NOT NULL, PRIMARY KEY(profile,section,category_id))");
            db.execSQL("CREATE TABLE IF NOT EXISTS import_progress(profile TEXT NOT NULL, section TEXT NOT NULL, session TEXT NOT NULL, cursor TEXT NOT NULL DEFAULT '', item_count INTEGER NOT NULL DEFAULT 0, updated INTEGER NOT NULL, PRIMARY KEY(profile,section))");
        }
    }

    public synchronized void replaceSection(String profile,String section,List<MediaEntry> items){
        SQLiteDatabase db=getWritableDatabase(); db.beginTransaction();
        try{
            db.delete("entries","profile=? AND type=?",new String[]{profile,section});
            for(MediaEntry e:items) upsertInternal(db,profile,e);
            ContentValues m=new ContentValues();m.put("profile",profile);m.put("section",section);m.put("updated",System.currentTimeMillis());m.put("item_count",items.size());
            db.insertWithOnConflict("meta",null,m,SQLiteDatabase.CONFLICT_REPLACE);
            db.setTransactionSuccessful();
        }finally{db.endTransaction();}
    }

    public void upsert(String profile,List<MediaEntry> items){
    if(items==null||items.isEmpty())return;
    SQLiteDatabase db=getWritableDatabase();
    final int chunk=80;
    for(int start=0;start<items.size()&&!Thread.currentThread().isInterrupted();start+=chunk){
        int end=Math.min(items.size(),start+chunk);boolean complete=true;
        db.beginTransaction();
        try{
            for(int i=start;i<end;i++){if(Thread.currentThread().isInterrupted()){complete=false;break;}upsertInternal(db,profile,items.get(i));}
            if(complete)db.setTransactionSuccessful();
        }finally{db.endTransaction();}
        if(!complete)break;
        Thread.yield();
    }
}

    private void upsertInternal(SQLiteDatabase db,String profile,MediaEntry e){
        upsertInternal(db,"entries",null,profile,e);
    }
    private void upsertInternal(SQLiteDatabase db,String table,String session,String profile,MediaEntry e){
        try{
            ContentValues v=new ContentValues();v.put("profile",profile);v.put("item_key",e.uniqueKey());v.put("type",e.type);v.put("name",safe(e.name));
            String nameNorm=norm(e.name);String hay=norm(safe(e.name)+" "+safe(e.plot)+" "+safe(e.group)+" "+safe(e.seriesTitle)+" "+safe(e.tvgName));
            v.put("name_norm",nameNorm);v.put("hay_norm",hay);v.put("lang_tag",ContentLanguage.detectTag(e));v.put("lang_scanned",1);v.put("payload",encode(e));
            if(session!=null){v.put("session",session);v.put("started",System.currentTimeMillis());}
            if(db.insertWithOnConflict(table,null,v,SQLiteDatabase.CONFLICT_REPLACE)<0)throw new IllegalStateException("cache_write_failed");
        }catch(Exception failure){throw new IllegalStateException("cache_write_failed",failure);}
    }


    public String beginSectionImport() { return beginSectionImport(null); }

    public String beginSectionImport(String resumeSession) {
        SQLiteDatabase db=getWritableDatabase();
        db.execSQL("CREATE TABLE IF NOT EXISTS import_entries(session TEXT NOT NULL,profile TEXT NOT NULL,item_key TEXT NOT NULL,type TEXT NOT NULL,name TEXT,name_norm TEXT,hay_norm TEXT,lang_tag TEXT NOT NULL DEFAULT '',lang_scanned INTEGER NOT NULL DEFAULT 1,payload TEXT NOT NULL,started INTEGER NOT NULL,PRIMARY KEY(session,item_key))");
        db.delete("import_entries","started<?",new String[]{Long.toString(System.currentTimeMillis()-86400000L)});
        String x=resumeSession==null?"":resumeSession.trim();
        return x.isEmpty()?java.util.UUID.randomUUID().toString():x;
    }

    public static final class ImportProgress {
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

    public synchronized boolean categoriesFresh(String profile,String section,long ttlMs){
        try(Cursor c=getReadableDatabase().rawQuery("SELECT COUNT(*),MAX(updated) FROM category_cache WHERE profile=? AND section=?",new String[]{profile,section})){
            if(!c.moveToFirst()||c.getInt(0)<=0)return false;
            long updated=c.isNull(1)?0L:c.getLong(1);
            return updated>0L && System.currentTimeMillis()-updated<Math.max(1L,ttlMs);
        }catch(Exception ignored){return false;}
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

    public void importBatch(String session,String profile,String section,List<MediaEntry> items) throws java.io.InterruptedIOException {
        com.nenotv.player.net.StreamingJsonArray.checkCancelled();
        SQLiteDatabase db=getWritableDatabase();db.beginTransaction();
        try {
            for(MediaEntry e:items){
                com.nenotv.player.net.StreamingJsonArray.checkCancelled();
                if(!section.equals(e.type))throw new IllegalArgumentException("import_section_mismatch");
                upsertInternal(db,"import_entries",session,profile,e);
            }
            db.setTransactionSuccessful();
        } finally {db.endTransaction();}
    }

    public int finishSectionImport(String session,String profile,String section) throws java.io.InterruptedIOException {
        com.nenotv.player.net.StreamingJsonArray.checkCancelled();
        SQLiteDatabase db=getWritableDatabase();db.beginTransaction();
        try {
            int count;
            try(Cursor c=db.rawQuery("SELECT COUNT(*) FROM import_entries WHERE session=? AND profile=? AND type=?",new String[]{session,profile,section})){c.moveToFirst();count=c.getInt(0);}
            db.delete("entries","profile=? AND type=?",new String[]{profile,section});
            db.execSQL("INSERT INTO entries(profile,item_key,type,name,name_norm,hay_norm,lang_tag,lang_scanned,payload) SELECT profile,item_key,type,name,name_norm,hay_norm,lang_tag,lang_scanned,payload FROM import_entries WHERE session=? AND profile=? AND type=?",new Object[]{session,profile,section});
            ContentValues m=new ContentValues();m.put("profile",profile);m.put("section",section);m.put("updated",System.currentTimeMillis());m.put("item_count",count);
            if(db.insertWithOnConflict("meta",null,m,SQLiteDatabase.CONFLICT_REPLACE)<0)throw new IllegalStateException("import_meta_failed");
            db.delete("import_entries","session=?",new String[]{session});
            com.nenotv.player.net.StreamingJsonArray.checkCancelled();
            db.setTransactionSuccessful();return count;
        } finally {db.endTransaction();}
    }

    public void abortSectionImport(String session) {
        if(session!=null)getWritableDatabase().delete("import_entries","session=?",new String[]{session});
    }

    public synchronized void clearAll(){try{SQLiteDatabase db=getWritableDatabase();db.delete("entries",null,null);db.delete("meta",null,null);}catch(Exception ignored){}}

    public synchronized void markSection(String profile,String section,int count){
        try{ContentValues m=new ContentValues();m.put("profile",profile);m.put("section",section);m.put("updated",System.currentTimeMillis());m.put("item_count",Math.max(0,count));getWritableDatabase().insertWithOnConflict("meta",null,m,SQLiteDatabase.CONFLICT_REPLACE);}catch(Exception ignored){}
    }

    public synchronized boolean isFresh(String profile,String section,long ttlMs){
        try(Cursor c=getReadableDatabase().rawQuery("SELECT updated FROM meta WHERE profile=? AND section=?",new String[]{profile,section})){
            return c.moveToFirst() && System.currentTimeMillis()-c.getLong(0)<ttlMs;
        }
    }

    public synchronized int count(String profile){
        try(Cursor c=getReadableDatabase().rawQuery("SELECT COUNT(*) FROM entries WHERE profile=?",new String[]{profile})){
            return c.moveToFirst()?c.getInt(0):0;
        }
    }

    public synchronized int countSection(String profile,String section){
        try(Cursor c=getReadableDatabase().rawQuery("SELECT COUNT(*) FROM entries WHERE profile=? AND type=?",new String[]{profile,section})){
            return c.moveToFirst()?c.getInt(0):0;
        }catch(Exception e){return 0;}
    }

    public synchronized boolean isComplete(String profile,String section){
        try(Cursor c=getReadableDatabase().rawQuery("SELECT 1 FROM meta WHERE profile=? AND section=? LIMIT 1",new String[]{profile,section})){return c.moveToFirst();}catch(Exception e){return false;}
    }

    public synchronized List<MediaEntry> sectionPage(String profile,String section,int offset,int limit){
        return sectionPage(profile,section,offset,limit,"provider");
    }

    public synchronized List<MediaEntry> sectionPage(String profile,String section,int offset,int limit,String sort){return sectionPage(profile,section,offset,limit,sort,"");}
    private void ensureLanguageHints(SQLiteDatabase db,String profile,String section){
    // v0.10.5: new index rows already contain language tags.
    // Never perform a full decode/write migration from a foreground read.
}

    public synchronized List<MediaEntry> sectionPage(String profile,String section,int offset,int limit,String sort,String preferredLanguage){
        ArrayList<MediaEntry> out=new ArrayList<>();int safeOffset=Math.max(0,offset),safeLimit=Math.max(1,Math.min(1000,limit));
        String order="rowid ASC";if("az".equals(sort))order="name_norm COLLATE NOCASE ASC";else if("za".equals(sort))order="name_norm COLLATE NOCASE DESC";
        SQLiteDatabase db=getWritableDatabase();ensureLanguageHints(db,profile,section);
        String sql="SELECT payload FROM entries WHERE profile=? AND type=? ORDER BY "+order+" LIMIT ? OFFSET ?";
        String[] args=new String[]{profile,section,String.valueOf(safeLimit),String.valueOf(safeOffset)};
        try(Cursor c=db.rawQuery(sql,args)){while(c.moveToNext()){MediaEntry e=decode(c.getString(0));if(e!=null)out.add(e);}}catch(Exception ignored){}
        return out;
    }

    public synchronized int countLanguage(String profile,String section,String tag){
        String t=tag==null?"":tag.trim().toLowerCase(Locale.ROOT);if(t.isEmpty())return 0;
        try(Cursor c=getReadableDatabase().rawQuery("SELECT COUNT(*) FROM entries WHERE profile=? AND type=? AND lang_tag=?",new String[]{profile,section,t})){return c.moveToFirst()?c.getInt(0):0;}catch(Exception e){return 0;}
    }

    public synchronized List<String> languageTags(String profile,String section){
        ArrayList<String> out=new ArrayList<>();SQLiteDatabase db=getWritableDatabase();ensureLanguageHints(db,profile,section);
        try(Cursor c=db.rawQuery("SELECT lang_tag,COUNT(*) n FROM entries WHERE profile=? AND type=? AND lang_tag<>'' GROUP BY lang_tag ORDER BY n DESC,lang_tag ASC",new String[]{profile,section})){while(c.moveToNext()){String t=ContentLanguage.normalizeTag(c.getString(0));if(!t.isEmpty()&&!out.contains(t))out.add(t);}}catch(Exception ignored){}
        return out;
    }

    public synchronized int countOther(String profile,String section){SQLiteDatabase db=getWritableDatabase();ensureLanguageHints(db,profile,section);try(Cursor c=db.rawQuery("SELECT COUNT(*) FROM entries WHERE profile=? AND type=? AND (lang_tag='' OR lang_tag IS NULL)",new String[]{profile,section})){return c.moveToFirst()?c.getInt(0):0;}catch(Exception e){return 0;}}
    public synchronized List<MediaEntry> otherPage(String profile,String section,int offset,int limit,String sort){
        ArrayList<MediaEntry> out=new ArrayList<>();int safeOffset=Math.max(0,offset),safeLimit=Math.max(1,Math.min(1000,limit));String base="rowid ASC";if("az".equals(sort))base="name_norm COLLATE NOCASE ASC";else if("za".equals(sort))base="name_norm COLLATE NOCASE DESC";
        SQLiteDatabase db=getWritableDatabase();ensureLanguageHints(db,profile,section);try(Cursor c=db.rawQuery("SELECT payload FROM entries WHERE profile=? AND type=? AND (lang_tag='' OR lang_tag IS NULL) ORDER BY "+base+" LIMIT ? OFFSET ?",new String[]{profile,section,String.valueOf(safeLimit),String.valueOf(safeOffset)})){while(c.moveToNext()){MediaEntry e=decode(c.getString(0));if(e!=null)out.add(e);}}catch(Exception ignored){}return out;
    }

    public synchronized List<MediaEntry> languagePage(String profile,String section,String tag,int offset,int limit,String sort){
        ArrayList<MediaEntry> out=new ArrayList<>();String t=tag==null?"":tag.trim().toLowerCase(Locale.ROOT);if(t.isEmpty())return out;
        int safeOffset=Math.max(0,offset),safeLimit=Math.max(1,Math.min(1000,limit));String base="rowid ASC";if("az".equals(sort))base="name_norm COLLATE NOCASE ASC";else if("za".equals(sort))base="name_norm COLLATE NOCASE DESC";
        SQLiteDatabase db=getWritableDatabase();ensureLanguageHints(db,profile,section);
        try(Cursor c=db.rawQuery("SELECT payload FROM entries WHERE profile=? AND type=? AND lang_tag=? ORDER BY "+base+" LIMIT ? OFFSET ?",new String[]{profile,section,t,String.valueOf(safeLimit),String.valueOf(safeOffset)})){while(c.moveToNext()){MediaEntry e=decode(c.getString(0));if(e!=null)out.add(e);}}catch(Exception ignored){}
        return out;
    }

    public synchronized List<MediaEntry> searchFiltered(String profile,String section,String query,String tag,int limit){
        String q=norm(query);if(q.isEmpty())return Collections.emptyList();String sec=section==null?"":section.trim();String t=tag==null?"":tag.trim().toLowerCase(Locale.ROOT);SQLiteDatabase db=getWritableDatabase();if(!sec.isEmpty())ensureLanguageHints(db,profile,sec);
        StringBuilder where=new StringBuilder("profile=?");ArrayList<String> base=new ArrayList<>();base.add(profile);if(!sec.isEmpty()){where.append(" AND type=?");base.add(sec);}if("other".equals(t)){where.append(" AND (lang_tag='' OR lang_tag IS NULL)");}else if(!t.isEmpty()){where.append(" AND lang_tag=?");base.add(t);}
        LinkedHashMap<String,MediaEntry> out=new LinkedHashMap<>();queryFilteredInto(out,where.toString(),base,"name_norm=?",q,limit);if(out.size()<limit)queryFilteredInto(out,where.toString(),base,"name_norm LIKE ?",q+"%",limit);if(out.size()<limit)queryFilteredInto(out,where.toString(),base,"hay_norm LIKE ?","%"+q+"%",limit*2);
        List<MediaEntry> result=new ArrayList<>(out.values());result.sort((a,b)->Integer.compare(score(b,q),score(a,q)));if(result.size()>limit)return new ArrayList<>(result.subList(0,limit));return result;
    }
    private void queryFilteredInto(LinkedHashMap<String,MediaEntry> out,String where,List<String> base,String extra,String value,int limit){ArrayList<String>a=new ArrayList<>(base);a.add(value);a.add(String.valueOf(limit));try(Cursor c=getReadableDatabase().rawQuery("SELECT payload FROM entries WHERE "+where+" AND "+extra+" LIMIT ?",a.toArray(new String[0]))){while(c.moveToNext()){MediaEntry e=decode(c.getString(0));if(e!=null)out.putIfAbsent(e.uniqueKey(),e);}}catch(Exception ignored){}}

    public synchronized List<MediaEntry> search(String profile,String query,int limit){
        String q=norm(query); if(q.isEmpty())return Collections.emptyList();
        LinkedHashMap<String,MediaEntry> out=new LinkedHashMap<>();
        queryInto(out,"SELECT payload FROM entries WHERE profile=? AND name_norm=? LIMIT ?",new String[]{profile,q,String.valueOf(limit)});
        if(out.size()<limit)queryInto(out,"SELECT payload FROM entries WHERE profile=? AND name_norm LIKE ? ORDER BY length(name_norm) ASC LIMIT ?",new String[]{profile,q+"%",String.valueOf(limit)});
        if(out.size()<limit)queryInto(out,"SELECT payload FROM entries WHERE profile=? AND hay_norm LIKE ? LIMIT ?",new String[]{profile,"%"+q+"%",String.valueOf(limit*2)});
        List<MediaEntry> result=new ArrayList<>(out.values());
        result.sort((a,b)->Integer.compare(score(b,q),score(a,q)));
        if(result.size()>limit)return new ArrayList<>(result.subList(0,limit));
        return result;
    }

    private void queryInto(LinkedHashMap<String,MediaEntry> out,String sql,String[] args){
        try(Cursor c=getReadableDatabase().rawQuery(sql,args)){
            while(c.moveToNext()){MediaEntry e=decode(c.getString(0));if(e!=null)out.putIfAbsent(e.uniqueKey(),e);}
        }
    }

    private static int score(MediaEntry e,String q){
        String n=norm(e.name), g=norm(e.group); int s=0;
        if(n.equals(q))s+=1000; else if(n.startsWith(q))s+=500; else if(n.contains(" "+q)||n.contains(q+" "))s+=250; else if(n.contains(q))s+=120;
        String nl=" "+n+" "+g+" ";String[] tags={" nederland "," dutch "," nl "," npo "," rtl "," sbs "," veronica "," net5 "," vlaams "," belgie "," belgië "};
        for(String t:tags)if(nl.contains(t))s+=10;
        if("series".equals(e.type))s+=4; else if("vod".equals(e.type))s+=3; else if("live".equals(e.type))s+=2;
        return s;
    }

    private static String encode(MediaEntry e)throws Exception{
        JSONObject x=new JSONObject();x.put("id",e.id);x.put("streamId",e.streamId);x.put("seriesId",e.seriesId);x.put("name",e.name);x.put("logo",e.logo);x.put("backdrop",e.backdrop);x.put("categoryId",e.categoryId);x.put("type",e.type);x.put("rating",e.rating);x.put("year",e.year);x.put("plot",e.plot);x.put("extension",e.extension);x.put("directSource",e.directSource);x.put("url",e.url);x.put("group",e.group);x.put("tvgId",e.tvgId);x.put("tvgName",e.tvgName);x.put("seriesTitle",e.seriesTitle);x.put("tmdbId",e.tmdbId);x.put("imdbId",e.imdbId);x.put("sourceId",e.sourceId);x.put("sourceName",e.sourceName);x.put("season",e.season);x.put("episode",e.episode);x.put("catchup",e.catchup);x.put("catchupDays",e.catchupDays);x.put("candidates",new JSONArray(e.candidates));return x.toString();
    }
    private static MediaEntry decode(String raw){
        try{JSONObject x=new JSONObject(raw);MediaEntry e=new MediaEntry();e.id=x.optString("id");e.streamId=x.optString("streamId");e.seriesId=x.optString("seriesId");e.name=x.optString("name","Untitled");e.logo=x.optString("logo");e.backdrop=x.optString("backdrop");e.categoryId=x.optString("categoryId");e.type=x.optString("type","live");e.rating=x.optString("rating");e.year=x.optString("year");e.plot=x.optString("plot");e.extension=x.optString("extension");e.directSource=x.optString("directSource");e.url=x.optString("url");e.group=x.optString("group");e.tvgId=x.optString("tvgId");e.tvgName=x.optString("tvgName");e.seriesTitle=x.optString("seriesTitle");e.tmdbId=x.optString("tmdbId");e.imdbId=x.optString("imdbId");e.sourceId=x.optString("sourceId");e.sourceName=x.optString("sourceName");e.season=x.optInt("season");e.episode=x.optInt("episode");e.catchup=x.optBoolean("catchup",false);e.catchupDays=x.optInt("catchupDays",0);JSONArray a=x.optJSONArray("candidates");if(a!=null)for(int i=0;i<a.length();i++)e.candidates.add(a.optString(i));return e;}catch(Exception ex){return null;}
    }
    private static String safe(String s){return s==null?"":s;}
    private static String norm(String s){return safe(s).toLowerCase(Locale.ROOT).replace('|',' ').replaceAll("\\s+"," ").trim();}
}
