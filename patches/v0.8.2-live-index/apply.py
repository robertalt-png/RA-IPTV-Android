from pathlib import Path
r=Path("source/RA_IPTV_Android_v0.1")

def R(path, old, new, label=''):
    p=r/path; s=p.read_text()
    if old not in s:
        raise SystemExit('missing '+(label or str(path)))
    p.write_text(s.replace(old,new))

R(Path('app/build.gradle'), 'versionCode 30', 'versionCode 31')
R(Path('app/build.gradle'), "versionName '0.8.1'", "versionName '0.8.2'")
R(Path('app/src/main/java/com/robertalt/raiptv/SettingsActivity.java'), 'Nivaro IPTV Player 0.8.1', 'Nivaro IPTV Player 0.8.2')

R(Path('app/src/main/java/com/robertalt/raiptv/model/MediaEntry.java'),
  '    public int season=0, episode=0;\n',
  '    public int season=0, episode=0, catchupDays=0;\n    public boolean catchup=false;\n')

R(Path('app/src/main/java/com/robertalt/raiptv/provider/XtreamProvider.java'),
  '        e.tvgId=x.optString("epg_channel_id",x.optString("tvg_id",""));e.tvgName=e.name;\n        e.type=type;\n',
  '        e.tvgId=x.optString("epg_channel_id",x.optString("tvg_id",""));e.tvgName=e.name;\n        e.catchup=x.optInt("tv_archive",0)>0||"1".equals(x.optString("tv_archive",""))||x.optBoolean("has_archive",false);e.catchupDays=x.optInt("tv_archive_duration",0);\n        e.type=type;\n')

R(Path('app/src/main/java/com/robertalt/raiptv/core/M3uParser.java'),
  '                MediaEntry e=new MediaEntry(); e.id=a.getOrDefault("tvg-id",a.getOrDefault("tvg-name",name)); e.name=name; e.logo=a.getOrDefault("tvg-logo",""); e.group=a.getOrDefault("group-title","Other"); e.tvgId=a.getOrDefault("tvg-id",""); e.tvgName=a.getOrDefault("tvg-name",name); e.type="live"; pending=e;\n',
  '                MediaEntry e=new MediaEntry(); e.id=a.getOrDefault("tvg-id",a.getOrDefault("tvg-name",name)); e.name=name; e.logo=a.getOrDefault("tvg-logo",""); e.group=a.getOrDefault("group-title","Other"); e.tvgId=a.getOrDefault("tvg-id",""); e.tvgName=a.getOrDefault("tvg-name",name); e.type="live"; String cu=a.getOrDefault("catchup",a.getOrDefault("timeshift",""));e.catchup=!cu.isEmpty()&&!"0".equals(cu)&&!"false".equalsIgnoreCase(cu)&&!"none".equalsIgnoreCase(cu);try{e.catchupDays=Integer.parseInt(a.getOrDefault("catchup-days",a.getOrDefault("timeshift","0")));}catch(Exception ignored){} pending=e;\n')

p= r/'app/src/main/java/com/robertalt/raiptv/storage/SearchIndexStore.java'; s=p.read_text()
old='x.put("imdbId",e.imdbId);x.put("season",e.season);x.put("episode",e.episode);x.put("candidates",new JSONArray(e.candidates));return x.toString();'
new='x.put("imdbId",e.imdbId);x.put("season",e.season);x.put("episode",e.episode);x.put("catchup",e.catchup);x.put("catchupDays",e.catchupDays);x.put("candidates",new JSONArray(e.candidates));return x.toString();'
if old not in s: raise SystemExit('missing encode catchup')
s=s.replace(old,new)
old='e.imdbId=x.optString("imdbId");e.season=x.optInt("season");e.episode=x.optInt("episode");JSONArray a=x.optJSONArray("candidates");'
new='e.imdbId=x.optString("imdbId");e.season=x.optInt("season");e.episode=x.optInt("episode");e.catchup=x.optBoolean("catchup",false);e.catchupDays=x.optInt("catchupDays",0);JSONArray a=x.optJSONArray("candidates");'
if old not in s: raise SystemExit('missing decode catchup')
p.write_text(s.replace(old,new))

R(Path('app/src/main/java/com/robertalt/raiptv/DisplayText.java'),
  '    public static String badges(MediaEntry e){return parse(e).badges();}\n',
  '    public static String badges(MediaEntry e){String b=parse(e).badges();if(e!=null&&e.catchup){String r=e.catchupDays>0?"↶ "+e.catchupDays+"D":"↶";return b.isEmpty()?r:b+" · "+r;}return b;}\n')

