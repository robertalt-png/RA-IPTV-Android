package com.nenotv.player;

import com.nenotv.player.model.MediaEntry;
import java.text.Normalizer;
import java.util.*;

/** Pure merge/dedupe logic. Loading stays in the Light provider layer. */
public final class SmartSourceMerger {
    private SmartSourceMerger(){}

    public static List<MediaEntry> tag(List<MediaEntry> input,String sourceId,String sourceName){
        if(input==null)return Collections.emptyList();
        for(MediaEntry e:input)if(e!=null){
            e.sourceId=sourceId==null?"":sourceId;
            e.sourceName=sourceName==null?"":sourceName;
        }
        return input;
    }

    public static List<MediaEntry> merge(List<MediaEntry> primary,List<MediaEntry> secondary){
        LinkedHashMap<String,MediaEntry> out=new LinkedHashMap<>();
        if(primary!=null)for(MediaEntry e:primary)if(e!=null)out.put(key(e),e);
        if(secondary!=null)for(MediaEntry e:secondary){
            if(e==null)continue;
            String k=key(e);MediaEntry existing=out.get(k);
            if(existing==null){out.put(k,e);continue;}
            mergeFallbacks(existing,e);
            if(empty(existing.logo)&&!empty(e.logo))existing.logo=e.logo;
            if(empty(existing.backdrop)&&!empty(e.backdrop))existing.backdrop=e.backdrop;
            if(empty(existing.plot)&&!empty(e.plot))existing.plot=e.plot;
            if(empty(existing.rating)&&!empty(e.rating))existing.rating=e.rating;
            if(empty(existing.year)&&!empty(e.year))existing.year=e.year;
            if(empty(existing.tvgId)&&!empty(e.tvgId))existing.tvgId=e.tvgId;
            if(empty(existing.tvgName)&&!empty(e.tvgName))existing.tvgName=e.tvgName;
        }
        return new ArrayList<>(out.values());
    }

    static void mergeFallbacks(MediaEntry a,MediaEntry b){
        LinkedHashSet<String> urls=new LinkedHashSet<>();
        add(urls,a.directSource);add(urls,a.url);if(a.candidates!=null)for(String x:a.candidates)add(urls,x);
        add(urls,b.directSource);add(urls,b.url);if(b.candidates!=null)for(String x:b.candidates)add(urls,x);
        a.candidates.clear();a.candidates.addAll(urls);
    }

    public static String key(MediaEntry e){
        if(e==null)return "null";
        String type=norm(e.type);
        if("live".equals(type)&&!empty(e.tvgId))return "live:tvg:"+norm(e.tvgId);
        if(("vod".equals(type)||"series".equals(type)||"episode".equals(type))&&!empty(e.tmdbId))
            return type+":tmdb:"+norm(e.tmdbId)+(("episode".equals(type))?":"+e.season+":"+e.episode:"");
        if(("vod".equals(type)||"series".equals(type)||"episode".equals(type))&&!empty(e.imdbId))
            return type+":imdb:"+norm(e.imdbId)+(("episode".equals(type))?":"+e.season+":"+e.episode:"");
        return type+":name:"+norm(e.name)+":"+norm(e.year)+(("episode".equals(type))?":"+e.season+":"+e.episode:"");
    }

    private static void add(Set<String>s,String x){if(x!=null){String v=x.trim();if(v.startsWith("http://")||v.startsWith("https://"))s.add(v);}}
    private static boolean empty(String x){return x==null||x.trim().isEmpty()||"null".equalsIgnoreCase(x.trim());}
    private static String norm(String x){
        if(x==null)return "";
        String s=Normalizer.normalize(x,Normalizer.Form.NFD).replaceAll("\\p{M}+","").toLowerCase(Locale.ROOT);
        return s.replaceAll("[^a-z0-9]+"," ").trim().replaceAll("\\s+"," ");
    }
}
