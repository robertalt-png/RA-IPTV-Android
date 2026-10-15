package com.nenotv.player;

import com.nenotv.player.model.MediaEntry;
import java.util.*;

/** Provider-language classifier. Unknown country/region/provider prefixes stay ungrouped. */
public final class ContentLanguage {
    private ContentLanguage(){}
    // Compiled once: these run for every catalogue item during import (was recompiled per call).
    private static final java.util.regex.Pattern NON_WORD=java.util.regex.Pattern.compile("[^\\p{L}\\p{Nd}]+");
    private static final String[] PREFERRED={"nl","en","de","fr","es","it","pt","tr","pl","ar"};
    private static final LinkedHashMap<String,String> ALIAS=new LinkedHashMap<>();
    static {
        alias("nl","nl","nld","dut","ned","dutch","nederlands","nederland","netherlands","holland","hollands","vlaams","flemish");
        alias("en","en","eng","english","engels");
        alias("de","de","deu","ger","german","deutsch","deutschsprachig","deutschland","germany");
        alias("fr","fr","fra","fre","french","francais","français","france");
        alias("es","es","spa","spanish","espanol","español","espana","españa","spain");
        alias("it","it","ita","italian","italiano","italia","italy");
        alias("pt","pt","por","portuguese","portugues","português","portugal");
        alias("tr","tr","tur","turkish","turkce","türkçe","turkiye","türkiye","turkey");
        alias("pl","pl","pol","polish","polski","polska","poland");
        alias("ar","ar","ara","arabic","عربي","العربية");
        alias("sq","sq","sqi","alb","al","albanian","shqip");
        alias("hi","hi","hin","hindi");
        alias("ru","ru","rus","russian");
        alias("uk","ukr","ukrainian");
        alias("el","el","ell","gre","greek");
        alias("ro","ro","ron","rum","romanian");
        alias("bg","bg","bul","bulgarian");
        alias("sr","sr","srp","serbian");
        alias("hr","hr","hrv","croatian");
        alias("cs","cs","ces","cze","czech");
        alias("sk","sk","slk","slo","slovak");
        alias("hu","hu","hun","hungarian");
        alias("sv","sv","swe","swedish");
        alias("no","no","nor","norwegian");
        alias("da","da","dan","danish");
        alias("fi","fi","fin","finnish");
        alias("he","he","heb","hebrew");
        alias("fa","fa","fas","per","persian","farsi");
        alias("ur","ur","urd","urdu");
        alias("bn","bn","ben","bengali");
        alias("ta","ta","tam","tamil");
        alias("te","te","tel","telugu");
        alias("zh","zh","zho","chi","chinese");
        alias("ja","ja","jpn","japanese");
        alias("ko","ko","kor","korean");
        alias("id","id","ind","indonesian");
        alias("ms","ms","msa","may","malay");
        alias("th","th","tha","thai");
        alias("vi","vi","vie","vietnamese");
    }
    /**
     * Word-match table built once from ALIAS: canonical code -> its aliases longer than two characters,
     * in the same canonical order as new LinkedHashSet<>(ALIAS.values()). Replaces the per-item scan of
     * every alias for every language (the former wordMatch), with an identical result.
     */
    private static final LinkedHashMap<String,List<String>> WORDS=new LinkedHashMap<>();
    static {
        for(Map.Entry<String,String> e:ALIAS.entrySet()){
            List<String> words=WORDS.get(e.getValue());
            if(words==null){words=new ArrayList<>();WORDS.put(e.getValue(),words);}
            if(e.getKey().length()>2)words.add(e.getKey());
        }
    }
    private static void alias(String canonical,String...values){for(String v:values)ALIAS.put(normToken(v),canonical);}
    private static String safe(String s){return s==null?"":s;}
    private static String normToken(String s){return NON_WORD.matcher(safe(s).trim().toLowerCase(Locale.ROOT)).replaceAll("");}
    /** Lower-cased letter/digit runs of raw: exactly the words the former " "+alias+" " search could match. */
    private static Set<String> words(String raw){
        HashSet<String> out=new HashSet<>();
        for(String token:NON_WORD.split(safe(raw).toLowerCase(Locale.ROOT)))if(!token.isEmpty())out.add(token);
        return out;
    }
    /** First canonical (in ALIAS value order) whose long alias occurs as a whole word in raw; "" if none. */
    private static String wordTag(String raw){
        Set<String> present=words(raw);
        if(present.isEmpty())return "";
        for(Map.Entry<String,List<String>> e:WORDS.entrySet())for(String word:e.getValue())if(present.contains(word))return e.getKey();
        return "";
    }
    private static String prefixToken(String raw){
        if(raw==null)return "";String s=raw.trim(),x="";
        if(s.startsWith("|")){int j=s.indexOf('|',1);if(j>1)x=s.substring(1,j);}
        else if(s.startsWith("[")){int j=s.indexOf(']');if(j>1)x=s.substring(1,j);}
        else if(s.startsWith("(")){int j=s.indexOf(')');if(j>1)x=s.substring(1,j);}
        else {
            int best=-1;
            for(char sep:new char[]{':','-','·','|','/'}){int j=s.indexOf(sep);if(j>0&&j<=16&&(best<0||j<best))best=j;}
            if(best>0)x=s.substring(0,best);
        }
        return normToken(x);
    }
    private static boolean multiPrefix(String raw){if(raw==null)return false;String p=prefixToken(raw);if("multi".equals(p)||"multiaudio".equals(p)||"dual".equals(p))return true;String s=raw.trim().toLowerCase(Locale.ROOT);return s.startsWith("multi -")||s.startsWith("multi ·")||s.startsWith("dual -")||s.startsWith("dual ·");}
    private static String aliasTag(String raw){String n=normToken(raw);String x=ALIAS.get(n);return x==null?"":x;}
    private static String explicitTag(String raw){
        String p=prefixToken(raw);if(!p.isEmpty()){String x=ALIAS.get(p);if(x!=null)return x;}
        if(raw!=null){String s=raw.trim(),head="";if(s.startsWith("|")){int j=s.indexOf('|',1);if(j>1)head=s.substring(1,j);}else if(s.startsWith("[")){int j=s.indexOf(']');if(j>1)head=s.substring(1,j);}else if(s.startsWith("(")){int j=s.indexOf(')');if(j>1)head=s.substring(1,j);}else{int best=-1;for(char sep:new char[]{':','-','·','|','/'}){int j=s.indexOf(sep);if(j>0&&j<=16&&(best<0||j<best))best=j;}if(best>0)head=s.substring(0,best);}
            if(!head.isEmpty())for(String token:NON_WORD.split(head)){String x=ALIAS.get(normToken(token));if(x!=null)return x;}
        }
        return aliasTag(raw);
    }
    public static boolean supported(String code){if(code==null)return false;for(String c:PREFERRED)if(c.equals(code.toLowerCase(Locale.ROOT)))return true;return false;}
    public static String preferenceCode(String code){return supported(code)?code.toLowerCase(Locale.ROOT):"";}
    public static String normalizeTag(String code){if(code==null)return "";String n=normToken(code);if("multi".equals(n)||"dual".equals(n)||"multiaudio".equals(n))return "multi";String x=ALIAS.get(n);return x==null?"":x;}
    public static boolean isLanguageTag(String code){String x=normalizeTag(code);return !x.isEmpty()&&!"multi".equals(x);}
    public static String[] preferredCodes(){return PREFERRED.clone();}

