from pathlib import Path

root=Path('source/RA_IPTV_Android_v0.1')

def rw(rel, old, new, count=1):
    p=root/rel
    s=p.read_text()
    if old not in s:
        raise SystemExit(f'missing target in {rel}: {old[:120]!r}')
    p.write_text(s.replace(old,new,count))

# Strong language ordering: explicit selected language first, then MULTI, then untagged/inferred, explicit other languages last.
(root/'app/src/main/java/com/robertalt/raiptv/ContentLanguage.java').write_text(r'''package com.robertalt.raiptv;

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
    private static boolean multiPrefix(String raw){if(raw==null)return false;String s=raw.trim().toLowerCase(Locale.ROOT);return s.startsWith("|multi|")||s.startsWith("| multi |")||s.startsWith("[multi]")||s.startsWith("(multi)")||s.startsWith("multi:")||s.startsWith("multi -")||s.startsWith("multi ·")||s.startsWith("|dual|")||s.startsWith("[dual]")||s.startsWith("dual:");}
    private static String explicitLanguage(String raw){for(String c:SUPPORTED)if(taggedPrefix(raw,c))return c;return "";}
    private static boolean wordMatch(String raw,String lang){String n=norm(raw);for(String w:words(lang))if(n.contains(" "+w.toLowerCase(Locale.ROOT)+" "))return true;return false;}
    public static int rankText(String raw,String preferred){if(preferred==null||preferred.isEmpty())return 2;String explicit=explicitLanguage(raw);if(!explicit.isEmpty())return preferred.equals(explicit)?0:3;if(multiPrefix(raw))return 1;return 2;}
    public static int rank(MediaEntry e,String preferred){if(e==null||preferred==null||preferred.isEmpty())return 2;String explicit=explicitLanguage(e.name);if(!explicit.isEmpty())return preferred.equals(explicit)?0:3;if(multiPrefix(e.name))return 1;for(String raw:new String[]{e.group,e.seriesTitle,e.tvgName}){explicit=explicitLanguage(raw);if(!explicit.isEmpty())return preferred.equals(explicit)?0:3;if(multiPrefix(raw))return 1;}for(String raw:new String[]{e.name,e.group,e.seriesTitle,e.tvgName})if(wordMatch(raw,preferred))return 2;return 2;}
    private static String prefixSql(String c){return "(lower(name)='"+c+"' OR lower(name) LIKE '|"+c+"|%' OR lower(name) LIKE '| "+c+" |%' OR lower(name) LIKE '["+c+"]%' OR lower(name) LIKE '("+c+")%' OR lower(name) LIKE '."+c+" %' OR lower(name) LIKE '"+c+":%' OR lower(name) LIKE '"+c+" -%' OR lower(name) LIKE '"+c+" ·%' OR lower(name) LIKE '"+c+".%')";}
    private static String multiSql(){return "(lower(name) LIKE '|multi|%' OR lower(name) LIKE '| multi |%' OR lower(name) LIKE '[multi]%' OR lower(name) LIKE '(multi)%' OR lower(name) LIKE 'multi:%' OR lower(name) LIKE 'multi -%' OR lower(name) LIKE 'multi ·%' OR lower(name) LIKE '|dual|%' OR lower(name) LIKE '[dual]%' OR lower(name) LIKE 'dual:%')";}
    public static String sqlLanguageOrder(String preferred){String c=(preferred==null?"":preferred.toLowerCase(Locale.ROOT));if(!Arrays.asList(SUPPORTED).contains(c))return "0";StringBuilder other=new StringBuilder();for(String x:SUPPORTED)if(!x.equals(c)){if(other.length()>0)other.append(" OR ");other.append(prefixSql(x));}return "CASE WHEN "+prefixSql(c)+" THEN 0 WHEN "+multiSql()+" THEN 1 WHEN ("+other+") THEN 3 ELSE 2 END";}
}
''')

rw('app/src/main/java/com/robertalt/raiptv/DisplayText.java',
   '    public static String title(MediaEntry e){return parse(e).title;}\n',
   '    public static String cleanTitle(String raw){MediaEntry e=new MediaEntry();e.name=raw==null?"":raw;return parse(e).title;}\n    public static String title(MediaEntry e){return parse(e).title;}\n')

rw('app/src/main/java/com/robertalt/raiptv/storage/SettingsStore.java',
   '    public static String contentLanguage(Context c){String x=contentLanguageSetting(c);return "auto".equals(x)?language(c):(supportedLanguage(x)?x:language(c));}\n',
   '    public static String contentLanguage(Context c){String x=contentLanguageSetting(c);return "auto".equals(x)?language(c):(supportedLanguage(x)?x:language(c));}\n    public static void migrateLanguagePreferences(Context c){SharedPreferences p=prefs(c);if(p.getBoolean("language_pref_migrated_063",false))return;String a=p.getString("audio","auto"),s=p.getString("subtitles","auto");SharedPreferences.Editor e=p.edit().putBoolean("language_pref_migrated_063",true);if("en".equals(a)&&"nl".equals(s)){e.putString("audio","auto");e.putString("subtitles","auto");}e.apply();}\n    public static String resolvedSubtitleLanguage(Context c){String x=subtitles(c);if("auto".equals(x))x=language(c);return supportedLanguage(x)?x:language(c);}\n')

