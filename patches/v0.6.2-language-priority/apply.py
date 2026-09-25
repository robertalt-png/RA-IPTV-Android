from pathlib import Path

root=Path('source/RA_IPTV_Android_v0.1')

# New language-priority helper
p=root/'app/src/main/java/com/robertalt/raiptv/ContentLanguage.java'
p.write_text(r'''package com.robertalt.raiptv;

import com.robertalt.raiptv.model.MediaEntry;
import java.util.*;

/** Detects explicit provider language labels without hiding other languages. */
public final class ContentLanguage {
    private ContentLanguage(){}
    private static final String[] SUPPORTED={"nl","en","de","fr","es","it","pt","tr","pl","ar"};
    private static String safe(String s){return s==null?"":s;}
    private static String[] words(String lang){switch(lang==null?"":lang){
        case "nl":return new String[]{"nederlands","dutch","nld","dut"};
        case "en":return new String[]{"english","engels","eng"};
        case "de":return new String[]{"deutsch","german","deutschsprachig","deu","ger"};
        case "fr":return new String[]{"français","francais","french","fra","fre"};
        case "es":return new String[]{"español","espanol","spanish","spa"};
        case "it":return new String[]{"italiano","italian","ita"};
        case "pt":return new String[]{"português","portugues","portuguese","por"};
        case "tr":return new String[]{"türkçe","turkce","turkish","tur"};
        case "pl":return new String[]{"polski","polish","pol"};
        case "ar":return new String[]{"arabic","العربية","عربي","ara"};
        default:return new String[0];}}
    private static String norm(String s){return (" "+safe(s).toLowerCase(Locale.ROOT).replaceAll("[^\\p{L}\\p{Nd}]+"," ")+" ").replaceAll("\\s+"," ");}
    private static boolean taggedPrefix(String raw,String lang){if(raw==null||lang==null)return false;String s=raw.trim().toLowerCase(Locale.ROOT),c=lang.toLowerCase(Locale.ROOT);return s.equals(c)||s.startsWith("|"+c+"|")||s.startsWith("| "+c+" |")||s.startsWith("["+c+"]")||s.startsWith("("+c+")")||s.startsWith("."+c+" ")||s.startsWith(c+":")||s.startsWith(c+" -")||s.startsWith(c+" ·")||s.startsWith(c+".");}
    private static boolean matches(String raw,String lang){if(taggedPrefix(raw,lang))return true;String n=norm(raw);for(String w:words(lang))if(n.contains(" "+w.toLowerCase(Locale.ROOT)+" "))return true;return false;}
    public static int rankText(String raw,String preferred){if(preferred==null||preferred.isEmpty())return 1;if(matches(raw,preferred))return 0;return 1;}
    public static int rank(MediaEntry e,String preferred){if(e==null||preferred==null||preferred.isEmpty())return 1;String raw=safe(e.name);if(matches(raw,preferred)||matches(e.group,preferred)||matches(e.seriesTitle,preferred)||matches(e.tvgName,preferred))return 0;return 1;}
    public static String sqlLanguageOrder(String preferred){String c=(preferred==null?"":preferred.toLowerCase(Locale.ROOT));if(!Arrays.asList(SUPPORTED).contains(c))return "0";ArrayList<String> q=new ArrayList<>();q.add("lower(name)='"+c+"'");q.add("lower(name) LIKE '|"+c+"|%'");q.add("lower(name) LIKE '| "+c+" |%'");q.add("lower(name) LIKE '["+c+"]%'");q.add("lower(name) LIKE '("+c+")%'");q.add("lower(name) LIKE '."+c+" %'");q.add("lower(name) LIKE '"+c+":%'");q.add("lower(name) LIKE '"+c+" -%'");q.add("lower(name) LIKE '"+c+" ·%'");q.add("lower(name) LIKE '"+c+".%'");for(String w:words(c)){String x=w.toLowerCase(Locale.ROOT).replace("'","''");q.add("(' '||lower(name)||' ') LIKE '% "+x+" %'");}return "CASE WHEN ("+android.text.TextUtils.join(" OR ",q)+") THEN 0 ELSE 1 END";}
}
''')

