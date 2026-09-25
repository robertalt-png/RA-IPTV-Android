from pathlib import Path

root=Path("source/RA_IPTV_Android_v0.1")
p=root/"app/src/main/java/com/robertalt/raiptv/MainActivity.java"
s=p.read_text()

old='''    boolean seriesEpisodeMode=false,settingCategories=false,indexRefreshRunning=false,activityPaused=false; volatile int fullLibraryToken=0; String appliedLanguage="";
    int requestSerial=0,heroSerial=0; String latestSearchQuery="";'''
new='''    boolean seriesEpisodeMode=false,settingCategories=false,indexRefreshRunning=false,activityPaused=false; volatile int fullLibraryToken=0; String appliedLanguage="";
    boolean cachePagingActive=false,cachePageLoading=false; String cachePagingSection=""; int cachePagingOffset=0,cachePagingTotal=0; static final int CACHE_PAGE_SIZE=600;
    int requestSerial=0,heroSerial=0; String latestSearchQuery="";'''
assert old in s
s=s.replace(old,new,1)

old='''        grid.setOnItemLongClickListener((p,v,pos,id)->{actions(gridAdapter.getItem(pos));return true;});
        heroAction.setOnClickListener(v->{if(selectedHero!=null)select(selectedHero);});'''
new='''        grid.setOnItemLongClickListener((p,v,pos,id)->{actions(gridAdapter.getItem(pos));return true;});
        grid.setOnScrollListener(new AbsListView.OnScrollListener(){public void onScrollStateChanged(AbsListView v,int state){}public void onScroll(AbsListView v,int first,int visible,int total){if(cachePagingActive&&!cachePageLoading&&visible>0&&total>0&&first+visible>=total-24)loadNextCachedPage(requestSerial,false);}});
        heroAction.setOnClickListener(v->{if(selectedHero!=null)select(selectedHero);});'''
assert old in s
s=s.replace(old,new,1)

old='''    void loadHome(){
        nextRequest();section="home";'''
new='''    void loadHome(){
        stopCachePaging();nextRequest();section="home";'''
assert old in s
s=s.replace(old,new,1)

old='''    void loadSection(String s){
        final int token=nextRequest();section=s;'''
new='''    void loadSection(String s){
        stopCachePaging();final int token=nextRequest();section=s;'''
assert old in s
s=s.replace(old,new,1)

start=s.index('    void loadCachedSection(String requested,int token,int cachedCount){')
end=s.index('\n    void setCategorySpinner',start)
replacement='''    void stopCachePaging(){cachePagingActive=false;cachePageLoading=false;cachePagingSection="";cachePagingOffset=0;cachePagingTotal=0;}

    void loadCachedSection(String requested,int token,int cachedCount){
        stopCachePaging();final boolean live=requested.equals("live");cachePagingActive=true;cachePagingSection=requested;cachePagingOffset=0;cachePagingTotal=cachedCount;all=new ArrayList<>();showMediaGrid(live);gridAdapter.set(Collections.emptyList(),live);busy(true,T("library_opening"));loadNextCachedPage(token,true);
    }

    void loadNextCachedPage(int token,boolean first){
        if(!cachePagingActive||cachePageLoading||!current(token)||!section.equals(cachePagingSection)||cachePagingOffset>=cachePagingTotal)return;
        cachePageLoading=true;final String requested=cachePagingSection;final int start=cachePagingOffset;final boolean live="live".equals(requested);final String sort=SettingsStore.sort(this);
        exec.execute(()->{
            try{
                List<MediaEntry>raw=searchIndex.sectionPage(profileKey(),requested,start,CACHE_PAGE_SIZE,sort);
                List<MediaEntry>page=visibleItems(raw);final int latestTotal=searchIndex.countSection(profileKey(),requested);final int consumed=raw.size();final ArrayList<MediaEntry>push=new ArrayList<>(page);
                runOnUiThread(()->{
                    if(!current(token)||!cachePagingActive||!section.equals(requested)){cachePageLoading=false;return;}
                    cachePagingTotal=Math.max(latestTotal,start+consumed);cachePagingOffset=start+consumed;
                    if(!push.isEmpty()){all.addAll(push);gridAdapter.append(push);if(selectedHero==null)previewAuto(push.get(0));}
                    cachePageLoading=false;busy(false,cachePagingTotal+" "+T("results")+" · "+T("local_index"));
                    if(consumed==0||cachePagingOffset>=cachePagingTotal)cachePagingActive=false;
                });
            }catch(Throwable e){runOnUiThread(()->{cachePageLoading=false;if(current(token))busy(false,"Cache: "+friendlyThrowable(e));});}
        });
    }
'''
s=s[:start]+replacement+s[end:]

old='''    void loadItems(String cat){
        final String requested=section;
        if("all".equals(cat)'''
