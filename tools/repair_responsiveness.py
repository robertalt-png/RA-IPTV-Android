from pathlib import Path


def replace_once(text, old, new):
    count = text.count(old)
    if count != 1:
        raise ValueError(f"Expected one source marker, found {count}: {old[:90]}")
    return text.replace(old, new, 1)


def repair_source(text):
    text = replace_once(text, '    void loadItems(String cat){', '''    void loadItems(String cat){
        final String requested=section;
        if("all".equals(cat)&&profile!=null&&profile.type==Profile.Type.XTREAM&&(requested.equals("vod")||requested.equals("series")||requested.equals("live"))){
            final int token=nextRequest();final String key=profileKey();busy(true,T("library_opening"));
            exec.execute(()->{try{final int cached=searchIndex.countSection(key,requested);runOnUiThread(()->{if(!current(token)||!section.equals(requested))return;if(cached>0)loadCachedSection(requested,token,cached);else loadAllIncremental(requested);});}catch(Exception e){runOnUiThread(()->{if(current(token))busy(false,T("loading_interrupted")+": "+friendly(e));});}});
            return;
        }
        loadCategoryItems(cat);
    }
    void loadCategoryItems(String cat){''')
    old = '        if("all".equals(cat)&&profile!=null&&profile.type==Profile.Type.XTREAM&&(requested.equals("vod")||requested.equals("series")||requested.equals("live"))){int cached=searchIndex.countSection(profileKey(),requested);if(cached>0){final int token=nextRequest();loadCachedSection(requested,token,cached);}else loadAllIncremental(requested);return;}\n'
    text = replace_once(text, old, '')

    text = replace_once(text, '    void loadLanguageGroup(String tag,boolean fromRefresh){', '''    void loadLanguageGroup(String tag,boolean fromRefresh){
        final String requested="epg".equals(section)?"live":section,expectedSection=section,key=profileKey(),sort=SettingsStore.sort(this);
        final String normalized=ContentLanguage.normalizeTag(tag);
        if(normalized.isEmpty()){if("epg".equals(section))loadEpgChannels("all");else loadItems("all");return;}
        stopCachePaging();final int token=nextRequest();final boolean epg="epg".equals(section);busy(true,T("library_opening"));
        exec.execute(()->{try{
            final int cached=searchIndex.countLanguage(key,requested,normalized);
            final List<MediaEntry> ready=epg&&cached>0?sortItems(visibleItems(searchIndex.languagePage(key,requested,normalized,0,Math.min(80,cached),sort))):Collections.emptyList();
            runOnUiThread(()->{if(current(token)&&section.equals(expectedSection))loadLanguageGroupWithCache(tag,fromRefresh,cached,ready);});
        }catch(Exception e){runOnUiThread(()->{if(current(token))busy(false,T("loading_interrupted")+": "+friendly(e));});}});
    }
    void loadLanguageGroupWithCache(String tag,boolean fromRefresh,int cached,List<MediaEntry> cachedPage){''')
    text = replace_once(text, '    int cached=searchIndex.countLanguage(profileKey(),requested,normalized);', '')
    text = replace_once(text, '            List<MediaEntry>x=sortItems(visibleItems(searchIndex.languagePage(profileKey(),requested,normalized,0,Math.min(80,cached),SettingsStore.sort(this))));', '            List<MediaEntry>x=cachedPage;')

    text = replace_once(text, '    void loadOtherGroup(boolean fromRefresh){', '''    void loadOtherGroup(boolean fromRefresh){
        final String requested="epg".equals(section)?"live":section,expectedSection=section,key=profileKey(),sort=SettingsStore.sort(this);
        stopCachePaging();final int token=nextRequest();final boolean epg="epg".equals(section);busy(true,T("library_opening"));
        exec.execute(()->{try{
            final int cached=searchIndex.countOther(key,requested);
            final List<MediaEntry> ready=epg&&cached>0?sortItems(visibleItems(searchIndex.otherPage(key,requested,0,Math.min(250,cached),sort))):Collections.emptyList();
            runOnUiThread(()->{if(current(token)&&section.equals(expectedSection))loadOtherGroupWithCache(fromRefresh,cached,ready);});
        }catch(Exception e){runOnUiThread(()->{if(current(token))busy(false,T("loading_interrupted")+": "+friendly(e));});}});
    }
    void loadOtherGroupWithCache(boolean fromRefresh,int cached,List<MediaEntry> ready){''')
    text = replace_once(text, 'final int cached=searchIndex.countOther(profileKey(),requested);final List<Category>matches=', 'final List<Category>matches=')
    text = replace_once(text, 'if(epg&&cached>0){List<MediaEntry>x=sortItems(visibleItems(searchIndex.otherPage(profileKey(),requested,0,Math.min(250,cached),SettingsStore.sort(this))));', 'if(epg&&cached>0){List<MediaEntry>x=ready;')
    text = replace_once(text, 'final int count=searchIndex.countOther(profileKey(),requested);runOnUiThread(()->{', 'final int count=searchIndex.countOther(profileKey(),requested);final List<MediaEntry> loadedPage=epg?sortItems(visibleItems(searchIndex.otherPage(profileKey(),requested,0,Math.min(250,count),SettingsStore.sort(this)))):Collections.emptyList();runOnUiThread(()->{')
    text = replace_once(text, 'if(epg){List<MediaEntry>x=sortItems(visibleItems(searchIndex.otherPage(profileKey(),requested,0,Math.min(250,count),SettingsStore.sort(this))));', 'if(epg){List<MediaEntry>x=loadedPage;')

    text = replace_once(text, 'tag,1000)));runOnUiThread(()->{', 'tag,1000)));final int indexed=searchIndex.count(profileKey());runOnUiThread(()->{')
    text = replace_once(text, 'int indexed=searchIndex.count(profileKey());if(x.isEmpty()', 'if(x.isEmpty()')

    # Sort callbacks reuse the asynchronous group loaders instead of querying SQLite.
    start = text.index('if((section.equals("live")||section.equals("vod")||section.equals("series"))&&currentCategoryId.startsWith("lang:")){String tag=', text.index('void showSortChooser()'))
    end = text.index('if(fullLibraryToken!=0', start)
    text = text[:start] + '''if(section.equals("live")||section.equals("vod")||section.equals("series")){
        if(currentCategoryId.startsWith("lang:")){loadLanguageGroup(currentCategoryId.substring(5),false);return;}
        if("multi".equals(currentCategoryId)){loadLanguageGroup("multi",false);return;}
        if("other".equals(currentCategoryId)){loadOtherGroup(false);return;}
        if("all".equals(currentCategoryId)){loadItems("all");return;}
    }''' + text[end:]

    text = replace_once(text, 'int hidden=0,done=0;long lastPublish=', 'int hidden=0,done=0;boolean sectionComplete=true;long lastPublish=')
    text = replace_once(text, 'if(!current(token)||!section.equals(requested)||Thread.currentThread().isInterrupted())break;\n                    List<MediaEntry>batch;', 'if(!current(token)||!section.equals(requested)||Thread.currentThread().isInterrupted()){sectionComplete=false;break;}\n                    List<MediaEntry>batch;')
    text = replace_once(text, 'catch(Exception one){done++;continue;}', 'catch(Exception one){sectionComplete=false;done++;continue;}')
    text = replace_once(text, 'try{searchIndex.upsert(profileKey(),batch);}catch(Exception ignored){}\n                    for(MediaEntry e:batch)', 'try{searchIndex.upsert(profileKey(),batch);}catch(Exception ignored){sectionComplete=false;}\n                    for(MediaEntry e:batch)')
    text = replace_once(text, 'if(!aggregate.isEmpty()){searchIndex.markSection(profileKey(),requested,aggregate.size());', 'if(sectionComplete&&done==cats.size()&&current(token)&&!Thread.currentThread().isInterrupted()&&!aggregate.isEmpty()){searchIndex.markSection(profileKey(),requested,aggregate.size());')
    text = replace_once(text, 'T("first_sync_wait")+" · "+pct+"% · "+finished+"/"+total+" "+T("categories")+" · "+Math.max(0,titles)+" "+T("titles")', 'pct+"% · "+finished+"/"+total+" "+T("categories")+" · "+Math.max(0,titles)+" "+T("titles")')
    return text


def repair_main(path):
    path = Path(path)
    source = path.read_text(encoding="utf-8")
    repaired = repair_source(source)
    path.write_text(repaired, encoding="utf-8")
