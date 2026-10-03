package com.nenotv.player.storage;

import android.content.Context;
import android.content.SharedPreferences;
import com.nenotv.player.model.Profile;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/**
 * Lightweight source registry for NenoTV Pro.
 * Credentials remain encrypted with the same Android Keystore-backed CryptoBox as the legacy profile.
 * Light keeps using SecureProfileStore; Pro can keep multiple sources and mirrors the selected source
 * into SecureProfileStore so the existing provider/import pipeline stays untouched.
 */
public final class SourceStore {
    public static final class Entry {
        public final String id;
        public final Profile profile;
        public boolean enabled;
        public int priority;
        public long updatedAt;
        Entry(String id,Profile profile,boolean enabled,int priority,long updatedAt){
            this.id=id;this.profile=profile;this.enabled=enabled;this.priority=priority;this.updatedAt=updatedAt;
        }
    }

    private static final String PREFS="nenotv_sources_v1";
    private static final String KEY_DATA="sources";
    private static final String KEY_ACTIVE="active_id";
    private static final String KEY_DIRTY="sync_dirty";
    private static final Object SYNC_LOCK=new Object();
    private final Context app;
    private final SharedPreferences prefs;
    private final CryptoBox crypto;

    public SourceStore(Context c){
        app=c.getApplicationContext();
        prefs=app.getSharedPreferences(PREFS,Context.MODE_PRIVATE);
        crypto=new CryptoBox();
        migrateLegacyIfNeeded();
    }

    private synchronized void migrateLegacyIfNeeded(){
        if(prefs.contains(KEY_DATA))return;
        SecureProfileStore legacy=new SecureProfileStore(app);
        JSONArray a=new JSONArray();
        String active="";
        if(legacy.exists()){
            Profile p=legacy.load();
            String id=UUID.randomUUID().toString();
            a.put(toJson(new Entry(id,p,true,0,System.currentTimeMillis())));
            active=id;
        }
        write(a,active);
        prefs.edit().putBoolean(KEY_DIRTY,legacy.exists()).apply();
    }

    public synchronized List<Entry> list(){
        JSONArray a=readArray();
        ArrayList<Entry> out=new ArrayList<>();
        for(int i=0;i<a.length();i++){
            JSONObject o=a.optJSONObject(i);
            if(o==null)continue;
            Entry e=fromJson(o);
            if(e!=null)out.add(e);
        }
        Collections.sort(out,Comparator.comparingInt((Entry e)->e.priority).thenComparingLong(e->-e.updatedAt));
        return out;
    }

    public synchronized String activeId(){return prefs.getString(KEY_ACTIVE,"");}

    public synchronized Entry active(){
        String id=activeId();
        for(Entry e:list())if(e.id.equals(id))return e;
        List<Entry> all=list();
        return all.isEmpty()?null:all.get(0);
    }

    public String upsert(String id,Profile profile,boolean makeActive){synchronized(SYNC_LOCK){return upsertLocked(id,profile,makeActive);}}
    private synchronized String upsertLocked(String id,Profile profile,boolean makeActive){
        if(profile==null)throw new IllegalArgumentException("profile");
        JSONArray a=readArray();
        String use=id==null||id.trim().isEmpty()?UUID.randomUUID().toString():id.trim();
        int found=-1;
        int priority=a.length();
        for(int i=0;i<a.length();i++){
            JSONObject o=a.optJSONObject(i);
            if(o!=null&&use.equals(o.optString("id"))){found=i;priority=o.optInt("priority",i);break;}
        }
        Entry entry=new Entry(use,copy(profile),true,priority,System.currentTimeMillis());
        try{if(found>=0)a.put(found,toJson(entry));else a.put(toJson(entry));}
        catch(Exception ex){throw new IllegalStateException("SOURCE_STORE_UPDATE_FAILED",ex);}
        String active=makeActive?use:prefs.getString(KEY_ACTIVE,"");
        if(active.isEmpty())active=use;
        write(a,active); markDirty();
        if(use.equals(active))new SecureProfileStore(app).save(profile);
        return use;
    }

    public synchronized boolean setActive(String id){
        if(id==null||id.isEmpty())return false;
        for(Entry e:list()){
            if(e.id.equals(id)&&e.enabled){
                prefs.edit().putString(KEY_ACTIVE,id).commit();
                new SecureProfileStore(app).save(e.profile);
                return true;
            }
        }
        return false;
    }

