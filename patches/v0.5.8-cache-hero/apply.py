from pathlib import Path

root = Path("source/RA_IPTV_Android_v0.1")
main = root / "app/src/main/java/com/robertalt/raiptv/MainActivity.java"
s = main.read_text()

old = 'adapter.setEpg(provider,epgStore,profileKey());gridAdapter.setEpg(provider,epgStore,profileKey());epgAdapter.configure(provider,profileKey());openStart();'
new = 'adapter.setEpg(provider,epgStore,profileKey());gridAdapter.setEpg(provider,epgStore,profileKey());epgAdapter.configure(provider,profileKey());openStart();ui.postDelayed(()->{if(provider!=null&&!indexRefreshRunning&&!isFinishing()&&!isDestroyed())refreshSearchIndex(false);},1500);'
assert old in s
s = s.replace(old, new, 1)

start = s.index('    void loadSection(String s){')
end = s.index('\n    void setCategorySpinner', start)
new_method = '''    void loadSection(String s){
        final int token=nextRequest();section=s;SettingsStore.setLastSection(this,s);seriesEpisodeMode=false;latestSearchQuery="";currentCategoryId="all";currentCategoryName=T("all");if(profile!=null&&profile.type==Profile.Type.M3U&&!s.equals("live")){showLocal(Collections.emptyList(),"Alleen live tv is beschikbaar voor dit M3U-profiel");return;}
        setHeroHeight(heroHeight());collapseSearch();search.setHint(T("search_everywhere"));filterBar.setVisibility(View.VISIBLE);sortButton.setVisibility(View.VISIBLE);categories.setVisibility(View.VISIBLE);genreButton.setVisibility((s.equals("vod")||s.equals("series"))?View.VISIBLE:View.GONE);showMediaGrid(s.equals("live"));gridAdapter.set(Collections.emptyList(),s.equals("live"));setHeroDefault(label(s),s.equals("live")?T("all_live_sub"):s.equals("vod")?T("all_movies_sub"):T("all_series_sub"));busy(true,T("categories_loading"));
        final int cachedCount=(profile==null?0:searchIndex.countSection(profileKey(),s));
        if(cachedCount>0)loadCachedSection(s,token,cachedCount);
        exec.execute(()->{try{List<Category>loaded=new ArrayList<>(provider.categories(s));List<Category>raw=visibleCategories(loaded);runOnUiThread(()->{if(!current(token)||!section.equals(s))return;currentCategories=raw;setCategorySpinner(raw,true);selectSpinner("all");if(cachedCount<=0)loadAllIncremental(s);});}catch(Exception e){runOnUiThread(()->{if(current(token)&&cachedCount<=0)busy(false,"Fout: "+friendly(e));});}});
    }

    void loadCachedSection(String requested,int token,int cachedCount){
        final boolean live=requested.equals("live");fullLibraryToken=token;all=new ArrayList<>();showMediaGrid(live);gridAdapter.set(Collections.emptyList(),live);busy(true,T("library_opening"));
        exec.execute(()->{int offset=0;final int pageSize=320;try{while(current(token)&&section.equals(requested)&&offset<cachedCount&&!Thread.currentThread().isInterrupted()){List<MediaEntry>page=visibleItems(searchIndex.sectionPage(profileKey(),requested,offset,pageSize));if(page.isEmpty()&&offset==0)break;offset+=pageSize;final ArrayList<MediaEntry>push=new ArrayList<>(page);final int shown=Math.min(offset,cachedCount);runOnUiThread(()->{if(!current(token)||!section.equals(requested))return;if(!push.isEmpty()){all.addAll(push);gridAdapter.append(push);if(selectedHero==null)previewAuto(push.get(0));}busy(true,Math.min(all.size(),cachedCount)+" "+T("loaded")+" · "+shown+"/"+cachedCount);});try{Thread.sleep(25);}catch(InterruptedException ie){Thread.currentThread().interrupt();break;}}
            runOnUiThread(()->{if(current(token)&&section.equals(requested))busy(false,all.size()+" "+T("results")+" · "+T("fully_scrollable"));});
        }catch(Throwable e){runOnUiThread(()->{if(current(token))busy(false,"Cache: "+friendlyThrowable(e));});}finally{if(fullLibraryToken==token)fullLibraryToken=0;}});
    }
'''
s = s[:start] + new_method + s[end:]

