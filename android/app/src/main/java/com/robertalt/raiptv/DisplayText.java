package com.nenotv.player;

import com.nenotv.player.model.MediaEntry;
import java.util.*;
import java.util.regex.*;

/** Presentation-only cleanup. Raw provider names remain untouched for search/stream identity. */
public final class DisplayText {
    private DisplayText(){}
    private static final Set<String> TAGS=new HashSet<>(Arrays.asList(
        "NL","EN","DE","FR","ES","IT","PT","TR","PL","AR","MULTI","MULTIAUDIO","DUAL","VO","VOST","VOSTFR",
        "4K","UHD","FHD","HD","SD","HDR","DV","DOLBY","ATMOS"));
    private static final Pattern PIPE=Pattern.compile("^\\s*[|│┃¦｜]\\s*([^|│┃¦｜]{1,18})\\s*[|│┃¦｜]\\s*");
    private static final Pattern BRACKET=Pattern.compile("^\\s*[\\[(]\\s*([A-Za-z0-9+._ -]{1,18})\\s*[\\])]\\s*");
    private static final Pattern QUALITY=Pattern.compile("(?i)(?:\\s*[-·|]\\s*|\\s+)(4K|UHD|FHD|HD|HDR|DV)(?=\\s*(?:[-·|]|$))");
    private static final Pattern YEAR=Pattern.compile("(?:\\s*[-·|]\\s*|\\s+)((?:19|20)\\d{2})\\s*$");
    public static class Parsed { public final String title; public final List<String> tags; Parsed(String t,List<String>x){title=t;tags=x;} public String badges(){return android.text.TextUtils.join(" · ",tags);} }
    static String norm(String x){if(x==null)return "";return x.trim().toUpperCase(Locale.ROOT).replace(" ","").replace("-","").replace("_","");}
    static String pretty(String x){String n=norm(x);if("MULTIAUDIO".equals(n)||"DUAL".equals(n))return "MULTI";return n;}
    public static Parsed parse(MediaEntry e){
        String raw=e==null||e.name==null?"":e.name.replaceAll("[\u200B-\u200D\uFEFF]","").trim();ArrayList<String> tags=new ArrayList<>();String title=raw;
        for(int pass=0;pass<4;pass++){Matcher m=PIPE.matcher(title);if(m.find()){String token=m.group(1).trim(),canon=ContentLanguage.categoryTag(token);boolean providerCode=token.matches("(?i)[a-z]{2,5}");if(TAGS.contains(norm(token))||!canon.isEmpty()||providerCode){add(tags,!canon.isEmpty()?("multi".equals(canon)?"MULTI":canon.toUpperCase(Locale.ROOT)):pretty(token));title=title.substring(m.end()).trim();continue;}}m=BRACKET.matcher(title);if(m.find()){String token=m.group(1).trim(),canon=ContentLanguage.categoryTag(token);if(TAGS.contains(norm(token))||!canon.isEmpty()){add(tags,!canon.isEmpty()?("multi".equals(canon)?"MULTI":canon.toUpperCase(Locale.ROOT)):pretty(token));title=title.substring(m.end()).trim();continue;}}break;}
        if(title.indexOf(' ')<0){int separators=0;for(int i=0;i<title.length();i++)if(title.charAt(i)=='.'||title.charAt(i)=='_')separators++;if(separators>=2)title=title.replace('.',' ').replace('_',' ').replaceAll("\\s+"," ").trim();}
        Matcher qm=QUALITY.matcher(title);while(qm.find())add(tags,qm.group(1).toUpperCase(Locale.ROOT));title=QUALITY.matcher(title).replaceAll(" ").trim();
        Matcher ym=YEAR.matcher(title);if(ym.find()){add(tags,ym.group(1));title=title.substring(0,ym.start()).trim();}else if(e!=null&&!e.displayYear().isEmpty())add(tags,e.displayYear());
        title=title.replaceAll("^[\\s|·:;-]+|[\\s|·:;-]+$","").replaceAll("\\s{2,}"," ").trim();if(title.isEmpty())title=raw;
        return new Parsed(title,tags);
    }
    static void add(List<String>x,String s){if(s!=null&&!s.isEmpty()&&!x.contains(s))x.add(s);}
    public static String category(String raw){return raw==null?"":raw.replaceAll("[|│┃¦｜]+"," ").replaceAll("\\s+"," ").trim();}
    public static String cleanTitle(String raw){MediaEntry e=new MediaEntry();e.name=raw==null?"":raw;return parse(e).title;}
    public static String title(MediaEntry e){return parse(e).title;}
    public static String badges(MediaEntry e){String b=parse(e).badges();if(e!=null&&e.catchup){String r=e.catchupDays>0?"↶ "+e.catchupDays+"D":"↶";return b.isEmpty()?r:b+" · "+r;}return b;}
    public static String meta(MediaEntry e){String b=badges(e),m=e==null?"":e.meta();if(m==null)m="";if(!b.isEmpty()){if(!m.isEmpty()&&e!=null&&!e.displayYear().isEmpty()&&m.startsWith(e.displayYear())){String y=e.displayYear();if(!y.isEmpty()){m=m.substring(y.length()).replaceFirst("^\\s*·\\s*","");}}return m.isEmpty()?b:b+" · "+m;}return m;}
    public static String shortMeta(MediaEntry e){String m=e==null?"":e.meta();if(m==null)m="";int n=m.indexOf("\n");if(n>=0)m=m.substring(0,n);String b=badges(e);if(!b.isEmpty()){if(!m.isEmpty()&&e!=null&&!e.displayYear().isEmpty()&&m.startsWith(e.displayYear())){m=m.substring(e.displayYear().length()).replaceFirst("^\s*·\s*","");}return m.isEmpty()?b:b+" · "+m;}return m;}

}
