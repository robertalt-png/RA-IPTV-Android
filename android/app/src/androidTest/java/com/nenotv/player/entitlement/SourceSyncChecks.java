package com.nenotv.player.entitlement;

import android.content.Context;
import android.content.SharedPreferences;
import com.nenotv.player.model.Profile;
import com.nenotv.player.storage.SecureProfileStore;
import com.nenotv.player.storage.SourceStore;
import java.io.IOException;
import java.util.*;

public final class SourceSyncChecks {
    static void check(boolean value,String message){if(!value)throw new AssertionError(message);}
    static SourceStore seed(Context context,boolean known){
        context.getSharedPreferences("nenotv_sources_v1",Context.MODE_PRIVATE).edit().clear().commit();
        new SecureProfileStore(context).clear();
        Profile profile=new Profile();profile.type=Profile.Type.M3U;profile.name="Priv\u00e9 QA";profile.m3uUrl="https://example.invalid/local.m3u";
        SourceStore sources=new SourceStore(context);sources.bindCloudAccount(new com.nenotv.player.storage.EntitlementStore(context).cloudAccountScope());sources.upsert("",profile,true);
        if(known){sources.markSynced(4);sources.touchSync();}
        return sources;
    }
    static void restore(SharedPreferences prefs,Map<String,?> previous){
        SharedPreferences.Editor editor=prefs.edit().clear();
        for(Map.Entry<String,?> entry:previous.entrySet()){
            Object value=entry.getValue();String key=entry.getKey();
            if(value instanceof String)editor.putString(key,(String)value);
            else if(value instanceof Long)editor.putLong(key,(Long)value);
            else if(value instanceof Integer)editor.putInt(key,(Integer)value);
            else if(value instanceof Boolean)editor.putBoolean(key,(Boolean)value);
        }
        editor.commit();
    }
    public static void run(Context context)throws Exception{
        SharedPreferences sourcePrefs=context.getSharedPreferences("nenotv_sources_v1",Context.MODE_PRIVATE);
        SharedPreferences entPrefs=context.getSharedPreferences("nenotv_entitlement",Context.MODE_PRIVATE);
        Map<String,?> oldSources=new HashMap<>(sourcePrefs.getAll()),oldEnt=new HashMap<>(entPrefs.getAll());
        SecureProfileStore profiles=new SecureProfileStore(context);Profile previous=profiles.exists()?profiles.load():null;
        try{
            entPrefs.edit().putString("level","PRO").putLong("expires_at",0).putString("account_email","source-owner@example.invalid").commit();
            sourcePrefs.edit().clear().commit();profiles.clear();
            SourceStore fresh=new SourceStore(context);
            com.nenotv.player.model.Profile offer=new com.nenotv.player.model.Profile();offer.type=com.nenotv.player.model.Profile.Type.M3U;offer.name="Website input";offer.m3uUrl="https://provider.example.invalid/list";
            String canonical="{\"ok\":true,\"revision\":1,\"sources\":[{\"id\":\"new-web-source\",\"type\":\"M3U\",\"name\":\"Website input\",\"m3u\":\"https://provider.example.invalid/list\",\"enabled\":true}]}";
            try(EntitlementClientChecks.Fixture fixture=new EntitlementClientChecks.Fixture(200,new String[]{"{\"ok\":true,\"revision\":0,\"sources\":[]}",canonical},false)){
                new SourceSyncClient(context,new EntitlementClient(context,fixture.url())).submitInitialSource(offer,"new-web-source");
                check(fixture.calls.get()==2&&fixture.requests.get(1).getInt("base_revision")==0,"Device input did not use website vault revision");
                check(!fresh.syncDirty()&&profiles.exists()&&"new-web-source".equals(fresh.activeId()),"Website source was not ready for package bootstrap");
            }
            String preserved=profiles.load().m3uUrl;
            try(EntitlementClientChecks.Fixture fixture=new EntitlementClientChecks.Fixture(409,"{\"ok\":false,\"error\":\"source_revision_conflict\"}",false)){
                try{new SourceSyncClient(context,new EntitlementClient(context,fixture.url())).submitInitialSource(offer,"");throw new AssertionError("Rejected source setup accepted");}catch(EntitlementClient.ServiceException expected){}
                check(preserved.equals(profiles.load().m3uUrl),"Rejected website setup destroyed previous profile");
            }
            SourceStore sources=seed(context,false);
            try(EntitlementClientChecks.Fixture fixture=new EntitlementClientChecks.Fixture(200,"{\"ok\":true,\"revision\":2,\"sources\":[]}",false)){
                new SourceSyncClient(context,new EntitlementClient(context,fixture.url())).sync();
                check(fixture.calls.get()==1&&sources.list().isEmpty()&&!profiles.exists(),"First sync restored cloud-deleted sources");
            }
            sources=seed(context,false);
            try(EntitlementClientChecks.Fixture fixture=new EntitlementClientChecks.Fixture(200,new String[]{"{\"ok\":true,\"revision\":0,\"sources\":[]}","{\"ok\":true,\"revision\":1,\"sources\":[]}"},false)){
                new SourceSyncClient(context,new EntitlementClient(context,fixture.url())).sync();
                check(fixture.calls.get()==2&&fixture.requests.get(1).getInt("base_revision")==0,"Initial upload did not use empty-vault revision");
                check("Priv\u00e9 QA".equals(fixture.requests.get(1).getJSONArray("sources").getJSONObject(0).getString("name")),"UTF-8 source name corrupted in request");
                check(!sources.syncDirty()&&sources.cloudRevision()==1,"Valid initial upload not acknowledged");
            }
            sources=seed(context,true);
            try(EntitlementClientChecks.Fixture fixture=new EntitlementClientChecks.Fixture(200,"{\"ok\":true,\"revision\":5}",false)){
                new SourceSyncClient(context,new EntitlementClient(context,fixture.url())).push();
                check(fixture.requests.get(0).getInt("base_revision")==4&&!sources.syncDirty(),"Push did not atomically capture base revision");
            }
            sources=seed(context,true);
            try(EntitlementClientChecks.Fixture fixture=new EntitlementClientChecks.Fixture(409,"{\"ok\":false,\"error\":\"source_revision_conflict\"}",false)){
                try{new SourceSyncClient(context,new EntitlementClient(context,fixture.url())).push();throw new AssertionError("Conflict accepted");}
                catch(EntitlementClient.ServiceException error){check("source_revision_conflict".equals(error.code),"Conflict identity lost");}
                check(fixture.calls.get()==1&&sources.syncDirty()&&sources.cloudRevision()==4,"Conflict repeated request or lost local edits");
            }
            sources=seed(context,true);
            try(EntitlementClientChecks.Fixture fixture=new EntitlementClientChecks.Fixture(200,"{\"ok\":true,\"revision\":5,\"sources\":[null]}",false)){
                try{new SourceSyncClient(context,new EntitlementClient(context,fixture.url())).pull();throw new AssertionError("Malformed source list accepted");}catch(IOException expected){}
                check(sources.list().size()==1&&profiles.exists(),"Malformed cloud list erased local sources");
            }
            try(EntitlementClientChecks.Fixture fixture=new EntitlementClientChecks.Fixture(200,"{\"ok\":true}",false)){
                try{new SourceSyncClient(context,new EntitlementClient(context,fixture.url())).push();throw new AssertionError("Missing revision accepted");}catch(IOException expected){}
                check(sources.syncDirty(),"Invalid push acknowledgment lost local edits");
            }
            sources=seed(context,true);
            try(EntitlementClientChecks.Fixture fixture=new EntitlementClientChecks.Fixture(200,"{\"ok\":true,\"revision\":5,\"sources\":[]}",false)){
                SourceSyncClient client=new SourceSyncClient(context,new EntitlementClient(context,fixture.url()));
                check(!client.pullAutomatically()&&fixture.calls.get()==0&&sources.list().size()==1,"Automatic download replaced local edits or uploaded credentials");
                sources.markSynced(4);sources.setAutomaticDownloadEnabled(false);
                check(!client.pullAutomatically()&&fixture.calls.get()==0,"Local-only mode contacted server");
                sources.setAutomaticDownloadEnabled(true);
                check(client.pullAutomatically()&&fixture.calls.get()==1&&sources.list().isEmpty()&&!profiles.exists(),"Automatic download did not apply cloud deletion");
                check(!fixture.requests.get(0).has("sources"),"Automatic download uploaded local credentials");
                check(!client.pullAutomatically()&&fixture.calls.get()==2,"Unchanged revision caused repeated import");
            }
            sources=seed(context,true);sources.markSynced(4);
            try(EntitlementClientChecks.Fixture fixture=new EntitlementClientChecks.Fixture(200,"{\"ok\":true,\"revision\":3,\"sources\":[]}",false)){
                try{new SourceSyncClient(context,new EntitlementClient(context,fixture.url())).pullAutomatically();throw new AssertionError("Cloud revision went backwards");}catch(IOException expected){}
                check(sources.list().size()==1,"Older snapshot erased local sources");
            }
            sources=seed(context,true);
            try(EntitlementClientChecks.Fixture fixture=new EntitlementClientChecks.Fixture(200,"{\"ok\":true,\"revision\":0,\"sources\":[]}",false)){
                SourceSyncClient oldClient=new SourceSyncClient(context,new EntitlementClient(context,fixture.url()));
                entPrefs.edit().putString("account_email","different-owner@example.invalid").commit();
                try{oldClient.push();throw new AssertionError("Old client uploaded to changed account");}catch(IOException expected){}
                check(fixture.calls.get()==0,"Account change exposed source payload before confirmation");
                SourceSyncClient newClient=new SourceSyncClient(context,new EntitlementClient(context,fixture.url()));
                try{newClient.sync();throw new AssertionError("New account silently inherited previous sources");}catch(IOException expected){}
                try{newClient.push();throw new AssertionError("New account uploaded before confirmation");}catch(IOException expected){}
                check(!newClient.pullAutomatically()&&fixture.calls.get()==0&&sources.list().size()==1&&sources.cloudRevision()==0&&sources.accountChangePending(),"Account change lost local sources or reused another account revision");
                newClient.pull();
                check(!sources.accountChangePending()&&sources.list().isEmpty()&&fixture.calls.get()==1,"Explicit new-account cloud choice not applied");
                check(fixture.requests.get(0).getString("account_scope").equals(new com.nenotv.player.storage.EntitlementStore(context).cloudAccountScope())&&!fixture.requests.get(0).has("sources"),"Explicit cloud choice uploaded previous credentials");
            }
            sources=seed(context,true);
            sourcePrefs.edit().putString("sources","invalid-encrypted-data").commit();
            boolean unreadable=false;try{sources.exportForSync();}catch(IllegalStateException expected){unreadable=true;}
            check(unreadable&&"invalid-encrypted-data".equals(sourcePrefs.getString("sources","")),"Unreadable local source vault was treated as empty");
            sources=seed(context,true);
            entPrefs.edit().putString("level","FREE").commit();
            try(EntitlementClientChecks.Fixture fixture=new EntitlementClientChecks.Fixture(200,"{\"ok\":true}",false)){
                SourceSyncClient client=new SourceSyncClient(context,new EntitlementClient(context,fixture.url()));
                try{client.push();throw new AssertionError("Basic pushed cloud sources");}catch(IOException expected){}
                try{client.pull();throw new AssertionError("Basic pulled cloud sources");}catch(IOException expected){}
                check(!client.pullAutomatically(),"Basic downloaded sources automatically");
                check(fixture.calls.get()==0,"Basic contacted source service");
            }
        }finally{restore(sourcePrefs,oldSources);restore(entPrefs,oldEnt);if(previous==null)profiles.clear();else profiles.save(previous);}
    }
}
