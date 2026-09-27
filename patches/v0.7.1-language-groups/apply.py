from pathlib import Path

root=Path("source/RA_IPTV_Android_v0.1")

def rep(path, old, new, label):
    p=root/path
    s=p.read_text()
    if old not in s:
        raise SystemExit("missing "+label+" in "+str(path))
    p.write_text(s.replace(old,new))

rep(Path("app/build.gradle"), "versionCode 26", "versionCode 27", "versionCode")
rep(Path("app/build.gradle"), "versionName '0.7.0'", "versionName '0.7.1'", "versionName")
rep(Path("app/src/main/java/com/robertalt/raiptv/SettingsActivity.java"),
    "Nivaro IPTV Player 0.7.0","Nivaro IPTV Player 0.7.1","settings version")

p=root/"app/src/main/java/com/robertalt/raiptv/ContentLanguage.java"
s=p.read_text()
old='    public static String preferenceCode(String code){return supported(code)?code.toLowerCase(Locale.ROOT):"";}\n'
new='''    public static String preferenceCode(String code){return supported(code)?code.toLowerCase(Locale.ROOT):"";}
    public static String normalizeTag(String code){if(code==null)return "";String c=code.trim().toLowerCase(Locale.ROOT);if("multi".equals(c))return c;return c.matches("[a-z]{2,3}")?c:"";}
    public static String[] supportedCodes(){return SUPPORTED.clone();}
    private static String anyPrefix(String raw){
        if(raw==null)return "";String s=raw.trim().toLowerCase(Locale.ROOT),x="";
        if(s.startsWith("|")){int j=s.indexOf('|',1);if(j>1)x=s.substring(1,j).trim();}
        else if(s.startsWith("[")){int j=s.indexOf(']');if(j>1)x=s.substring(1,j).trim();}
        else if(s.startsWith("(")){int j=s.indexOf(')');if(j>1)x=s.substring(1,j).trim();}
        if(x.matches("[a-z]{2,3}"))return x;return "";
    }
'''
if old not in s: raise SystemExit("ContentLanguage preferenceCode point missing")
s=s.replace(old,new)

old='''        String x=explicitLanguage(raw);if(!x.isEmpty())return x;
        if(multiPrefix(raw))return "multi";
        for(String c:SUPPORTED)if(wordMatch(raw,c))return c;
'''
new='''        String x=explicitLanguage(raw);if(!x.isEmpty())return x;
        if(multiPrefix(raw))return "multi";
        x=anyPrefix(raw);if(!x.isEmpty())return x;
        for(String c:SUPPORTED)if(wordMatch(raw,c))return c;
'''
if old not in s: raise SystemExit("categoryTag point missing")
s=s.replace(old,new,1)

old='''        for(String raw:new String[]{e.name,e.group,e.tvgName,e.seriesTitle}){
            String x=explicitLanguage(raw);if(!x.isEmpty())return x;
            if(multiPrefix(raw))return "multi";
        }
'''
new='''        for(String raw:new String[]{e.name,e.group,e.tvgName,e.seriesTitle}){
            String x=explicitLanguage(raw);if(!x.isEmpty())return x;
            if(multiPrefix(raw))return "multi";
            x=anyPrefix(raw);if(!x.isEmpty())return x;
        }
'''
if old not in s: raise SystemExit("detectTag point missing")
s=s.replace(old,new)

old='''        if(multiPrefix(raw))return 1;
        for(String c:SUPPORTED)if(wordMatch(raw,c))return p.equals(c)?0:3;
'''
new='''        if(multiPrefix(raw))return 1;
        x=anyPrefix(raw);if(!x.isEmpty())return p.equals(x)?0:3;
        for(String c:SUPPORTED)if(wordMatch(raw,c))return p.equals(c)?0:3;
'''
if old not in s: raise SystemExit("rankText point missing")
s=s.replace(old,new)
p.write_text(s)