    public boolean remove(String id){synchronized(SYNC_LOCK){return removeLocked(id);}}
    private synchronized boolean removeLocked(String id){
        if(id==null||id.isEmpty())return false;
        JSONArray old=readArray(),next=new JSONArray();
        boolean removed=false;
        for(int i=0;i<old.length();i++){
            JSONObject o=old.optJSONObject(i);
            if(o!=null&&id.equals(o.optString("id"))){removed=true;continue;}
            if(o!=null)next.put(o);
        }
        if(!removed)return false;
        String active=prefs.getString(KEY_ACTIVE,"");
        if(id.equals(active))active="";
        if(active.isEmpty()){
            for(int i=0;i<next.length();i++){
                JSONObject candidate=next.optJSONObject(i);
                if(candidate!=null&&candidate.optBoolean("enabled",true)){active=candidate.optString("id","");if(!active.isEmpty())break;}
            }
        }
        write(next,active); markDirty();
        new SmartEpgStore(app).setUrls(id,Collections.emptyList());
        if(!active.isEmpty()){if(!setActive(active))clearActiveProfile();}
        else clearActiveProfile();
        return true;
    }

    public void setEnabled(String id,boolean enabled){synchronized(SYNC_LOCK){setEnabledLocked(id,enabled);}}
    private synchronized void setEnabledLocked(String id,boolean enabled){
        JSONArray a=readArray();
        for(int i=0;i<a.length();i++){
            JSONObject o=a.optJSONObject(i);
            if(o!=null&&id.equals(o.optString("id"))){try{o.put("enabled",enabled);a.put(i,o);}catch(Exception ex){throw new IllegalStateException("SOURCE_STORE_UPDATE_FAILED",ex);}break;}
        }
        String active=prefs.getString(KEY_ACTIVE,"");
        write(a,active); markDirty();
        if(!enabled&&id.equals(active)){
            String replacement="";
            for(Entry e:list())if(e.enabled&&!e.id.equals(id)){replacement=e.id;break;}
            if(!replacement.isEmpty()){if(!setActive(replacement))clearActiveProfile();}
            else clearActiveProfile();
        }else if(enabled&&(active==null||active.isEmpty()))setActive(id);
    }

    public void move(String id,int delta){synchronized(SYNC_LOCK){moveLocked(id,delta);}}
    private synchronized void moveLocked(String id,int delta){
        List<Entry> all=list();
        int at=-1;
        for(int i=0;i<all.size();i++)if(all.get(i).id.equals(id)){at=i;break;}
        int to=at+delta;
        if(at<0||to<0||to>=all.size())return;
        Entry x=all.get(at);all.set(at,all.get(to));all.set(to,x);
        JSONArray a=new JSONArray();
        for(int i=0;i<all.size();i++){all.get(i).priority=i;a.put(toJson(all.get(i)));}
        write(a,prefs.getString(KEY_ACTIVE,"")); markDirty();
    }

    public synchronized JSONArray exportForSync(){
        JSONArray out=new JSONArray();
        for(Entry e:list())out.put(toJson(e));
        return out;
    }

    public void applyCloudSnapshot(JSONArray remote,int revision){synchronized(SYNC_LOCK){applyCloudSnapshotLocked(remote,revision);}}
    private synchronized void applyCloudSnapshotLocked(JSONArray remote,int revision){
        if(remote==null)return;
        JSONArray clean=new JSONArray();
        for(int i=0;i<remote.length();i++){
            JSONObject o=remote.optJSONObject(i);if(o==null)continue;
            Entry e=fromJson(o);if(e==null||e.id==null||e.id.isEmpty())continue;
            e.priority=clean.length();new SmartEpgStore(app).setFromJson(e.id,o.optJSONArray("epg_extra"));clean.put(toJson(e));
        }
        String active=prefs.getString(KEY_ACTIVE,"");
        boolean activeFound=false;
        for(int i=0;i<clean.length();i++){
            JSONObject o=clean.optJSONObject(i);
            if(o!=null&&active.equals(o.optString("id"))&&o.optBoolean("enabled",true)){activeFound=true;break;}
        }
        if(!activeFound){
            active="";
            for(int i=0;i<clean.length();i++){
                JSONObject row=clean.optJSONObject(i);
                if(row.optBoolean("enabled",true)){active=row.optString("id","");break;}
            }
        }
        for(Entry old:list()){
            boolean retained=false;
            for(int i=0;i<clean.length();i++)if(old.id.equals(clean.optJSONObject(i).optString("id"))){retained=true;break;}
            if(!retained)new SmartEpgStore(app).setUrls(old.id,Collections.emptyList());
        }
        write(clean,active);
        prefs.edit().putInt("cloud_revision",Math.max(0,revision)).putBoolean(KEY_DIRTY,false).putLong("local_revision",prefs.getLong("local_revision",0L)+1L).apply();
        if(!active.isEmpty()){if(!setActive(active))clearActiveProfile();}else clearActiveProfile();
    }