    /** Only real/known language aliases become a language group. AFG/AFR/etc stay ungrouped. */
    public static String categoryTag(String raw){
        if(raw==null)return "";if(multiPrefix(raw))return "multi";String x=explicitTag(raw);if(!x.isEmpty())return x;
        return wordTag(raw);
    }
    public static boolean categoryMatches(String raw,String preferred){String p=normalizeTag(preferred);return !p.isEmpty()&&p.equals(categoryTag(raw));}
    public static boolean categoryMulti(String raw){return "multi".equals(categoryTag(raw));}

    public static String detectTag(MediaEntry e){
        if(e==null)return "";
        for(String raw:new String[]{e.group,e.name,e.tvgName,e.seriesTitle}){if(multiPrefix(raw))return "multi";String x=explicitTag(raw);if(!x.isEmpty())return x;}
        for(String raw:new String[]{e.group,e.tvgName}){String x=wordTag(raw);if(!x.isEmpty())return x;}
        return "";
    }

    /** 0 preferred, 1 MULTI, 2 ungrouped, 3 another known language. */
    public static int rankText(String raw,String preferred){return rankTag(categoryTag(raw),preferred);}
    public static int rank(MediaEntry e,String preferred){return rankTag(detectTag(e),preferred);}
    /** preferred is one code ("nl") or a list ("nl,tr"). With n preferred languages: their position (0..n-1), then MULTI (n),
     *  unknown (n+1), any other language (n+2). One language gives the old order 0 / 1 / 2 / 3. */
    public static int rankTag(String tag,String preferred){
        String x=tag==null?"":tag.trim().toLowerCase(Locale.ROOT);int n=0;
        if(preferred!=null)for(String part:preferred.split(",")){String p=preferenceCode(part.trim());if(p.isEmpty())continue;if(p.equals(x))return n;n++;}
        if(n==0)n=1;if(x.isEmpty())return n+1;if("multi".equals(x))return n;return n+2;
    }
}