# SettingsStore: content language setting follows app language by default
p=root/'app/src/main/java/com/robertalt/raiptv/storage/SettingsStore.java'; s=p.read_text()
old='''    public static Locale appLocale(Context c){try{return Locale.forLanguageTag(language(c));}catch(Exception e){return Locale.ENGLISH;}}\n    public static boolean supportedLanguage(String x){return Arrays.asList("nl","en","de","fr","es","it","pt","tr","pl","ar").contains(x);}'''
new='''    public static Locale appLocale(Context c){try{return Locale.forLanguageTag(language(c));}catch(Exception e){return Locale.ENGLISH;}}\n    public static String contentLanguageSetting(Context c){return prefs(c).getString("content_language","auto");}\n    public static String contentLanguage(Context c){String x=contentLanguageSetting(c);return "auto".equals(x)?language(c):(supportedLanguage(x)?x:language(c));}\n    public static boolean supportedLanguage(String x){return Arrays.asList("nl","en","de","fr","es","it","pt","tr","pl","ar").contains(x);}'''
assert old in s
s=s.replace(old,new,1); p.write_text(s)

# UiText: insert new keys for all languages
p=root/'app/src/main/java/com/robertalt/raiptv/UiText.java'; s=p.read_text()
extra={
'EN':('Preferred content language','Follow app language','Channels, movies and series in this language are shown first. Other languages stay available.'),
'NL':('Voorkeurstaal inhoud','Volg app-taal','Zenders, films en series in deze taal worden eerst getoond. Andere talen blijven beschikbaar.'),
'ES':('Idioma preferido del contenido','Seguir idioma de la app','Los canales, películas y series de este idioma se muestran primero. Los demás idiomas siguen disponibles.'),
'DE':('Bevorzugte Inhaltssprache','App-Sprache folgen','Sender, Filme und Serien in dieser Sprache werden zuerst angezeigt. Andere Sprachen bleiben verfügbar.'),
'FR':('Langue préférée du contenu','Suivre la langue de l’app','Les chaînes, films et séries dans cette langue sont affichés en premier. Les autres langues restent disponibles.'),
'IT':('Lingua preferita dei contenuti','Segui la lingua dell’app','Canali, film e serie in questa lingua vengono mostrati per primi. Le altre lingue restano disponibili.'),
'PT':('Idioma preferido do conteúdo','Seguir idioma da aplicação','Canais, filmes e séries neste idioma são mostrados primeiro. Os outros idiomas continuam disponíveis.'),
'TR':('Tercih edilen içerik dili','Uygulama dilini izle','Bu dildeki kanallar, filmler ve diziler önce gösterilir. Diğer diller kullanılabilir kalır.'),
'PL':('Preferowany język treści','Użyj języka aplikacji','Kanały, filmy i seriale w tym języku są wyświetlane jako pierwsze. Inne języki pozostają dostępne.'),
'AR':('لغة المحتوى المفضلة','اتبع لغة التطبيق','تظهر القنوات والأفلام والمسلسلات بهذه اللغة أولاً. وتبقى اللغات الأخرى متاحة.'),
}
for lang,(a,b,c) in extra.items():
    marker=f'    private static final Map<String,String> {lang}=map(new String[][]{{\n'
    assert marker in s,lang
    ins=marker+f'        {{"content_language","{a}"}},\n        {{"follow_app_language","{b}"}},\n        {{"content_language_help","{c}"}},\n'
    s=s.replace(marker,ins,1)
p.write_text(s)