    public synchronized boolean syncDirty(){return prefs.getBoolean(KEY_DIRTY,false);}
    public void touchSync(){synchronized(SYNC_LOCK){markDirty();}}
    public void markSynced(int revision){synchronized(SYNC_LOCK){prefs.edit().putInt("cloud_revision",Math.max(0,revision)).putBoolean(KEY_DIRTY,false).apply();}}
    private void markDirty(){prefs.edit().putBoolean(KEY_DIRTY,true).putLong("local_revision",prefs.getLong("local_revision",0L)+1L).apply();}

    public static final class SyncSnapshot {
        public final JSONArray sources;
        public final long localRevision;
        SyncSnapshot(JSONArray sources,long revision){this.sources=sources;localRevision=revision;}
    }
    public SyncSnapshot snapshotForSync(){synchronized(SYNC_LOCK){return new SyncSnapshot(exportForSync(),prefs.getLong("local_revision",0L));}}
    public boolean applyCloudSnapshotIfUnchanged(JSONArray remote,int revision,long expected){synchronized(SYNC_LOCK){
        if(prefs.getLong("local_revision",0L)!=expected)return false;
        applyCloudSnapshotLocked(remote,revision);return true;
    }}
    public boolean markSyncedIfUnchanged(int revision,long expected){synchronized(SYNC_LOCK){
        boolean unchanged=prefs.getLong("local_revision",0L)==expected;
        prefs.edit().putInt("cloud_revision",Math.max(0,revision)).putBoolean(KEY_DIRTY,!unchanged).apply();return unchanged;
    }}

    public synchronized int cloudRevision(){return prefs.getInt("cloud_revision",0);}

    private void clearActiveProfile(){
        prefs.edit().putString(KEY_ACTIVE,"").commit();
        SecureProfileStore legacy=new SecureProfileStore(app);
        if(legacy.exists())legacy.clear();
    }

    private JSONArray readArray(){
        String enc=prefs.getString(KEY_DATA,"");
        if(enc.isEmpty())return new JSONArray();
        try{return new JSONArray(crypto.decrypt(enc));}catch(Exception e){return new JSONArray();}
    }

    private void write(JSONArray a,String active){
        String enc=crypto.encrypt(a.toString());
        if(!prefs.edit().putString(KEY_DATA,enc).putString(KEY_ACTIVE,active==null?"":active).commit())
            throw new IllegalStateException("SOURCE_STORE_WRITE_FAILED");
    }

    private JSONObject toJson(Entry e){
        try{
            JSONObject o=new JSONObject();
            o.put("id",e.id);o.put("enabled",e.enabled);o.put("priority",e.priority);o.put("updated_at",e.updatedAt);
            Profile p=e.profile;
            o.put("type",p.type.name());o.put("name",safe(p.name));o.put("server",safe(p.server));
            o.put("username",safe(p.username));o.put("password",safe(p.password));o.put("m3u",safe(p.m3uUrl));
            o.put("epg",safe(p.epgUrl));o.put("epg_extra",new SmartEpgStore(app).toJson(e.id));o.put("bridge",safe(p.bridgeUrl));o.put("bridge_token",safe(p.bridgeToken));
            return o;
        }catch(Exception ex){throw new IllegalStateException("SOURCE_STORE_ENCODE_FAILED",ex);}
    }

    private Entry fromJson(JSONObject o){
        try{
            Profile p=new Profile();
            try{p.type=Profile.Type.valueOf(o.optString("type","XTREAM"));}catch(Exception ignored){}
            p.name=o.optString("name","My IPTV");p.server=o.optString("server","");p.username=o.optString("username","");
            p.password=o.optString("password","");p.m3uUrl=o.optString("m3u","");p.epgUrl=o.optString("epg","");
            p.bridgeUrl=o.optString("bridge","");p.bridgeToken=o.optString("bridge_token","");
            return new Entry(o.optString("id"),p,o.optBoolean("enabled",true),o.optInt("priority",0),o.optLong("updated_at",0L));
        }catch(Exception e){return null;}
    }

    private static Profile copy(Profile p){
        Profile q=new Profile();q.type=p.type;q.name=p.name;q.server=p.server;q.username=p.username;q.password=p.password;
        q.m3uUrl=p.m3uUrl;q.epgUrl=p.epgUrl;q.bridgeUrl=p.bridgeUrl;q.bridgeToken=p.bridgeToken;return q;
    }
    private static String safe(String s){return s==null?"":s;}
}
