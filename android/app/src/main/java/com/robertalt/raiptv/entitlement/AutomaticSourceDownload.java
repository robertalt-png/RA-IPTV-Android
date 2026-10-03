package com.nenotv.player.entitlement;

import android.content.Context;
import android.os.SystemClock;
import com.nenotv.player.storage.EntitlementStore;
import com.nenotv.player.storage.SourceStore;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** One bounded account-source check per foreground interval, independent of media import. */
public final class AutomaticSourceDownload {
    private static final ExecutorService WORKER=Executors.newSingleThreadExecutor();
    private static boolean running;
    private static long lastAttempt=-1;
    private AutomaticSourceDownload(){}
    public static void check(Context context,Runnable changed){
        Context app=context.getApplicationContext();
        try{
            SourceStore sources=new SourceStore(app);
            if(!new EntitlementStore(app).isPro()||!sources.automaticDownloadEnabled()||sources.syncDirty())return;
        }catch(Exception ignored){return;}
        synchronized(AutomaticSourceDownload.class){
            long now=SystemClock.elapsedRealtime();
            if(running||(lastAttempt>=0&&now-lastAttempt<60000))return;
            running=true;lastAttempt=now;
        }
        WORKER.execute(()->{
            try{if(new SourceSyncClient(app).pullAutomatically())changed.run();}
            catch(Exception ignored){} // Offline, conflicts and unreadable storage leave local data intact.
            finally{synchronized(AutomaticSourceDownload.class){running=false;}}
        });
    }
}
