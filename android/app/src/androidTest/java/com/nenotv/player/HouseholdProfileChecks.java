package com.nenotv.player;

import android.content.Context;
import android.content.SharedPreferences;
import com.nenotv.player.storage.*;
import com.nenotv.player.model.MediaEntry;
import java.util.*;

final class HouseholdProfileChecks {
    static void sourceIndexMigration(Context context)throws Exception{
        String database="qa-source-key-"+UUID.randomUUID()+".db";
        SearchIndexStore helper=new SearchIndexStore(context);
        try(android.database.sqlite.SQLiteDatabase db=context.openOrCreateDatabase(database,Context.MODE_PRIVATE,null)){
            helper.onCreate(db);
            db.execSQL("CREATE TABLE import_entries(session TEXT,profile TEXT,item_key TEXT,payload TEXT,PRIMARY KEY(session,item_key))");
            org.json.JSONObject primary=new org.json.JSONObject().put("id","42").put("type","vod").put("name","Primary");
            org.json.JSONObject secondary=new org.json.JSONObject().put("id","43").put("type","vod").put("name","Secondary").put("sourceId","other");
            for(org.json.JSONObject item:new org.json.JSONObject[]{primary,secondary}){
                db.execSQL("INSERT INTO entries(profile,item_key,type,payload) VALUES(?,?,?,?)",new Object[]{"qa","vod:"+item.getString("id"),"vod",item.toString()});
                db.execSQL("INSERT INTO import_entries(session,profile,item_key,payload) VALUES(?,?,?,?)",new Object[]{"resume","qa","vod:"+item.getString("id"),item.toString()});
            }
            db.execSQL("INSERT INTO import_progress(profile,section,session,cursor,item_count,updated) VALUES('qa','vod','resume','category:2',2,1)");
            helper.onUpgrade(db,3,4);
            helper.onUpgrade(db,3,4);
            for(String table:new String[]{"entries","import_entries"})try(android.database.Cursor c=db.rawQuery("SELECT item_key FROM "+table+" ORDER BY rowid",null)){
                check(c.moveToFirst()&&c.getString(0).equals("vod:42"),"Index migration changed primary legacy identity");
                check(c.moveToNext()&&c.getString(0).equals("source:5:other|vod:43")&&!c.moveToNext(),"Index migration lost or duplicated secondary identity");
            }
            try(android.database.Cursor c=db.rawQuery("SELECT cursor,item_count FROM import_progress",null)){
                check(c.moveToFirst()&&c.getString(0).equals("category:2")&&c.getInt(1)==2,"Index migration lost import checkpoint");
            }
            db.beginTransaction();
            try(android.database.sqlite.SQLiteStatement insert=db.compileStatement("INSERT INTO entries(profile,item_key,type,payload) VALUES('qa',?,'vod',?)")){
                for(int n=0;n<4096;n++){
                    insert.bindString(1,"vod:bulk-"+n);
                    insert.bindString(2,new org.json.JSONObject().put("id","bulk-"+n).put("type","vod").put("sourceId","bulk").toString());
                    insert.executeInsert();
                }
                db.setTransactionSuccessful();
            }finally{db.endTransaction();}
            long started=android.os.SystemClock.elapsedRealtime();
            // SQLiteOpenHelper upgrades inside one transaction; measure the same path.
            db.beginTransaction();
            try{helper.onUpgrade(db,3,4);db.setTransactionSuccessful();}finally{db.endTransaction();}
            android.util.Log.i("NenoTVIndexMigration","4098 rows migrated in "+(android.os.SystemClock.elapsedRealtime()-started)+" ms");
            try(android.database.Cursor c=db.rawQuery("SELECT COUNT(*) FROM entries WHERE item_key LIKE 'source:4:bulk|vod:bulk-%'",null)){
                check(c.moveToFirst()&&c.getInt(0)==4096,"Batched migration skipped or duplicated rows");
            }
        }finally{helper.close();context.deleteDatabase(database);}
    }
    static void check(boolean value,String message){if(!value)throw new AssertionError(message);}
    static void restore(SharedPreferences prefs,Map<String,?> values){
        SharedPreferences.Editor editor=prefs.edit().clear();
        for(Map.Entry<String,?> entry:values.entrySet()){
            Object value=entry.getValue();String key=entry.getKey();
            if(value instanceof String)editor.putString(key,(String)value);
            else if(value instanceof Long)editor.putLong(key,(Long)value);
            else if(value instanceof Integer)editor.putInt(key,(Integer)value);
            else if(value instanceof Boolean)editor.putBoolean(key,(Boolean)value);
            else if(value instanceof Set)editor.putStringSet(key,new HashSet<>((Set<String>)value));
        }
        editor.commit();
    }
    static void run(Context context)throws Exception{
        sourceIndexMigration(context);
        SharedPreferences profiles=context.getSharedPreferences("nenotv_viewers",Context.MODE_PRIVATE);
        SharedPreferences legacy=context.getSharedPreferences("library",Context.MODE_PRIVATE);
        SharedPreferences settings=SettingsStore.prefs(context);
        Map<String,?> oldProfiles=new HashMap<>(profiles.getAll()),oldLibrary=new HashMap<>(legacy.getAll()),oldSettings=new HashMap<>(settings.getAll());
        List<String> created=new ArrayList<>();
        try{
            profiles.edit().clear().commit();
            HouseholdProfileStore viewers=new HouseholdProfileStore(context);
            check(viewers.list().size()==1&&viewers.activeId().equals("default"),"Default household profile missing");
            LibraryStore original=new LibraryStore(context);
            MediaEntry movie=new MediaEntry();movie.id="qa-household-movie";movie.type="movie";movie.name="QA";
            if(!original.isFavorite(movie))original.toggleFavorite(movie);
            original.saveProgress(movie,40000,100000,true);original.recent(movie);
            MediaEntry other=new MediaEntry();other.id=movie.id;other.type=movie.type;other.name="Other source";
            other.sourceId="qa-secondary";other.sourceName="QA provider";other.directSource="https://example.invalid/private-stream";
            other.url=other.directSource;other.catchup=true;other.catchupDays=7;other.candidates.add("https://example.invalid/fallback");
            check(!movie.uniqueKey().equals(other.uniqueKey()),"Different sources share a library key");
            original.saveProgress(other,70000,100000,true);original.recent(other);original.toggleFavorite(other);
            LibraryStore reopened=new LibraryStore(context);
            check(reopened.progress(movie)==40000&&reopened.progress(other)==70000,"Secondary source overwrote primary progress");
            MediaEntry restored=null;for(MediaEntry entry:reopened.favorites())if(other.uniqueKey().equals(entry.uniqueKey()))restored=entry;
            check(restored!=null&&restored.sourceId.equals(other.sourceId)&&restored.sourceName.equals(other.sourceName)
                &&restored.directSource.equals(other.directSource)&&restored.catchup&&restored.catchupDays==7
                &&restored.candidates.equals(other.candidates),"Encrypted library lost source or catchup information");
            String encrypted=legacy.getString("item:"+other.uniqueKey(),"");
            check(!encrypted.isEmpty()&&!encrypted.contains("private-stream")&&!encrypted.contains("QA provider"),"Library credentials stored unencrypted");
            check(movie.uniqueKey().equals("movie:qa-household-movie"),"Primary legacy key changed");
            SettingsStore.setPrimaryLanguage(context,"nl");settings.edit().putString("sort","favorites").putString("qa_import_cursor","keep-device-state").commit();
            String second=viewers.add("Second viewer");created.add(second);viewers.select(second);
            LibraryStore separate=new LibraryStore(context);
            check(!separate.isFavorite(movie)&&separate.progress(movie)==0&&separate.recent().isEmpty(),"Viewer inherited another viewer's library");
            separate.saveProgress(movie,60000,100000,true);separate.toggleFavorite(movie);
            SettingsStore.setPrimaryLanguage(context,"de");settings.edit().putString("sort","recent").commit();
            check(original.progress(movie)==40000&&original.isFavorite(movie),"Active player changed its library ownership");
            check(viewers.rename(second,"Renamed viewer")&&new LibraryStore(context).progress(movie)==60000,"Rename lost progress or changed identity");
            viewers.select("default");check(new LibraryStore(context).progress(movie)==40000,"Default profile migration lost existing library");
            check(SettingsStore.language(context).equals("nl")&&SettingsStore.sort(context).equals("favorites"),"Viewer settings not restored");
            check(settings.getString("qa_import_cursor","").equals("keep-device-state"),"Profile switch changed import/device state");
            viewers.select(second);check(SettingsStore.language(context).equals("de")&&SettingsStore.sort(context).equals("recent"),"Second viewer settings not isolated");viewers.select("default");
            check(!viewers.remove("default")&&!viewers.select("invalid-id"),"Default profile deleted or arbitrary ID selected");
            for(String name:new String[]{""," ","12345678901234567890123456789012345678901","bad\nname","Renamed viewer"}){
                boolean rejected=false;try{created.add(viewers.add(name));}catch(IllegalArgumentException expected){rejected=true;}
                check(rejected,"Invalid or duplicate viewer name accepted");
            }
            while(viewers.list().size()<HouseholdProfileStore.LIMIT)created.add(viewers.add("Viewer "+viewers.list().size()));
            boolean limited=false;try{viewers.add("One too many");}catch(IllegalStateException expected){limited=true;}
            check(limited,"Household profile limit ignored");
            viewers.select(second);check(viewers.remove(second)&&viewers.activeId().equals("default"),"Deleting active viewer did not return to default");
            check(context.getSharedPreferences(HouseholdProfileStore.libraryName(second),Context.MODE_PRIVATE).getAll().isEmpty(),"Deleted viewer's encrypted library remained");
            profiles.edit().putString("profiles","not-json").commit();
            boolean failed=false;try{viewers.add("Do not overwrite");}catch(IllegalStateException expected){failed=true;}
            check(failed&&"not-json".equals(profiles.getString("profiles","")),"Corrupt profile registry overwritten");
        }finally{
            for(String id:created)context.deleteSharedPreferences(HouseholdProfileStore.libraryName(id));
            restore(profiles,oldProfiles);restore(legacy,oldLibrary);restore(settings,oldSettings);
        }
    }
}
