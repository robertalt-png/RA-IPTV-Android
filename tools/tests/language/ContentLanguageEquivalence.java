package com.nenotv.player;

import com.nenotv.player.model.MediaEntry;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.regex.*;

/**
 * Proves the optimised ContentLanguage returns exactly what the previous implementation returned,
 * and reports how much faster it is. Run from the repository root:
 *   javac -d build/lang $(cat tools/tests/language/sources.txt) && java -cp build/lang com.nenotv.player.ContentLanguageEquivalence
 * Exit code 1 on the first mismatch.
 */
public final class ContentLanguageEquivalence {
    private static final String[] SEP={"|"," | ","[","]","(",")",":"," - ","-","·","/"," ","  ",""};
    private static final String[] NOISE={"HD","FHD","4K","VIP","Sports","Movies","Series","News","Kids","24/7","AFR","EX-YU","US","UK","CA","AU","İstanbul","Ελλάδα","Россия","中国","العربية","Ñoño","Straße","2024","S01","E05"};

    private static int checked=0;
    private static void same(String what,Object a,Object b,String input){
        checked++;
        if(!Objects.equals(a,b)){
            System.err.println("MISMATCH "+what+" for input <"+input+">: legacy=<"+a+"> new=<"+b+">");
            System.exit(1);
        }
    }

    private static List<String> aliasWords() throws Exception {
        // Every alias spelling the classifier knows, taken from its own source so the fuzz always covers them.
        String src=new String(Files.readAllBytes(Paths.get("android/app/src/main/java/com/robertalt/raiptv/ContentLanguage.java")),StandardCharsets.UTF_8);
        ArrayList<String> out=new ArrayList<>();
        Matcher m=Pattern.compile("alias\\(([^;]*)\\);").matcher(src);
        while(m.find()){Matcher q=Pattern.compile("\"([^\"]*)\"").matcher(m.group(1));while(q.find())out.add(q.group(1));}
        if(out.size()<150)throw new IllegalStateException("alias table not found: "+out.size());
        return out;
    }

    private static String randomText(Random r,List<String> words){
        StringBuilder s=new StringBuilder();
        int parts=1+r.nextInt(5);
        if(r.nextInt(4)==0)s.append(SEP[r.nextInt(6)]);
        for(int i=0;i<parts;i++){
            String w=r.nextInt(3)==0?NOISE[r.nextInt(NOISE.length)]:words.get(r.nextInt(words.size()));
            int c=r.nextInt(3);if(c==0)w=w.toUpperCase(Locale.ROOT);else if(c==1&&!w.isEmpty())w=Character.toUpperCase(w.charAt(0))+w.substring(1);
            s.append(w).append(SEP[r.nextInt(SEP.length)]);
        }
        if(r.nextInt(10)==0)s.append("MULTI - ");
        if(r.nextInt(25)==0)return "";
        return s.toString();
    }

    private static MediaEntry entry(String group,String name,String tvgName,String seriesTitle,String type){
        MediaEntry e=new MediaEntry();e.group=group;e.name=name;e.tvgName=tvgName;e.seriesTitle=seriesTitle;e.type=type;return e;
    }

    private static void compare(MediaEntry e){
        String in=e.group+" ‖ "+e.name+" ‖ "+e.tvgName+" ‖ "+e.seriesTitle;
        same("detectTag",LegacyContentLanguage.detectTag(e),ContentLanguage.detectTag(e),in);
        same("rank(nl)",LegacyContentLanguage.rank(e,"nl"),ContentLanguage.rank(e,"nl"),in);
        for(String raw:new String[]{e.group,e.name}){
            same("categoryTag",LegacyContentLanguage.categoryTag(raw),ContentLanguage.categoryTag(raw),String.valueOf(raw));
            same("rankText(de)",LegacyContentLanguage.rankText(raw,"de"),ContentLanguage.rankText(raw,"de"),String.valueOf(raw));
        }
    }

    public static void main(String[] args) throws Exception {
        List<String> words=aliasWords();
        Random r=new Random(20261006L);
        ArrayList<MediaEntry> corpus=new ArrayList<>();

        // 1. Same shape and size as the measured customer list: 24.890 live, 138.724 vod, 35.454 series.
        int[][] mix={{24890,0},{138724,1},{35454,2}};String[] types={"live","vod","series"};
        for(int[] m:mix)for(int i=0;i<m[0];i++){
            String group=randomText(r,words),name=randomText(r,words)+" Title "+i;
            corpus.add(entry(group,name,r.nextBoolean()?name:randomText(r,words),m[1]==2?name:"",types[m[1]]));
        }
        // 2. The real demo catalogue shipped with the server.
        String demo=new String(Files.readAllBytes(Paths.get("server/nenotv-demo-catalog.json")),StandardCharsets.UTF_8);
        Matcher obj=Pattern.compile("\\{[^{}]*\\}").matcher(demo);int demoItems=0;
        while(obj.find()){
            String o=obj.group();
            corpus.add(entry(field(o,"group"),field(o,"name"),field(o,"tvgName"),field(o,"seriesTitle"),"vod"));demoItems++;
        }
        // 3. Fuzz, including nulls and empty fields.
        for(int i=0;i<300000;i++){
            corpus.add(entry(r.nextInt(30)==0?null:randomText(r,words),r.nextInt(30)==0?null:randomText(r,words),
                    r.nextInt(30)==0?null:randomText(r,words),r.nextInt(3)==0?randomText(r,words):"","live"));
        }

        for(MediaEntry e:corpus)compare(e);
        same("detectTag(null)",LegacyContentLanguage.detectTag(null),ContentLanguage.detectTag(null),"null");
        same("categoryTag(null)",LegacyContentLanguage.categoryTag(null),ContentLanguage.categoryTag(null),"null");

        Map<String,Integer> tags=new TreeMap<>();
        for(MediaEntry e:corpus)tags.merge(ContentLanguage.detectTag(e),1,Integer::sum);

        // Timing on the 199.068-item customer-shaped part only, best of three.
        List<MediaEntry> customer=corpus.subList(0,199068);
        long legacy=Long.MAX_VALUE,fast=Long.MAX_VALUE;int sink=0;
        for(int round=0;round<3;round++){
            long t=System.nanoTime();for(MediaEntry e:customer)sink+=LegacyContentLanguage.detectTag(e).length();legacy=Math.min(legacy,System.nanoTime()-t);
            t=System.nanoTime();for(MediaEntry e:customer)sink+=ContentLanguage.detectTag(e).length();fast=Math.min(fast,System.nanoTime()-t);
        }
        System.out.printf(Locale.ROOT,"OK: %d comparisons, %d entries (%d demo), 0 mismatches%n",checked,corpus.size()+1,demoItems);
        System.out.printf(Locale.ROOT,"detectTag on 199068 items: legacy %.2f s, new %.2f s (%.1fx) [%d]%n",legacy/1e9,fast/1e9,(double)legacy/fast,sink);
        System.out.println("tag distribution: "+tags);
        if(demoItems==0)throw new IllegalStateException("demo catalogue not parsed");
    }

    private static String field(String obj,String key){
        Matcher m=Pattern.compile("\""+key+"\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"").matcher(obj);
        return m.find()?m.group(1).replace("\\/","/").replace("\\\"","\""):"";
    }
}
