from pathlib import Path

root=Path("source/RA_IPTV_Android_v0.1")

def rw(rel, old, new, count=1):
    p=root/rel
    s=p.read_text()
    if old not in s:
        raise SystemExit("missing target in "+rel+": "+repr(old[:160]))
    p.write_text(s.replace(old,new,count))

# Version bump.
rw("app/build.gradle","versionCode 24","versionCode 25")
rw("app/build.gradle","versionName '0.6.5'","versionName '0.6.6'")
rw("app/src/main/java/com/robertalt/raiptv/SettingsActivity.java","Nivaro IPTV Player 0.6.5\\n","Nivaro IPTV Player 0.6.6\\n")

# Keep the settings viewport below system bars even while the user scrolls.
p=root/"app/src/main/java/com/robertalt/raiptv/SettingsActivity.java"
s=p.read_text()
old='''  void build(){ScrollView sv=new ScrollView(this);sv.setBackgroundColor(0xFF07090D);box=new LinearLayout(this);box.setOrientation(LinearLayout.VERTICAL);box.setPadding(dp(18),dp(12),dp(18),dp(30));sv.addView(box);
'''
new='''  void build(){ScrollView sv=new ScrollView(this);sv.setBackgroundColor(0xFF07090D);sv.setClipToPadding(true);sv.setOnApplyWindowInsetsListener((v,insets)->{v.setPadding(0,insets.getSystemWindowInsetTop(),0,insets.getSystemWindowInsetBottom());return insets;});sv.requestApplyInsets();box=new LinearLayout(this);box.setOrientation(LinearLayout.VERTICAL);box.setPadding(dp(18),dp(12),dp(18),dp(30));sv.addView(box);
'''
if old not in s: raise SystemExit("settings inset anchor missing")
p.write_text(s.replace(old,new,1))

p=root/"app/src/main/java/com/robertalt/raiptv/MainActivity.java"
s=p.read_text()

# New generation forces one clean rebuild because v0.6.6 changes how category language
# is inherited and in which order provider categories are indexed.
s=s.replace("static final int SEARCH_INDEX_GENERATION=3;","static final int SEARCH_INDEX_GENERATION=4;",1)

old='''    boolean cachePagingActive=false,cachePageLoading=false,autoReindexAfterConnect=false; String cachePagingSection=""; int cachePagingOffset=0,cachePagingTotal=0; static final int CACHE_PAGE_SIZE=600;
'''
new='''    boolean cachePagingActive=false,cachePageLoading=false,autoReindexAfterConnect=false; String cachePagingSection=""; int cachePagingOffset=0,cachePagingTotal=0; static final int CACHE_PAGE_SIZE=600; long lastIndexUiPublish=0L; String indexAutoHeroKey="";
'''
if old not in s: raise SystemExit("paging fields anchor missing")
s=s.replace(old,new,1)

# Publish the newly indexed top page while rebuilding, but only when the user is at
# the top of the matching "All" grid. This prevents an old NL/MULTI first page from
# remaining on screen after preferred-language rows become available.
anchor='''    void refreshSearchIndex(boolean force){
'''
methods='''    void publishIndexedTop(String type){
        long now=android.os.SystemClock.elapsedRealtime();if(now-lastIndexUiPublish<1200)return;lastIndexUiPublish=now;
        final String key=profileKey(),sort=SettingsStore.sort(this),pref=SettingsStore.contentLanguage(this);final int total=searchIndex.countSection(key,type);if(total<=0)return;
        final List<MediaEntry> page=visibleItems(searchIndex.sectionPage(key,type,0,CACHE_PAGE_SIZE,sort,pref));if(page.isEmpty())return;
        runOnUiThread(()->{
            if(!isUiAlive()||!type.equals(section)||!"all".equals(currentCategoryId)||(latestSearchQuery!=null&&!latestSearchQuery.isEmpty())||currentCategories.isEmpty())return;
            if(grid.getVisibility()!=View.VISIBLE||grid.getFirstVisiblePosition()>2)return;
            stopCachePaging();all=new ArrayList<>(page);showMediaGrid("live".equals(type));gridAdapter.set(page,"live".equals(type));
            MediaEntry first=page.get(0);if(selectedHero==null||selectedHero.uniqueKey().equals(indexAutoHeroKey)){previewAuto(first);indexAutoHeroKey=first.uniqueKey();}
            busy(true,total+" "+T("results")+" · "+T("index_building"));
        });
    }

    void reloadIndexedSectionWhenReady(String type){
        runOnUiThread(()->{
            if(!isUiAlive()||!type.equals(section)||!"all".equals(currentCategoryId)||(latestSearchQuery!=null&&!latestSearchQuery.isEmpty()))return;
            indexAutoHeroKey="";loadSection(type);
        });
    }

'''
if anchor not in s: raise SystemExit("refreshSearchIndex anchor missing")
s=s.replace(anchor,methods+anchor,1)

