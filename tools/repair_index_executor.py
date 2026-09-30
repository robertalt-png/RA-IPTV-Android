from pathlib import Path

p=Path("app/src/main/java/com/robertalt/raiptv/MainActivity.java")
s=p.read_text()

old='boolean seriesEpisodeMode=false,settingCategories=false,activityPaused=false,autoDefaultGroup=true; volatile boolean indexRefreshRunning=false,resumeIndexAfterPlayback=false,indexCategoryBusy=false;'
new='boolean seriesEpisodeMode=false,settingCategories=false,activityPaused=false,autoDefaultGroup=true; volatile boolean activityDestroyed=false,indexRefreshRunning=false,resumeIndexAfterPlayback=false,indexCategoryBusy=false;'
if old not in s:
    raise SystemExit("repair_index_executor: lifecycle field anchor missing")
s=s.replace(old,new,1)

old='    void scheduleBackgroundIndex(){ if(provider!=null&&profile!=null&&!indexRefreshRunning)refreshSearchIndex(false); }'
new='''    void scheduleBackgroundIndex(){
        if(activityDestroyed||!isUiAlive()||provider==null||profile==null||indexRefreshRunning||indexExec.isShutdown()||indexExec.isTerminated())return;
        refreshSearchIndex(false);
    }'''
if old not in s:
    raise SystemExit("repair_index_executor: scheduleBackgroundIndex anchor missing")
s=s.replace(old,new,1)

old='''    void refreshSearchIndex(boolean force){
        if(provider==null||profile==null||indexRefreshRunning)return;'''
new='''    void refreshSearchIndex(boolean force){
        if(activityDestroyed||!isUiAlive()||provider==null||profile==null||indexRefreshRunning||indexExec.isShutdown()||indexExec.isTerminated())return;'''
if old not in s:
    raise SystemExit("repair_index_executor: refresh guard anchor missing")
s=s.replace(old,new,1)

old='''        indexRefreshRunning=true;
        indexFuture=indexExec.submit(()->{'''
new='''        indexRefreshRunning=true;
        try{
        indexFuture=indexExec.submit(()->{'''
if old not in s:
    raise SystemExit("repair_index_executor: submit anchor missing")
s=s.replace(old,new,1)

old='''                runOnUiThread(()->{
                    if(ok)hideIndexBanner("");
                    else{restoreFirstSyncBanner();ui.postDelayed(()->scheduleBackgroundIndex(),4000);}
                });
            }
        });
    }

    void waitWhilePaused()'''
new='''                runOnUiThread(()->{
                    if(activityDestroyed||!isUiAlive())return;
                    if(ok)hideIndexBanner("");
                    else{
                        restoreFirstSyncBanner();
                        if(delayedIndexResume!=null)ui.removeCallbacks(delayedIndexResume);
                        delayedIndexResume=()->{if(!activityDestroyed&&isUiAlive())scheduleBackgroundIndex();};
                        ui.postDelayed(delayedIndexResume,4000);
                    }
                });
            }
        });
        }catch(RejectedExecutionException ignored){
            indexRefreshRunning=false;indexFuture=null;
        }
    }

    void waitWhilePaused()'''
if old not in s:
    raise SystemExit("repair_index_executor: delayed retry anchor missing")
s=s.replace(old,new,1)

old='    @Override protected void onDestroy(){requestSerial++;heroSerial++;if(pendingSearch!=null)ui.removeCallbacks(pendingSearch);if(delayedIndexResume!=null)ui.removeCallbacks(delayedIndexResume);Future<?> f=indexFuture;if(f!=null)f.cancel(true);exec.shutdownNow();heroExec.shutdownNow();indexExec.shutdownNow();if(searchIndex!=null)searchIndex.close();if(epgAdapter!=null)epgAdapter.shutdown();if(epgStore!=null)epgStore.close();super.onDestroy();}'
new='    @Override protected void onDestroy(){activityDestroyed=true;requestSerial++;heroSerial++;ui.removeCallbacksAndMessages(null);Future<?> f=indexFuture;if(f!=null)f.cancel(true);indexRefreshRunning=false;exec.shutdownNow();heroExec.shutdownNow();indexExec.shutdownNow();try{epgExec.shutdownNow();}catch(Exception ignored){}if(searchIndex!=null)searchIndex.close();if(epgAdapter!=null)epgAdapter.shutdown();if(epgStore!=null)epgStore.close();super.onDestroy();}'
if old not in s:
    raise SystemExit("repair_index_executor: onDestroy anchor missing")
s=s.replace(old,new,1)

p.write_text(s)
print("Applied NenoTV lifecycle/index executor repair")