p=r/'app/src/main/res/layout/activity_main.xml'; s=p.read_text()
anchor='''  <FrameLayout android:id="@+id/heroContainer" android:layout_width="match_parent" android:layout_height="185dp"'''
banner='''  <LinearLayout android:id="@+id/indexBanner" android:layout_width="match_parent" android:layout_height="42dp" android:layout_marginLeft="14dp" android:layout_marginRight="14dp" android:layout_marginBottom="5dp" android:gravity="center_vertical" android:paddingLeft="12dp" android:paddingRight="12dp" android:background="@drawable/bg_search" android:visibility="gone">
    <ProgressBar android:layout_width="18dp" android:layout_height="18dp"/>
    <TextView android:id="@+id/indexBannerText" android:layout_width="0dp" android:layout_height="wrap_content" android:layout_weight="1" android:paddingLeft="9dp" android:textColor="@color/text" android:textSize="11.5sp" android:maxLines="2" android:ellipsize="end"/>
  </LinearLayout>
'''
if anchor not in s: raise SystemExit('missing hero anchor')
p.write_text(s.replace(anchor,banner+anchor))

p=r/'app/src/main/java/com/robertalt/raiptv/MainActivity.java'; s=p.read_text()
s=s.replace('TextView status,title,heroTitle,heroSubtitle;', 'TextView status,title,heroTitle,heroSubtitle,indexBannerText;')
s=s.replace('ScrollView browseScroll,epgBoard; LinearLayout browseContainer,epgBoardContainer;', 'ScrollView browseScroll,epgBoard; LinearLayout browseContainer,epgBoardContainer,indexBanner;')
s=s.replace('static final int SEARCH_INDEX_GENERATION=9;', 'static final int SEARCH_INDEX_GENERATION=10;')
s=s.replace('status=findViewById(R.id.status);title=findViewById(R.id.title);', 'status=findViewById(R.id.status);indexBanner=findViewById(R.id.indexBanner);indexBannerText=findViewById(R.id.indexBannerText);title=findViewById(R.id.title);')
s=s.replace('List<MediaEntry>page=visibleItems(raw);final int latestTotal=', 'List<MediaEntry>page=visibleItems(raw);if(live)page=sortItems(page);final int latestTotal=')

old='''                            for(int ci=startAt;ci<cats.size();ci++){
                                Category c=cats.get(ci);if(Thread.currentThread().isInterrupted())break;
                                waitWhilePaused();waitForLibraryLoad();
                                try{
                                    List<MediaEntry>x=provider.items(type,c.id);for(MediaEntry e:x)if(e!=null&&(e.group==null||e.group.trim().isEmpty()))e.group=c.name;
                                    searchIndex.upsert(key,x);
                                    total+=x.size();publishIndexedTop(type);
                                }catch(Exception ignored){}
                                try{SettingsStore.prefs(this).edit().putString(cacheCursorKey(type),safe(c.id)).apply();}catch(Exception ignored){}
                                try{Thread.sleep(140);}catch(InterruptedException ie){Thread.currentThread().interrupt();break;}
'''
new='''                            if(("vod".equals(type)||"series".equals(type))&&isUiAlive()){final int fc=cats.size(),fs=startAt;runOnUiThread(()->showIndexBanner(type,fs,fc,searchIndex.countSection(key,type)));}
                            for(int ci=startAt;ci<cats.size();ci++){
                                Category c=cats.get(ci);if(Thread.currentThread().isInterrupted())break;
                                waitWhilePaused();waitForLibraryLoad();
                                try{
                                    List<MediaEntry>x=provider.items(type,c.id);for(MediaEntry e:x)if(e!=null&&(e.group==null||e.group.trim().isEmpty()))e.group=c.name;
                                    searchIndex.upsert(key,x);
                                    total+=x.size();publishIndexedTop(type);if(("vod".equals(type)||"series".equals(type))&&isUiAlive()){final int fd=ci+1,fc=cats.size(),fi=searchIndex.countSection(key,type);runOnUiThread(()->showIndexBanner(type,fd,fc,fi));}
                                }catch(Exception ignored){}
                                try{SettingsStore.prefs(this).edit().putString(cacheCursorKey(type),safe(c.id)).apply();}catch(Exception ignored){}
                                try{Thread.sleep(140);}catch(InterruptedException ie){Thread.currentThread().interrupt();break;}
'''
if old not in s: raise SystemExit('missing index loop')
s=s.replace(old,new)

