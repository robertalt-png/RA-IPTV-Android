package com.nenotv.player.storage;

import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import android.database.sqlite.SQLiteStatement;
import android.os.SystemClock;
import android.util.Base64;
import com.nenotv.player.model.MediaEntry;
import java.io.File;
import java.io.FileInputStream;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.UUID;
import org.json.JSONObject;

public final class CachePrivacyChecks {
    private static final String SECRET="PRIVATE-CACHE-SECRET";
    static void check(boolean value,String message){if(!value)throw new AssertionError(message);}
    static boolean contains(File file,String marker)throws Exception{
        if(!file.exists())return false;
        try(FileInputStream input=new FileInputStream(file)){
            byte[] buffer=new byte[8192];String tail="";int n;
            while((n=input.read(buffer))!=-1){String chunk=tail+new String(buffer,0,n,StandardCharsets.ISO_8859_1);if(chunk.contains(marker))return true;tail=chunk.substring(Math.max(0,chunk.length()-marker.length()));}
        }
        return false;
    }
    public static void run(Context context)throws Exception{
        String name="qa-private-cache-"+UUID.randomUUID()+".db";
        String key=StoredMediaKey.of(name);File path=context.getDatabasePath(name);
        SearchIndexStore helper=new SearchIndexStore(context,name);
        try{
            SearchIndexStore fixtureHelper=helper;
            SQLiteOpenHelper legacy=new SQLiteOpenHelper(context,name,null,4){
                public void onCreate(SQLiteDatabase db){
                    fixtureHelper.onCreate(db);
                    db.execSQL("CREATE TABLE import_entries(session TEXT NOT NULL,profile TEXT NOT NULL,item_key TEXT NOT NULL,type TEXT NOT NULL,name TEXT,name_norm TEXT,hay_norm TEXT,lang_tag TEXT NOT NULL DEFAULT '',lang_scanned INTEGER NOT NULL DEFAULT 1,payload TEXT NOT NULL,started INTEGER NOT NULL,PRIMARY KEY(session,item_key))");
                }
                public void onUpgrade(SQLiteDatabase db,int oldVersion,int newVersion){throw new AssertionError("Legacy fixture unexpectedly upgraded");}
            };
            try{
                SQLiteDatabase db=legacy.getWritableDatabase();try(Cursor c=db.rawQuery("PRAGMA secure_delete=OFF",null)){check(c.moveToFirst()&&c.getInt(0)==0,"Legacy fixture secure-delete state");}db.beginTransaction();
                try(SQLiteStatement insert=db.compileStatement("INSERT INTO entries(profile,item_key,type,name,name_norm,hay_norm,lang_tag,payload) VALUES('qa',?,'vod','NL QA','nl qa','nl qa','nl',?)")){
                    for(int n=0;n<4096;n++){
                        String url="https://example.invalid/user/"+SECRET+"/"+n;
                        insert.bindString(1,"vod:"+n);
                        insert.bindString(2,new JSONObject().put("id",String.valueOf(n)).put("type","vod").put("name","NL QA "+n).put("sourceId",n%2==0?"":"secondary").put("url",url).put("directSource",url).put("candidates",new org.json.JSONArray().put(url)).toString());insert.executeInsert();
                    }
                    String url="https://example.invalid/"+SECRET+"/idless";
                    insert.bindString(1,"vod:"+url);insert.bindString(2,new JSONObject().put("id","").put("type","vod").put("name","NL QA "+url).put("url",url).put("plot",url).toString());insert.executeInsert();
                    db.execSQL("INSERT INTO import_entries(session,profile,item_key,type,payload,started) VALUES('resume','resume-profile','vod:pending','vod',?,?)",new Object[]{new JSONObject().put("id","pending").put("type","vod").put("name","NL Pending").put("url",url).toString(),System.currentTimeMillis()});
                    db.execSQL("INSERT INTO import_progress(profile,section,session,cursor,item_count,updated) VALUES('resume-profile','vod','resume','category:2',1,1)");
                    db.setTransactionSuccessful();
                }finally{db.endTransaction();}
            }finally{legacy.close();}
            check(contains(path,SECRET),"Plaintext migration fixture missing private stream data");
            long start=SystemClock.elapsedRealtime();SQLiteDatabase db=helper.getWritableDatabase();
            android.util.Log.i("NenoTVCachePrivacy","4098 encrypted migration rows and cleanup in "+(SystemClock.elapsedRealtime()-start)+" ms");
            check(db.getVersion()==5&&helper.countSection("qa","vod")==4097,"Encrypted upgrade lost catalogue rows");
            check(helper.importCount("resume","resume-profile","vod")==1,"Encrypted upgrade lost pending import");
            SearchIndexStore.ImportProgress progress=helper.importProgress("resume-profile","vod");
            check(progress!=null&&progress.cursor.equals("category:2")&&progress.itemCount==1,"Encrypted migration lost resume cursor");
            check(helper.sectionPage("qa","vod",0,100).size()==100&&helper.languagePage("qa","vod","nl",0,100,"provider").size()==100,"Encrypted catalogue cannot page or filter");
            check(!helper.search("qa","QA",20).isEmpty(),"Encrypted catalogue cannot search metadata");
            try(Cursor c=db.rawQuery("SELECT item_key,payload FROM entries UNION ALL SELECT item_key,payload FROM import_entries",null)){
                int rows=0;while(c.moveToNext()){rows++;check(c.getString(0).startsWith("media2:")&&!c.getString(0).contains(SECRET),"Private URL leaked through index identity");check(c.getString(1).startsWith("cache1:")&&!c.getString(1).contains(SECRET),"Index payload remained plaintext");}
                check(rows==4098,"Index privacy migration skipped rows");
            }
            MediaEntry item=new MediaEntry();item.type="vod";item.name="NL Privacy";item.url="https://example.invalid/"+SECRET+"/new";item.candidates.add(item.url);
            start=SystemClock.elapsedRealtime();helper.upsert("new",Collections.singletonList(item));
            check(helper.sectionPage("new","vod",0,10).get(0).url.equals(item.url),"Encrypted new row lost stream URL");
            CachePayloadCipher cipher=new CachePayloadCipher(context,name);String one=cipher.encrypt(SECRET),two=cipher.encrypt(SECRET);
            check(!one.equals(two)&&cipher.decrypt(one).equals(SECRET),"Cache encryption reused a nonce or failed roundtrip");
            byte[] frame=Base64.decode(one.substring("cache1:".length()),Base64.NO_WRAP);frame[frame.length-1]^=1;
            String damaged="cache1:"+Base64.encodeToString(frame,Base64.NO_WRAP);
            boolean rejected=false;try{cipher.decrypt(damaged);}catch(Exception expected){rejected=true;}check(rejected,"Cache accepted a modified authentication tag");
            db.execSQL("UPDATE entries SET payload=? WHERE profile='new'",new Object[]{damaged});
            check(helper.sectionPage("new","vod",0,10).isEmpty(),"Damaged encrypted cache fell back to plaintext");
            db.execSQL("UPDATE entries SET payload=? WHERE profile='new'",new Object[]{new JSONObject().put("url",item.url).toString()});
            check(helper.sectionPage("new","vod",0,10).isEmpty(),"Version 5 reader accepted plaintext injection");
            db.delete("entries","profile='new'",null);
            String session=helper.beginSectionImport();long benchmark=SystemClock.elapsedRealtime();
            String plot=new String(new char[512]).replace('\0','x');
            for(int offset=0;offset<4096;offset+=80){
                java.util.ArrayList<MediaEntry> batch=new java.util.ArrayList<>();
                for(int n=offset;n<Math.min(4096,offset+80);n++){
                    MediaEntry e=new MediaEntry();e.id="bench-"+n;e.type="vod";e.name="NL Bench "+n;e.plot=plot;
                    e.url="https://example.invalid/"+SECRET+"/bench/"+n;e.directSource=e.url;e.candidates.add(e.url);batch.add(e);
                }
                helper.importBatch(session,"performance","vod",batch);
            }
            check(helper.finishSectionImport(session,"performance","vod")==4096,"Encrypted batched import lost rows");
            android.util.Log.i("NenoTVCachePrivacy","4096 encrypted 80-row batches with 512-character metadata in "+(SystemClock.elapsedRealtime()-benchmark)+" ms");
            helper.close();
            for(String suffix:new String[]{"","-wal","-journal","-shm"})check(!contains(new File(path.getPath()+suffix),SECRET),"Private address remained in database or journal file");
            android.content.SharedPreferences keys=context.getSharedPreferences("nenotv_cache_keys",Context.MODE_PRIVATE);
            String wrapped=keys.getString(key,"");keys.edit().putString(key,"broken-key").commit();
            boolean missingRejected=false;try{new CachePayloadCipher(context,name);}catch(IllegalStateException expected){missingRejected=true;}
            check(missingRejected&&keys.getString(key,"").equals("broken-key"),"Unreadable wrapped key silently regenerated");
            keys.edit().putString(key,wrapped).commit();
            helper=new SearchIndexStore(context,name);
            check(helper.sectionPage("qa","vod",4096,1).get(0).url.contains(SECRET),"Encrypted catalogue failed after reopen");
            check(!context.getSharedPreferences("nenotv_cache_maintenance",Context.MODE_PRIVATE).contains(key),"Successful cleanup marker remained pending");
        }finally{
            helper.close();context.deleteDatabase(name);
            context.getSharedPreferences("nenotv_cache_keys",Context.MODE_PRIVATE).edit().remove(key).commit();
            context.getSharedPreferences("nenotv_cache_maintenance",Context.MODE_PRIVATE).edit().remove(key).commit();
        }
    }
}

