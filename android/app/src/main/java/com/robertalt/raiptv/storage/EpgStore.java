package com.nenotv.player.storage;

import android.content.*;
import android.database.Cursor;
import android.database.sqlite.*;
import com.nenotv.player.model.EpgEntry;
import org.json.*;
import java.util.*;

public class EpgStore extends SQLiteOpenHelper {
    private static final String DB="nenotv_epg.db"; private static final int VERSION=2; private final Context app;
    public static final long TTL_MS=12*60*1000L;
    private volatile boolean closed=false;
    public boolean isClosed(){return closed;}
    public String language(){return SettingsStore.primaryLanguage(app);}
    public EpgStore(Context c){super(c,DB,null,VERSION);app=c.getApplicationContext();}
    @Override public void onCreate(SQLiteDatabase db){db.execSQL("CREATE TABLE epg(cache_key TEXT PRIMARY KEY, updated INTEGER NOT NULL, payload TEXT NOT NULL)");}
    @Override public void onUpgrade(SQLiteDatabase db,int a,int b){db.execSQL("DROP TABLE IF EXISTS epg");onCreate(db);}
    public synchronized List<EpgEntry> get(String key){
        if(closed)return Collections.emptyList();
        try(Cursor c=getReadableDatabase().rawQuery("SELECT updated,payload FROM epg WHERE cache_key=?",new String[]{key})){
            if(!c.moveToFirst()||System.currentTimeMillis()-c.getLong(0)>TTL_MS)return Collections.emptyList();return decode(c.getString(1));
        }catch(Exception e){return Collections.emptyList();}
    }
    public synchronized void put(String key,List<EpgEntry> rows){
        if(closed)return;
        try{ContentValues v=new ContentValues();v.put("cache_key",key);v.put("updated",System.currentTimeMillis());v.put("payload",encode(rows));getWritableDatabase().insertWithOnConflict("epg",null,v,SQLiteDatabase.CONFLICT_REPLACE);}catch(Exception ignored){}
    }
    private final java.util.concurrent.ConcurrentHashMap<String,java.util.concurrent.FutureTask<List<EpgEntry>>> fetches=new java.util.concurrent.ConcurrentHashMap<>();
    private synchronized boolean fresh(String key){if(closed)return false;try(Cursor c=getReadableDatabase().rawQuery("SELECT updated FROM epg WHERE cache_key=?",new String[]{key})){return c.moveToFirst()&&System.currentTimeMillis()-c.getLong(0)<=TTL_MS;}catch(Exception e){return false;}}
    @Override public synchronized void close(){closed=true;super.close();}
    public List<EpgEntry> getOrFetch(com.nenotv.player.provider.Provider provider,String profile,com.nenotv.player.model.MediaEntry channel)throws Exception{
        return getOrFetch(provider,profile,channel,language());
    }
    public List<EpgEntry> getOrFetch(com.nenotv.player.provider.Provider provider,String profile,com.nenotv.player.model.MediaEntry channel,String language)throws Exception{
        if(closed)throw new java.io.IOException("EPG_STORE_CLOSED");
        if(provider==null||channel==null)throw new IllegalArgumentException("EPG_CONTEXT_MISSING");
        boolean smart=new EntitlementStore(app).isPro()&&SettingsStore.prefs(app).getBoolean("pro_smart_epg",false);
        List<String> extraUrls=Collections.emptyList();
        if(smart){String sourceId=channel.sourceId==null||channel.sourceId.isEmpty()?new SourceStore(app).activeId():channel.sourceId;extraUrls=new SmartEpgStore(app).urls(sourceId);}
        final List<String> configuredUrls=extraUrls;
        JSONArray identity=new JSONArray();identity.put(profile);identity.put(channel.uniqueKey());identity.put(channel.tvgId);identity.put(channel.tvgName);identity.put(language);identity.put(smart);identity.put(new JSONArray(configuredUrls));
        byte[] digest=java.security.MessageDigest.getInstance("SHA-256").digest(identity.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
        StringBuilder hash=new StringBuilder();for(byte b:digest)hash.append(String.format(Locale.ROOT,"%02x",b&255));
        String key="timeline3|"+hash;
        java.util.concurrent.FutureTask<List<EpgEntry>> created=new java.util.concurrent.FutureTask<>(()->{
            if(fresh(key))return com.nenotv.player.EpgTimeline.normalize(get(key));
            List<EpgEntry> rows;
            try{rows=com.nenotv.player.EpgTimeline.normalize(provider.epgEntries(channel,50));}
            catch(Exception primaryFailure){if(!smart)throw primaryFailure;rows=Collections.emptyList();}
            if(rows.isEmpty()&&smart){
                for(String url:configuredUrls){
                    try{
                        List<EpgEntry> extra=com.nenotv.player.EpgTimeline.normalize(com.nenotv.player.core.XmltvGuide.lookupEntries(url,channel.tvgId,channel.tvgName,50,language));
                        if(!extra.isEmpty()){rows=extra;break;}
                    }catch(Exception ignored){}
                }
            }
            if(closed)throw new java.io.IOException("EPG_STORE_CLOSED");
            put(key,rows);return rows;
        });
        java.util.concurrent.FutureTask<List<EpgEntry>> previous=fetches.putIfAbsent(key,created);
        boolean owner=previous==null;java.util.concurrent.FutureTask<List<EpgEntry>> request=owner?created:previous;
        if(owner)request.run();
        try{return request.get();}
        catch(java.util.concurrent.ExecutionException failure){Throwable cause=failure.getCause();if(cause instanceof Exception)throw (Exception)cause;if(cause instanceof Error)throw (Error)cause;throw new java.io.IOException("EPG_FETCH_FAILED",cause);}
        catch(InterruptedException interrupted){Thread.currentThread().interrupt();throw new java.io.InterruptedIOException("EPG_FETCH_INTERRUPTED");}
        finally{if(owner)fetches.remove(key,created);}
    }
    private String encode(List<EpgEntry> rows)throws Exception{JSONArray a=new JSONArray();for(EpgEntry e:rows){JSONObject x=new JSONObject();x.put("t",e.title);x.put("d",e.description);x.put("sr",e.startRaw);x.put("er",e.endRaw);x.put("s",e.startEpoch);x.put("e",e.endEpoch);a.put(x);}return a.toString();}
    private List<EpgEntry> decode(String raw)throws Exception{List<EpgEntry>o=new ArrayList<>();JSONArray a=new JSONArray(raw);for(int i=0;i<a.length();i++){JSONObject x=a.getJSONObject(i);EpgEntry e=new EpgEntry();e.title=x.optString("t");e.description=x.optString("d");e.startRaw=x.optString("sr");e.endRaw=x.optString("er");e.startEpoch=x.optLong("s");e.endEpoch=x.optLong("e");o.add(e);}return o;}
}
