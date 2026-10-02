from pathlib import Path
from repair_responsiveness import replace_once

RUNTIME = Path(__file__).resolve().parent / "runtime"


def repair_source(text):
    text = replace_once(text, '    boolean seriesEpisodeMode=false,settingCategories=false,activityPaused=false,autoDefaultGroup=true;', '    volatile boolean activityPaused=false,playbackActive=false;\n    boolean seriesEpisodeMode=false,settingCategories=false,autoDefaultGroup=true;')
    text = replace_once(text, 'volatile boolean indexRefreshRunning=false,', 'volatile boolean indexRefreshRequested=false,indexRefreshRunning=false,')
    text = replace_once(text, 'epgStore=new EpgStore(this);migrateSearchIndexIfNeeded();', 'epgStore=new EpgStore(this);')
    text = replace_once(text, '    void migrateSearchIndexIfNeeded(){', '    synchronized void migrateSearchIndexIfNeeded(){')
    text = replace_once(text, 'exec.execute(()->{try{provider.authenticate();', 'final Provider connectingProvider=provider;\n        exec.execute(()->{try{migrateSearchIndexIfNeeded();connectingProvider.authenticate();')
    start = text.index('    void pauseBackgroundIndexForUi(){')
    end = text.index('void scheduleBackgroundIndex(){', start)
    text = text[:start] + '''    void pauseBackgroundIndexForUi(){
        if(delayedIndexResume!=null)ui.removeCallbacks(delayedIndexResume);
    }
''' + text[end:]
    text = replace_once(text, '    void openProfile(){\n', '    void openProfile(){\n        Future<?> previousIndex=indexFuture;if(previousIndex!=null)previousIndex.cancel(true);\n')
    text = replace_once(text, 'try{searchIndex.clearAll();}catch(Exception ignored){}refreshSearchIndex(true);', 'refreshSearchIndex(true);')
    text = replace_once(text, 'activityPaused=true;resumeIndexAfterPlayback=indexRefreshRunning;\n        Future<?> f=indexFuture;if(f!=null&&!f.isDone())f.cancel(true);', 'playbackActive=true;activityPaused=true;resumeIndexAfterPlayback=false;')
    text = replace_once(text, 'catch(RuntimeException ex){activityPaused=false;', 'catch(RuntimeException ex){playbackActive=false;activityPaused=false;')
    text = replace_once(text, 'ProModuleInstaller.syncEntitlement(this);activityPaused=false;', 'ProModuleInstaller.syncEntitlement(this);playbackActive=false;activityPaused=false;')
    text = replace_once(text, 'while(activityPaused&&!isFinishing()', 'while(activityPaused&&!playbackActive&&!isFinishing()')
    text = replace_once(text, 'String cacheCursorKey(String type){return "cache_cursor_"+profileKey()+"_"+type;}', 'String cacheCursorKey(String type){return cacheCursorKey(profileKey(),type);} String cacheCursorKey(String key,String type){return "cache_cursor_"+key+"_"+type;}')

    start = text.index('    void refreshSearchIndex(boolean force){')
    end = text.index('    void waitWhilePaused()', start)
    worker = text[start:end]
    worker = replace_once(worker, '    void refreshSearchIndex(boolean force){', '    synchronized void refreshSearchIndex(boolean force){')
    worker = replace_once(worker, 'if(provider==null||profile==null||indexRefreshRunning)return;', 'if(provider==null||profile==null)return;if(indexRefreshRunning){if(force)indexRefreshRequested=true;return;}')
    worker = replace_once(worker, 'indexRefreshRunning=true;final String key=profileKey();', 'indexRefreshRunning=true;final Provider indexProvider=provider;final String key=profileKey();')
    worker = worker.replace('provider.items(', 'indexProvider.items(').replace('provider.categories(', 'indexProvider.categories(').replace('provider instanceof XtreamProvider', 'indexProvider instanceof XtreamProvider').replace('(XtreamProvider)provider', '(XtreamProvider)indexProvider').replace('profile.type==Profile.Type.M3U', 'indexProvider instanceof M3uProvider')
    worker = worker.replace('cacheCursorKey("', 'cacheCursorKey(key,"').replace('cacheCursorKey(type)', 'cacheCursorKey(key,type)')
    worker = worker.replace('if(activityPaused||', 'if((activityPaused&&!playbackActive)||')
    fallback_start = worker.index('                        boolean sectionOk=true;')
    fallback_end = worker.index('                    }\n\n                    allComplete=', fallback_start)
    worker = worker[:fallback_start] + (RUNTIME / 'CategoryFallback.java.txt').read_text(encoding='utf-8') + worker[fallback_end:]
    worker = replace_once(worker, '            ExecutorService fetchPool=Executors.newFixedThreadPool(3);\n', '')
    worker = replace_once(worker, '                try{fetchPool.shutdownNow();}catch(Exception ignored){}\n', '')
    worker = replace_once(worker, 'indexCategoryBusy=false;indexRefreshRunning=false;indexFuture=null;', 'indexCategoryBusy=false;synchronized(MainActivity.this){indexRefreshRunning=false;indexFuture=null;}')
    worker = replace_once(worker, 'runOnUiThread(()->{if(!isUiAlive())return;if(done)hideIndexBanner("");else restoreFirstSyncBanner();});', 'runOnUiThread(()->{if(!isUiAlive()||!key.equals(profileKey()))return;if(done)hideIndexBanner("");else restoreFirstSyncBanner();if(indexRefreshRequested){indexRefreshRequested=false;refreshSearchIndex(true);}});')
    worker = replace_once(worker, '                    try{searchIndex.clearAll();}catch(Exception ignored){}\n', '')
    worker = replace_once(worker, '            }catch(Exception ignored){}finally{', '''            }catch(Exception failure){
                if(!Thread.currentThread().isInterrupted())runOnUiThread(()->{if(isUiAlive()&&key.equals(profileKey()))status.setText(T("loading_interrupted"));});
            }finally{''')
    text = text[:start] + worker + text[end:]

    start = text.index('    void loadAllIncremental(String requested){')
    end = text.index('    void loadShowcase(', start)
    text = text[:start] + (RUNTIME / "OpenLibrary.java.txt").read_text(encoding="utf-8") + text[end:]
    text = replace_once(text, 'if(searchIndex!=null)searchIndex.close();', 'closeSearchIndexAfterWorkers();')
    text = replace_once(text, '    int dp(int v){', '''    void closeSearchIndexAfterWorkers(){
        new Thread(()->{
            try{
                while(!exec.awaitTermination(30,TimeUnit.SECONDS)){}
                while(!indexExec.awaitTermination(30,TimeUnit.SECONDS)){}
                if(searchIndex!=null)searchIndex.close();
            }catch(InterruptedException interrupted){Thread.currentThread().interrupt();}
        },"nenotv-cache-close").start();
    }
    int dp(int v){''')
    return text


def repair_main(path):
    path = Path(path)
    path.write_text(repair_source(path.read_text(encoding="utf-8")), encoding="utf-8")


def prepare_instrumentation(root):
    root = Path(root)
    gradle = root / 'app/build.gradle'
    config = replace_once(gradle.read_text(encoding='utf-8'), '    defaultConfig {', '    defaultConfig {\n        testInstrumentationRunner "com.nenotv.player.ImportInstrumentation"')
    java = root / 'app/src/androidTest/java/com/nenotv/player'
    java.mkdir(parents=True, exist_ok=True)
    (java / 'ImportInstrumentation.java').write_text((RUNTIME / 'ImportInstrumentation.java').read_text(encoding='utf-8'), encoding='utf-8')
    gradle.write_text(config, encoding='utf-8')
