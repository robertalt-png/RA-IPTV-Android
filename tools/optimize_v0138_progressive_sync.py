from pathlib import Path

root=Path(".")
main=root/"app/src/main/java/com/robertalt/raiptv/MainActivity.java"
if not main.exists(): raise SystemExit("missing MainActivity.java")
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
print("Applied v0.13.8 progressive first-sync category discovery")
