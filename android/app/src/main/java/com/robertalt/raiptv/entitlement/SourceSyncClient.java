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

    public SourceSyncClient(Context context){this(context,new EntitlementClient(context));}
    SourceSyncClient(Context context,EntitlementClient transport){
        entitlement=new EntitlementStore(context);
        sources=new SourceStore(context);
        this.transport=transport;
    }

    private void requirePro()throws IOException{if(!entitlement.isPro())throw new IOException("PRO_REQUIRED");}

    public JSONObject sync()throws Exception{
        requirePro();
        // First contact must not overwrite an account vault with a migrated local source.
        if(sources.cloudRevision()<=0){
            SourceStore.SyncSnapshot snapshot=sources.snapshotForSync();
            JSONObject remote=post("pull",new JSONObject());
            JSONArray rows=remote.optJSONArray("sources");
            int revision=remote.optInt("revision",0);
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

    public JSONObject push()throws Exception{
        requirePro();
        SourceStore.SyncSnapshot snapshot=sources.snapshotForSync();
        if(snapshot.sources.length()>20)throw new IOException("SOURCE_LIMIT");
        JSONObject body=new JSONObject().put("sources",snapshot.sources).put("base_revision",snapshot.cloudRevision);
        JSONObject out=post("push",body);
        int revision=out.optInt("revision",-1);
        if(revision<=snapshot.cloudRevision)throw new IOException("INVALID_SOURCE_RESPONSE");
        if(!sources.markSyncedIfUnchanged(revision,snapshot.localRevision))throw new IOException("LOCAL_SOURCES_CHANGED_RETRY_SYNC");
        return out;
    }

    private JSONObject post(String action,JSONObject body)throws Exception{return transport.request("sources/"+action,body,1048576);}
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
