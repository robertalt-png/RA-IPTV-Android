package com.nenotv.player.core;

import com.nenotv.player.model.EpgEntry;
import java.io.*; import java.net.*; import java.time.*; import java.time.format.DateTimeFormatter; import java.util.*; import java.util.zip.GZIPInputStream; import javax.xml.parsers.SAXParserFactory; import org.xml.sax.*; import org.xml.sax.helpers.DefaultHandler;

/** Streaming XMLTV parser with language-aware title/description selection. */
public final class XmltvGuide {
    private XmltvGuide(){}
    public static String lookup(String url,String channelId,String channelName,int limit)throws Exception{List<EpgEntry>x=lookupEntries(url,channelId,channelName,limit,Locale.getDefault().getLanguage());if(x.isEmpty())return "No EPG available.";StringBuilder s=new StringBuilder();for(EpgEntry e:x){if(s.length()>0)s.append("\n\n");String r=e.range();if(!r.isEmpty())s.append(r).append(" · ");s.append(e.title);if(e.description!=null&&!e.description.isEmpty())s.append("\n").append(e.description);}return s.toString();}
    public static List<EpgEntry> lookupEntries(String url,String channelId,String channelName,int limit,String preferredLanguage)throws Exception{
        HttpURLConnection c=(HttpURLConnection)new URL(url).openConnection();c.setConnectTimeout(12000);c.setReadTimeout(30000);c.setRequestProperty("User-Agent","SunnyIPTV-Android/0.8.0");InputStream in=c.getInputStream();String enc=c.getContentEncoding();if((enc!=null&&enc.toLowerCase(Locale.ROOT).contains("gzip"))||url.toLowerCase(Locale.ROOT).endsWith(".gz"))in=new GZIPInputStream(in);
        Handler h=new Handler(channelId,channelName,limit,preferredLanguage);SAXParserFactory.newInstance().newSAXParser().parse(in,h);in.close();c.disconnect();return h.rows;
    }
    static final class Handler extends DefaultHandler{
        final String id,name,preferred;final int limit;String current="",start="",stop="",field="",fieldLang="";StringBuilder chars=new StringBuilder();List<EpgEntry>rows=new ArrayList<>();LinkedHashMap<String,String>titles=new LinkedHashMap<>(),descs=new LinkedHashMap<>();long now=System.currentTimeMillis()/1000L;
        Handler(String i,String n,int l,String p){id=i==null?"":i;name=n==null?"":n;limit=l;preferred=base(p);}
        public void startElement(String u,String l,String q,Attributes a){if("programme".equals(q)){current=a.getValue("channel");start=a.getValue("start");stop=a.getValue("stop");titles.clear();descs.clear();}else if("title".equals(q)||"desc".equals(q)){field=q;fieldLang=base(a.getValue("lang"));chars.setLength(0);}}
        public void characters(char[]ch,int s,int n){if(!field.isEmpty())chars.append(ch,s,n);}
        public void endElement(String u,String l,String q){if(q.equals(field)){String v=chars.toString().trim();if(!v.isEmpty())("title".equals(field)?titles:descs).put(fieldLang,v);field="";}else if("programme".equals(q)&&matches()&&rows.size()<limit){long en=parse(stop);if(en==0||en>=now){EpgEntry e=new EpgEntry();e.startRaw=start==null?"":start;e.endRaw=stop==null?"":stop;e.startEpoch=parse(start);e.endEpoch=parse(stop);e.title=pick(titles);if(e.title.isEmpty())e.title="Programme";e.description=pick(descs);rows.add(e);}}}
        boolean matches(){if(!id.isEmpty()&&id.equalsIgnoreCase(current))return true;return !name.isEmpty()&&name.equalsIgnoreCase(current);}
        String pick(LinkedHashMap<String,String>m){if(m.isEmpty())return "";String x=m.get(preferred);if(x!=null)return x;x=m.get("");if(x!=null)return x;return m.values().iterator().next();}
        static String base(String x){if(x==null)return "";String z=x.trim().toLowerCase(Locale.ROOT).replace('_','-');int i=z.indexOf('-');return i>0?z.substring(0,i):z;}
        long parse(String s){try{if(s==null||s.length()<14)return 0;String d=s.substring(0,14);LocalDateTime t=LocalDateTime.parse(d,DateTimeFormatter.ofPattern("yyyyMMddHHmmss"));ZoneOffset off=ZoneOffset.UTC;if(s.length()>=20){String z=s.substring(15,20).replace(" ","");if(z.matches("[+-]\\d{4}"))off=ZoneOffset.of(z.substring(0,3)+":"+z.substring(3));}return t.toEpochSecond(off);}catch(Exception e){return 0;}}
    }
}
