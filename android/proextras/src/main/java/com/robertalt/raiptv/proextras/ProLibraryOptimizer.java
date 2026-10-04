package com.nenotv.player.proextras;

import com.nenotv.player.ContentLanguage;
import com.nenotv.player.model.MediaEntry;
import java.util.*;

/** Pure post-load optimizer. It never authenticates, fetches categories, or calls an IPTV provider. */
public final class ProLibraryOptimizer {
    private ProLibraryOptimizer(){}
    public static List<MediaEntry> optimize(List<MediaEntry> loaded,String section,String preferredLanguage){
        LinkedHashMap<String,MediaEntry> unique=new LinkedHashMap<>();
        if(loaded!=null)for(MediaEntry e:loaded)if(e!=null)unique.putIfAbsent(e.uniqueKey(),e);
        ArrayList<MediaEntry> out=new ArrayList<>(unique.values());
        final String pref=preferredLanguage==null?"":preferredLanguage;
        Comparator<MediaEntry> language=(a,b)->Integer.compare(ContentLanguage.rank(a,pref),ContentLanguage.rank(b,pref));
        Comparator<MediaEntry> quality=(a,b)->Integer.compare(liveRank(a),liveRank(b));
        Comparator<MediaEntry> name=(a,b)->safe(a.name).compareToIgnoreCase(safe(b.name));
        if("live".equals(section))out.sort(language.thenComparing(quality).thenComparing(name));
        else if("vod".equals(section)||"series".equals(section))out.sort(language.thenComparing(name));
        else if(!out.isEmpty()&&"episode".equals(out.get(0).type))out.sort(Comparator.comparingInt((MediaEntry e)->e.season).thenComparingInt(e->e.episode));
        return out;
    }
    private static int liveRank(MediaEntry e){
        String x=(safe(e==null?"":e.name)+" "+safe(e==null?"":e.group)).toLowerCase(Locale.ROOT);
        int r=0;if(isRadio(x))r+=10000;else if(isMainNl(x))r-=600;if(e!=null&&e.catchup)r-=350;
        if(x.matches(".*(?:\\b4k\\b|\\buhd\\b).*"))r-=260;else if(x.matches(".*\\bfhd\\b.*"))r-=220;else if(x.matches(".*\\bhd\\b.*"))r-=180;else r+=40;return r;
    }
    private static boolean isRadio(String x){return x.matches(".*\\b(?:radio|fm)\\b.*");}
    private static boolean isMainNl(String x){String z=x.replaceAll("[^a-z0-9]+"," ");return z.matches(".*\\b(?:npo ?[123]|rtl ?[4578]|sbs ?6|net ?5|veronica|rtl z|rtl lounge)\\b.*");}
    private static String safe(String s){return s==null?"":s;}
}