old = '        if("all".equals(cat)&&profile!=null&&profile.type==Profile.Type.XTREAM&&(requested.equals("vod")||requested.equals("series")||requested.equals("live"))){loadAllIncremental(requested);return;}'
new = '        if("all".equals(cat)&&profile!=null&&profile.type==Profile.Type.XTREAM&&(requested.equals("vod")||requested.equals("series")||requested.equals("live"))){int cached=searchIndex.countSection(profileKey(),requested);if(cached>0){final int token=nextRequest();loadCachedSection(requested,token,cached);}else loadAllIncremental(requested);return;}'
assert old in s
s = s.replace(old, new, 1)

old = '                    done++;long now=android.os.SystemClock.elapsedRealtime();boolean publish=done==1||done==cats.size()||done%8==0||(now-lastPublish)>=700;'
new = '                    done++;try{SettingsStore.prefs(this).edit().putString(cacheCursorKey(requested),safe(c.id)).apply();}catch(Exception ignored){}long now=android.os.SystemClock.elapsedRealtime();boolean publish=done==1||done==cats.size()||done%8==0||(now-lastPublish)>=700;'
assert old in s
s = s.replace(old, new, 1)

old = '                final List<MediaEntry>finalList=sortItems(aggregate);'
new = '                try{if(!aggregate.isEmpty()){searchIndex.markSection(profileKey(),requested,aggregate.size());SettingsStore.prefs(this).edit().remove(cacheCursorKey(requested)).apply();}}catch(Exception ignored){}\\n                final List<MediaEntry>finalList=sortItems(aggregate);'
assert old in s
s = s.replace(old, new, 1)

old = 'String safe(String s){return s==null?"":s;} String profileKey(){if(profile==null)return "none";String base=profile.type.name()+"|"+safe(profile.server)+"|"+safe(profile.username)+"|"+safe(profile.m3uUrl);return Integer.toHexString(base.hashCode())+":"+profile.type.name();}'
new = old + ' String cacheCursorKey(String type){return "cache_cursor_"+profileKey()+"_"+type;}'
assert old in s
s = s.replace(old, new, 1)

old = '''                            List<Category> cats=provider.categories(type);
                            for(Category c:cats){
                                if(Thread.currentThread().isInterrupted())break;
                                waitWhilePaused();waitForLibraryLoad();
                                try{
                                    List<MediaEntry>x=provider.items(type,c.id);
                                    searchIndex.upsert(key,x);
                                    total+=x.size();
                                }catch(Exception ignored){}
                                String q=latestSearchQuery;
                                if(q!=null&&!q.isEmpty()&&isUiAlive())runOnUiThread(()->{if(isUiAlive())searchEverywhere(q);});
                            }
                            if(total>0)searchIndex.markSection(key,type,total);'''
new = '''                            List<Category> cats=provider.categories(type);int startAt=0;String cursor=force?"":SettingsStore.prefs(this).getString(cacheCursorKey(type),"");if(cursor!=null&&!cursor.isEmpty()){for(int ci=0;ci<cats.size();ci++)if(cursor.equals(cats.get(ci).id)){startAt=ci+1;break;}if(startAt>=cats.size())startAt=0;}
                            for(int ci=startAt;ci<cats.size();ci++){
                                Category c=cats.get(ci);if(Thread.currentThread().isInterrupted())break;
                                waitWhilePaused();waitForLibraryLoad();
                                try{
                                    List<MediaEntry>x=provider.items(type,c.id);
                                    searchIndex.upsert(key,x);
                                    total+=x.size();
                                }catch(Exception ignored){}
                                try{SettingsStore.prefs(this).edit().putString(cacheCursorKey(type),safe(c.id)).apply();}catch(Exception ignored){}
                                try{Thread.sleep(140);}catch(InterruptedException ie){Thread.currentThread().interrupt();break;}
                                String q=latestSearchQuery;
                                if(q!=null&&!q.isEmpty()&&isUiAlive())runOnUiThread(()->{if(isUiAlive())searchEverywhere(q);});
                            }
                            if(!Thread.currentThread().isInterrupted()){int count=searchIndex.countSection(key,type);if(count>0){searchIndex.markSection(key,type,count);SettingsStore.prefs(this).edit().remove(cacheCursorKey(type)).apply();}}
                            try{Thread.sleep(700);}catch(InterruptedException ie){Thread.currentThread().interrupt();break;}'''