p=root/"app/src/main/java/com/robertalt/raiptv/storage/SearchIndexStore.java"
s=p.read_text()
needle='''    public synchronized int countLanguage(String profile,String section,String tag){
        String t=tag==null?"":tag.trim().toLowerCase(Locale.ROOT);if(t.isEmpty())return 0;
        try(Cursor c=getReadableDatabase().rawQuery("SELECT COUNT(*) FROM entries WHERE profile=? AND type=? AND lang_tag=?",new String[]{profile,section,t})){return c.moveToFirst()?c.getInt(0):0;}catch(Exception e){return 0;}
    }

'''
insert=needle+'''    public synchronized List<String> languageTags(String profile,String section){
        ArrayList<String> out=new ArrayList<>();SQLiteDatabase db=getWritableDatabase();ensureLanguageHints(db,profile,section);
        try(Cursor c=db.rawQuery("SELECT lang_tag,COUNT(*) n FROM entries WHERE profile=? AND type=? AND lang_tag<>'' GROUP BY lang_tag ORDER BY n DESC,lang_tag ASC",new String[]{profile,section})){while(c.moveToNext()){String t=c.getString(0);if(t!=null&&!t.trim().isEmpty())out.add(t.trim().toLowerCase(Locale.ROOT));}}catch(Exception ignored){}
        return out;
    }

'''
if needle not in s: raise SystemExit("SearchIndexStore countLanguage point missing")
p.write_text(s.replace(needle,insert))

p=root/"app/src/main/java/com/robertalt/raiptv/MainActivity.java"
s=p.read_text()
if "static final int SEARCH_INDEX_GENERATION=5;" not in s: raise SystemExit("generation point missing")
s=s.replace("static final int SEARCH_INDEX_GENERATION=5;","static final int SEARCH_INDEX_GENERATION=6;")
s=s.replace('String start=hasLanguageCategories(raw,pref)?"lang:"+pref:(hasMultiCategories(raw)?"multi":"all")',
            'String start=hasLanguageGroup(raw,pref)?"lang:"+pref:(hasMultiGroup(raw)?"multi":"all")')

