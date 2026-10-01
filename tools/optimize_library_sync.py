from pathlib import Path

p=Path("app/src/main/java/com/robertalt/raiptv/MainActivity.java")
if not p.exists():
    raise SystemExit("MainActivity.java not found")
s=p.read_text()

start=s.find("    void refreshSearchIndex(boolean force){")
end=s.find("\n    void waitWhilePaused()", start)
if start<0 or end<0:
    raise SystemExit("refreshSearchIndex markers missing")

method=r'''    void refreshSearchIndex(boolean force){
        if(provider==null||profile==null||indexRefreshRunning)return;
        indexRefreshRunning=true;final String key=profileKey();final boolean requestedForce=force;
        indexFuture=indexExec.submit(()->{
            boolean allComplete=false;
            ExecutorService fetchPool=Executors.newFixedThreadPool(3);
            try{
                android.content.SharedPreferences sp=SettingsStore.prefs(this);
                final int schema=4;
                boolean migration=sp.getInt("language_index_version_"+key,0)<schema;
                if(migration&&!sp.getBoolean("language_index_rebuild_started_"+key,false)){
                    try{searchIndex.clearAll();}catch(Exception ignored){}
                    sp.edit().putBoolean("language_index_rebuild_started_"+key,true).putBoolean("first_sync_done_"+key,false)
                      .remove(cacheCursorKey("vod")).remove(cacheCursorKey("series")).remove(cacheCursorKey("live"))
                      .putInt("first_sync_done_count_"+key,0).putInt("first_sync_total_count_"+key,0).putInt("first_sync_titles_"+key,0).apply();
                }
                if(profile.type==Profile.Type.M3U){
                    List<MediaEntry>x=provider.items("live","all");searchIndex.replaceSection(key,"live",x);
                    allComplete=true;
                }else{
                    final String[] baseTypes={"vod","series","live"};
                    LinkedHashMap<String,List<Category>> catMap=new LinkedHashMap<>();
                    int globalTotal=0;
                    final String preferred=SettingsStore.contentLanguage(this);
                    for(String type:baseTypes){
                        List<Category> cats=new ArrayList<>(provider.categories(type));
                        cats.sort((ca,cb)->{int z=Integer.compare(ContentLanguage.rankText(ca.name,preferred),ContentLanguage.rankText(cb.name,preferred));return z!=0?z:safe(ca.name).compareToIgnoreCase(safe(cb.name));});
                        catMap.put(type,cats);globalTotal+=cats.size();
                    }
                    ArrayList<String> order=new ArrayList<>();
                    if(section.equals("live")||section.equals("vod")||section.equals("series"))order.add(section);
                    for(String t:baseTypes)if(!order.contains(t))order.add(t);

                    int globalDone=0;
                    for(String type:baseTypes){
                        List<Category> cats=catMap.get(type);
                        if(!migration&&!requestedForce&&searchIndex.isFresh(key,type,SEARCH_INDEX_TTL_MS)){globalDone+=cats.size();continue;}
                        String cursor=SettingsStore.prefs(this).getString(cacheCursorKey(type),"");
                        int startAt=0;
                        if(cursor!=null&&!cursor.isEmpty()){
                            for(int ci=0;ci<cats.size();ci++)if(cursor.equals(cats.get(ci).id)){startAt=ci+1;break;}
                            if(startAt>=cats.size())startAt=0;
                        }
                        globalDone+=startAt;
                    }

                    final int grandTotal=Math.max(1,globalTotal);
                    int estimatedTitles=searchIndex.count(key);
                    final int initialDone=globalDone,initialTitles=estimatedTitles;
                    if(isUiAlive())runOnUiThread(()->showIndexBanner("",initialDone,grandTotal,initialTitles));

                    final int FETCH_WINDOW=3;
                    for(String type:order){
                        if(Thread.currentThread().isInterrupted())break;
                        List<Category> cats=catMap.get(type);
                        if(!migration&&!requestedForce&&searchIndex.isFresh(key,type,SEARCH_INDEX_TTL_MS))continue;

                        int startAt=0;
                        String cursor=requestedForce?"":SettingsStore.prefs(this).getString(cacheCursorKey(type),"");
                        if(cursor!=null&&!cursor.isEmpty()){
                            for(int ci=0;ci<cats.size();ci++)if(cursor.equals(cats.get(ci).id)){startAt=ci+1;break;}
                            if(startAt>=cats.size())startAt=0;
                        }

                        // Fast path: standard Xtream servers can return a complete section in one request.
                        // Preserve category names locally; fall back to bounded per-category fetches if bulk fails.
                        boolean bulkDone=false;
                        if(!Thread.currentThread().isInterrupted()){
                            try{
                                waitWhilePaused();waitForLibraryLoad();
                                if(!activityPaused&&!Thread.currentThread().isInterrupted()){
                                    List<MediaEntry> bulk=provider.items(type,"all");
                                    if(bulk!=null&&!bulk.isEmpty()){
                                        HashMap<String,String> groupNames=new HashMap<>();
                                        for(Category c:cats)groupNames.put(safe(c.id),c.name);
                                        for(MediaEntry e:bulk)if(e!=null){
                                            String g=groupNames.get(safe(e.categoryId));
                                            if(g!=null&&!g.trim().isEmpty())e.group=g;
                                        }
                                        indexCategoryBusy=true;
                                        try{searchIndex.replaceSection(key,type,bulk);}finally{indexCategoryBusy=false;}
                                        SettingsStore.prefs(this).edit().remove(cacheCursorKey(type)).apply();
                                        globalDone+=Math.max(0,cats.size()-startAt);
                                        estimatedTitles=searchIndex.count(key);
                                        final int gd=globalDone,gt=grandTotal,ti=estimatedTitles;
                                        if(isUiAlive())runOnUiThread(()->showIndexBanner("",gd,gt,ti));
                                        publishIndexedTop(type);
                                        reloadIndexedSectionWhenReady(type);
                                        bulkDone=true;
                                    }
                                }
                            }catch(Exception bulkError){bulkDone=false;}
                        }
                        if(bulkDone)continue;

                        boolean sectionOk=true;
                        final String fetchType=type;
                        for(int batchStart=startAt;batchStart<cats.size()&&sectionOk;batchStart+=FETCH_WINDOW){
                            if(Thread.currentThread().isInterrupted()){sectionOk=false;break;}
                            waitWhilePaused();waitForLibraryLoad();
                            if(activityPaused||Thread.currentThread().isInterrupted()){sectionOk=false;break;}

                            int batchEnd=Math.min(cats.size(),batchStart+FETCH_WINDOW);
                            ArrayList<Future<List<MediaEntry>>> futures=new ArrayList<>();
                            for(int ci=batchStart;ci<batchEnd;ci++){
                                final Category c=cats.get(ci);
                                futures.add(fetchPool.submit(()->provider.items(fetchType,c.id)));
                            }

                            int lastContiguous=batchStart-1;
                            for(int offset=0;offset<futures.size();offset++){
                                int ci=batchStart+offset;
                                Category cat=cats.get(ci);
                                if(Thread.currentThread().isInterrupted()){sectionOk=false;break;}
                                waitWhilePaused();waitForLibraryLoad();
                                if(activityPaused||Thread.currentThread().isInterrupted()){sectionOk=false;break;}

                                List<MediaEntry> items;
                                try{
                                    items=futures.get(offset).get();
                                }catch(Exception parallelError){
                                    try{items=provider.items(fetchType,cat.id);}
                                    catch(Exception retryError){sectionOk=false;break;}
                                }

                                indexCategoryBusy=true;
                                try{
                                    for(MediaEntry e:items)if(e!=null)e.group=cat.name;
                                    searchIndex.upsert(key,items);
                                }finally{indexCategoryBusy=false;}
                                if(Thread.currentThread().isInterrupted()){sectionOk=false;break;}

                                lastContiguous=ci;
                                globalDone++;
                                estimatedTitles+=items==null?0:items.size();
                                final int gd=globalDone,gt=grandTotal,ti=estimatedTitles;
                                if(isUiAlive())runOnUiThread(()->showIndexBanner("",gd,gt,ti));

                                String q=latestSearchQuery;
                                if(q!=null&&!q.isEmpty()&&isUiAlive())runOnUiThread(()->{if(isUiAlive())searchEverywhere(q);});
                            }

                            if(lastContiguous>=batchStart){
                                try{SettingsStore.prefs(this).edit().putString(cacheCursorKey(type),safe(cats.get(lastContiguous).id)).apply();}catch(Exception ignored){}
                                publishIndexedTop(type);
                            }
                            if(!sectionOk){
                                for(Future<List<MediaEntry>> f:futures)if(!f.isDone())f.cancel(true);
                                break;
                            }
                        }

                        if(sectionOk&&!Thread.currentThread().isInterrupted()){
                            int count=searchIndex.countSection(key,type);
                            if(count>0){
                                searchIndex.markSection(key,type,count);
                                SettingsStore.prefs(this).edit().remove(cacheCursorKey(type)).apply();
                                reloadIndexedSectionWhenReady(type);
                            }
                        }
                    }

                    allComplete=searchIndex.isComplete(key,"vod")&&searchIndex.isComplete(key,"series")&&searchIndex.isComplete(key,"live");
                    if(allComplete){
                        sp.edit().putInt("language_index_version_"+key,schema).putBoolean("language_index_rebuild_started_"+key,false)
                          .putBoolean("first_sync_done_"+key,true).putInt("first_sync_done_count_"+key,grandTotal).putInt("first_sync_total_count_"+key,grandTotal).apply();
                    }
                }
            }catch(Exception ignored){}finally{
                try{fetchPool.shutdownNow();}catch(Exception ignored){}
                indexCategoryBusy=false;indexRefreshRunning=false;indexFuture=null;final boolean done=allComplete;
                runOnUiThread(()->{if(!isUiAlive())return;if(done)hideIndexBanner("");else restoreFirstSyncBanner();});
            }
        });
    }
'''

s=s[:start]+method+s[end:]
p.write_text(s)

if "ExecutorService fetchPool=Executors.newFixedThreadPool(3)" not in p.read_text():
    raise SystemExit("bounded fetch pool patch failed")
print("Applied bounded 3-way IPTV library prefetch with sequential DB writes")
