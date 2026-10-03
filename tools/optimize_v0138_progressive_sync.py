from pathlib import Path

root=Path(".")
main=root/"app/src/main/java/com/robertalt/raiptv/MainActivity.java"
instr=root/"app/src/androidTest/java/com/nenotv/player/ImportInstrumentation.java"
for p in (main,instr):\n    if not p.exists(): raise SystemExit(f"missing {p}")
m=main.read_text(encoding="utf-8")

old='''                    final String[] baseTypes={"live","vod","series"};
                    LinkedHashMap<String,List<Category>> catMap=new LinkedHashMap<>();
                    int globalTotal=0;
                    final String preferred=SettingsStore.contentLanguage(this);
                    for(String type:baseTypes){
                        List<Category> cats=searchIndex.cachedCategories(key,type);
                        boolean categoryCacheFresh=searchIndex.categoriesFresh(key,type,30L*60L*1000L);
                        if(cats.isEmpty()||migration||requestedForce||!categoryCacheFresh){
                            try{
                                List<Category> remoteCats=new ArrayList<>(indexProvider.categories(type));
                                searchIndex.replaceCategories(key,type,remoteCats);
                                cats=remoteCats;
                            }catch(Exception categoryError){
                                if(cats.isEmpty())throw categoryError;
                            }
                        }
                        catMap.put(type,cats);globalTotal+=cats.size();
                    }
                    ArrayList<String> order=new ArrayList<>();
                    if(section.equals("live")||section.equals("vod")||section.equals("series"))order.add(section);
                    for(String t:baseTypes)if(!order.contains(t))order.add(t);
'''
new='''                    final String[] baseTypes={"live","vod","series"};
                    ArrayList<String> order=new ArrayList<>();
                    if(section.equals("live")||section.equals("vod")||section.equals("series"))order.add(section);
                    for(String t:baseTypes)if(!order.contains(t))order.add(t);

                    LinkedHashMap<String,List<Category>> catMap=new LinkedHashMap<>();
                    int globalTotal=0;
                    final String preferred=SettingsStore.contentLanguage(this);
                    final String firstType=order.isEmpty()?"live":order.get(0);
                    for(String type:baseTypes){
                        List<Category> cats=searchIndex.cachedCategories(key,type);
                        boolean categoryCacheFresh=searchIndex.categoriesFresh(key,type,30L*60L*1000L);
                        if(type.equals(firstType)&&(cats.isEmpty()||migration||requestedForce||!categoryCacheFresh)){
                            try{
                                List<Category> remoteCats=new ArrayList<>(indexProvider.categories(type));
                                searchIndex.replaceCategories(key,type,remoteCats);
                                cats=remoteCats;
                            }catch(Exception categoryError){
                                if(cats.isEmpty())throw categoryError;
                            }
                        }
                        catMap.put(type,cats);globalTotal+=cats.size();
                    }
'''
if old not in m: raise SystemExit("initial category discovery marker missing")
m=m.replace(old,new,1)

old='''                    final int grandTotal=Math.max(1,globalTotal);
                    int estimatedTitles=searchIndex.count(key);
                    final int initialDone=globalDone,initialTitles=estimatedTitles;
                    if(isUiAlive())runOnUiThread(()->showIndexBanner("",initialDone,grandTotal,initialTitles));

                    for(String type:order){
                        if(Thread.currentThread().isInterrupted())break;
                        List<Category> cats=catMap.get(type);
                        if(!migration&&!requestedForce&&searchIndex.isFresh(key,type,SEARCH_INDEX_TTL_MS))continue;
'''
new='''                    int grandTotal=Math.max(1,globalTotal);
                    int estimatedTitles=searchIndex.count(key);
                    final int initialDone=globalDone,initialTitles=estimatedTitles,initialTotal=grandTotal;
                    if(isUiAlive())runOnUiThread(()->showIndexBanner("",initialDone,initialTotal,initialTitles));

                    for(String type:order){
                        if(Thread.currentThread().isInterrupted())break;
                        if(!migration&&!requestedForce&&searchIndex.isFresh(key,type,SEARCH_INDEX_TTL_MS))continue;
                        List<Category> cats=catMap.get(type);
                        boolean categoryCacheFresh=searchIndex.categoriesFresh(key,type,30L*60L*1000L);
                        if(cats==null)cats=new ArrayList<>();
                        if(cats.isEmpty()||migration||requestedForce||!categoryCacheFresh){
                            int previousCategoryCount=cats.size();
                            try{
                                List<Category> remoteCats=new ArrayList<>(indexProvider.categories(type));
                                searchIndex.replaceCategories(key,type,remoteCats);
                                cats=remoteCats;
                                catMap.put(type,cats);
                                grandTotal=Math.max(1,grandTotal+(cats.size()-previousCategoryCount));
                                SettingsStore.prefs(this).edit().putInt("first_sync_total_count_"+key,grandTotal).apply();
                                final int discoveredTotal=grandTotal,discoveredDone=globalDone,discoveredTitles=searchIndex.count(key);
                                runOnUiThread(()->{if(isUiAlive()&&key.equals(profileKey()))showIndexBanner("",discoveredDone,discoveredTotal,discoveredTitles);});
                            }catch(Exception categoryError){
                                if(cats.isEmpty())throw categoryError;
                            }
                        }
'''
if old not in m: raise SystemExit("progressive section marker missing")
m=m.replace(old,new,1)