new='''    void loadItems(String cat){
        final String requested=section;if(!"all".equals(cat))stopCachePaging();
        if("all".equals(cat)'''
assert old in s
s=s.replace(old,new,1)

old='''    void loadGenre(String genreKey,String genreLabel){List<Category>matches=new ArrayList<>();'''
new='''    void loadGenre(String genreKey,String genreLabel){stopCachePaging();List<Category>matches=new ArrayList<>();'''
assert old in s
s=s.replace(old,new,1)

old='''    void loadEpg(){
        final int token=nextRequest();'''
new='''    void loadEpg(){
        stopCachePaging();final int token=nextRequest();'''
assert old in s
s=s.replace(old,new,1)

old='''    void select(MediaEntry e){if(e.type.equals("series")){final int token=nextRequest();'''
new='''    void select(MediaEntry e){if(e.type.equals("series")){stopCachePaging();final int token=nextRequest();'''
assert old in s
s=s.replace(old,new,1)

# Sorting a cached "All" library should restart DB paging instead of sorting only the already materialized subset.
old='''SettingsStore.prefs(this).edit().putString("sort",values[w]).apply();d.dismiss();if(fullLibraryToken!=0&&(section.equals("live")||section.equals("vod")||section.equals("series"))){'''
new='''SettingsStore.prefs(this).edit().putString("sort",values[w]).apply();d.dismiss();if((section.equals("live")||section.equals("vod")||section.equals("series"))&&searchIndex.countSection(profileKey(),section)>0&&"all".equals(currentCategoryId)){final int token=nextRequest();loadCachedSection(section,token,searchIndex.countSection(profileKey(),section));return;}if(fullLibraryToken!=0&&(section.equals("live")||section.equals("vod")||section.equals("series"))){'''
assert old in s
s=s.replace(old,new,1)
p.write_text(s)

p=root/"app/src/main/java/com/robertalt/raiptv/storage/SearchIndexStore.java"
s=p.read_text()
old='''    public synchronized List<MediaEntry> sectionPage(String profile,String section,int offset,int limit){
        ArrayList<MediaEntry> out=new ArrayList<>();int safeOffset=Math.max(0,offset),safeLimit=Math.max(1,Math.min(1000,limit));
        try(Cursor c=getReadableDatabase().rawQuery("SELECT payload FROM entries WHERE profile=? AND type=? ORDER BY rowid ASC LIMIT ? OFFSET ?",new String[]{profile,section,String.valueOf(safeLimit),String.valueOf(safeOffset)})){
            while(c.moveToNext()){MediaEntry e=decode(c.getString(0));if(e!=null)out.add(e);}
        }catch(Exception ignored){}
        return out;
    }
'''
new='''    public synchronized List<MediaEntry> sectionPage(String profile,String section,int offset,int limit){
        return sectionPage(profile,section,offset,limit,"provider");
    }

    public synchronized List<MediaEntry> sectionPage(String profile,String section,int offset,int limit,String sort){
        ArrayList<MediaEntry> out=new ArrayList<>();int safeOffset=Math.max(0,offset),safeLimit=Math.max(1,Math.min(1000,limit));String order="rowid ASC";if("az".equals(sort))order="name_norm COLLATE NOCASE ASC";else if("za".equals(sort))order="name_norm COLLATE NOCASE DESC";
        try(Cursor c=getReadableDatabase().rawQuery("SELECT payload FROM entries WHERE profile=? AND type=? ORDER BY "+order+" LIMIT ? OFFSET ?",new String[]{profile,section,String.valueOf(safeLimit),String.valueOf(safeOffset)})){
            while(c.moveToNext()){MediaEntry e=decode(c.getString(0));if(e!=null)out.add(e);}
        }catch(Exception ignored){}
        if("favorites".equals(sort)||"recent".equals(sort)){List<MediaEntry> tmp=new ArrayList<>(out);out.clear();out.addAll(tmp);}
        return out;
    }
'''
assert old in s
s=s.replace(old,new,1)
p.write_text(s)

p=root/"app/build.gradle"
s=p.read_text().replace("versionCode 17","versionCode 18").replace("versionName '0.5.8'","versionName '0.5.9'")
p.write_text(s)

assert "CACHE_PAGE_SIZE=600" in (root/"app/src/main/java/com/robertalt/raiptv/MainActivity.java").read_text()
assert "loadNextCachedPage" in (root/"app/src/main/java/com/robertalt/raiptv/MainActivity.java").read_text()
assert "sectionPage(String profile,String section,int offset,int limit,String sort)" in (root/"app/src/main/java/com/robertalt/raiptv/storage/SearchIndexStore.java").read_text()
assert "versionName '0.5.9'" in (root/"app/build.gradle").read_text()
print("v0.5.9 DB paging patch applied")
