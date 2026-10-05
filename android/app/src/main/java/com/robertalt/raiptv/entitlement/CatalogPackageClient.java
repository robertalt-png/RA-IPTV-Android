package com.nenotv.player.entitlement;

import android.content.Context;
import android.content.SharedPreferences;
import com.nenotv.player.BuildConfig;
import com.nenotv.player.storage.*;
import com.nenotv.player.model.Profile;
import com.nenotv.player.net.StreamingJsonArray;
import org.json.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/** Website bootstrap; provider maintenance remains independent after successful import. */
public final class CatalogPackageClient {
    private final Context context;
    private final EntitlementStore entitlement;
    private final SourceStore sources;
    private final SharedPreferences settings;
    private final String scope;
    public CatalogPackageClient(Context c){context=c.getApplicationContext();entitlement=new EntitlementStore(context);sources=new SourceStore(context);settings=SettingsStore.prefs(context);scope=entitlement.cloudAccountScope();}
    public static final class Pending extends IOException {public Pending(){super("CATALOG_PREPARING");}}
    private void guard(String id,long revision){
        if(!entitlement.isPro()||scope.isEmpty()||!scope.equals(entitlement.cloudAccountScope())||sources.syncDirty()||sources.accountChangePending()||sources.localRevision()!=revision||!id.equals(sources.activeId()))throw new IllegalStateException("CATALOG_ACCOUNT_CHANGED");
    }
    public boolean bootstrap(SearchIndexStore index,String profileKey,Profile profile,CatalogPackageImporter.Progress progress)throws Exception {
        if(com.nenotv.player.DemoPolicy.isDemo(profile))return false;
        SourceStore.Entry active=sources.active();
        if(!entitlement.isPro()||scope.isEmpty()||sources.syncDirty()||sources.accountChangePending()||active==null||!active.enabled)return false;
        Profile actual=active.profile;
        if(actual.type!=profile.type||!actual.server.equals(profile.server)||!actual.username.equals(profile.username)||!actual.password.equals(profile.password)||!actual.m3uUrl.equals(profile.m3uUrl))return false;
        String marker="catalog_bootstrap_"+scope+"_"+active.id+"_"+profileKey;
        if(settings.getBoolean(marker,false)&&index.isComplete(profileKey,"live")&&index.isComplete(profileKey,"vod")&&index.isComplete(profileKey,"series"))return true;
        // Preserve an existing fully imported library instead of replacing it with a server snapshot.
        if(settings.getBoolean("first_sync_done_"+profileKey,false))return false;
        SourceStore.SyncSnapshot snapshot=sources.snapshotForSync();long revision=snapshot.localRevision;guard(active.id,revision);
        EntitlementClient transport=new EntitlementClient(context);
        JSONObject body=new JSONObject().put("source_id",active.id).put("account_scope",scope);
        JSONObject manifest=transport.request("catalog/status",body,16384);guard(active.id,revision);
        if("failed".equals(manifest.optString("state")))throw new IOException("CATALOG_PREPARATION_FAILED");
        if(!"ready".equals(manifest.optString("state")))throw new Pending();
        if(manifest.getInt("schema")!=1||!active.id.equals(manifest.getString("source_id"))||!manifest.getString("fingerprint").matches("[a-f0-9]{64}"))throw new IOException("CATALOG_MANIFEST");
        File temp=File.createTempFile("nenotv-catalog-",".gz",context.getCacheDir());
        try{
            download(manifest,temp,()->guard(active.id,revision));
            CatalogPackageImporter.importFile(temp,manifest,index,profileKey,progress,()->guard(active.id,revision));
            guard(active.id,revision);
            settings.edit().putBoolean(marker,true).putBoolean("first_sync_done_"+profileKey,true).putInt("language_index_version_"+profileKey,4).putBoolean("language_index_rebuild_started_"+profileKey,false).commit();
            try{transport.request("catalog/ack",new JSONObject().put("source_id",active.id).put("account_scope",scope).put("fingerprint",manifest.getString("fingerprint")).put("sha256",manifest.getString("sha256")));}catch(Exception ignored){/* The verified local import remains usable if its acknowledgement is offline. */}
            return true;
        }finally{if(!temp.delete())temp.deleteOnExit();}
    }
    private void download(JSONObject manifest,File file,Runnable guard)throws Exception {
        long expected=manifest.getLong("bytes");if(expected<1||expected>CatalogPackageImporter.MAX_COMPRESSED)throw new IOException("CATALOG_SIZE");
        JSONObject body=new JSONObject().put("device_id",entitlement.deviceId()).put("device_key",entitlement.deviceKey()).put("public_device_id",entitlement.publicDeviceId()).put("platform","android").put("app_version",BuildConfig.VERSION_NAME).put("account_scope",scope).put("source_id",manifest.getString("source_id")).put("fingerprint",manifest.getString("fingerprint"));
        byte[] payload=body.toString().getBytes(StandardCharsets.UTF_8);
        HttpURLConnection connection=(HttpURLConnection)new URL("https://sunnyiptv.com/wp-json/nenotv/v1/catalog/download").openConnection();
        try{
            connection.setInstanceFollowRedirects(false);connection.setConnectTimeout(15000);connection.setReadTimeout(20000);connection.setRequestMethod("POST");connection.setDoOutput(true);connection.setFixedLengthStreamingMode(payload.length);connection.setRequestProperty("Content-Type","application/json");connection.setRequestProperty("Accept","application/vnd.nenotv.catalog+gzip");connection.setRequestProperty("Accept-Encoding","identity");
            try(OutputStream out=connection.getOutputStream()){out.write(payload);}
            if(connection.getResponseCode()!=200||!"application/vnd.nenotv.catalog+gzip".equals(connection.getContentType())||!manifest.getString("sha256").equals(connection.getHeaderField("X-NenoTV-SHA256")))throw new IOException("CATALOG_DOWNLOAD");
            long deadline=android.os.SystemClock.elapsedRealtime()+180000;long bytes=0;
            try(InputStream in=connection.getInputStream();OutputStream out=new BufferedOutputStream(new FileOutputStream(file),65536)){
                byte[] buf=new byte[65536];int n;while((n=in.read(buf))!=-1){StreamingJsonArray.checkCancelled();guard.run();if(android.os.SystemClock.elapsedRealtime()>deadline||(bytes+=n)>expected)throw new IOException("CATALOG_DOWNLOAD_LIMIT");out.write(buf,0,n);}
            }
            if(bytes!=expected)throw new IOException("CATALOG_TRUNCATED");
        }finally{connection.disconnect();}
    }
}
