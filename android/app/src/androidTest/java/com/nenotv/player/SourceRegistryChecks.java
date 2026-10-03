package com.nenotv.player;

import android.content.Context;
import android.content.SharedPreferences;
import com.nenotv.player.model.Profile;
import com.nenotv.player.storage.SecureProfileStore;
import com.nenotv.player.storage.SmartEpgStore;
import com.nenotv.player.storage.SourceStore;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

final class SourceRegistryChecks {
    static void check(boolean value,String message){if(!value)throw new AssertionError(message);}
    static JSONObject row(String id,boolean enabled)throws Exception{
        return new JSONObject().put("id",id).put("enabled",enabled).put("type","M3U").put("name",id).put("m3u","https://example.invalid/"+id);
    }
    static void restore(SharedPreferences prefs,Map<String,?> values){
        SharedPreferences.Editor editor=prefs.edit().clear();
        for(Map.Entry<String,?> entry:values.entrySet()){
            Object value=entry.getValue();String key=entry.getKey();
            if(value instanceof String)editor.putString(key,(String)value);
            else if(value instanceof Boolean)editor.putBoolean(key,(Boolean)value);
            else if(value instanceof Integer)editor.putInt(key,(Integer)value);
            else if(value instanceof Long)editor.putLong(key,(Long)value);
        }
        editor.commit();
    }
    static void run(Context context)throws Exception{
        SharedPreferences sourcePrefs=context.getSharedPreferences("nenotv_sources_v1",Context.MODE_PRIVATE);
        SharedPreferences epgPrefs=context.getSharedPreferences("nenotv_smart_epg_v1",Context.MODE_PRIVATE);
        Map<String,?> oldSources=new HashMap<>(sourcePrefs.getAll()),oldEpg=new HashMap<>(epgPrefs.getAll());
        SecureProfileStore profiles=new SecureProfileStore(context);Profile previous=profiles.exists()?profiles.load():null;
        try{
            sourcePrefs.edit().clear().commit();profiles.clear();
            SourceStore store=new SourceStore(context),secondInstance=new SourceStore(context);
            Profile p=new Profile();p.type=Profile.Type.M3U;p.name="Local";p.m3uUrl="https://example.invalid/local";
            String first=store.upsert("",p,false);
            check(first.equals(store.activeId())&&profiles.exists(),"First source did not mirror active profile");
            SmartEpgStore epg=new SmartEpgStore(context);epg.setUrls(first,Collections.singletonList("https://example.invalid/epg"));
            store.applyCloudSnapshot(new JSONArray().put(row("disabled",false)).put(row("enabled",true)),1);
            check("enabled".equals(store.activeId())&&"enabled".equals(profiles.load().name),"Cloud selected disabled source");
            check(epg.urls(first).isEmpty(),"Cloud removal retained private EPG URLs");
            SourceStore.SyncSnapshot beforeEdit=store.snapshotForSync();
            secondInstance.upsert("",p,false);
            check(!store.applyCloudSnapshotIfUnchanged(new JSONArray(),2,beforeEdit.localRevision),"Cloud overwrote local edit during pull");
            check(!store.markSyncedIfUnchanged(2,beforeEdit.localRevision)&&store.syncDirty(),"Push acknowledgment lost pending edit");
            SourceStore.SyncSnapshot current=store.snapshotForSync();
            check(store.markSyncedIfUnchanged(3,current.localRevision)&&!store.syncDirty(),"Unchanged push stayed dirty");
            store.applyCloudSnapshot(new JSONArray().put(row("disabled",false)),4);
            check(store.activeId().isEmpty()&&!profiles.exists(),"All-disabled snapshot retained deleted active profile");
            store.applyCloudSnapshot(new JSONArray(),5);
            check(store.list().isEmpty()&&!profiles.exists(),"Empty cloud snapshot retained source");
            String removed=store.upsert("",p,true);epg.setUrls(removed,Collections.singletonList("https://example.invalid/private-guide"));
            check(store.remove(removed)&&epg.urls(removed).isEmpty(),"Local deletion retained private EPG URL");
        }finally{
            restore(sourcePrefs,oldSources);restore(epgPrefs,oldEpg);
            if(previous==null)profiles.clear();else profiles.save(previous);
        }
    }
}
