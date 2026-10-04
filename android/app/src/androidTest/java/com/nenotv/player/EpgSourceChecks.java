package com.nenotv.player;

import android.content.*;
import android.view.*;
import android.widget.*;
import com.nenotv.player.model.*;
import com.nenotv.player.provider.*;
import com.nenotv.player.storage.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

final class EpgSourceChecks {
    static void boundedCache(){
        AtomicLong clock=new AtomicLong(100);EpgRequests.Cache cache=new EpgRequests.Cache(2,100,20,clock::get);
        cache.put("a",rows("A"));cache.put("b",rows("B"));check(cache.get("a")!=null,"EPG snapshot missing");cache.put("c",rows("C"));
        check(cache.get("b")==null&&cache.get("a")!=null&&cache.get("c")!=null,"EPG cache not bounded by LRU");
        clock.set(201);check(cache.get("a")==null&&cache.get("c")==null,"EPG snapshot never expired");
        cache.failed("failure");check(cache.get("failure")!=null&&cache.get("failure").isEmpty(),"EPG error retry has no backoff");
        clock.set(222);check(cache.get("failure")==null,"EPG error permanently prevented retry");
        cache.put("clear",rows("Clear"));cache.clear();check(cache.get("clear")==null,"EPG cache clear failed");
    }
    interface Guide { List<EpgEntry> load(MediaEntry channel)throws Exception; }
    static void check(boolean value,String message){if(!value)throw new AssertionError(message);}
    static Provider provider(Guide guide){return new Provider(){
        public void authenticate(){}
        public List<Category> categories(String type){return Collections.emptyList();}
        public List<MediaEntry> items(String type,String category){return Collections.emptyList();}
        public List<EpgEntry> epgEntries(MediaEntry channel,int limit)throws Exception{return guide.load(channel);}
    };}
    static List<EpgEntry> rows(String title){EpgEntry e=new EpgEntry();e.title=title;e.startEpoch=System.currentTimeMillis()/1000-60;e.endEpoch=e.startEpoch+3600;return Collections.singletonList(e);}
    static MediaEntry channel(String source){MediaEntry e=new MediaEntry();e.id="42";e.streamId="42";e.type="live";e.name="QA channel";e.sourceId=source;return e;}
    static String text(View view){StringBuilder out=new StringBuilder();if(view instanceof TextView)out.append(((TextView)view).getText());if(view instanceof ViewGroup)for(int n=0;n<((ViewGroup)view).getChildCount();n++)out.append(text(((ViewGroup)view).getChildAt(n)));return out.toString();}
    static final class Views {
        final UiInstrumentation test;final Context context;final MediaRowAdapter row;final MediaGridAdapter grid;final EpgAdapter epg;
        final List<MediaEntry> channels;final View[][] rendered;final LinearLayout parent;
        Views(UiInstrumentation test,Context context,EpgStore store,List<MediaEntry> channels){
            this.test=test;this.context=context;this.channels=channels;LibraryStore library=new LibraryStore(context);
            row=new MediaRowAdapter(context,library);grid=new MediaGridAdapter(context,library);epg=new EpgAdapter(context,store);
            parent=new LinearLayout(context);rendered=new View[3][channels.size()];
        }
        void configure(EpgRequests requests){test.runOnMainSync(()->{row.setEpg(requests);grid.setEpg(requests);epg.configure(requests);row.set(channels);grid.set(channels,true);epg.set(channels);render();});}
        void render(){for(int n=0;n<channels.size();n++){rendered[0][n]=row.getView(n,rendered[0][n],parent);rendered[1][n]=grid.getView(n,rendered[1][n],parent);rendered[2][n]=epg.getView(n,rendered[2][n],parent);}}
        void await(String[] expected,boolean includeGrid)throws Exception{
            AtomicBoolean complete=new AtomicBoolean();long until=android.os.SystemClock.elapsedRealtime()+10000;
            while(!complete.get()&&android.os.SystemClock.elapsedRealtime()<until){test.runOnMainSync(()->{render();boolean all=true;for(int a=0;a<3;a++){if(a==1&&!includeGrid)continue;for(int n=0;n<expected.length;n++)all&=text(rendered[a][n]).contains(expected[n]);}complete.set(all);});Thread.sleep(50);}
            check(complete.get(),"Source-aware EPG did not reach all adapter views: "+Arrays.toString(expected));
        }
        void close(){test.runOnMainSync(()->{row.setEpg(null);grid.setEpg(null);epg.shutdown();});}
    }
    static void concurrentSnapshot(Context context,EpgStore store)throws Exception{
        ExecutorService pool=Executors.newFixedThreadPool(6);CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1);
        AtomicInteger count=new AtomicInteger();Provider p=provider(channel->{count.incrementAndGet();entered.countDown();check(release.await(5,TimeUnit.SECONDS),"Concurrent EPG gate timed out");return rows("Shared guide");});
        String profile="qa-epg-concurrent-"+System.nanoTime();MediaEntry channel=channel("");
        try{
            List<Future<List<EpgEntry>>> results=new ArrayList<>();for(int n=0;n<6;n++)results.add(pool.submit(()->store.getOrFetch(p,profile,channel)));
            check(entered.await(5,TimeUnit.SECONDS),"Shared EPG request did not start");Thread.sleep(150);
            AtomicReference<Throwable> interrupted=new AtomicReference<>();AtomicBoolean preserved=new AtomicBoolean();
            Thread waiter=new Thread(()->{try{store.getOrFetch(p,profile,channel);}catch(Throwable failure){interrupted.set(failure);preserved.set(Thread.currentThread().isInterrupted());}});
            waiter.start();Thread.sleep(150);waiter.interrupt();waiter.join(2000);
            check(!waiter.isAlive()&&interrupted.get() instanceof java.io.InterruptedIOException&&preserved.get(),"EPG waiter interruption was lost");
            release.countDown();for(Future<List<EpgEntry>> result:results)check(result.get(5,TimeUnit.SECONDS).get(0).title.equals("Shared guide"),"EPG readers did not share snapshot");
            check(count.get()==1,"Concurrent views downloaded the same guide more than once");
        }finally{release.countDown();pool.shutdownNow();check(pool.awaitTermination(5,TimeUnit.SECONDS),"EPG fixture worker did not finish");}
        AtomicInteger attempts=new AtomicInteger();Provider failing=provider(e->{if(attempts.incrementAndGet()==1)throw new java.io.IOException("QA_FAILURE");return rows("Retried guide");});
        String retry="qa-epg-retry-"+System.nanoTime();boolean failed=false;try{store.getOrFetch(failing,retry,channel);}catch(java.io.IOException expected){failed=true;}
        check(failed&&store.getOrFetch(failing,retry,channel).get(0).title.equals("Retried guide")&&attempts.get()==2,"Failed EPG request prevented retry");
    }
    static void run(UiInstrumentation test)throws Exception{
        boundedCache();
        Context c=test.getTargetContext();SharedPreferences sourcePrefs=c.getSharedPreferences("nenotv_sources_v1",Context.MODE_PRIVATE),settings=SettingsStore.prefs(c);
        Map<String,?> oldSources=new HashMap<>(sourcePrefs.getAll()),oldSettings=new HashMap<>(settings.getAll());
        SecureProfileStore profiles=new SecureProfileStore(c);Profile previous=profiles.exists()?profiles.load():null;
        try(EpgStore store=new EpgStore(c)){
            sourcePrefs.edit().clear().commit();profiles.clear();settings.edit().putBoolean("pro_smart_epg",false).commit();SettingsStore.setPrimaryLanguage(c,"nl");
            SourceStore sources=new SourceStore(c);Profile a=new Profile();a.type=Profile.Type.M3U;a.m3uUrl="https://example.invalid/guide-a";
            Profile b=new Profile();b.type=Profile.Type.M3U;b.m3uUrl="https://example.invalid/guide-b";
            String first=sources.upsert("",a,false),second=sources.upsert("",b,false);AtomicInteger primaryCount=new AtomicInteger(),aCount=new AtomicInteger(),bCount=new AtomicInteger();
            Provider primary=provider(e->{primaryCount.incrementAndGet();return rows("GUIDE Primary");});
            SourceProviderResolver resolver=new SourceProviderResolver(c,(p,language)->provider(e->{if(p.m3uUrl.equals(a.m3uUrl)){aCount.incrementAndGet();return rows("GUIDE A");}bCount.incrementAndGet();return rows("GUIDE B");}));
            String profile="qa-epg-sources-"+System.nanoTime();EpgRequests requests=new EpgRequests(store,primary,profile,resolver,()->true);
            Views views=new Views(test,c,store,Arrays.asList(channel(""),channel(first),channel(second)));
            try{views.configure(requests);views.await(new String[]{"GUIDE Primary","GUIDE A","GUIDE B"},true);check(requests.text(channel(first)).contains("GUIDE A"),"Full guide used another source");check(primaryCount.get()==1&&aCount.get()==1&&bCount.get()==1,"Adapters did not share per-source guide downloads");}
            finally{views.close();}
            sources.setEnabled(second,false);boolean denied=false;try{requests.load(channel(second));}catch(java.io.IOException expected){denied=true;}
            check(denied&&bCount.get()==1,"Disabled source reused a cached guide");
            SourceProviderResolver languages=new SourceProviderResolver(c,(p,language)->provider(e->rows("GUIDE "+language)));
            String languageProfile="qa-epg-language-"+System.nanoTime();
            SettingsStore.setPrimaryLanguage(c,"nl");EpgRequests dutch=new EpgRequests(store,primary,languageProfile,languages,()->true);
            check(dutch.load(channel(first)).get(0).title.equals("GUIDE nl"),"Dutch guide language not passed to provider");
            SettingsStore.setPrimaryLanguage(c,"de");EpgRequests german=new EpgRequests(store,primary,languageProfile,languages,()->true);
            check(german.load(channel(first)).get(0).title.equals("GUIDE de")&&dutch.load(channel(first)).get(0).title.equals("GUIDE nl"),"Language switch contaminated provider or cached guide");
            SettingsStore.setPrimaryLanguage(c,"nl");
            AtomicInteger changedCount=new AtomicInteger();SourceProviderResolver changing=new SourceProviderResolver(c,(p,language)->provider(e->{if(changedCount.incrementAndGet()==1){a.m3uUrl="https://example.invalid/guide-a-new";sources.upsert(first,a,false);return rows("GUIDE old credentials");}return rows("GUIDE new credentials");}));
            EpgRequests changed=new EpgRequests(store,primary,profile+"changed",changing,()->true);denied=false;
            try{changed.load(channel(first));}catch(java.io.IOException expected){denied=true;}
            check(denied&&changed.load(channel(first)).get(0).title.equals("GUIDE new credentials")&&changedCount.get()==2,"EPG reused old credentials or stale guide after source edit");
            concurrentSnapshot(c,store);
            CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1);AtomicInteger oldCount=new AtomicInteger(),newCount=new AtomicInteger();
            Provider old=provider(e->{oldCount.incrementAndGet();entered.countDown();check(release.await(5,TimeUnit.SECONDS),"Old EPG gate timed out");return rows("GUIDE OLD");});
            Provider current=provider(e->{newCount.incrementAndGet();return rows("GUIDE NEW");});String lifecycle="qa-epg-lifecycle-"+System.nanoTime();
            Views swapped=new Views(test,c,store,Collections.singletonList(channel("")));
            try{
                SettingsStore.setPrimaryLanguage(c,"nl");swapped.configure(new EpgRequests(store,old,lifecycle,resolver,()->true));check(entered.await(5,TimeUnit.SECONDS),"Old EPG request missing");
                SettingsStore.setPrimaryLanguage(c,"de");swapped.configure(new EpgRequests(store,current,lifecycle,resolver,()->true));swapped.await(new String[]{"GUIDE NEW"},false);
                release.countDown();Thread.sleep(200);swapped.await(new String[]{"GUIDE NEW"},true);
                check(oldCount.get()==1&&newCount.get()==1,"Language/view switch duplicated guide or shared the wrong language snapshot");
            }finally{release.countDown();swapped.close();}
            try(android.database.Cursor cursor=store.getReadableDatabase().rawQuery("SELECT cache_key FROM epg",null)){
                while(cursor.moveToNext())check(cursor.getString(0).matches("timeline3\\|[0-9a-f]{64}"),"EPG cache key contains raw source/stream credentials");
            }
            store.close();denied=false;try{store.getOrFetch(primary,"closed",channel(""));}catch(java.io.IOException expected){denied=true;}
            check(denied,"Closed EPG store started another provider request");
        }finally{SourceRegistryChecks.restore(sourcePrefs,oldSources);HouseholdProfileChecks.restore(settings,oldSettings);if(previous==null)profiles.clear();else profiles.save(previous);}
    }
}