old='''                            if(!Thread.currentThread().isInterrupted()){int count=searchIndex.countSection(key,type);if(count>0){searchIndex.markSection(key,type,count);SettingsStore.prefs(this).edit().remove(cacheCursorKey(type)).apply();reloadIndexedSectionWhenReady(type);}}
                            try{Thread.sleep(700);}catch(InterruptedException ie){Thread.currentThread().interrupt();break;}
'''
new='''                            if(!Thread.currentThread().isInterrupted()){int count=searchIndex.countSection(key,type);if(count>0){searchIndex.markSection(key,type,count);SettingsStore.prefs(this).edit().remove(cacheCursorKey(type)).apply();reloadIndexedSectionWhenReady(type);}if(("vod".equals(type)||"series".equals(type))&&isUiAlive())runOnUiThread(()->hideIndexBanner(type));}
                            try{Thread.sleep(700);}catch(InterruptedException ie){Thread.currentThread().interrupt();break;}
'''
if old not in s: raise SystemExit('missing index completion')
s=s.replace(old,new)
s=s.replace('            }catch(Exception ignored){}finally{indexRefreshRunning=false;}\n', '            }catch(Exception ignored){}finally{indexRefreshRunning=false;runOnUiThread(()->{if(isUiAlive())hideIndexBanner("");});}\n')

needle='''    int hiddenCount(List<MediaEntry>a,List<MediaEntry>b){return Math.max(0,(a==null?0:a.size())-(b==null?0:b.size()));}
    List<MediaEntry> sortItems'''
helpers='''    int hiddenCount(List<MediaEntry>a,List<MediaEntry>b){return Math.max(0,(a==null?0:a.size())-(b==null?0:b.size()));}
    void showIndexBanner(String type,int done,int cats,int titles){if(indexBanner==null||indexBannerText==null)return;String target="vod".equals(type)?T("movies"):T("series");String prefix="nl".equals(SettingsStore.primaryLanguage(this))?"Nog even geduld — ":"";String msg=prefix+T("loading")+" "+target+" · "+Math.max(0,done)+"/"+Math.max(1,cats)+" "+T("categories")+" · "+Math.max(0,titles)+" "+T("titles");indexBannerText.setText(msg);indexBanner.setTag(type);indexBanner.setVisibility(View.VISIBLE);}
    void hideIndexBanner(String type){if(indexBanner==null)return;Object t=indexBanner.getTag();if(type==null||type.isEmpty()||t==null||type.equals(String.valueOf(t))){indexBanner.setVisibility(View.GONE);indexBanner.setTag(null);}}
    boolean isMainDutchTv(MediaEntry e){String x=(safe(e==null?"":e.name)+" "+safe(e==null?"":e.group)).toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+"," ");return x.matches(".*\\\\b(?:npo ?[123]|rtl ?[4578]|sbs ?6|net ?5|veronica|rtl z|rtl lounge)\\\\b.*");}
    int liveRank(MediaEntry e){String x=(safe(e==null?"":e.name)+" "+safe(e==null?"":e.group)).toLowerCase(Locale.ROOT);int r=0;if(isRadioChannel(e))r+=10000;else if(isMainDutchTv(e))r-=600;if(e!=null&&e.catchup)r-=350;if(x.matches(".*(?:\\\\b4k\\\\b|\\\\buhd\\\\b).*"))r-=260;else if(x.matches(".*\\\\bfhd\\\\b.*"))r-=220;else if(x.matches(".*\\\\bhd\\\\b.*"))r-=180;else r+=40;return r;}
    List<MediaEntry> sortItems'''
if needle not in s: raise SystemExit('missing sort insertion')
s=s.replace(needle,helpers)
old='''List<MediaEntry> sortItems(List<MediaEntry>src){List<MediaEntry>o=new ArrayList<>(src==null?Collections.emptyList():src);String mode=SettingsStore.sort(this),pref=SettingsStore.contentLanguage(this);Comparator<MediaEntry>language=(a,b)->Integer.compare(ContentLanguage.rank(a,pref),ContentLanguage.rank(b,pref));Comparator<MediaEntry>az=(a,b)->safe(a.name).compareToIgnoreCase(safe(b.name));Comparator<MediaEntry>secondary=(a,b)->0;'''
new='''List<MediaEntry> sortItems(List<MediaEntry>src){List<MediaEntry>o=new ArrayList<>(src==null?Collections.emptyList():src);String mode=SettingsStore.sort(this),pref=SettingsStore.contentLanguage(this);Comparator<MediaEntry>language=(a,b)->Integer.compare(ContentLanguage.rank(a,pref),ContentLanguage.rank(b,pref));Comparator<MediaEntry>livePriority=(a,b)->("live".equals(a.type)&&"live".equals(b.type))?Integer.compare(liveRank(a),liveRank(b)):0;Comparator<MediaEntry>az=(a,b)->safe(a.name).compareToIgnoreCase(safe(b.name));Comparator<MediaEntry>secondary=(a,b)->0;'''
if old not in s: raise SystemExit('missing sortItems head')
s=s.replace(old,new)
s=s.replace('o.sort(language.thenComparing(secondary));return o;}', 'o.sort(language.thenComparing(livePriority).thenComparing(secondary));return o;}')
p.write_text(s)
print('v0.8.2 applied')