# Sort the provider categories by the chosen content language before a background
# rebuild, inherit the category name into entries that have no group, publish the
# preferred-language page as it arrives, and reload once that section is complete.
old='''                            List<Category> cats=provider.categories(type);int startAt=0;String cursor=force?"":SettingsStore.prefs(this).getString(cacheCursorKey(type),"");if(cursor!=null&&!cursor.isEmpty()){for(int ci=0;ci<cats.size();ci++)if(cursor.equals(cats.get(ci).id)){startAt=ci+1;break;}if(startAt>=cats.size())startAt=0;}
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
'''
new='''                            List<Category> cats=new ArrayList<>(provider.categories(type));final String preferred=SettingsStore.contentLanguage(this);cats.sort((a,b)->{int x=Integer.compare(ContentLanguage.rankText(a.name,preferred),ContentLanguage.rankText(b.name,preferred));return x!=0?x:safe(a.name).compareToIgnoreCase(safe(b.name));});int startAt=0;String cursor=force?"":SettingsStore.prefs(this).getString(cacheCursorKey(type),"");if(cursor!=null&&!cursor.isEmpty()){for(int ci=0;ci<cats.size();ci++)if(cursor.equals(cats.get(ci).id)){startAt=ci+1;break;}if(startAt>=cats.size())startAt=0;}
                            for(int ci=startAt;ci<cats.size();ci++){
                                Category c=cats.get(ci);if(Thread.currentThread().isInterrupted())break;
                                waitWhilePaused();waitForLibraryLoad();
                                try{
                                    List<MediaEntry>x=provider.items(type,c.id);for(MediaEntry e:x)if(e!=null&&(e.group==null||e.group.trim().isEmpty()))e.group=c.name;
                                    searchIndex.upsert(key,x);
                                    total+=x.size();publishIndexedTop(type);
                                }catch(Exception ignored){}
                                try{SettingsStore.prefs(this).edit().putString(cacheCursorKey(type),safe(c.id)).apply();}catch(Exception ignored){}
                                try{Thread.sleep(140);}catch(InterruptedException ie){Thread.currentThread().interrupt();break;}
                                String q=latestSearchQuery;
                                if(q!=null&&!q.isEmpty()&&isUiAlive())runOnUiThread(()->{if(isUiAlive())searchEverywhere(q);});
                            }
                            if(!Thread.currentThread().isInterrupted()){int count=searchIndex.countSection(key,type);if(count>0){searchIndex.markSection(key,type,count);SettingsStore.prefs(this).edit().remove(cacheCursorKey(type)).apply();reloadIndexedSectionWhenReady(type);}}
'''
if old not in s: raise SystemExit("refresh loop anchor missing")
s=s.replace(old,new,1)

p.write_text(s)

# Validation.
main=p.read_text()
assert "SEARCH_INDEX_GENERATION=4" in main
assert "cats.sort((a,b)->" in main
assert "e.group=c.name" in main
assert "publishIndexedTop(type)" in main
assert "reloadIndexedSectionWhenReady(type)" in main
assert "versionName '0.6.6'" in (root/"app/build.gradle").read_text()
assert "Nivaro IPTV Player 0.6.6" in (root/"app/src/main/java/com/robertalt/raiptv/SettingsActivity.java").read_text()
assert "setOnApplyWindowInsetsListener" in (root/"app/src/main/java/com/robertalt/raiptv/SettingsActivity.java").read_text()
print("v0.6.6 preferred-language rebuild/live-refresh patch applied")
