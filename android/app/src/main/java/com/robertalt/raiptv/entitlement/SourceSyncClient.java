package com.nenotv.player.entitlement;

import android.content.Context;
import com.nenotv.player.BuildConfig;
import com.nenotv.player.storage.EntitlementStore;
import com.nenotv.player.storage.SourceStore;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/** Account-scoped Pro source sync. Light never depends on this class. */
public final class SourceSyncClient {
    private final Context app;
    private final EntitlementStore entitlement;
    private final SourceStore sources;

    public SourceSyncClient(Context c){
        app=c.getApplicationContext();
        entitlement=new EntitlementStore(app);
        sources=new SourceStore(app);
    }

    public JSONObject sync() throws Exception {
        if(!entitlement.isPro())throw new IOException("PRO_REQUIRED");
        // First contact is cloud-first so a newly upgraded/reinstalled device cannot
        // overwrite an existing account vault with its locally migrated legacy source.
        if(sources.cloudRevision()<=0){
            JSONObject remote=post("pull",baseBody());
            JSONArray rows=remote.optJSONArray("sources");
            int revision=remote.optInt("revision",0);
            if(rows!=null&&rows.length()>0){
                sources.applyCloudSnapshot(rows,revision);
                return remote;
            }
            if(sources.syncDirty())return push();
            sources.markSynced(revision);
            return remote;
        }
        if(sources.syncDirty())return push();
        return pull();
    }

    public JSONObject pull() throws Exception {
        JSONObject out=post("pull",baseBody());
        JSONArray remote=out.optJSONArray("sources");
        if(remote!=null)sources.applyCloudSnapshot(remote,out.optInt("revision",0));
        return out;
    }

    public JSONObject push() throws Exception {
        JSONObject body=baseBody();
        body.put("sources",sources.exportForSync());
        JSONObject out=post("push",body);
        sources.markSynced(out.optInt("revision",0));
        return out;
    }

    private JSONObject baseBody() throws Exception {
        JSONObject o=new JSONObject();
        o.put("device_id",entitlement.deviceId());
        o.put("public_device_id",entitlement.publicDeviceId());
        o.put("device_key",entitlement.deviceKey());
        o.put("platform","android");
        o.put("app_version",BuildConfig.VERSION_NAME);
        return o;
    }

    private JSONObject post(String action,JSONObject body) throws Exception {
        Exception first=null;
        try{return postUrl("https://nenotv.com/wp-json/nenotv/v1/sources/"+action,body);}
        catch(Exception e){first=e;}
        try{return postUrl("https://nenotv.com/index.php?rest_route=/nenotv/v1/sources/"+action,body);}
        catch(Exception e){
            String m=e.getMessage();
            if(m==null||m.trim().isEmpty())m=first==null?"NenoTV source sync unavailable":first.getMessage();
            throw new IOException(m);
        }
    }

    private JSONObject postUrl(String url,JSONObject body) throws Exception {
        HttpURLConnection c=(HttpURLConnection)new URL(url).openConnection();
        c.setInstanceFollowRedirects(false);
        c.setConnectTimeout(9000);
        c.setReadTimeout(12000);
        c.setRequestMethod("POST");
        c.setDoOutput(true);
        c.setRequestProperty("Content-Type","application/json; charset=utf-8");
        c.setRequestProperty("Accept","application/json");
        c.setRequestProperty("User-Agent","NenoTV/"+BuildConfig.VERSION_NAME+" Android");
        byte[] bytes=body.toString().getBytes(StandardCharsets.UTF_8);
        c.setFixedLengthStreamingMode(bytes.length);
        try(OutputStream os=c.getOutputStream()){os.write(bytes);}
        int code=c.getResponseCode();
        InputStream in=code>=200&&code<300?c.getInputStream():c.getErrorStream();
        String text=read(in).trim();
        if(text.startsWith("<"))throw new IOException("Unexpected HTML response");
        JSONObject out=new JSONObject(text);
        if(code<200||code>=300||!out.optBoolean("ok",false))
            throw new IOException(out.optString("message",out.optString("error","HTTP "+code)));
        return out;
    }

    private static String read(InputStream in)throws IOException{
        if(in==null)return "";
        try(BufferedReader r=new BufferedReader(new InputStreamReader(in,StandardCharsets.UTF_8))){
            StringBuilder b=new StringBuilder();String s;while((s=r.readLine())!=null)b.append(s);return b.toString();
        }
    }
}
