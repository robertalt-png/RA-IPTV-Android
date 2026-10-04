package com.nenotv.player.entitlement;

import android.content.Context;
import com.nenotv.player.storage.EntitlementStore;
import com.nenotv.player.storage.SourceStore;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.IOException;

/** Account-scoped Pro source sync. Light never depends on this class. */
public final class SourceSyncClient {
    private final EntitlementStore entitlement;
    private final SourceStore sources;
    private final EntitlementClient transport;
    private final String accountScope;

    public SourceSyncClient(Context context){this(context,new EntitlementClient(context));}
    SourceSyncClient(Context context,EntitlementClient transport){
        entitlement=new EntitlementStore(context);
        sources=new SourceStore(context);
        this.transport=transport;
        accountScope=entitlement.cloudAccountScope();
    }

    private void requirePro()throws IOException{
        if(!entitlement.isPro())throw new IOException("PRO_REQUIRED");
        if(accountScope.isEmpty())throw new IOException("ACCOUNT_SCOPE_REQUIRED");
        if(!accountScope.equals(entitlement.cloudAccountScope()))throw new IOException("SOURCE_ACCOUNT_CHANGED_CONFIRM");
        sources.bindCloudAccount(accountScope);
    }

    public JSONObject sync()throws Exception{
        requirePro();
        // First contact must not overwrite an account vault with a migrated local source.
        if(sources.accountChangePending())throw new IOException("SOURCE_ACCOUNT_CHANGED_CONFIRM");
        if(sources.cloudRevision()<=0){
            SourceStore.SyncSnapshot snapshot=sources.snapshotForSync();
            JSONObject remote=post("pull",new JSONObject());
            JSONArray rows=remote.optJSONArray("sources");
            int revision=remote.optInt("revision",-1);
            if(rows==null||revision<0)throw new IOException("INVALID_SOURCE_RESPONSE");
            validateRows(rows);
            if(rows.length()>0||revision>0){
                if(!sources.applyCloudSnapshotIfUnchanged(rows,revision,snapshot.localRevision))throw new IOException("LOCAL_SOURCES_CHANGED_RETRY_SYNC");
                return remote;
            }
            if(!sources.markCloudRevisionIfUnchanged(revision,snapshot.localRevision))throw new IOException("LOCAL_SOURCES_CHANGED_RETRY_SYNC");
            if(sources.syncDirty())return push();
            return remote;
        }
        return sources.syncDirty()?push():pull();
    }

    public JSONObject pull()throws Exception{
        requirePro();
        SourceStore.SyncSnapshot snapshot=sources.snapshotForSync();
        JSONObject out=post("pull",new JSONObject());
        JSONArray remote=out.optJSONArray("sources");
        int revision=out.optInt("revision",-1);
        if(remote==null||revision<0)throw new IOException("INVALID_SOURCE_RESPONSE");
        validateRows(remote);
        if(!sources.applyCloudSnapshotIfUnchanged(remote,revision,snapshot.localRevision))throw new IOException("LOCAL_SOURCES_CHANGED_RETRY_SYNC");
        return out;
    }

    /** Download only; background work never uploads credentials or replaces unsynced edits. */
    public boolean pullAutomatically()throws Exception{
        if(!entitlement.isPro()||!sources.automaticDownloadEnabled())return false;
        requirePro();
        if(sources.syncDirty()||sources.accountChangePending())return false;
        SourceStore.SyncSnapshot snapshot=sources.snapshotForSync();
        JSONObject out=post("pull",new JSONObject());
        JSONArray remote=out.optJSONArray("sources");
        int revision=out.optInt("revision",-1);
        if(remote==null||revision<snapshot.cloudRevision)throw new IOException("INVALID_SOURCE_RESPONSE");
        validateRows(remote);
        if(revision==snapshot.cloudRevision)return false;
        requirePro();
        return sources.applyAutomaticCloudSnapshotIfUnchanged(remote,revision,snapshot.localRevision);
    }

