package com.nenotv.player.core;

import java.net.URLEncoder;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.ArrayList;
import java.util.LinkedHashSet;

public final class XtreamUrls {
    private XtreamUrls(){}

    public static String base(String value) {
        if (value == null) return "";
        String s = value.trim();
        if (s.isEmpty()) return "";
        String lower = s.toLowerCase(Locale.ROOT);
        if (!lower.startsWith("http://") && !lower.startsWith("https://")) s = "http://" + s;
        int q = s.indexOf('?');
        String noQuery = q >= 0 ? s.substring(0, q) : s;
        String endpoint = noQuery.toLowerCase(Locale.ROOT);
        if (endpoint.endsWith("/player_api.php") || endpoint.endsWith("/get.php")) s = noQuery.substring(0, noQuery.lastIndexOf('/'));
        else if (q >= 0) s = noQuery;
        while (s.endsWith("/")) s = s.substring(0, s.length() - 1);
        return s;
    }

    public static String enc(String s){ try{return URLEncoder.encode(s == null ? "" : s, "UTF-8");}catch(java.io.UnsupportedEncodingException e){throw new IllegalStateException(e);} }

    public static String api(String server,String u,String p,String action,String category){
        StringBuilder x = new StringBuilder(base(server)).append("/player_api.php?username=").append(enc(u)).append("&password=").append(enc(p));
        if(action != null && !action.isEmpty()) x.append("&action=").append(enc(action));
        if(category != null && !category.isEmpty() && !category.equals("all")) x.append("&category_id=").append(enc(category));
        return x.toString();
    }

    public static List<String> liveCandidates(String server,String u,String p,String id){
        String b=base(server), uu=enc(u), pp=enc(p), ii=enc(id);
        return Arrays.asList(b+"/live/"+uu+"/"+pp+"/"+ii+".m3u8", b+"/live/"+uu+"/"+pp+"/"+ii+".ts");
    }

    public static String vod(String server,String u,String p,String id,String ext){
        return base(server)+"/movie/"+enc(u)+"/"+enc(p)+"/"+enc(id)+"."+(ext==null||ext.isEmpty()?"mp4":ext);
    }

    public static String episode(String server,String u,String p,String id,String ext){
        return base(server)+"/series/"+enc(u)+"/"+enc(p)+"/"+enc(id)+"."+(ext==null||ext.isEmpty()?"mp4":ext);
    }

    public static List<String> episodeCandidates(String server,String u,String p,String id,String ext){
        LinkedHashSet<String> out=new LinkedHashSet<>();
        String b=base(server)+"/series/"+enc(u)+"/"+enc(p)+"/"+enc(id);
        String e=ext==null?"":ext.trim().toLowerCase(Locale.ROOT);
        if(e.startsWith("."))e=e.substring(1);
        if(!e.isEmpty())out.add(b+"."+e);
        out.add(b+".mp4");
        out.add(b+".mkv");
        out.add(b+".ts");
        out.add(b+".m3u8");
        out.add(b);
        return new ArrayList<>(out);
    }
}
