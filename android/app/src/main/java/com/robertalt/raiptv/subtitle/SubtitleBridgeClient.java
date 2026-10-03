package com.nenotv.player.subtitle;

import android.net.Uri; import com.nenotv.player.model.*; import com.nenotv.player.net.HttpText; import org.json.*; import java.io.*; import java.nio.charset.StandardCharsets; import java.util.*;

public class SubtitleBridgeClient {
    private final Profile p; private final String language; public SubtitleBridgeClient(Profile p){this(p,"nl");} public SubtitleBridgeClient(Profile p,String language){this.p=p;this.language=language==null||language.trim().isEmpty()?"nl":language.trim().toLowerCase(java.util.Locale.ROOT);}
    private String base(){String s=p.bridgeUrl==null?"":p.bridgeUrl.trim();while(s.endsWith("/"))s=s.substring(0,s.length()-1);return s;}
    public boolean enabled(){return !base().isEmpty();}
    private Uri.Builder url(String path){Uri.Builder b=Uri.parse(base()+path).buildUpon();if(p.bridgeToken!=null&&!p.bridgeToken.isEmpty())b.appendQueryParameter("token",p.bridgeToken);return b;}
    public Result best(MediaEntry e)throws Exception{if(!enabled()||e.type.equals("live"))return null;Uri.Builder b=url("/subtitle/search");b.appendQueryParameter("title",clean(e.seriesTitle.isEmpty()?e.name:e.seriesTitle));b.appendQueryParameter("year",e.year);b.appendQueryParameter("type",e.type.equals("episode")?"tv":"movie");if(e.season>0)b.appendQueryParameter("season",String.valueOf(e.season));if(e.episode>0)b.appendQueryParameter("episode",String.valueOf(e.episode));if(!e.tmdbId.isEmpty())b.appendQueryParameter("tmdb_id",e.tmdbId);if(!e.imdbId.isEmpty())b.appendQueryParameter("imdb_id",e.imdbId);b.appendQueryParameter("language",language);JSONObject d=new JSONObject(HttpText.get(b.build().toString()));JSONArray rows=d.optJSONArray("results");if(rows==null||rows.length()==0)return null;JSONObject r=rows.getJSONObject(0);Result x=new Result();x.provider=r.optString("provider");x.ref=r.optString("ref");x.label=r.optString("languageLabel","Dutch")+" · "+r.optString("providerLabel",x.provider);return x;}
    public File fetchTo(File dir,Result r)throws Exception{Uri.Builder b=url("/subtitle/fetch");b.appendQueryParameter("provider",r.provider);b.appendQueryParameter("ref",r.ref);String vtt=HttpText.get(b.build().toString());File f=new File(dir,"external-"+System.currentTimeMillis()+".vtt");try(OutputStreamWriter w=new OutputStreamWriter(new FileOutputStream(f),StandardCharsets.UTF_8)){w.write(vtt);}return f;}
    private String clean(String s){return (s==null?"":s).replaceAll("(?i)\\bS\\d{1,2}E\\d{1,2}\\b"," ").replaceAll("\\b(19|20)\\d{2}\\b"," ").replace('_',' ').replace('.', ' ').replaceAll("\\s+"," ").trim();}
    public static class Result{public String provider,ref,label;}
}