    /** Only called after explicit website-preparation consent; no provider fetch on the app. */
    public String submitInitialSource(com.nenotv.player.model.Profile profile,String requestedId)throws Exception {
        requirePro();
        if(sources.accountChangePending()||sources.syncDirty())throw new IOException("LOCAL_SOURCES_CHANGED_RETRY_SYNC");
        SourceStore.SyncSnapshot snapshot=sources.snapshotForSync();
        JSONObject remote=post("pull",new JSONObject());
        JSONArray rows=remote.getJSONArray("sources");validateRows(rows);
        String id=requestedId==null||requestedId.isEmpty()?java.util.UUID.randomUUID().toString():requestedId;
        JSONObject row=new JSONObject().put("id",id).put("type",profile.type.name()).put("name",profile.name)
            .put("server",profile.server).put("username",profile.username).put("password",profile.password)
            .put("m3u",profile.m3uUrl).put("epg",profile.epgUrl).put("epg_extra",new JSONArray())
            .put("enabled",true).put("priority",0).put("updated_at",System.currentTimeMillis());
        boolean replaced=false;
        for(int i=0;i<rows.length();i++)if(id.equals(rows.getJSONObject(i).getString("id"))){row.put("priority",rows.getJSONObject(i).optInt("priority",0));rows.put(i,row);replaced=true;break;}
        if(!replaced)rows.put(row);
        if(rows.length()>20)throw new IOException("SOURCE_LIMIT");
        if(sources.localRevision()!=snapshot.localRevision)throw new IOException("LOCAL_SOURCES_CHANGED_RETRY_SYNC");
        JSONObject saved=post("push",new JSONObject().put("sources",rows).put("base_revision",remote.getInt("revision")));
        JSONArray canonical=saved.getJSONArray("sources");validateRows(canonical);
        if(!sources.applyCloudSnapshotIfUnchanged(canonical,saved.getInt("revision"),snapshot.localRevision))throw new IOException("LOCAL_SOURCES_CHANGED_RETRY_SYNC");
        sources.setActive(id);
        return id;
    }

    public JSONObject push()throws Exception{
        requirePro();
        if(sources.accountChangePending())throw new IOException("SOURCE_ACCOUNT_CHANGED_CONFIRM");
        SourceStore.SyncSnapshot snapshot=sources.snapshotForSync();
        if(snapshot.sources.length()>20)throw new IOException("SOURCE_LIMIT");
        JSONObject body=new JSONObject().put("sources",snapshot.sources).put("base_revision",snapshot.cloudRevision);
        JSONObject out=post("push",body);
        int revision=out.optInt("revision",-1);
        if(revision<=snapshot.cloudRevision)throw new IOException("INVALID_SOURCE_RESPONSE");
        if(!sources.markSyncedIfUnchanged(revision,snapshot.localRevision))throw new IOException("LOCAL_SOURCES_CHANGED_RETRY_SYNC");
        return out;
    }

    private JSONObject post(String action,JSONObject body)throws Exception{
        requirePro();body.put("account_scope",accountScope);
        JSONObject result=transport.request("sources/"+action,body,1048576);
        requirePro();return result;
    }
    private static void validateRows(JSONArray rows)throws IOException{
        if(rows.length()>20)throw new IOException("INVALID_SOURCE_RESPONSE");
        java.util.HashSet<String> ids=new java.util.HashSet<>();
        for(int i=0;i<rows.length();i++){
            JSONObject row=rows.optJSONObject(i);
            if(row==null)throw new IOException("INVALID_SOURCE_RESPONSE");
            String id=row.optString("id","");
            if(id.isEmpty()||id.length()>80||!ids.add(id)||!java.util.Arrays.asList("M3U","XTREAM").contains(row.optString("type","")))throw new IOException("INVALID_SOURCE_RESPONSE");
        }
    }
}