# Settings screen: preferred content language becomes first setting
p=root/'app/src/main/java/com/robertalt/raiptv/SettingsActivity.java'; s=p.read_text()
old='''    sec(T("language"));spin(T("app_language"),"language",appLanguageLabels(),new String[]{"auto","nl","en","de","fr","es","it","pt","tr","pl","ar"});TextView langHelp=t(T("language_help"),12);langHelp.setTextColor(0xFF8D96A4);langHelp.setPadding(0,0,0,dp(8));box.addView(langHelp);'''
new='''    sec(T("language"));spin(T("content_language"),"content_language",contentLanguageLabels(),new String[]{"auto","nl","en","de","fr","es","it","pt","tr","pl","ar"});TextView contentHelp=t(T("content_language_help"),12);contentHelp.setTextColor(0xFF8D96A4);contentHelp.setPadding(0,0,0,dp(8));box.addView(contentHelp);spin(T("app_language"),"language",appLanguageLabels(),new String[]{"auto","nl","en","de","fr","es","it","pt","tr","pl","ar"});TextView langHelp=t(T("language_help"),12);langHelp.setTextColor(0xFF8D96A4);langHelp.setPadding(0,0,0,dp(8));box.addView(langHelp);'''
assert old in s
s=s.replace(old,new,1)
marker='''  String[] appLanguageLabels(){return new String[]{T("auto_device"),languageName("nl"),languageName("en"),languageName("de"),languageName("fr"),languageName("es"),languageName("it"),languageName("pt"),languageName("tr"),languageName("pl"),languageName("ar")};}\n'''
assert marker in s
s=s.replace(marker,marker+'''  String[] contentLanguageLabels(){return new String[]{T("follow_app_language"),languageName("nl"),languageName("en"),languageName("de"),languageName("fr"),languageName("es"),languageName("it"),languageName("pt"),languageName("tr"),languageName("pl"),languageName("ar")};}\n''',1)
p.write_text(s)

# SearchIndex: SQL paging honors content language globally before normal sort
p=root/'app/src/main/java/com/robertalt/raiptv/storage/SearchIndexStore.java'; s=p.read_text()
if 'import com.robertalt.raiptv.ContentLanguage;' not in s:
    s=s.replace('import com.robertalt.raiptv.model.MediaEntry;','import com.robertalt.raiptv.model.MediaEntry;\nimport com.robertalt.raiptv.ContentLanguage;',1)
old='''    public synchronized List<MediaEntry> sectionPage(String profile,String section,int offset,int limit,String sort){\n        ArrayList<MediaEntry> out=new ArrayList<>();int safeOffset=Math.max(0,offset),safeLimit=Math.max(1,Math.min(1000,limit));String order="rowid ASC";if("az".equals(sort))order="name_norm COLLATE NOCASE ASC";else if("za".equals(sort))order="name_norm COLLATE NOCASE DESC";\n        try(Cursor c=getReadableDatabase().rawQuery("SELECT payload FROM entries WHERE profile=? AND type=? ORDER BY "+order+" LIMIT ? OFFSET ?",new String[]{profile,section,String.valueOf(safeLimit),String.valueOf(safeOffset)})){\n            while(c.moveToNext()){MediaEntry e=decode(c.getString(0));if(e!=null)out.add(e);}\n        }catch(Exception ignored){}\n        if("favorites".equals(sort)||"recent".equals(sort)){List<MediaEntry> tmp=new ArrayList<>(out);out.clear();out.addAll(tmp);}\n        return out;\n    }'''
new='''    public synchronized List<MediaEntry> sectionPage(String profile,String section,int offset,int limit,String sort){return sectionPage(profile,section,offset,limit,sort,"");}\n    public synchronized List<MediaEntry> sectionPage(String profile,String section,int offset,int limit,String sort,String preferredLanguage){\n        ArrayList<MediaEntry> out=new ArrayList<>();int safeOffset=Math.max(0,offset),safeLimit=Math.max(1,Math.min(1000,limit));String base="rowid ASC";if("az".equals(sort))base="name_norm COLLATE NOCASE ASC";else if("za".equals(sort))base="name_norm COLLATE NOCASE DESC";String order=ContentLanguage.sqlLanguageOrder(preferredLanguage)+", "+base;\n        try(Cursor c=getReadableDatabase().rawQuery("SELECT payload FROM entries WHERE profile=? AND type=? ORDER BY "+order+" LIMIT ? OFFSET ?",new String[]{profile,section,String.valueOf(safeLimit),String.valueOf(safeOffset)})){\n            while(c.moveToNext()){MediaEntry e=decode(c.getString(0));if(e!=null)out.add(e);}\n        }catch(Exception ignored){}\n        return out;\n    }'''
assert old in s
s=s.replace(old,new,1); p.write_text(s)

