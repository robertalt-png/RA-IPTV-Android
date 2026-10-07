package com.nenotv.player.core;

import com.nenotv.player.model.MediaEntry;
import java.util.*;
import java.util.regex.*;

public final class M3uParser {
    private M3uParser(){}
    public static final class Result { public final List<MediaEntry> items; public final String epgUrl; Result(List<MediaEntry> i,String e){items=i;epgUrl=e;} }
    private static final Pattern ATTR=Pattern.compile("([\\w-]+)=\"([^\"]*)\"");
    public static Result parse(String text){
        String[] lines=(text==null?"":text).replace("\r","").split("\n");
        String epg=""; List<MediaEntry> out=new ArrayList<>(); MediaEntry pending=null;
        // P3: catch-up given once on the #EXTM3U line applies to every channel that does not set its own.
        Map<String,String> playlist=new HashMap<>();
        if(lines.length>0 && lines[0].startsWith("#EXTM3U")){
            Matcher h=ATTR.matcher(lines[0]); while(h.find()){String k=h.group(1).toLowerCase(Locale.ROOT);if(k.equals("catchup")||k.equals("catchup-source")||k.equals("catchup-days")||k.equals("timeshift"))playlist.put(k,h.group(2));}
            Matcher m=Pattern.compile("(?:url-tvg|x-tvg-url)=\"([^\"]+)\"",Pattern.CASE_INSENSITIVE).matcher(lines[0]); if(m.find()) epg=m.group(1);
        }
        for(String raw:lines){ String line=raw.trim(); if(line.isEmpty())continue;
            if(line.startsWith("#EXTINF:")){
                Map<String,String>a=new HashMap<>(playlist); Matcher m=ATTR.matcher(line); while(m.find())a.put(m.group(1).toLowerCase(Locale.ROOT),m.group(2));
                int comma=line.indexOf(','); String name=comma>=0?line.substring(comma+1).trim():a.getOrDefault("tvg-name","Untitled");
                MediaEntry e=new MediaEntry(); e.id=a.getOrDefault("tvg-id",a.getOrDefault("tvg-name",name)); e.name=name; e.logo=a.getOrDefault("tvg-logo",""); e.group=a.getOrDefault("group-title","Other"); e.tvgId=a.getOrDefault("tvg-id",""); e.tvgName=a.getOrDefault("tvg-name",name); e.type="live"; try{e.number=Math.max(0,Integer.parseInt(a.getOrDefault("tvg-chno",a.getOrDefault("channel-number","0")).trim()));}catch(Exception ignored){} String cu=a.getOrDefault("catchup",a.getOrDefault("timeshift",""));e.catchup=!cu.isEmpty()&&!"0".equals(cu)&&!"false".equalsIgnoreCase(cu)&&!"none".equalsIgnoreCase(cu);e.catchupType=e.catchup?cu.trim():"";e.catchupSource=a.getOrDefault("catchup-source","").trim();try{e.catchupDays=Integer.parseInt(a.getOrDefault("catchup-days",a.getOrDefault("timeshift","0")));}catch(Exception ignored){} pending=e;
            } else if(!line.startsWith("#") && pending!=null){ pending.url=line; pending.candidates.add(line); out.add(pending); pending=null; }
        }
        return new Result(out,epg);
    }
}