assert old in s
s = s.replace(old, new, 1)

old = 'int heroHeight(){String h=SettingsStore.hero(this);int v="small".equals(h)?135:"large".equals(h)?205:165;if(SettingsStore.compact(this))v-=10;return dp(v);}'
new = 'int heroHeight(){String h=SettingsStore.hero(this);int v="small".equals(h)?165:"large".equals(h)?215:185;return dp(v);}'
assert old in s
s = s.replace(old, new, 1)
main.write_text(s)

p = root / "app/src/main/java/com/robertalt/raiptv/storage/SearchIndexStore.java"
s = p.read_text()
anchor = '''    public synchronized int count(String profile){
        try(Cursor c=getReadableDatabase().rawQuery("SELECT COUNT(*) FROM entries WHERE profile=?",new String[]{profile})){
            return c.moveToFirst()?c.getInt(0):0;
        }
    }
'''
assert anchor in s
insert = anchor + '''
    public synchronized int countSection(String profile,String section){
        try(Cursor c=getReadableDatabase().rawQuery("SELECT COUNT(*) FROM entries WHERE profile=? AND type=?",new String[]{profile,section})){
            return c.moveToFirst()?c.getInt(0):0;
        }catch(Exception e){return 0;}
    }

    public synchronized List<MediaEntry> sectionPage(String profile,String section,int offset,int limit){
        ArrayList<MediaEntry> out=new ArrayList<>();int safeOffset=Math.max(0,offset),safeLimit=Math.max(1,Math.min(1000,limit));
        try(Cursor c=getReadableDatabase().rawQuery("SELECT payload FROM entries WHERE profile=? AND type=? ORDER BY rowid ASC LIMIT ? OFFSET ?",new String[]{profile,section,String.valueOf(safeLimit),String.valueOf(safeOffset)})){
            while(c.moveToNext()){MediaEntry e=decode(c.getString(0));if(e!=null)out.add(e);}
        }catch(Exception ignored){}
        return out;
    }
'''
s = s.replace(anchor, insert, 1)
p.write_text(s)

p = root / "app/src/main/res/layout/activity_main.xml"
s = p.read_text()
s = s.replace('android:layout_height="155dp" android:layout_marginLeft="14dp"', 'android:layout_height="185dp" android:layout_marginLeft="14dp"', 1)
s = s.replace('android:gravity="bottom" android:padding="15dp"', 'android:gravity="bottom" android:paddingLeft="15dp" android:paddingRight="15dp" android:paddingTop="12dp" android:paddingBottom="12dp"', 1)
s = s.replace('android:textSize="21sp" android:textStyle="bold"', 'android:textSize="20sp" android:textStyle="bold"', 1)
s = s.replace('android:textSize="11.5sp" android:maxLines="2"', 'android:textSize="11.5sp" android:maxLines="1"', 1)
s = s.replace('android:layout_height="40dp" android:text="▶ Afspelen"', 'android:layout_height="36dp" android:minHeight="0dp" android:text="▶ Afspelen"', 1)
s = s.replace('android:layout_height="40dp" android:layout_marginLeft="6dp" android:text="ⓘ Info"', 'android:layout_height="36dp" android:minHeight="0dp" android:layout_marginLeft="6dp" android:text="ⓘ Info"', 1)
p.write_text(s)

p = root / "app/build.gradle"
s = p.read_text().replace("versionCode 16", "versionCode 17").replace("versionName '0.5.7'", "versionName '0.5.8'")
p.write_text(s)

assert "countSection" in (root/"app/src/main/java/com/robertalt/raiptv/storage/SearchIndexStore.java").read_text()
assert "loadCachedSection" in main.read_text()
assert "versionName '0.5.8'" in (root/"app/build.gradle").read_text()
assert 'android:layout_height="185dp"' in (root/"app/src/main/res/layout/activity_main.xml").read_text()
print("v0.5.8 cache + hero patch applied")
