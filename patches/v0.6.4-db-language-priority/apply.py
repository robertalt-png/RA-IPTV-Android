from pathlib import Path

root=Path("source/RA_IPTV_Android_v0.1")

def rw(rel, old, new, count=1):
    p=root/rel
    s=p.read_text()
    if old not in s:
        raise SystemExit("missing target in "+rel+": "+repr(old[:120]))
    p.write_text(s.replace(old,new,count))

# Durable provider-language classification, independent of selected UI language.
(root/"app/src/main/java/com/robertalt/raiptv/ContentLanguage.java").write_text(r'''package com.robertalt.raiptv;

import com.robertalt.raiptv.model.MediaEntry;
import java.util.*;

/** Detects provider language labels without hiding other languages. */
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
    public static boolean supported(String code){if(code==null)return false;for(String c:SUPPORTED)if(c.equals(code.toLowerCase(Locale.ROOT)))return true;return false;}
    public static String preferenceCode(String code){return supported(code)?code.toLowerCase(Locale.ROOT):"";}

    /** Preferred-independent provider tag used for persistent DB ordering. Empty means untagged. */
    public static String detectTag(MediaEntry e){
        if(e==null)return "";
        for(String raw:new String[]{e.name,e.group,e.tvgName,e.seriesTitle}){
            String x=explicitLanguage(raw);if(!x.isEmpty())return x;
            if(multiPrefix(raw))return "multi";
        }
        // Provider groups may contain a language name without a |DE| style tag.
        // Do not inspect the movie title here: a title such as "German Crime Story"
        // must not automatically become German content.
        for(String raw:new String[]{e.group,e.tvgName})for(String c:SUPPORTED)if(wordMatch(raw,c))return c;
        return "";
    }

    /** 0 preferred, 1 MULTI, 2 untagged, 3 explicitly another language. */
    public static int rankText(String raw,String preferred){
        String p=preferenceCode(preferred);if(p.isEmpty())return 2;
        String x=explicitLanguage(raw);if(!x.isEmpty())return p.equals(x)?0:3;
        if(multiPrefix(raw))return 1;
        for(String c:SUPPORTED)if(wordMatch(raw,c))return p.equals(c)?0:3;
        return 2;
    }
    public static int rank(MediaEntry e,String preferred){String p=preferenceCode(preferred);if(p.isEmpty())return 2;String x=detectTag(e);if(x.isEmpty())return 2;if("multi".equals(x))return 1;return p.equals(x)?0:3;}

    private static String prefixSql(String c){return "(lower(name)='"+c+"' OR lower(name) LIKE '|"+c+"|%' OR lower(name) LIKE '| "+c+" |%' OR lower(name) LIKE '["+c+"]%' OR lower(name) LIKE '("+c+")%' OR lower(name) LIKE '."+c+" %' OR lower(name) LIKE '"+c+":%' OR lower(name) LIKE '"+c+" -%' OR lower(name) LIKE '"+c+" ·%' OR lower(name) LIKE '"+c+".%')";}
    private static String multiSql(){return "(lower(name) LIKE '|multi|%' OR lower(name) LIKE '| multi |%' OR lower(name) LIKE '[multi]%' OR lower(name) LIKE '(multi)%' OR lower(name) LIKE 'multi:%' OR lower(name) LIKE 'multi -%' OR lower(name) LIKE 'multi ·%' OR lower(name) LIKE '|dual|%' OR lower(name) LIKE '[dual]%' OR lower(name) LIKE 'dual:%')";}
    public static String sqlLanguageOrder(String preferred){String c=preferenceCode(preferred);if(c.isEmpty())return "0";StringBuilder other=new StringBuilder();for(String x:SUPPORTED)if(!x.equals(c)){if(other.length()>0)other.append(" OR ");other.append(prefixSql(x));}return "CASE WHEN "+prefixSql(c)+" THEN 0 WHEN "+multiSql()+" THEN 1 WHEN ("+other+") THEN 3 ELSE 2 END";}
}
''')