rw('app/src/main/java/com/robertalt/raiptv/SettingsActivity.java',
   '@Override public void onCreate(Bundle x){super.onCreate(x);p=SettingsStore.prefs(this);langAtOpen=SettingsStore.language(this);build();UiText.applyDirection(this);}',
   '@Override public void onCreate(Bundle x){super.onCreate(x);SettingsStore.migrateLanguagePreferences(this);p=SettingsStore.prefs(this);langAtOpen=SettingsStore.language(this);build();UiText.applyDirection(this);}')
rw('app/src/main/java/com/robertalt/raiptv/SettingsActivity.java',
   'sec(T("about"));TextView a=t("Nivaro IPTV Player 0.6.1\\\\n"+T("about_version_notes"),13);',
   'sec(T("about"));TextView a=t("Nivaro IPTV Player 0.6.3\\n"+T("about_version_notes"),13);')
rw('app/src/main/java/com/robertalt/raiptv/SettingsActivity.java',
   'String[] audioLanguageLabels(){return new String[]{T("automatic"),T("original")',
   'String[] audioLanguageLabels(){return new String[]{T("follow_app_language"),T("original")')
rw('app/src/main/java/com/robertalt/raiptv/SettingsActivity.java',
   'String[] subtitleLanguageLabels(){return new String[]{T("automatic"),T("off")',
   'String[] subtitleLanguageLabels(){return new String[]{T("follow_app_language"),T("off")')

rw('app/src/main/java/com/robertalt/raiptv/MainActivity.java',
   'super.onCreate(b);CrashGuard.install(this);setContentView(R.layout.activity_main);UiText.applyDirection(this);',
   'super.onCreate(b);CrashGuard.install(this);SettingsStore.migrateLanguagePreferences(this);setContentView(R.layout.activity_main);UiText.applyDirection(this);')
rw('app/src/main/java/com/robertalt/raiptv/MainActivity.java','setTitle(channel.name).setMessage(','setTitle(DisplayText.title(channel)).setMessage(')
rw('app/src/main/java/com/robertalt/raiptv/MainActivity.java','h.setText(safe(d.title).isEmpty()?item.name:d.title);','h.setText(DisplayText.cleanTitle(safe(d.title).isEmpty()?item.name:d.title));')
rw('app/src/main/java/com/robertalt/raiptv/MainActivity.java','" · "+next.name);','" · "+DisplayText.title(next));')

rw('app/src/main/java/com/robertalt/raiptv/EpgAdapter.java','h.channel.setText(e.name);','h.channel.setText(DisplayText.title(e));')

rw('app/src/main/java/com/robertalt/raiptv/PlayerActivity.java','status.setText(T("zapping")+" · "+next.name);','status.setText(T("zapping")+" · "+DisplayText.title(next));')
rw('app/src/main/java/com/robertalt/raiptv/PlayerActivity.java','status.setText(T("next_episode")+" · "+next.name);','status.setText(T("next_episode")+" · "+DisplayText.title(next));')
rw('app/src/main/java/com/robertalt/raiptv/PlayerActivity.java','.setLanguage(SettingsStore.language(this)).setLabel(SettingsStore.displayLanguage(this,SettingsStore.language(this))+" "+T("external_subtitle"))','.setLanguage(SettingsStore.resolvedSubtitleLanguage(this)).setLabel(SettingsStore.displayLanguage(this,SettingsStore.resolvedSubtitleLanguage(this))+" "+T("external_subtitle"))')
rw('app/src/main/java/com/robertalt/raiptv/PlayerActivity.java','SubtitleBridgeClient c=new SubtitleBridgeClient(profile);','SubtitleBridgeClient c=new SubtitleBridgeClient(profile,SettingsStore.resolvedSubtitleLanguage(this));')

rw('app/src/main/java/com/robertalt/raiptv/subtitle/SubtitleBridgeClient.java',
   'private final Profile p; public SubtitleBridgeClient(Profile p){this.p=p;}',
   'private final Profile p; private final String language; public SubtitleBridgeClient(Profile p){this(p,"nl");} public SubtitleBridgeClient(Profile p,String language){this.p=p;this.language=language==null||language.trim().isEmpty()?"nl":language.trim().toLowerCase(java.util.Locale.ROOT);}')
rw('app/src/main/java/com/robertalt/raiptv/subtitle/SubtitleBridgeClient.java','b.appendQueryParameter("language","nl");','b.appendQueryParameter("language",language);')

rw('app/build.gradle','versionCode 21','versionCode 22')
rw('app/build.gradle',"versionName '0.6.2'","versionName '0.6.3'")

# validations
assert "Nivaro IPTV Player 0.6.3\\n" in (root/'app/src/main/java/com/robertalt/raiptv/SettingsActivity.java').read_text()
assert 'WHEN '+'' in (root/'app/src/main/java/com/robertalt/raiptv/ContentLanguage.java').read_text()
assert 'SettingsStore.migrateLanguagePreferences(this)' in (root/'app/src/main/java/com/robertalt/raiptv/MainActivity.java').read_text()
assert 'DisplayText.cleanTitle' in (root/'app/src/main/java/com/robertalt/raiptv/MainActivity.java').read_text()
assert "versionName '0.6.3'" in (root/'app/build.gradle').read_text()
print('v0.6.3 patch applied')