package com.nenotv.player.entitlement;

import android.content.*;
import com.nenotv.player.ProfileCacheKey;
import com.nenotv.player.model.Profile;
import com.nenotv.player.storage.*;
import org.json.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

public final class WebsiteSetupChecks {
    private static void check(boolean b,String message){if(!b)throw new AssertionError(message);}
    private static final String FP=String.join("",Collections.nCopies(64,"c"));
    private static final class Fixture implements AutoCloseable {
        final ServerSocket server;
        final Thread thread;
        final byte[] packageBytes;
        final JSONObject manifest;
        final List<String> paths=new java.util.concurrent.CopyOnWriteArrayList<>();
        volatile int pulls,statuses,downloads;
        Fixture(boolean corrupt)throws Exception {
            String header=new JSONObject().put("kind","header").put("schema",1).put("source_id","website-test").put("fingerprint",FP)+"\n";
            String category=new JSONObject().put("kind","category").put("type","live").put("id","c").put("name","QA")+"\n";
            StringBuilder records=new StringBuilder();for(int i=0;i<1000;i++)records.append(new JSONObject().put("kind","item").put("entry",CatalogPackageChecks.entry("web"+i,"live"))).append('\n');
            ByteArrayOutputStream bytes=new ByteArrayOutputStream();bytes.write(CatalogPackageChecks.gzip(header+category));bytes.write(CatalogPackageChecks.gzip(records.toString()));bytes.write(CatalogPackageChecks.gzip(CatalogPackageChecks.end(1000)));packageBytes=bytes.toByteArray();
            StringBuilder sha=new StringBuilder();for(byte b:MessageDigest.getInstance("SHA-256").digest(packageBytes))sha.append(String.format(Locale.ROOT,"%02x",b&255));
            manifest=new JSONObject().put("ok",true).put("state","ready").put("schema",1).put("source_id","website-test").put("fingerprint",FP).put("bytes",packageBytes.length).put("sha256",corrupt?String.join("",Collections.nCopies(64,"0")):sha.toString()).put("counts",new JSONObject().put("live",1000).put("vod",0).put("series",0));
            server=new ServerSocket(0,8,InetAddress.getByName("127.0.0.1"));
            thread=new Thread(()->{
                while(!server.isClosed())try(Socket socket=server.accept()){
                    socket.setSoTimeout(5000);InputStream in=socket.getInputStream();ByteArrayOutputStream head=new ByteArrayOutputStream();int tail=0;
                    while(head.size()<16384){int b=in.read();if(b<0)throw new EOFException();head.write(b);tail=(tail<<8)|b;if(tail==0x0d0a0d0a)break;}
                    String[] lines=head.toString("US-ASCII").split("\r\n");String path=lines[0].split(" ")[1];paths.add(path);int length=0;
                    for(String line:lines)if(line.toLowerCase(Locale.ROOT).startsWith("content-length:"))length=Integer.parseInt(line.substring(15).trim());
                    byte[] body=new byte[length];int offset=0;while(offset<length){int n=in.read(body,offset,length-offset);if(n<0)throw new EOFException();offset+=n;}
                    JSONObject request=new JSONObject(new String(body,StandardCharsets.UTF_8));
                    boolean download=path.endsWith("catalog/download");byte[] response;
                    if(path.endsWith("sources/pull")){
                        pulls++;JSONArray sources=new JSONArray();
                        if(pulls>1)sources.put(new JSONObject().put("id","website-test").put("type","M3U").put("name","Synthetic website list").put("m3u",url()+"/never-fetch-provider").put("enabled",true));
                        response=new JSONObject().put("ok",true).put("revision",pulls>1?1:0).put("sources",sources).toString().getBytes(StandardCharsets.UTF_8);
                    }else if(path.endsWith("catalog/status")){
                        statuses++;response=(statuses==1?new JSONObject().put("ok",true).put("state","building"):manifest).toString().getBytes(StandardCharsets.UTF_8);
                    }else if(download){downloads++;response=packageBytes;}
                    else response="{\"ok\":true}".getBytes(StandardCharsets.UTF_8);
                    check(request.getString("account_scope").equals(FP),"Wrong source account sent");
                    OutputStream out=socket.getOutputStream();String headers="HTTP/1.1 200 OK\r\nContent-Type: "+(download?"application/vnd.nenotv.catalog+gzip":"application/json")+"\r\nContent-Length: "+response.length+"\r\nConnection: close\r\n";
                    if(download)headers+="X-SunnyIPTV-SHA256: "+manifest.getString("sha256")+"\r\n";
                    out.write((headers+"\r\n").getBytes(StandardCharsets.US_ASCII));out.write(response);out.flush();
                }catch(Exception ignored){}
            },"website-fixture");thread.setDaemon(true);thread.start();
        }
        String url(){return "http://127.0.0.1:"+server.getLocalPort();}
        public void close()throws Exception{server.close();thread.join(5000);}
    }
    public static void run(Context context)throws Exception {
        String[] names={"nenotv_sources_v1","nenotv_entitlement","nenotv_account_link_v1","sunnyiptv_family_v1"};
        Map<String,Map<String,?>> before=new HashMap<>();for(String name:names)before.put(name,new HashMap<>(context.getSharedPreferences(name,Context.MODE_PRIVATE).getAll()));
        SecureProfileStore secure=new SecureProfileStore(context);Profile previous=secure.exists()?secure.load():null;
        WebsiteSetupJob.Network old=WebsiteSetupJob.network;WebsiteSetupJob job=null;
        try {
            context.getSharedPreferences("sunnyiptv_family_v1",Context.MODE_PRIVATE).edit().clear().commit();
            context.getSharedPreferences("nenotv_entitlement",Context.MODE_PRIVATE).edit().putString("level","FREE").commit();
            new AccountLinkStore(context).apply(new JSONObject().put("status","active").put("kind","account").put("account_id",FP));
            for(boolean corrupt:new boolean[]{false,true})try(Fixture fixture=new Fixture(corrupt)){
                secure.clear();context.getSharedPreferences("nenotv_sources_v1",Context.MODE_PRIVATE).edit().clear().commit();
                WebsiteSetupJob.network=new WebsiteSetupJob.Network(){
                    public SourceSyncClient sources(Context c){return new SourceSyncClient(c,new EntitlementClient(c,fixture.url()));}
                    public CatalogPackageClient catalog(Context c){return new CatalogPackageClient(c,fixture.url());}
                };
                // Android Keystore encryption is substantially slower on some hosted phone emulators.
                job=WebsiteSetupJob.start(context,false);long started=android.os.SystemClock.elapsedRealtime(),end=started+120000;
                while(android.os.SystemClock.elapsedRealtime()<end&&WebsiteSetupJob.busy(job.state))Thread.sleep(100);
                check(job.state==(corrupt?WebsiteSetupJob.State.FAILED:WebsiteSetupJob.State.READY),"Website setup did not reach verified outcome: "+job.state
                        +"; elapsed_ms="+(android.os.SystemClock.elapsedRealtime()-started)+"; pulls="+fixture.pulls
                        +"; statuses="+fixture.statuses+"; downloads="+fixture.downloads);
                check(job.bytesReceived==job.totalBytes&&job.totalBytes==fixture.packageBytes.length&&job.downloadElapsedMillis>=0,"Download progress did not measure the complete package");
                if(!corrupt)check(job.savedItems==1000&&job.totalItems==1000&&job.lastProgressAt>=job.phaseStarted,"Stored-item progress did not match the verified list");
                try(SearchIndexStore index=new SearchIndexStore(context)){
                    String key=ProfileCacheKey.of(secure.load());
                    check(index.countSection(key,"live")== (corrupt?0:1000),"Website import exposed a partial or corrupt library");
                    if(!corrupt)check(index.isComplete(key,"vod")&&index.isComplete(key,"series"),"Website list not committed as one package");
                    for(String type:new String[]{"live","vod","series"})index.replaceSection(key,type,Collections.emptyList());
                    SettingsStore.prefs(context).edit().remove("first_sync_done_"+key).commit();
                }
                check(fixture.pulls>=2&&fixture.statuses>=2&&fixture.downloads==1,"Website handoff did not wait for source and complete package");
                check(fixture.paths.stream().allMatch(p->p.contains("/account/")),"Basic setup contacted paid or provider routes");
                check(!new EntitlementStore(context).isPro(),"Basic package granted Pro");
            }
        } finally {
            if(job!=null){java.lang.reflect.Field field=WebsiteSetupJob.class.getDeclaredField("worker");field.setAccessible(true);Thread worker=(Thread)field.get(job);worker.interrupt();worker.join(5000);}
            WebsiteSetupJob.network=old;
            for(String name:names)SourceSyncChecks.restore(context.getSharedPreferences(name,Context.MODE_PRIVATE),before.get(name));
            if(previous==null)secure.clear();else secure.save(previous);
        }
    }
}