p=root/"app/src/main/java/com/robertalt/raiptv/storage/SearchIndexStore.java"
s=p.read_text()
s=s.replace("private static final int VERSION=1;","private static final int VERSION=2;",1)
s=s.replace('db.execSQL("CREATE TABLE entries(profile TEXT NOT NULL, item_key TEXT NOT NULL, type TEXT NOT NULL, name TEXT, name_norm TEXT, hay_norm TEXT, payload TEXT NOT NULL, PRIMARY KEY(profile,item_key))");',
'''db.execSQL("CREATE TABLE entries(profile TEXT NOT NULL, item_key TEXT NOT NULL, type TEXT NOT NULL, name TEXT, name_norm TEXT, hay_norm TEXT, lang_tag TEXT NOT NULL DEFAULT '', lang_scanned INTEGER NOT NULL DEFAULT 1, payload TEXT NOT NULL, PRIMARY KEY(profile,item_key))");''',1)
s=s.replace('db.execSQL("CREATE INDEX idx_entries_type ON entries(profile,type)");',
'''db.execSQL("CREATE INDEX idx_entries_type ON entries(profile,type)");
        db.execSQL("CREATE INDEX idx_entries_lang ON entries(profile,type,lang_tag)");''',1)
old='@Override public void onUpgrade(SQLiteDatabase db,int oldV,int newV){ db.execSQL("DROP TABLE IF EXISTS entries"); db.execSQL("DROP TABLE IF EXISTS meta"); onCreate(db); }'
new='''@Override public void onUpgrade(SQLiteDatabase db,int oldV,int newV){
        if(oldV<2){
            try{db.execSQL("ALTER TABLE entries ADD COLUMN lang_tag TEXT NOT NULL DEFAULT ''");}catch(Exception ignored){}
            try{db.execSQL("ALTER TABLE entries ADD COLUMN lang_scanned INTEGER NOT NULL DEFAULT 0");}catch(Exception ignored){}
            try{db.execSQL("CREATE INDEX IF NOT EXISTS idx_entries_lang ON entries(profile,type,lang_tag)");}catch(Exception ignored){}
        }
    }'''
if old not in s: raise SystemExit("onUpgrade target missing")
s=s.replace(old,new,1)
s=s.replace('v.put("name_norm",nameNorm);v.put("hay_norm",hay);v.put("payload",encode(e));',
'v.put("name_norm",nameNorm);v.put("hay_norm",hay);v.put("lang_tag",ContentLanguage.detectTag(e));v.put("lang_scanned",1);v.put("payload",encode(e));',1)

old='''    public synchronized List<MediaEntry> sectionPage(String profile,String section,int offset,int limit,String sort,String preferredLanguage){
        ArrayList<MediaEntry> out=new ArrayList<>();int safeOffset=Math.max(0,offset),safeLimit=Math.max(1,Math.min(1000,limit));String base="rowid ASC";if("az".equals(sort))base="name_norm COLLATE NOCASE ASC";else if("za".equals(sort))base="name_norm COLLATE NOCASE DESC";String order=ContentLanguage.sqlLanguageOrder(preferredLanguage)+", "+base;
        try(Cursor c=getReadableDatabase().rawQuery("SELECT payload FROM entries WHERE profile=? AND type=? ORDER BY "+order+" LIMIT ? OFFSET ?",new String[]{profile,section,String.valueOf(safeLimit),String.valueOf(safeOffset)})){
            while(c.moveToNext()){MediaEntry e=decode(c.getString(0));if(e!=null)out.add(e);}
        }catch(Exception ignored){}
        return out;
    }
'''
new='''    private void ensureLanguageHints(SQLiteDatabase db,String profile,String section){
        boolean pending=false;try(Cursor c=db.rawQuery("SELECT 1 FROM entries WHERE profile=? AND type=? AND lang_scanned=0 LIMIT 1",new String[]{profile,section})){pending=c.moveToFirst();}catch(Exception ignored){}
        if(!pending)return;
        ArrayList<Long> ids=new ArrayList<>();ArrayList<String> tags=new ArrayList<>();
        try(Cursor c=db.rawQuery("SELECT rowid,payload FROM entries WHERE profile=? AND type=? AND lang_scanned=0",new String[]{profile,section})){
            while(c.moveToNext()){MediaEntry e=decode(c.getString(1));ids.add(c.getLong(0));tags.add(ContentLanguage.detectTag(e));}
        }catch(Exception ignored){return;}
        db.beginTransaction();try{for(int i=0;i<ids.size();i++){ContentValues v=new ContentValues();v.put("lang_tag",tags.get(i));v.put("lang_scanned",1);db.update("entries",v,"rowid=?",new String[]{String.valueOf(ids.get(i))});}db.setTransactionSuccessful();}finally{db.endTransaction();}
    }

    public synchronized List<MediaEntry> sectionPage(String profile,String section,int offset,int limit,String sort,String preferredLanguage){
        ArrayList<MediaEntry> out=new ArrayList<>();int safeOffset=Math.max(0,offset),safeLimit=Math.max(1,Math.min(1000,limit));String base="rowid ASC";if("az".equals(sort))base="name_norm COLLATE NOCASE ASC";else if("za".equals(sort))base="name_norm COLLATE NOCASE DESC";
        SQLiteDatabase db=getWritableDatabase();ensureLanguageHints(db,profile,section);String pref=ContentLanguage.preferenceCode(preferredLanguage);String sql;
        if(pref.isEmpty())sql="SELECT payload FROM entries WHERE profile=? AND type=? ORDER BY "+base+" LIMIT ? OFFSET ?";
        else sql="SELECT payload FROM entries WHERE profile=? AND type=? ORDER BY CASE WHEN lang_tag='"+pref+"' THEN 0 WHEN lang_tag='multi' THEN 1 WHEN lang_tag='' THEN 2 ELSE 3 END, "+base+" LIMIT ? OFFSET ?";
        String[] args=new String[]{profile,section,String.valueOf(safeLimit),String.valueOf(safeOffset)};
        try(Cursor c=db.rawQuery(sql,args)){while(c.moveToNext()){MediaEntry e=decode(c.getString(0));if(e!=null)out.add(e);}}catch(Exception ignored){}
        return out;
    }
'''
if old not in s: raise SystemExit("sectionPage target missing")
s=s.replace(old,new,1)
p.write_text(s)