old='''    int pct=Math.max(0,Math.min(100,(finished*100)/total));
    indexBannerText.setText(pct+"% · "+finished+"/"+total+" "+T("categories")+" · "+Math.max(0,titles)+" "+T("titles"));'''
new='''    int pct=Math.max(0,Math.min(100,(finished*100)/total));
    sp.edit().putInt("first_sync_done_count_"+key,finished).putInt("first_sync_total_count_"+key,total).putInt("first_sync_titles_"+key,Math.max(0,titles)).apply();
    indexBannerText.setText(pct+"% · "+finished+"/"+total+" "+T("categories")+" · "+Math.max(0,titles)+" "+T("titles"));'''
if old not in m: raise SystemExit("showIndexBanner marker missing")
m=m.replace(old,new,1)

main.write_text(m,encoding="utf-8")

i=instr.read_text(encoding="utf-8")
old_prepare='''            store.importBatch(session,PROFILE,"live",range("resume",0,80,"live"));
            store.importBatch(session,PROFILE,"live",range("resume",80,160,"live"));
            store.checkpointImport(PROFILE,"live",session,"cat-2",160);'''
new_prepare='''            ArrayList<MediaEntry> resumeBatch1=range("resume",0,80,"live");
            ArrayList<MediaEntry> resumeBatch2=range("resume",80,160,"live");
            store.importBatch(session,PROFILE,"live",resumeBatch1);store.upsert(PROFILE,resumeBatch1);
            store.importBatch(session,PROFILE,"live",resumeBatch2);store.upsert(PROFILE,resumeBatch2);
            SettingsStore.prefs(getTargetContext()).edit().putInt("first_sync_done_count_"+PROFILE,2).putInt("first_sync_total_count_"+PROFILE,5).putInt("first_sync_titles_"+PROFILE,160).commit();
            store.checkpointImport(PROFILE,"live",session,"cat-2",160);'''
if old_prepare not in i: raise SystemExit("resume visible progress prepare marker missing")
i=i.replace(old_prepare,new_prepare,1)
old_verify='''            require(store.importCount(p.session,PROFILE,"live")==160,"Staged rows did not survive process boundary");'''
new_verify='''            require(store.importCount(p.session,PROFILE,"live")==160,"Staged rows did not survive process boundary");
            require(store.count(PROFILE)>=160,"Visible partial library returned to zero after restart");
            android.content.SharedPreferences progressPrefs=SettingsStore.prefs(getTargetContext());
            require(progressPrefs.getInt("first_sync_titles_"+PROFILE,0)>=160,"Visible title counter returned to zero after restart");
            require(progressPrefs.getInt("first_sync_done_count_"+PROFILE,0)==2,"Visible category progress was lost after restart");
            require(progressPrefs.getInt("first_sync_total_count_"+PROFILE,0)==5,"Visible category total was lost after restart");'''
if old_verify not in i: raise SystemExit("resume visible progress verify marker missing")
i=i.replace(old_verify,new_verify,1)
instr.write_text(i,encoding="utf-8")

print("Applied v0.13.8 progressive first-sync category discovery")