# MainActivity: content language preference applies everywhere and categories are prioritized
p=root/'app/src/main/java/com/robertalt/raiptv/MainActivity.java'; s=p.read_text()
s=s.replace('String appliedLanguage="";','String appliedLanguage="",appliedContentLanguage="";',1)
s=s.replace('appliedLanguage=SettingsStore.language(this);applyStaticLanguage();wire();','appliedLanguage=SettingsStore.language(this);appliedContentLanguage=SettingsStore.contentLanguage(this);applyStaticLanguage();wire();',1)
s=s.replace('final boolean live="live".equals(requested);final String sort=SettingsStore.sort(this);','final boolean live="live".equals(requested);final String sort=SettingsStore.sort(this);final String preferredLanguage=SettingsStore.contentLanguage(this);',1)
s=s.replace('searchIndex.sectionPage(profileKey(),requested,start,CACHE_PAGE_SIZE,sort)','searchIndex.sectionPage(profileKey(),requested,start,CACHE_PAGE_SIZE,sort,preferredLanguage)',1)
old='''    void setCategorySpinner(List<Category>raw,boolean includeAll){List<Category>c=new ArrayList<>();c.add(new Category("",T("choose_category"),section));if(includeAll)c.add(new Category("all",T("all"),section));c.addAll(raw);'''
new='''    void setCategorySpinner(List<Category>raw,boolean includeAll){List<Category>ordered=new ArrayList<>(raw);final String pref=SettingsStore.contentLanguage(this);ordered.sort((a,b)->Integer.compare(ContentLanguage.rankText(a.name,pref),ContentLanguage.rankText(b.name,pref)));List<Category>c=new ArrayList<>();c.add(new Category("",T("choose_category"),section));if(includeAll)c.add(new Category("all",T("all"),section));c.addAll(ordered);'''
assert old in s
s=s.replace(old,new,1)
old='''        final int token=nextRequest();fullLibraryToken=token;final boolean live=requested.equals("live");final List<Category>cats=new ArrayList<>(currentCategories);'''
new='''        final int token=nextRequest();fullLibraryToken=token;final boolean live=requested.equals("live");final List<Category>cats=new ArrayList<>(currentCategories);final String pref=SettingsStore.contentLanguage(this);cats.sort((a,b)->Integer.compare(ContentLanguage.rankText(a.name,pref),ContentLanguage.rankText(b.name,pref)));'''
assert old in s
s=s.replace(old,new,1)
old='''    Category chooseLiveCategory(List<Category>cats){for(Category c:cats){String n=" "+norm(c.name)+" ";if(n.contains(" nederland ")||n.contains(" dutch ")||n.contains(" nl "))return c;}return cats.get(0);}'''
new='''    Category chooseLiveCategory(List<Category>cats){if(cats==null||cats.isEmpty())return null;String pref=SettingsStore.contentLanguage(this);Category best=cats.get(0);int rank=ContentLanguage.rankText(best.name,pref);for(Category c:cats){int r=ContentLanguage.rankText(c.name,pref);if(r<rank){best=c;rank=r;if(rank==0)break;}}return best;}'''
assert old in s
s=s.replace(old,new,1)
old='''    List<MediaEntry> sortItems(List<MediaEntry>src){List<MediaEntry>o=new ArrayList<>(src==null?Collections.emptyList():src);String mode=SettingsStore.sort(this);Comparator<MediaEntry>az=(a,b)->safe(a.name).compareToIgnoreCase(safe(b.name));if("az".equals(mode))o.sort(az);else if("za".equals(mode))o.sort(az.reversed());else if("favorites".equals(mode))o.sort((a,b)->{int x=Boolean.compare(library.isFavorite(b),library.isFavorite(a));return x!=0?x:az.compare(a,b);});else if("recent".equals(mode))o.sort((a,b)->{int x=Integer.compare(library.recentRank(a),library.recentRank(b));return x!=0?x:az.compare(a,b);});return o;}'''
new='''    List<MediaEntry> sortItems(List<MediaEntry>src){List<MediaEntry>o=new ArrayList<>(src==null?Collections.emptyList():src);String mode=SettingsStore.sort(this),pref=SettingsStore.contentLanguage(this);Comparator<MediaEntry>language=(a,b)->Integer.compare(ContentLanguage.rank(a,pref),ContentLanguage.rank(b,pref));Comparator<MediaEntry>az=(a,b)->safe(a.name).compareToIgnoreCase(safe(b.name));Comparator<MediaEntry>secondary=(a,b)->0;if("az".equals(mode))secondary=az;else if("za".equals(mode))secondary=az.reversed();else if("favorites".equals(mode))secondary=(a,b)->{int x=Boolean.compare(library.isFavorite(b),library.isFavorite(a));return x!=0?x:az.compare(a,b);};else if("recent".equals(mode))secondary=(a,b)->{int x=Integer.compare(library.recentRank(a),library.recentRank(b));return x!=0?x:az.compare(a,b);};o.sort(language.thenComparing(secondary));return o;}'''
assert old in s
s=s.replace(old,new,1)
old='''    @Override protected void onResume(){super.onResume();activityPaused=false;String nowLang=SettingsStore.language(this);if(appliedLanguage!=null&&!appliedLanguage.isEmpty()&&!appliedLanguage.equals(nowLang)){recreate();return;}if(provider!=null){setHeroHeight(heroHeight());'''
new='''    @Override protected void onResume(){super.onResume();activityPaused=false;String nowLang=SettingsStore.language(this);if(appliedLanguage!=null&&!appliedLanguage.isEmpty()&&!appliedLanguage.equals(nowLang)){recreate();return;}String nowContent=SettingsStore.contentLanguage(this);if(appliedContentLanguage!=null&&!appliedContentLanguage.isEmpty()&&!appliedContentLanguage.equals(nowContent)){appliedContentLanguage=nowContent;if(provider!=null){if("home".equals(section)||"local".equals(section))loadHome();else if("epg".equals(section))loadEpg();else loadSection(section);}return;}if(provider!=null){setHeroHeight(heroHeight());'''
assert old in s
s=s.replace(old,new,1)
# Translate on-demand EPG dialog too
old='''String g=provider.epg(e);runOnUiThread(()->{if(isUiAlive())new AlertDialog.Builder(this).setTitle(DisplayText.title(e)).setMessage(g) .setPositiveButton(T("watch"),(dd,ww)->play(e)).setNegativeButton(T("close"),null).show();});'''
new='''String g=provider.epg(e);InfoTranslator.translate(g,SettingsStore.language(this),translated->runOnUiThread(()->{if(isUiAlive())new AlertDialog.Builder(this).setTitle(DisplayText.title(e)).setMessage(translated).setPositiveButton(T("watch"),(dd,ww)->play(e)).setNegativeButton(T("close"),null).show();}));'''
assert old in s
s=s.replace(old,new,1)
p.write_text(s)


