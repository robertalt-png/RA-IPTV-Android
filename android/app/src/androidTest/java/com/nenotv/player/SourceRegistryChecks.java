package com.nenotv.player;

import android.content.Context;
import android.content.SharedPreferences;
import com.nenotv.player.model.Profile;
import com.nenotv.player.model.MediaEntry;
import com.nenotv.player.model.Category;
import com.nenotv.player.provider.Provider;
import com.nenotv.player.provider.SourceProviderResolver;
import com.nenotv.player.storage.SecureProfileStore;
import com.nenotv.player.storage.SmartEpgStore;
import com.nenotv.player.storage.SourceStore;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

final class SourceRegistryChecks {
    static void concurrentBootstrap(Context context,SharedPreferences prefs,SecureProfileStore profiles)throws Exception{
        Profile legacy=new Profile();legacy.type=Profile.Type.M3U;legacy.m3uUrl="https://example.invalid/bootstrap";profiles.save(legacy);prefs.edit().clear().commit();
        java.util.concurrent.ExecutorService pool=java.util.concurrent.Executors.newFixedThreadPool(6);
        java.util.concurrent.CountDownLatch ready=new java.util.concurrent.CountDownLatch(6),start=new java.util.concurrent.CountDownLatch(1);
        try{
            java.util.List<java.util.concurrent.Future<String>> ids=new java.util.ArrayList<>();
            for(int n=0;n<6;n++)ids.add(pool.submit(()->{ready.countDown();check(start.await(5,java.util.concurrent.TimeUnit.SECONDS),"Bootstrap start timed out");return new SourceStore(context).activeId();}));
            check(ready.await(5,java.util.concurrent.TimeUnit.SECONDS),"Bootstrap workers did not start");start.countDown();
            String first=ids.get(0).get(10,java.util.concurrent.TimeUnit.SECONDS);check(!first.isEmpty(),"Legacy migration missing source ID");
            for(java.util.concurrent.Future<String> result:ids)check(first.equals(result.get(10,java.util.concurrent.TimeUnit.SECONDS)),"Concurrent legacy migration changed source identity");
            check(new SourceStore(context).list().size()==1,"Concurrent migration duplicated legacy source");
        }finally{start.countDown();pool.shutdownNow();check(pool.awaitTermination(5,java.util.concurrent.TimeUnit.SECONDS),"Bootstrap workers remained active");prefs.edit().clear().commit();profiles.clear();}
    }
    static Provider fake(Runnable authenticated){return new Provider(){
        public void authenticate(){authenticated.run();}
        public java.util.List<Category> categories(String type){return Collections.emptyList();}
        public java.util.List<MediaEntry> items(String type,String category){return Collections.emptyList();}
    };}
    static void sourceRouting(Context context,SourceStore store)throws Exception{
        Profile second=new Profile();second.type=Profile.Type.M3U;second.m3uUrl="https://example.invalid/second";
        String id=store.upsert("",second,false);MediaEntry item=new MediaEntry();item.sourceId=id;
        int[] authentications={0};Provider primary=fake(()->{throw new AssertionError("Primary authenticated for secondary item");});
        SourceProviderResolver.Factory factory=(profile,language)->{check(profile.m3uUrl.equals(second.m3uUrl),"Wrong provider profile routed");return fake(()->authentications[0]++);};
        SourceProviderResolver resolver=new SourceProviderResolver(context,factory);
        Provider routed=resolver.resolve(item,primary,true);
        com.nenotv.player.provider.PlaybackSourceRoute playback=com.nenotv.player.provider.PlaybackSourceRoute.resolve(context,item,true);
        check(playback.profile().m3uUrl.equals(second.m3uUrl)&&playback.isCurrent(true),"Playback used the primary profile for a secondary item");
        check(!playback.isCurrent(false),"Basic retained a secondary playback route");
        Profile exported=playback.profile();exported.m3uUrl="mutated";
        check(playback.profile().m3uUrl.equals(second.m3uUrl),"Playback snapshot exposed mutable source credentials");
        boolean playbackDenied=false;try{com.nenotv.player.provider.PlaybackSourceRoute.resolve(context,item,false);}catch(java.io.IOException expected){playbackDenied=true;}
        check(playbackDenied,"Basic opened a tagged cached favorite");
        check(com.nenotv.player.provider.PlaybackSourceRoute.resolve(context,new MediaEntry(),false).isCurrent(false),"Primary playback requires Pro");
        check(routed!=primary&&resolver.resolve(item,primary,true)==routed&&authentications[0]==1,"Secondary provider reloaded or fell back to primary");
        check(resolver.resolve(new MediaEntry(),primary,false)==primary,"Local primary requires Pro");
        check(new SourceProviderResolver(context,factory).resolve(item,primary,true)!=primary&&authentications[0]==2,"Restart lost source routing");
        boolean denied=false;try{resolver.resolve(item,primary,false);}catch(java.io.IOException expected){denied=true;}
        check(denied,"Basic used cached secondary provider");
        second.m3uUrl="https://example.invalid/changed";store.upsert(id,second,false);
        check(!playback.isCurrent(true),"Playback retained an edited source snapshot");
        second.bridgeUrl="https://example.invalid/second-bridge";second.bridgeToken="private-test-token";store.upsert(id,second,false);
        playback=com.nenotv.player.provider.PlaybackSourceRoute.resolve(context,item,true);
        check(playback.profile().bridgeUrl.equals(second.bridgeUrl)&&playback.profile().bridgeToken.equals(second.bridgeToken),"Secondary subtitles used primary bridge credentials");
        check(resolver.resolve(item,primary,true)!=routed&&authentications[0]==3,"Edited credentials reused stale provider");
        store.setEnabled(id,false);denied=false;try{resolver.resolve(item,primary,true);}catch(java.io.IOException expected){denied=true;}
        check(!playback.isCurrent(true),"Disabled source retained a playback snapshot");
        playbackDenied=false;try{com.nenotv.player.provider.PlaybackSourceRoute.resolve(context,item,true);}catch(java.io.IOException expected){playbackDenied=true;}
        check(playbackDenied,"Disabled source reopened through the queue");
        check(denied&&authentications[0]==3,"Disabled source authenticated or fell back to primary");
        store.setEnabled(id,true);
        SourceProviderResolver revoked=new SourceProviderResolver(context,(profile,language)->fake(()->store.remove(id)));
        denied=false;try{revoked.resolve(item,primary,true);}catch(java.io.IOException expected){denied=true;}
        check(denied,"Source revoked during authentication was cached");
        denied=false;try{resolver.resolve(item,primary,true);}catch(java.io.IOException expected){denied=true;}
        check(denied,"Removed source fell back to primary");
        check(!playback.isCurrent(true),"Deleted source retained a playback snapshot");
        playbackDenied=false;try{com.nenotv.player.provider.PlaybackSourceRoute.resolve(context,item,true);}catch(java.io.IOException expected){playbackDenied=true;}
        check(playbackDenied,"Deleted source fell back to primary during playback");
        store.upsert(id,second,false);
        SourceProviderResolver edited=new SourceProviderResolver(context,(profile,language)->fake(()->{
            second.m3uUrl="https://example.invalid/edited-during-auth";store.upsert(id,second,false);
        }));
        denied=false;try{edited.resolve(item,primary,true);}catch(java.io.IOException expected){denied=true;}
        check(denied,"Credentials changed during authentication were cached");
        store.remove(id);
    }
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
            concurrentBootstrap(context,sourcePrefs,profiles);
            SourceStore store=new SourceStore(context),secondInstance=new SourceStore(context);
            Profile p=new Profile();p.type=Profile.Type.M3U;p.name="Local";p.m3uUrl="https://example.invalid/local";
            String first=store.upsert("",p,false);
            check(first.equals(store.activeId())&&profiles.exists(),"First source did not mirror active profile");
            sourceRouting(context,store);
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