# Clean filename-style provider titles such as De.La.Muerte.2009.
rw("app/src/main/java/com/robertalt/raiptv/DisplayText.java",
'''        for(int pass=0;pass<4;pass++){Matcher m=PIPE.matcher(title);if(m.find()&&TAGS.contains(norm(m.group(1)))){add(tags,pretty(m.group(1)));title=title.substring(m.end()).trim();continue;}m=BRACKET.matcher(title);if(m.find()&&TAGS.contains(norm(m.group(1)))){add(tags,pretty(m.group(1)));title=title.substring(m.end()).trim();continue;}break;}
        Matcher qm=QUALITY.matcher(title);''',
'''        for(int pass=0;pass<4;pass++){Matcher m=PIPE.matcher(title);if(m.find()&&TAGS.contains(norm(m.group(1)))){add(tags,pretty(m.group(1)));title=title.substring(m.end()).trim();continue;}m=BRACKET.matcher(title);if(m.find()&&TAGS.contains(norm(m.group(1)))){add(tags,pretty(m.group(1)));title=title.substring(m.end()).trim();continue;}break;}
        if(title.indexOf(' ')<0){int separators=0;for(int i=0;i<title.length();i++)if(title.charAt(i)=='.'||title.charAt(i)=='_')separators++;if(separators>=2)title=title.replace('.',' ').replace('_',' ').replaceAll("\\\\s+"," ").trim();}
        Matcher qm=QUALITY.matcher(title);''')

rw("app/build.gradle","versionCode 22","versionCode 23")
rw("app/build.gradle","versionName '0.6.3'","versionName '0.6.4'")
rw("app/src/main/java/com/robertalt/raiptv/SettingsActivity.java","Nivaro IPTV Player 0.6.3\\\\n","Nivaro IPTV Player 0.6.4\\\\n")

assert "private static final int VERSION=2" in (root/"app/src/main/java/com/robertalt/raiptv/storage/SearchIndexStore.java").read_text()
assert "lang_scanned=0" in (root/"app/src/main/java/com/robertalt/raiptv/storage/SearchIndexStore.java").read_text()
assert "ContentLanguage.detectTag(e)" in (root/"app/src/main/java/com/robertalt/raiptv/storage/SearchIndexStore.java").read_text()
assert "versionName '0.6.4'" in (root/"app/build.gradle").read_text()
print("v0.6.4 database language-priority patch applied")