# Keep grid/list metadata language-neutral; translate hero description instead of exposing provider-English plots.
p=root/'app/src/main/java/com/robertalt/raiptv/DisplayText.java'; x=p.read_text()
marker='    public static String meta(MediaEntry e){String b=badges(e),m=e==null?"":e.meta();if(m==null)m="";if(!b.isEmpty()){if(!m.isEmpty()&&m.startsWith(e.year==null?"":e.year)){String y=e.year==null?"":e.year;if(!y.isEmpty()){m=m.substring(y.length()).replaceFirst("^\\\\s*·\\\\s*","");}}return m.isEmpty()?b:b+" · "+m;}return m;}'
assert marker in x
short='    public static String shortMeta(MediaEntry e){String m=e==null?"":e.meta();if(m==null)m="";int n=m.indexOf("\\n");if(n>=0)m=m.substring(0,n);String b=badges(e);if(!b.isEmpty()){if(!m.isEmpty()&&e!=null&&e.year!=null&&!e.year.isEmpty()&&m.startsWith(e.year)){m=m.substring(e.year.length()).replaceFirst("^\\s*·\\s*","");}return m.isEmpty()?b:b+" · "+m;}return m;}\n'
x=x.replace(marker,marker+'\n'+short,1);p.write_text(x)
p=root/'app/src/main/java/com/robertalt/raiptv/MediaGridAdapter.java'; x=p.read_text(); x=x.replace('String meta=isLive?(e.group==null?"":e.group):e.meta();','String meta=isLive?(e.group==null?"":e.group):DisplayText.shortMeta(e);',1); p.write_text(x)
p=root/'app/src/main/java/com/robertalt/raiptv/MediaRowAdapter.java'; x=p.read_text(); x=x.replace('String meta=DisplayText.meta(e);','String meta=DisplayText.shortMeta(e);',1); p.write_text(x)
p=root/'app/src/main/java/com/robertalt/raiptv/MainActivity.java'; x=p.read_text()
old='heroSubtitle.setText(meta);heroAction.setText("series".equals(e.type)?T("episodes"):"▶  "+T("play"));'
new='heroSubtitle.setText(meta);final String heroMeta=meta,heroKey=e.uniqueKey();if(heroMeta!=null&&!heroMeta.trim().isEmpty())InfoTranslator.translate(heroMeta,SettingsStore.language(this),translated->runOnUiThread(()->{if(isUiAlive()&&selectedHero!=null&&heroKey.equals(selectedHero.uniqueKey())&&translated!=null&&!translated.trim().isEmpty())heroSubtitle.setText(translated);}));heroAction.setText("series".equals(e.type)?T("episodes"):"▶  "+T("play"));'
assert old in x;x=x.replace(old,new,1);p.write_text(x)

# Avoid duplicated English "Score" after localized score label
p=root/'app/src/main/java/com/robertalt/raiptv/model/MediaDetails.java'; s=p.read_text(); s=s.replace('if(rating!=null&&!rating.trim().isEmpty()) return "Score " + rating.trim();','if(rating!=null&&!rating.trim().isEmpty()) return rating.trim();',1); p.write_text(s)

# Version
p=root/'app/build.gradle'; s=p.read_text().replace('versionCode 20','versionCode 21').replace("versionName '0.6.1'","versionName '0.6.2'"); p.write_text(s)

# Sanity
assert "versionName '0.6.2'" in (root/'app/build.gradle').read_text()
assert 'contentLanguage(Context c)' in (root/'app/src/main/java/com/robertalt/raiptv/storage/SettingsStore.java').read_text()
assert 'content_language' in (root/'app/src/main/java/com/robertalt/raiptv/SettingsActivity.java').read_text()
assert 'ContentLanguage.rank(a,pref)' in (root/'app/src/main/java/com/robertalt/raiptv/MainActivity.java').read_text()
assert 'sqlLanguageOrder' in (root/'app/src/main/java/com/robertalt/raiptv/storage/SearchIndexStore.java').read_text()
print('v0.6.2 applied')