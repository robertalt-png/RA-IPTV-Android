from pathlib import Path

root=Path(".")
main=root/"app/src/main/java/com/robertalt/raiptv/MainActivity.java"
instr=root/"app/src/androidTest/java/com/nenotv/player/ImportInstrumentation.java"
window=root/"app/src/main/java/com/robertalt/raiptv/CategoryCompletionWindow.java"
for p in (main,instr):
    if not p.exists(): raise SystemExit(f"missing {p}")

window.write_text(r'''package com.nenotv.player;

public final class CategoryCompletionWindow {
    private final boolean[] done;
    private int committed;

    public CategoryCompletionWindow(int total,int resumeAt){
        done=new boolean[Math.max(0,total)];
        committed=Math.max(-1,resumeAt-1);
    }

    public synchronized int markDone(int index){
        if(index>=0&&index<done.length)done[index]=true;
        while(committed+1<done.length&&done[committed+1])committed++;
        return committed;
    }

    public synchronized int committedIndex(){return committed;}
}''',encoding="utf-8")

m=main.read_text(encoding="utf-8")
start_marker='''                            for(int ci=resumeAt;ci<cats.size();ci++){'''
end_marker='''                            searchIndex.finishSectionImport(importSession,key,type);fallbackSession=null;'''
start=m.find(start_marker)
end=m.find(end_marker,start)
if start<0 or end<0: raise SystemExit("fallback category loop markers missing")
old=m[start:end]
new='''                            final int categoryWindowSize=2;
                            ExecutorService categoryPool=Executors.newFixedThreadPool(categoryWindowSize);
                            CompletionService<Integer> categoryResults=new ExecutorCompletionService<>(categoryPool);
                            CategoryCompletionWindow completionWindow=new CategoryCompletionWindow(cats.size(),resumeAt);
                            int nextCategory=resumeAt;
                            int inFlight=0;
                            try{
                                while(nextCategory<cats.size()&&inFlight<categoryWindowSize){
                                    final int categoryIndex=nextCategory++;
                                    final Category category=cats.get(categoryIndex);
                                    categoryResults.submit(()->{
                                        waitWhilePaused();waitForLibraryLoad();
                                        com.nenotv.player.net.StreamingJsonArray.checkCancelled();
                                        ((XtreamProvider)indexProvider).streamCategory(type,category.id,batch->{
                                            waitWhilePaused();waitForLibraryLoad();
                                            for(MediaEntry e:batch)e.group=category.name;
                                            searchIndex.importBatch(importSession,key,type,batch);
                                            if(!searchIndex.isComplete(key,type))searchIndex.upsert(key,batch);
                                        });
                                        return categoryIndex;
                                    });
                                    inFlight++;
                                }
                                while(inFlight>0){
                                    Future<Integer> completedFuture=categoryResults.take();
                                    int completedIndex;
                                    try{completedIndex=completedFuture.get();}
                                    catch(ExecutionException failed){
                                        Throwable cause=failed.getCause();
                                        if(cause instanceof Exception)throw (Exception)cause;
                                        throw new RuntimeException(cause);
                                    }
                                    inFlight--;
                                    globalDone++;
                                    int committedIndex=completionWindow.markDone(completedIndex);
                                    int staged=searchIndex.importCount(importSession,key,type);
                                    android.content.SharedPreferences.Editor progressEdit=SettingsStore.prefs(this).edit()
                                        .putInt("first_sync_done_count_"+key,globalDone)
                                        .putInt("first_sync_titles_"+key,searchIndex.count(key));
                                    if(committedIndex>=resumeAt){
                                        String committedCategoryId=cats.get(committedIndex).id;
                                        searchIndex.checkpointImport(key,type,importSession,committedCategoryId,staged);
                                        progressEdit.putString(cacheCursorKey(key,type),committedCategoryId);
                                    }else{
                                        searchIndex.checkpointImport(key,type,importSession,"",staged);
                                    }
                                    progressEdit.apply();
                                    final int gd=globalDone,gt=grandTotal,ti=searchIndex.count(key);
                                    runOnUiThread(()->{if(isUiAlive()&&key.equals(profileKey()))showIndexBanner("",gd,gt,ti);});
                                    while(nextCategory<cats.size()&&inFlight<categoryWindowSize){
                                        final int categoryIndex=nextCategory++;
                                        final Category category=cats.get(categoryIndex);
                                        categoryResults.submit(()->{
                                            waitWhilePaused();waitForLibraryLoad();
                                            com.nenotv.player.net.StreamingJsonArray.checkCancelled();
                                            ((XtreamProvider)indexProvider).streamCategory(type,category.id,batch->{
                                                waitWhilePaused();waitForLibraryLoad();
                                                for(MediaEntry e:batch)e.group=category.name;
                                                searchIndex.importBatch(importSession,key,type,batch);
                                                if(!searchIndex.isComplete(key,type))searchIndex.upsert(key,batch);
                                            });
                                            return categoryIndex;
                                        });
                                        inFlight++;
                                    }
                                }
                            }catch(InterruptedException interrupted){
                                Thread.currentThread().interrupt();
                                throw new RuntimeException(interrupted);
                            }finally{
                                categoryPool.shutdownNow();
                            }
'''
m=m[:start]+new+m[end:]
main.write_text(m,encoding="utf-8")

i=instr.read_text(encoding="utf-8")
anchor='''            ArrayList<Category> qaCategories = new ArrayList<>();'''
if anchor not in i: raise SystemExit("instrumentation category anchor missing")
if "Concurrent category import" not in i:
    test='''            CategoryCompletionWindow completionWindow=new CategoryCompletionWindow(4,0);
            require(completionWindow.markDone(1)==-1,"Out-of-order category advanced cursor");
            require(completionWindow.markDone(0)==1,"Contiguous cursor did not advance");
            require(completionWindow.markDone(3)==1,"Gap advanced cursor");
            require(completionWindow.markDone(2)==3,"Cursor did not close gap");

            String concurrentSession=store.beginSectionImport();
            java.util.concurrent.ExecutorService concurrentPool=java.util.concurrent.Executors.newFixedThreadPool(2);
            java.util.concurrent.Future<?> concurrentA=concurrentPool.submit(()->{
                try{store.importBatch(concurrentSession,PROFILE,"live",range("parallelA",0,80,"live"));}
                catch(Exception e){throw new RuntimeException(e);}
            });
            java.util.concurrent.Future<?> concurrentB=concurrentPool.submit(()->{
                try{store.importBatch(concurrentSession,PROFILE,"live",range("parallelB",0,80,"live"));}
                catch(Exception e){throw new RuntimeException(e);}
            });
            concurrentA.get();concurrentB.get();concurrentPool.shutdownNow();
            require(store.importCount(concurrentSession,PROFILE,"live")==160,"Concurrent category import lost rows");
            store.abortSectionImport(concurrentSession);
'''
    i=i.replace(anchor,test+anchor,1)
instr.write_text(i,encoding="utf-8")
print("Applied v0.13.7 bounded two-category fallback")
