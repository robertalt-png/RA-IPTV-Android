package com.nenotv.player.entitlement;

import android.content.Context;
import com.nenotv.player.DemoPolicy;
import com.nenotv.player.ProfileCacheKey;
import com.nenotv.player.storage.*;

/** Shares a bounded import across browser handoff and the return activity. */
public final class WebsiteSetupJob {
    public enum State { WAITING, PREPARING, DOWNLOADING, VERIFYING, IMPORTING, COMMITTING, READY, CHOICE, FAILED, EXPIRED }
    private static WebsiteSetupJob current;
    interface Network {
        SourceSyncClient sources(Context context);
        CatalogPackageClient catalog(Context context);
        default EntitlementClient diagnostics(Context context){return null;}
    }
    static Network network = new Network() {
        public SourceSyncClient sources(Context c) { return new SourceSyncClient(c); }
        public CatalogPackageClient catalog(Context c) { return new CatalogPackageClient(c); }
        public EntitlementClient diagnostics(Context c){return new EntitlementClient(c);}
    };
    public volatile State state = State.WAITING;
    public volatile long bytesReceived,totalBytes,downloadElapsedMillis=-1,phaseStarted,lastProgressAt;
    public volatile int savedItems,totalItems;
    public volatile String errorCode="NONE";
    public static boolean busy(State s) { return s==State.WAITING||s==State.PREPARING||s==State.DOWNLOADING||s==State.VERIFYING||s==State.IMPORTING||s==State.COMMITTING; }
    private final String account;
    private final Thread worker;
    private final Network transport;
    public boolean valid(Context context) { return account.equals(new AccountLinkStore(context).accountId()) && !account.isEmpty(); }

    public static synchronized WebsiteSetupJob start(Context context, boolean replaceLocal) {
        String account = new AccountLinkStore(context).accountId();
        if (current != null && current.account.equals(account)
                && current.worker.isAlive() && busy(current.state)) return current;
        if (current != null) current.worker.interrupt();
        current = new WebsiteSetupJob(context.getApplicationContext(), account, replaceLocal);
        current.worker.start();
        return current;
    }

    private WebsiteSetupJob(Context context, String account, boolean replaceLocal) {
        this.account = account;
        transport = network;
        worker = new Thread(() -> run(context, replaceLocal), "SunnyIPTV-website-import");
    }

    private void run(Context context, boolean replaceLocal) {
        long deadline = android.os.SystemClock.elapsedRealtime() + 20 * 60 * 1000L;
        SourceStore sources = new SourceStore(context);
        SetupProcessLog log=new SetupProcessLog(context,account,transport.diagnostics(context));
        log.record(this);
        log.observe(this);
        try (SearchIndexStore index = new SearchIndexStore(context)) {
            while (android.os.SystemClock.elapsedRealtime() < deadline) {
                if (Thread.currentThread().isInterrupted()) return;
                if (account.isEmpty() || !account.equals(new AccountLinkStore(context).accountId())
                        || FamilyStore.active(context)) { state = State.FAILED; return; }
                SourceSyncClient sync = transport.sources(context);
                if (replaceLocal) {
                    if (!sync.pullForWebsite()) { state = State.WAITING; Thread.sleep(4000); continue; }
                    replaceLocal = false;
                } else {
                    if (sources.syncDirty() || sources.accountChangePending() || !sources.automaticDownloadEnabled()) {
                        state = State.CHOICE; return;
                    }
                    sync.pullAutomatically();
                }
                SourceStore.Entry active = sources.active();
                if (active == null || !active.enabled || DemoPolicy.isDemo(active.profile)) {
                    state = State.WAITING; Thread.sleep(4000); continue;
                }
                state = State.PREPARING;log.record(this);
                try {
                    String key = ProfileCacheKey.of(active.profile);
                    boolean ready = transport.catalog(context).bootstrap(index, key, active.profile,
                            new CatalogPackageImporter.Progress(){
                                private long now(){return android.os.SystemClock.elapsedRealtime();}
                                public void downloading(long bytes,long total){
                                    if(state!=State.DOWNLOADING){phaseStarted=now();downloadElapsedMillis=-1;}
                                    bytesReceived=bytes;totalBytes=total;lastProgressAt=now();state=State.DOWNLOADING;log.record(WebsiteSetupJob.this);
                                }
                                public void downloaded(long elapsed){downloadElapsedMillis=elapsed;}
                                public void verifying(){phaseStarted=lastProgressAt=now();state=State.VERIFYING;log.record(WebsiteSetupJob.this);}
                                public void importing(int total){savedItems=0;totalItems=total;phaseStarted=lastProgressAt=now();state=State.IMPORTING;log.record(WebsiteSetupJob.this);}
                                public void update(int count){savedItems=count;lastProgressAt=now();log.record(WebsiteSetupJob.this);}
                                public void committing(){lastProgressAt=now();state=State.COMMITTING;log.record(WebsiteSetupJob.this);}
                            });
                    if (ready || (SettingsStore.prefs(context).getBoolean("first_sync_done_" + key, false)
                            && index.isComplete(key,"live") && index.isComplete(key,"vod") && index.isComplete(key,"series"))) {
                        if (!account.equals(new AccountLinkStore(context).accountId()) || sources.syncDirty() || sources.accountChangePending()) { state=State.FAILED; return; }
                        state = State.READY;
                        // The new library is already visible; remove the previous one off the customer's wait.
                        try { index.purgeRetired(key); } catch (Exception ignored) { /* retried after the next import */ }
                        return;
                    }
                    state = State.CHOICE; return;
                } catch (CatalogPackageClient.Pending pending) { state = State.PREPARING; }
                Thread.sleep(4000);
            }
            state = State.EXPIRED;
        } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
        catch (Exception failure) {
            String message=failure.getMessage();
            errorCode="CATALOG_CHECKSUM".equals(message)?"CHECKSUM":failure instanceof java.io.IOException?"NETWORK_OR_PACKAGE":"SETUP_FAILED";
            state = State.FAILED;
        }
        finally {log.record(this);log.close();}
    }
}