start=s.index("    boolean hasLanguageCategories(List<Category>raw,String code)")
end=s.index("    void selectSpinner(String id)",start)
helpers='''    boolean hasLanguageCategories(List<Category>raw,String code){if(raw==null)return false;String tag=ContentLanguage.normalizeTag(code);if(tag.isEmpty())return false;for(Category x:raw)if(tag.equals(ContentLanguage.categoryTag(x.name)))return true;return false;}
    boolean hasMultiCategories(List<Category>raw){if(raw==null)return false;for(Category x:raw)if(ContentLanguage.categoryMulti(x.name))return true;return false;}
    String indexedSection(){return "epg".equals(section)?"live":section;}
    boolean canUseIndexedLanguage(){String s=indexedSection();return profile!=null&&(s.equals("live")||s.equals("vod")||s.equals("series"));}
    boolean hasIndexedLanguage(String tag){String t=ContentLanguage.normalizeTag(tag);return canUseIndexedLanguage()&&!t.isEmpty()&&searchIndex.countLanguage(profileKey(),indexedSection(),t)>0;}
    boolean hasLanguageGroup(List<Category>raw,String tag){return hasLanguageCategories(raw,tag)||(!"epg".equals(section)&&hasIndexedLanguage(tag));}
    boolean hasMultiGroup(List<Category>raw){return hasMultiCategories(raw)||(!"epg".equals(section)&&hasIndexedLanguage("multi"));}
    List<Category> matchingLanguageCategories(String tag){List<Category>out=new ArrayList<>();String t=ContentLanguage.normalizeTag(tag);for(Category x:currentCategories){boolean ok="multi".equals(t)?ContentLanguage.categoryMulti(x.name):t.equals(ContentLanguage.categoryTag(x.name));if(ok)out.add(x);}out.sort((a,b)->safe(a.name).compareToIgnoreCase(safe(b.name)));return out;}
    String languageGroupLabel(String code){String n=SettingsStore.displayLanguage(this,code);if(n==null||n.trim().isEmpty())n=code.toUpperCase(Locale.ROOT);return ContentLanguage.preferenceCode(code).equals(SettingsStore.contentLanguage(this))?"★ "+n:n;}
    LinkedHashSet<String> availableLanguageTags(List<Category>raw){LinkedHashSet<String>tags=new LinkedHashSet<>();if(raw!=null)for(Category x:raw){String t=ContentLanguage.categoryTag(x.name);if(!t.isEmpty())tags.add(t);}if(!"epg".equals(section)&&canUseIndexedLanguage())try{tags.addAll(searchIndex.languageTags(profileKey(),indexedSection()));}catch(Exception ignored){}return tags;}
    void setCategorySpinner(List<Category>raw,boolean includeAll){
        final String pref=SettingsStore.contentLanguage(this);LinkedHashSet<String>tags=availableLanguageTags(raw);List<Category>c=new ArrayList<>();
        if(tags.remove(pref))c.add(new Category("lang:"+pref,languageGroupLabel(pref),section));
        if(tags.remove("multi"))c.add(new Category("multi","MULTI",section));
        ArrayList<String>others=new ArrayList<>(tags);others.sort((a,b)->languageGroupLabel(a).compareToIgnoreCase(languageGroupLabel(b)));for(String t:others)c.add(new Category("lang:"+t,languageGroupLabel(t),section));
        if(includeAll)c.add(new Category("all",T("all"),section));
        List<Category>untagged=new ArrayList<>();if(raw!=null)for(Category x:raw)if(ContentLanguage.categoryTag(x.name).isEmpty())untagged.add(x);untagged.sort((a,b)->safe(a.name).compareToIgnoreCase(safe(b.name)));c.addAll(untagged);
        if(c.isEmpty())c.add(new Category("",T("choose_category"),section));
        ArrayAdapter<Category>a=new ArrayAdapter<Category>(this,android.R.layout.simple_spinner_item,c){@Override public View getView(int position,View convertView,ViewGroup parent){View v=super.getView(position,convertView,parent);if(v instanceof TextView){TextView t=(TextView)v;t.setTextColor(getResources().getColor(R.color.text));t.setPadding(12,10,12,10);}return v;}@Override public View getDropDownView(int position,View convertView,ViewGroup parent){View v=super.getDropDownView(position,convertView,parent);if(v instanceof TextView){TextView t=(TextView)v;t.setTextColor(getResources().getColor(R.color.text));t.setBackgroundColor(getResources().getColor(R.color.card));t.setPadding(24,18,24,18);}return v;}};a.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);settingCategories=true;categories.setAdapter(a);categories.setSelection(0,false);settingCategories=false;
    }
'''
s=s[:start]+helpers+s[end:]

