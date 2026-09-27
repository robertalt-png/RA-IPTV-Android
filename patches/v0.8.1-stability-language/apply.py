from pathlib import Path
r=Path("source/RA_IPTV_Android_v0.1")
def R(p,a,b):
 p=r/p;s=p.read_text()
 if a not in s: raise SystemExit("missing "+str(p))
 p.write_text(s.replace(a,b))
R(Path("app/build.gradle"),"versionCode 29","versionCode 30")
R(Path("app/build.gradle"),"versionName '0.8.0'","versionName '0.8.1'")
R(Path("app/src/main/java/com/robertalt/raiptv/SettingsActivity.java"),"Nivaro IPTV Player 0.8.0","Nivaro IPTV Player 0.8.1")
R(Path("app/src/main/java/com/robertalt/raiptv/ContentLanguage.java"),
'alias("nl","nl","nld","dut","dutch","nederlands");',
'alias("nl","nl","nld","dut","ned","dutch","nederlands","nederland","netherlands","holland","hollands","vlaams","flemish");')
R(Path("app/src/main/java/com/robertalt/raiptv/ContentLanguage.java"),
'    private static String explicitTag(String raw){String p=prefixToken(raw);if(!p.isEmpty()){String x=ALIAS.get(p);if(x!=null)return x;}String direct=aliasTag(raw);return direct;}\n',
'''    private static String explicitTag(String raw){
        String p=prefixToken(raw);if(!p.isEmpty()){String x=ALIAS.get(p);if(x!=null)return x;}
        if(raw!=null){String s=raw.trim(),head="";if(s.startsWith("|")){int j=s.indexOf('|',1);if(j>1)head=s.substring(1,j);}else if(s.startsWith("[")){int j=s.indexOf(']');if(j>1)head=s.substring(1,j);}else if(s.startsWith("(")){int j=s.indexOf(')');if(j>1)head=s.substring(1,j);}else{int j=s.indexOf(':');if(j>0&&j<=16)head=s.substring(0,j);}
            if(!head.isEmpty())for(String token:head.split("[^\\\\p{L}\\\\p{Nd}]+")){String x=ALIAS.get(normToken(token));if(x!=null)return x;}
        }
        return aliasTag(raw);
    }
''')
p=r/"app/src/main/java/com/robertalt/raiptv/storage/SearchIndexStore.java";s=p.read_text()
n='''    public synchronized int countSection(String profile,String section){
        try(Cursor c=getReadableDatabase().rawQuery("SELECT COUNT(*) FROM entries WHERE profile=? AND type=?",new String[]{profile,section})){
            return c.moveToFirst()?c.getInt(0):0;
        }catch(Exception e){return 0;}
    }

'''
if n not in s: raise SystemExit("missing countSection")
p.write_text(s.replace(n,n+'''    public synchronized boolean isComplete(String profile,String section){
        try(Cursor c=getReadableDatabase().rawQuery("SELECT 1 FROM meta WHERE profile=? AND section=? LIMIT 1",new String[]{profile,section})){return c.moveToFirst();}catch(Exception e){return false;}
    }

'''))
R(Path("app/src/main/java/com/robertalt/raiptv/provider/XtreamProvider.java"),
'''        e.categoryId=String.valueOf(x.opt("category_id"));
        e.type=type;
''',
'''        e.categoryId=String.valueOf(x.opt("category_id"));
        e.tvgId=x.optString("epg_channel_id",x.optString("tvg_id",""));e.tvgName=e.name;
        e.type=type;
''')
p=r/"app/src/main/java/com/robertalt/raiptv/MainActivity.java";s=p.read_text()
s=s.replace("static final int SEARCH_INDEX_GENERATION=8;","static final int SEARCH_INDEX_GENERATION=9;")
s=s.replace("boolean seriesEpisodeMode=false,settingCategories=false,indexRefreshRunning=false,activityPaused=false,autoDefaultGroup=true;","boolean seriesEpisodeMode=false,settingCategories=false,activityPaused=false,autoDefaultGroup=true; volatile boolean indexRefreshRunning=false;")
s=s.replace('''        if(have>=SEARCH_INDEX_GENERATION)return;
        if(have<6)try{searchIndex.clearAll();}catch(Exception ignored){}
        android.content.SharedPreferences.Editor ed=sp.edit().putInt("search_index_generation",SEARCH_INDEX_GENERATION);
        if(have<6)try{for(String k:sp.getAll().keySet())if(k!=null&&k.startsWith("cache_cursor_"))ed.remove(k);}catch(Exception ignored){}
''','''        if(have>=SEARCH_INDEX_GENERATION)return;
        if(have<SEARCH_INDEX_GENERATION)try{searchIndex.clearAll();}catch(Exception ignored){}
        android.content.SharedPreferences.Editor ed=sp.edit().putInt("search_index_generation",SEARCH_INDEX_GENERATION);
        if(have<SEARCH_INDEX_GENERATION)try{for(String k:sp.getAll().keySet())if(k!=null&&k.startsWith("cache_cursor_"))ed.remove(k);}catch(Exception ignored){}
''')
s=s.replace('''    boolean hasIndexedLanguage(String tag){String t=ContentLanguage.normalizeTag(tag);return canUseIndexedLanguage()&&!t.isEmpty()&&searchIndex.countLanguage(profileKey(),indexedSection(),t)>0;}
    boolean hasLanguageGroup(List<Category>raw,String tag){return hasLanguageCategories(raw,tag)||hasIndexedLanguage(tag);}
    boolean hasMultiGroup(List<Category>raw){return hasMultiCategories(raw)||hasIndexedLanguage("multi");}
    boolean hasOtherGroup(List<Category>raw){return hasOtherCategories(raw)||(canUseIndexedLanguage()&&searchIndex.countOther(profileKey(),indexedSection())>0);}
''','''    boolean hasIndexedLanguage(String tag){return false;}
    boolean hasLanguageGroup(List<Category>raw,String tag){return hasLanguageCategories(raw,tag);}
    boolean hasMultiGroup(List<Category>raw){return hasMultiCategories(raw);}
    boolean hasOtherGroup(List<Category>raw){return hasOtherCategories(raw);}
''')
s=s.replace('''    LinkedHashSet<String> availableLanguageTags(List<Category>raw){LinkedHashSet<String>tags=new LinkedHashSet<>();if(raw!=null)for(Category x:raw){String t=ContentLanguage.categoryTag(x.name);if(!t.isEmpty())tags.add(t);}if(canUseIndexedLanguage())try{tags.addAll(searchIndex.languageTags(profileKey(),indexedSection()));}catch(Exception ignored){}tags.remove("");return tags;}
''','''    LinkedHashSet<String> availableLanguageTags(List<Category>raw){LinkedHashSet<String>tags=new LinkedHashSet<>();if(raw!=null)for(Category x:raw){String t=ContentLanguage.categoryTag(x.name);if(!t.isEmpty())tags.add(t);}tags.remove("");return tags;}
''')
a=s.index("    void loadLanguageGroup(String tag,boolean fromRefresh){");b=s.index("    void loadOtherGroup(boolean fromRefresh){",a)
s=s[:a]+'''    void loadLanguageGroup(String tag,boolean fromRefresh){
        final String requested="epg".equals(section)?"live":section;final String normalized=ContentLanguage.normalizeTag(tag);if(normalized.isEmpty()){if("epg".equals(section))loadEpgChannels("all");else loadItems("all");return;}
        stopCachePaging();final int token=nextRequest();final boolean epg="epg".equals(section);final String wantedId="multi".equals(normalized)?"multi":"lang:"+normalized;currentCategoryId=wantedId;currentCategoryName=groupLabel(wantedId);final List<Category>matches=matchingLanguageCategories(normalized);
        if(epg){showEpgByMode(Collections.emptyList());busy(true,currentCategoryName+" · "+T("channels_loading"));}else{showMediaGrid("live".equals(requested));gridAdapter.set(Collections.emptyList(),"live".equals(requested));busy(true,currentCategoryName+" · "+T("library_opening"));}
        exec.execute(()->{try{
            int count=searchIndex.countLanguage(profileKey(),requested,normalized);
            if(count<=0&&!matches.isEmpty()){for(Category c:matches){if(!current(token)||Thread.currentThread().isInterrupted())break;try{List<MediaEntry>batch=provider.items(requested,c.id);for(MediaEntry e:batch)if(e!=null&&(e.group==null||e.group.trim().isEmpty()))e.group=c.name;searchIndex.upsert(profileKey(),batch);}catch(Exception ignored){}}count=searchIndex.countLanguage(profileKey(),requested,normalized);}
            final int readyCount=count;final boolean complete=searchIndex.isComplete(profileKey(),requested);final List<MediaEntry>epgRows=epg&&readyCount>0?sortItems(visibleItems(searchIndex.languagePage(profileKey(),requested,normalized,0,Math.min(250,readyCount),SettingsStore.sort(this)))):Collections.emptyList();
            runOnUiThread(()->{if(!current(token)||!wantedId.equals(currentCategoryId))return;if(readyCount>0){if(epg){all=epgRows;showEpgByMode(epgRows);busy(false,epgRows.size()+" "+T("channels")+" · "+currentCategoryName);}else loadCachedLanguage(requested,token,normalized,readyCount);return;}if(!complete){busy(true,currentCategoryName+" · "+T("index_building"));if(!indexRefreshRunning)refreshSearchIndex(false);}else busy(false,T("no_results"));});
        }catch(Throwable ex){runOnUiThread(()->{if(current(token))busy(false,T("error_prefix")+": "+friendlyThrowable(ex));});}});
    }

'''+s[b:]
a=s.index("    void publishIndexedTop(String type){");b=s.index("    void reloadIndexedSectionWhenReady(String type){",a)
s=s[:a]+'''    void publishIndexedTop(String type){
        long now=android.os.SystemClock.elapsedRealtime();if(now-lastIndexUiPublish<1200)return;lastIndexUiPublish=now;
        final String key=profileKey(),sort=SettingsStore.sort(this),pref=SettingsStore.contentLanguage(this),expected="lang:"+SettingsStore.primaryLanguage(this);final int total=searchIndex.countSection(key,type);if(total<=0)return;
        List<MediaEntry>ready=visibleItems(searchIndex.sectionPage(key,type,0,CACHE_PAGE_SIZE,sort,pref));if(expected.equals(currentCategoryId)){int n=searchIndex.countLanguage(key,type,SettingsStore.primaryLanguage(this));ready=n>0?visibleItems(searchIndex.languagePage(key,type,SettingsStore.primaryLanguage(this),0,Math.min(CACHE_PAGE_SIZE,n),sort)):Collections.emptyList();}if(ready.isEmpty())return;final ArrayList<MediaEntry>shown=new ArrayList<>(ready);
        runOnUiThread(()->{if(!isUiAlive()||!type.equals(section)||(latestSearchQuery!=null&&!latestSearchQuery.isEmpty())||currentCategories.isEmpty())return;if(!"all".equals(currentCategoryId)&&!expected.equals(currentCategoryId))return;if(grid.getVisibility()!=View.VISIBLE||grid.getFirstVisiblePosition()>2)return;stopCachePaging();all=new ArrayList<>(shown);showMediaGrid("live".equals(type));gridAdapter.set(shown,"live".equals(type));MediaEntry first=shown.get(0);if(selectedHero==null||selectedHero.uniqueKey().equals(indexAutoHeroKey)){previewAuto(first);indexAutoHeroKey=first.uniqueKey();}busy(true,total+" "+T("results")+" · "+T("index_building"));});
    }

'''+s[b:]
s=s.replace('''    void reloadIndexedSectionWhenReady(String type){
        runOnUiThread(()->{
            if(!isUiAlive()||!type.equals(section)||!autoDefaultGroup||(latestSearchQuery!=null&&!latestSearchQuery.isEmpty()))return;
            indexAutoHeroKey="";loadSection(type);
        });
    }
''','''    void reloadIndexedSectionWhenReady(String type){
        runOnUiThread(()->{
            if(!isUiAlive()||!type.equals(section)||(latestSearchQuery!=null&&!latestSearchQuery.isEmpty()))return;String preferred="lang:"+SettingsStore.primaryLanguage(this);if(!autoDefaultGroup&&!preferred.equals(currentCategoryId))return;
            indexAutoHeroKey="";loadSection(type);
        });
    }
''')
s=s.replace('''                    for(String type:new String[]{"vod","series","live"}){
''','''                    ArrayList<String>order=new ArrayList<>();if(section.equals("live")||section.equals("vod")||section.equals("series"))order.add(section);for(String t:new String[]{"vod","series","live"})if(!order.contains(t))order.add(t);for(String type:order){
''')
s=s.replace('''    void renderEpgBoard(List<MediaEntry>channels){
        hideContentViews();epgBoard.setVisibility(View.VISIBLE);epgBoardContainer.removeAllViews();List<MediaEntry>src=channels==null?Collections.emptyList():channels;epgBaseEpoch=(System.currentTimeMillis()/1000L/1800L)*1800L;addEpgTimelineHeader();int n=Math.min(24,src.size());for(int i=0;i<n;i++)addEpgBoardRow(src.get(i));if(src.size()>n){TextView more=new TextView(this);more.setText("+ "+(src.size()-n)+" "+T("channels")+" · "+T("list"));more.setTextColor(getResources().getColor(R.color.muted));more.setTextSize(12);more.setGravity(Gravity.CENTER);more.setPadding(dp(8),dp(14),dp(8),dp(18));more.setOnClickListener(v->setEpgMode(false));epgBoardContainer.addView(more);}}
''','''    void renderEpgBoard(List<MediaEntry>channels){
        hideContentViews();epgBoard.setVisibility(View.VISIBLE);epgBoardContainer.removeAllViews();List<MediaEntry>src=new ArrayList<>(channels==null?Collections.emptyList():channels);src.sort((a,b)->{int ar=isRadioChannel(a)?1:0,br=isRadioChannel(b)?1:0;return ar!=br?Integer.compare(ar,br):0;});epgBaseEpoch=(System.currentTimeMillis()/1000L/1800L)*1800L;addEpgTimelineHeader();int n=Math.min(24,src.size());for(int i=0;i<n;i++)addEpgBoardRow(src.get(i));if(src.size()>n){TextView more=new TextView(this);more.setText("+ "+(src.size()-n)+" "+T("channels")+" · "+T("list"));more.setTextColor(getResources().getColor(R.color.muted));more.setTextSize(12);more.setGravity(Gravity.CENTER);more.setPadding(dp(8),dp(14),dp(8),dp(18));more.setOnClickListener(v->setEpgMode(false));epgBoardContainer.addView(more);}}
    boolean isRadioChannel(MediaEntry e){String x=(safe(e==null?"":e.group)+" "+safe(e==null?"":e.name)).toLowerCase(Locale.ROOT);return x.contains("radio")||x.matches(".*\\\\b(?:fm|dab)\\\\b.*");}
''')
s=s.replace('''    void addEpgTimelineHeader(){LinearLayout row=new LinearLayout(this);row.setOrientation(LinearLayout.HORIZONTAL);TextView blank=new TextView(this);blank.setText(T("now"));blank.setTextColor(getResources().getColor(R.color.muted));blank.setTextSize(10);blank.setGravity(Gravity.CENTER_VERTICAL);row.addView(blank,new LinearLayout.LayoutParams(dp(105),dp(28)));FrameLayout times=new FrameLayout(this);int w=epgTimelineWidth();for(int i=0;i<=4;i++){TextView t=new TextView(this);t.setText(clockAt(epgBaseEpoch+i*1800L));t.setTextColor(getResources().getColor(R.color.muted));t.setTextSize(9);FrameLayout.LayoutParams lp=new FrameLayout.LayoutParams(dp(48),dp(28));lp.leftMargin=Math.max(0,dp(w*i/4)-dp(i==4?48:0));t.setLayoutParams(lp);times.addView(t);}row.addView(times,new LinearLayout.LayoutParams(dp(w),dp(28)));epgBoardContainer.addView(row);}
''','''    void addEpgTimelineHeader(){LinearLayout row=new LinearLayout(this);row.setOrientation(LinearLayout.HORIZONTAL);TextView blank=new TextView(this);blank.setText(T("now"));blank.setTextColor(getResources().getColor(R.color.muted));blank.setTextSize(10);blank.setGravity(Gravity.CENTER_VERTICAL);row.addView(blank,new LinearLayout.LayoutParams(dp(105),dp(28)));FrameLayout times=new FrameLayout(this);int w=epgTimelineWidth(),labelW=44;for(int i=0;i<=4;i++){TextView t=new TextView(this);t.setText(clockAt(epgBaseEpoch+i*1800L));t.setTextColor(getResources().getColor(R.color.muted));t.setTextSize(9);FrameLayout.LayoutParams lp=new FrameLayout.LayoutParams(dp(labelW),dp(28));lp.leftMargin=dp(Math.max(0,(w-labelW)*i/4));t.setLayoutParams(lp);times.addView(t);}row.addView(times,new LinearLayout.LayoutParams(dp(w),dp(28)));epgBoardContainer.addView(row);}
''')
p.write_text(s)
print("v0.8.1 applied")