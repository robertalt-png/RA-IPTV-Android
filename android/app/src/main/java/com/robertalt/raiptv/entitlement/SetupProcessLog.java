package com.nenotv.player.entitlement;

import android.content.Context;
import android.os.SystemClock;
import com.nenotv.player.BuildConfig;
import com.nenotv.player.storage.AccountLinkStore;
import org.json.JSONObject;
import java.util.UUID;
import java.util.concurrent.*;

/** Bounded, best-effort technical diagnostics; never send provider values or exception text. */
final class SetupProcessLog implements AutoCloseable {
    private final Context context;
    private final String account,run=UUID.randomUUID().toString();
    private final long started=SystemClock.elapsedRealtime();
    private final EntitlementClient client;
    private final ThreadPoolExecutor sender=new ThreadPoolExecutor(1,1,10,TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(8),r->{Thread t=new Thread(r,"SunnyIPTV-setup-log");t.setDaemon(true);return t;},new ThreadPoolExecutor.DiscardPolicy());
    private WebsiteSetupJob.State previous;
    private long last;
    private ScheduledExecutorService ticker;
    SetupProcessLog(Context c,String account,EntitlementClient client){this.context=c;this.account=account;this.client=client;}
    synchronized void observe(WebsiteSetupJob job){
        if(client==null)return;
        ticker=Executors.newSingleThreadScheduledExecutor(r->{Thread t=new Thread(r,"SunnyIPTV-setup-heartbeat");t.setDaemon(true);return t;});
        ticker.scheduleAtFixedRate(()->record(job),5,5,TimeUnit.SECONDS);
    }
    synchronized void record(WebsiteSetupJob job){
        long now=SystemClock.elapsedRealtime();WebsiteSetupJob.State state=job.state;
        if(client==null||account.isEmpty()||sender.isShutdown()||(state==previous&&now-last<30000))return;
        previous=state;last=now;
        try{
            JSONObject row=new JSONObject().put("account_scope",account).put("run",run).put("event",state.name()).put("version",BuildConfig.VERSION_NAME).put("error",job.errorCode)
                    .put("elapsed_ms",Math.min(7200000,now-started)).put("saved_items",job.savedItems).put("total_items",job.totalItems)
                    .put("bytes",job.bytesReceived).put("total_bytes",job.totalBytes).put("download_ms",Math.max(0,job.downloadElapsedMillis))
                    .put("idle_ms",job.lastProgressAt==0?0:Math.min(7200000,Math.max(0,now-job.lastProgressAt)));
            sender.execute(()->{try{if(account.equals(new AccountLinkStore(context).accountId()))client.request("account/setup-log",row,4096);}catch(Exception ignored){/* Diagnostics never prevent setup or expose exception messages. */}});
        }catch(Exception ignored){}
    }
    @Override public synchronized void close(){if(ticker!=null)ticker.shutdownNow();sender.shutdown();}
}