start=s.index("    void loadLanguageGroup(String tag,boolean fromRefresh){")
end=s.index("    void loadAllIncremental(String requested){",start)
method='''    void loadLanguageGroup(String tag,boolean fromRefresh){
        final String requested="epg".equals(section)?"live":section;final String normalized=ContentLanguage.normalizeTag(tag);if(normalized.isEmpty()){if("epg".equals(section))loadEpgChannels("all");else loadItems("all");return;}
        final int token=nextRequest();final boolean epg="epg".equals(section);final String wantedId="multi".equals(normalized)?"multi":"lang:"+normalized;currentCategoryId=wantedId;
        final int cached=!epg?searchIndex.countLanguage(profileKey(),requested,normalized):0;
        if(!epg&&cached>0)loadCachedLanguage(requested,token,normalized,cached);
        else if(epg){showEpgByMode(Collections.emptyList());busy(true,currentCategoryName+" · "+T("channels_loading"));}
        else{showMediaGrid("live".equals(requested));gridAdapter.set(Collections.emptyList(),"live".equals(requested));busy(true,currentCategoryName+" · "+T("library_opening"));}
        final List<Category>matches=matchingLanguageCategories(normalized);
        if(matches.isEmpty()){if(epg)busy(false,T("no_results"));else if(cached<=0)busy(false,T("no_results"));return;}
        exec.execute(()->{LinkedHashMap<String,MediaEntry>dedup=new LinkedHashMap<>();int done=0;try{
            for(Category c:matches){if(!current(token)||Thread.currentThread().isInterrupted())break;List<MediaEntry>batch;try{batch=provider.items(requested,c.id);}catch(Exception ex){done++;continue;}for(MediaEntry e:batch)if(e!=null&&(e.group==null||e.group.trim().isEmpty()))e.group=c.name;try{searchIndex.upsert(profileKey(),batch);}catch(Exception ignored){}for(MediaEntry e:batch)if(e!=null&&!isAdultLocked(e)&&normalized.equals(ContentLanguage.detectTag(e)))dedup.putIfAbsent(e.uniqueKey(),e);done++;final int fd=done;
                if(epg&&(!dedup.isEmpty())&&(fd==1||fd==matches.size()||fd%3==0)){final ArrayList<MediaEntry>snap=new ArrayList<>(dedup.values());runOnUiThread(()->{if(current(token)&&"epg".equals(section)&&wantedId.equals(currentCategoryId)){all=sortItems(snap);showEpgByMode(all);busy(true,all.size()+" "+T("channels")+" · "+fd+"/"+matches.size()+" "+T("categories"));}});}
            }
            if(epg){final ArrayList<MediaEntry>result=new ArrayList<>(dedup.values());runOnUiThread(()->{if(!current(token)||!"epg".equals(section)||!wantedId.equals(currentCategoryId))return;all=sortItems(result);showEpgByMode(all);busy(false,all.size()+" "+T("channels")+" · "+currentCategoryName);});}
            else{final int count=searchIndex.countLanguage(profileKey(),requested,normalized);runOnUiThread(()->{if(!current(token)||!requested.equals(section)||!wantedId.equals(currentCategoryId))return;if(count>0)loadCachedLanguage(requested,token,normalized,count);else busy(false,T("no_results"));});}
        }catch(Throwable ex){runOnUiThread(()->{if(current(token))busy(false,T("error_prefix")+": "+friendlyThrowable(ex));});}});
    }

'''
s=s[:start]+method+s[end:]

old='''                final List<MediaEntry>finalList=sortItems(aggregate);final int fh=hidden;
                runOnUiThread(()->{if(!current(token)||!section.equals(requested))return;all=finalList;gridAdapter.set(finalList,live);if(!finalList.isEmpty()&&selectedHero==null)previewAuto(finalList.get(0));busy(false,finalList.size()+" "+T("results")+" · "+T("fully_scrollable")+(fh>0?" · "+T("hidden_adult"):""));});
'''
new='''                final List<MediaEntry>finalList=sortItems(aggregate);final int fh=hidden;
                runOnUiThread(()->{if(!current(token)||!section.equals(requested))return;all=finalList;gridAdapter.set(finalList,live);if(!finalList.isEmpty()&&selectedHero==null)previewAuto(finalList.get(0));String preferredNow=SettingsStore.contentLanguage(this);setCategorySpinner(currentCategories,true);if(hasLanguageGroup(currentCategories,preferredNow)){currentCategoryId="lang:"+preferredNow;currentCategoryName=languageGroupLabel(preferredNow);selectSpinner(currentCategoryId);loadLanguageGroup(preferredNow,true);}else busy(false,finalList.size()+" "+T("results")+" · "+T("fully_scrollable")+(fh>0?" · "+T("hidden_adult"):""));});
'''
if old not in s: raise SystemExit("loadAll completion point missing")
s=s.replace(old,new)
p.write_text(s)

print("Nivaro v0.7.1 language-group hotfix applied")
