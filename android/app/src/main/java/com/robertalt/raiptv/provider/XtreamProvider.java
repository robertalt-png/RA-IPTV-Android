package com.nenotv.player.provider;

import com.nenotv.player.core.XtreamUrls;
import com.nenotv.player.model.*;
import com.nenotv.player.net.HttpText;
import org.json.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class XtreamProvider implements Provider {
    private static final long ITEMS_TTL_MS = 10 * 60 * 1000L;
    private static final long CATEGORIES_TTL_MS = 30 * 60 * 1000L;

    private final Profile p;
    private final Map<String, Timed<List<MediaEntry>>> itemCache = new ConcurrentHashMap<>();
    private final Map<String, Timed<List<Category>>> categoryCache = new ConcurrentHashMap<>();
    private final Map<String, Timed<String>> backdropCache = new ConcurrentHashMap<>();
    private final Map<String, Object> locks = new ConcurrentHashMap<>();

    private static final class Timed<T> {
        final T value;
        final long at;
        Timed(T value){ this.value=value; this.at=System.currentTimeMillis(); }
        boolean fresh(long ttl){ return System.currentTimeMillis()-at < ttl; }
    }

    public XtreamProvider(Profile p){this.p=p;}

    private Object lock(String key){
        Object existing=locks.get(key);
        if(existing!=null)return existing;
        Object created=new Object();
        Object raced=locks.putIfAbsent(key,created);
        return raced==null?created:raced;
    }

    private JSONArray arr(String action,String category)throws Exception{
        String s=HttpText.get(XtreamUrls.api(p.server,p.username,p.password,action,category));
        return new JSONArray(s);
    }

    @Override public void authenticate() throws Exception {
        JSONObject x=new JSONObject(HttpText.get(XtreamUrls.api(p.server,p.username,p.password,"","")));
        if(!"1".equals(String.valueOf(x.getJSONObject("user_info").opt("auth"))))throw new Exception("LOGIN_FAILED");
        XtreamServerZone.remember(p.server,x);
    }

    @Override public List<Category> categories(String type)throws Exception{
        String key="cat:"+type;
        Timed<List<Category>> cached=categoryCache.get(key);
        if(cached!=null&&cached.fresh(CATEGORIES_TTL_MS))return new ArrayList<>(cached.value);
        synchronized(lock(key)){
            cached=categoryCache.get(key);
            if(cached!=null&&cached.fresh(CATEGORIES_TTL_MS))return new ArrayList<>(cached.value);
            String a=type.equals("vod")?"get_vod_categories":type.equals("series")?"get_series_categories":"get_live_categories";
            JSONArray rows=arr(a,"");
            List<Category>o=new ArrayList<>();
            for(int i=0;i<rows.length();i++){
                JSONObject x=rows.getJSONObject(i);
                o.add(new Category(String.valueOf(x.opt("category_id")),x.optString("category_name","Other"),type));
            }
            categoryCache.put(key,new Timed<>(new ArrayList<>(o)));
            return o;
        }
    }

    @Override public List<MediaEntry> items(String type,String category)throws Exception{
        String cat=(category==null||category.isEmpty())?"all":category;
        String key="items:"+type+":"+cat;
        Timed<List<MediaEntry>> cached=itemCache.get(key);
        if(cached!=null&&cached.fresh(ITEMS_TTL_MS))return new ArrayList<>(cached.value);
        synchronized(lock(key)){
            cached=itemCache.get(key);
            if(cached!=null&&cached.fresh(ITEMS_TTL_MS))return new ArrayList<>(cached.value);
            String a=type.equals("vod")?"get_vod_streams":type.equals("series")?"get_series":"get_live_streams";
            List<MediaEntry>o=new ArrayList<>();
            com.nenotv.player.net.StreamingJsonArray.read(XtreamUrls.api(p.server,p.username,p.password,a,cat),json->o.add(from(new JSONObject(json.toString()),type)));
            itemCache.put(key,new Timed<>(new ArrayList<>(o)));trimItemCache();
            return o;
        }
    }

    private void trimItemCache(){
        if(itemCache.size()<=2)return;
        ArrayList<Map.Entry<String,Timed<List<MediaEntry>>>> entries=new ArrayList<>(itemCache.entrySet());
        entries.sort(Comparator.comparingLong(e->e.getValue().at));
        int remove=Math.max(0,entries.size()-2);
        for(int i=0;i<remove;i++)itemCache.remove(entries.get(i).getKey(),entries.get(i).getValue());
    }
    public void clearTransientItemCache(){itemCache.clear();backdropCache.clear();}


    public interface BatchReceiver { void accept(List<MediaEntry> items) throws Exception; }

    public long streamSection(String type,BatchReceiver receiver) throws Exception {
        return streamCategory(type,"all",receiver);
    }

    public long streamCategory(String type,String category,BatchReceiver receiver) throws Exception {
        String action=type.equals("vod")?"get_vod_streams":type.equals("series")?"get_series":"get_live_streams";
        ArrayList<MediaEntry> batch=new ArrayList<>(80);
        long count=com.nenotv.player.net.StreamingJsonArray.read(XtreamUrls.api(p.server,p.username,p.password,action,category),json->{
            batch.add(from(new JSONObject(json.toString()),type));
            if(batch.size()==80){receiver.accept(batch);batch.clear();}
        });
        if(!batch.isEmpty())receiver.accept(batch);
        return count;
    }

    private MediaEntry from(JSONObject x,String type){
        MediaEntry e=new MediaEntry();
        e.streamId=x.has("stream_id")?String.valueOf(x.opt("stream_id")):"";
        e.seriesId=x.has("series_id")?String.valueOf(x.opt("series_id")):"";
        e.id=!e.streamId.isEmpty()?e.streamId:e.seriesId;
        e.name=x.optString("name",x.optString("title","Untitled"));
        e.logo=x.optString("stream_icon",x.optString("cover",x.optString("movie_image","")));
        e.backdrop=firstUrl(x.opt("backdrop_path"));
        if(e.backdrop.isEmpty())e.backdrop=x.optString("cover_big","");
        e.categoryId=String.valueOf(x.opt("category_id"));
        e.tvgId=x.optString("epg_channel_id",x.optString("tvg_id",""));e.tvgName=e.name;
        e.catchup=x.optInt("tv_archive",0)>0||"1".equals(x.optString("tv_archive",""))||x.optBoolean("has_archive",false);e.catchupDays=x.optInt("tv_archive_duration",0);
        e.type=type;
        e.rating=x.optString("rating","");
        e.year=x.optString("year",x.optString("releaseDate",""));
        e.plot=x.optString("plot","");
        e.extension=x.optString("container_extension","");
        e.tmdbId=x.optString("tmdb_id",x.optString("tmdb",""));
        e.imdbId=x.optString("imdb_id",x.optString("imdb",""));
        e.directSource=x.optString("direct_source","");
        if(!e.directSource.isEmpty()&&(e.directSource.startsWith("http://")||e.directSource.startsWith("https://")))e.candidates.add(e.directSource);
        else if(type.equals("live"))e.candidates.addAll(XtreamUrls.liveCandidates(p.server,p.username,p.password,e.streamId));
        else if(type.equals("vod"))e.candidates.add(XtreamUrls.vod(p.server,p.username,p.password,e.streamId,e.extension));
        return e;
    }

    @Override public List<MediaEntry> seriesEpisodes(MediaEntry series)throws Exception{
        JSONObject data=new JSONObject(HttpText.get(XtreamUrls.api(p.server,p.username,p.password,"get_series_info","")+"&series_id="+XtreamUrls.enc(series.seriesId)));
        JSONObject eps=data.optJSONObject("episodes");
        List<MediaEntry>o=new ArrayList<>();
        if(eps==null)return o;
        List<String> seasons=new ArrayList<>();
        for(Iterator<String>it=eps.keys();it.hasNext();)seasons.add(it.next());
        seasons.sort(Comparator.comparingInt(s->{try{return Integer.parseInt(s);}catch(Exception e){return 0;}}));
        for(String s:seasons){
            JSONArray rows=eps.optJSONArray(s); if(rows==null)continue;
            for(int i=0;i<rows.length();i++){
                JSONObject x=rows.optJSONObject(i);if(x==null)continue;
                MediaEntry e=new MediaEntry();
                e.id=String.valueOf(x.opt("id"));e.streamId=e.id;
                e.name=x.optString("title","Episode "+x.optInt("episode_num",i+1));
                e.type="episode";e.extension=x.optString("container_extension","");e.seriesId=series.seriesId;e.backdrop=series.backdrop;
                try{e.season=Integer.parseInt(s);}catch(Exception ignored){e.season=0;}
                e.episode=x.optInt("episode_num",i+1);e.seriesTitle=series.name;e.tmdbId=series.tmdbId;e.imdbId=series.imdbId;e.year=series.year;
                JSONObject info=x.optJSONObject("info");if(info!=null){e.plot=info.optString("plot","");e.logo=info.optString("movie_image",info.optString("cover_big",""));String epBack=firstUrl(info.opt("backdrop_path"));if(!epBack.isEmpty())e.backdrop=epBack;}
                String direct=x.optString("direct_source","");
                if((direct==null||direct.isEmpty())&&info!=null)direct=info.optString("direct_source","");
                if(direct!=null&&(direct.startsWith("http://")||direct.startsWith("https://")))e.candidates.add(direct);
                for(String candidate:XtreamUrls.episodeCandidates(p.server,p.username,p.password,e.streamId,e.extension))if(!e.candidates.contains(candidate))e.candidates.add(candidate);
                o.add(e);
            }
        }
        return o;
    }

    @Override public MediaDetails details(MediaEntry item)throws Exception{
        MediaDetails d=new MediaDetails(); if(item==null)return d;
        d.title=item.name; d.year=item.year; d.plot=item.plot; d.rating=item.rating; d.imdbId=item.imdbId; d.tmdbId=item.tmdbId; d.poster=item.logo; d.backdrop=item.backdrop;
        if("live".equals(item.type))return d;
        boolean series="series".equals(item.type)||"episode".equals(item.type);
        String id=series?item.seriesId:item.streamId; if(id==null||id.isEmpty())return d;
        String url=XtreamUrls.api(p.server,p.username,p.password,series?"get_series_info":"get_vod_info","")+(series?"&series_id=":"&vod_id=")+XtreamUrls.enc(id);
        JSONObject data=new JSONObject(HttpText.get(url));
        JSONObject info=data.optJSONObject("info"); JSONObject movie=data.optJSONObject("movie_data");
        JSONObject src=info!=null?info:(movie!=null?movie:data);
        d.title=firstNonEmpty(src.optString("name",""),src.optString("title",""),d.title);
        d.year=firstNonEmpty(src.optString("year",""),src.optString("releaseDate",""),src.optString("release_date",""),d.year);
        d.genre=firstNonEmpty(src.optString("genre",""),src.optString("genres",""));
        d.duration=firstNonEmpty(src.optString("duration",""),src.optString("duration_secs",""));
        d.director=firstNonEmpty(src.optString("director",""));
        d.cast=firstNonEmpty(src.optString("actors",""),src.optString("cast",""));
        d.country=firstNonEmpty(src.optString("country",""));
        d.plot=firstNonEmpty(src.optString("plot",""),src.optString("description",""),d.plot);
        d.imdbRating=firstNonEmpty(src.optString("imdb_rating",""),src.optString("imdbRating",""));
        d.tmdbRating=firstNonEmpty(src.optString("tmdb_rating",""),src.optString("tmdbRating",""));
        d.rating=firstNonEmpty(src.optString("rating",""),src.optString("rating_5based",""),d.rating);
        d.imdbId=firstNonEmpty(src.optString("imdb_id",""),src.optString("imdb",""),d.imdbId);
        d.tmdbId=firstNonEmpty(src.optString("tmdb_id",""),src.optString("tmdb",""),d.tmdbId);
        d.trailer=firstNonEmpty(src.optString("youtube_trailer",""),src.optString("trailer",""));
        d.backdrop=firstUrl(src.opt("backdrop_path")); if(d.backdrop.isEmpty())d.backdrop=firstNonEmpty(src.optString("cover_big",""),d.backdrop);
        d.poster=firstNonEmpty(src.optString("movie_image",""),src.optString("cover",""),src.optString("stream_icon",""),d.poster);
        if(movie!=null&&movie!=src){
            d.imdbId=firstNonEmpty(d.imdbId,movie.optString("imdb_id",""),movie.optString("imdb",""));
            d.tmdbId=firstNonEmpty(d.tmdbId,movie.optString("tmdb_id",""),movie.optString("tmdb",""));
        }
        item.imdbId=d.imdbId; item.tmdbId=d.tmdbId; if(!d.backdrop.isEmpty())item.backdrop=d.backdrop;
        return d;
    }

    private static String firstNonEmpty(String... values){ if(values==null)return ""; for(String v:values)if(v!=null&&!v.trim().isEmpty()&&!"null".equalsIgnoreCase(v.trim()))return v.trim(); return ""; }

    public String backdrop(MediaEntry item)throws Exception{
        if(item==null)return "";
        if(item.backdrop!=null&&!item.backdrop.trim().isEmpty())return item.backdrop;
        if("live".equals(item.type))return item.logo==null?"":item.logo;
        String id="series".equals(item.type)||"episode".equals(item.type)?item.seriesId:item.streamId;
        if(id==null||id.isEmpty())return item.logo==null?"":item.logo;
        String key="backdrop:"+item.type+":"+id;
        Timed<String> cached=backdropCache.get(key);
        if(cached!=null&&cached.fresh(CATEGORIES_TTL_MS))return cached.value;
        synchronized(lock(key)){
            cached=backdropCache.get(key);
            if(cached!=null&&cached.fresh(CATEGORIES_TTL_MS))return cached.value;
            String resolved="";
            try{
                boolean series="series".equals(item.type)||"episode".equals(item.type);
                String url=XtreamUrls.api(p.server,p.username,p.password,series?"get_series_info":"get_vod_info","")
                        +(series?"&series_id=":"&vod_id=")+XtreamUrls.enc(id);
                JSONObject data=new JSONObject(HttpText.get(url));
                JSONObject info=data.optJSONObject("info");
                if(info!=null){
                    resolved=firstUrl(info.opt("backdrop_path"));
                    if(resolved.isEmpty())resolved=info.optString("cover_big",info.optString("movie_image",info.optString("cover","")));
                }
                if(resolved.isEmpty()){
                    JSONObject movie=data.optJSONObject("movie_data");
                    if(movie!=null){resolved=firstUrl(movie.opt("backdrop_path"));if(resolved.isEmpty())resolved=movie.optString("stream_icon",movie.optString("cover_big",""));}
                }
            }catch(Exception ignored){}
            if(resolved==null||resolved.trim().isEmpty())resolved=item.logo==null?"":item.logo;
            item.backdrop=resolved;
            backdropCache.put(key,new Timed<>(resolved));
            return resolved;
        }
    }

    private static String firstUrl(Object value){
        if(value==null||value==JSONObject.NULL)return "";
        if(value instanceof JSONArray){JSONArray a=(JSONArray)value;for(int i=0;i<a.length();i++){String x=a.optString(i,"");if(x.startsWith("http://")||x.startsWith("https://"))return x;}return "";}
        String s=String.valueOf(value).trim();
        if(s.startsWith("[")&&s.endsWith("]")){try{return firstUrl(new JSONArray(s));}catch(Exception ignored){}}
        return s.startsWith("http://")||s.startsWith("https://")?s:"";
    }

    /** Full XMLTV guide of the Xtream server (7 days back and ahead when the provider keeps them). */
    @Override public List<String> catchupUrls(MediaEntry channel,long startEpoch,long endEpoch){
        if(channel==null||!channel.catchup||channel.streamId==null||channel.streamId.isEmpty())return Collections.emptyList();
        return com.nenotv.player.core.CatchupUrls.xtream(p.server,p.username,p.password,channel.streamId,startEpoch,endEpoch,XtreamServerZone.get(p.server,p.username,p.password));
    }
    @Override public String guideUrl(){if(p.server==null||p.server.trim().isEmpty()||p.username==null||p.username.isEmpty())return "";return XtreamUrls.base(p.server)+"/xmltv.php?username="+XtreamUrls.enc(p.username)+"&password="+XtreamUrls.enc(p.password);}

    @Override public List<EpgEntry> epgEntries(MediaEntry item,int limit)throws Exception{
        int n=Math.max(2,Math.min(12,limit));
        String url=XtreamUrls.api(p.server,p.username,p.password,"get_short_epg","")+"&stream_id="+XtreamUrls.enc(item.streamId)+"&limit="+n;
        JSONObject data=new JSONObject(HttpText.get(url));JSONArray rows=data.optJSONArray("epg_listings");
        List<EpgEntry> out=new ArrayList<>();if(rows==null)return out;
        for(int i=0;i<rows.length();i++){JSONObject x=rows.optJSONObject(i);if(x==null)continue;EpgEntry e=new EpgEntry();e.title=decodeMaybe(x.optString("title",""));if(e.title.isEmpty())e.title="Programma";e.description=decodeMaybe(x.optString("description",x.optString("desc","")));e.startRaw=x.optString("start","");e.endRaw=x.optString("end",x.optString("stop",""));e.startEpoch=readEpoch(x,"start_timestamp",e.startRaw);e.endEpoch=readEpoch(x,"stop_timestamp",e.endRaw);out.add(e);}
        out.sort((a,b)->Long.compare(a.startEpoch,b.startEpoch));return out;
    }

    private long readEpoch(JSONObject x,String field,String raw){
        try{long v=x.optLong(field,0);if(v>100000000000L)v/=1000L;if(v>0)return v;}catch(Exception ignored){}
        try{if(raw==null||raw.trim().isEmpty())return 0;String z=raw.trim();java.time.format.DateTimeFormatter f=java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");java.time.LocalDateTime t=java.time.LocalDateTime.parse(z.substring(0,19),f);return t.atZone(java.time.ZoneId.systemDefault()).toEpochSecond();}catch(Exception ignored){}
        return 0;
    }

    private String decodeMaybe(String s){try{if(s!=null&&s.matches("^[A-Za-z0-9+/=]+$")&&s.length()%4==0)return new String(java.util.Base64.getDecoder().decode(s),java.nio.charset.StandardCharsets.UTF_8);}catch(Exception ignored){}return s==null?"":s;}
}
