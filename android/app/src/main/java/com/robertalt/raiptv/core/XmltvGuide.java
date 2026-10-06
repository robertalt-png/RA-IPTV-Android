package com.nenotv.player.core;

import com.nenotv.player.model.EpgEntry;
import java.io.*; import java.net.*; import java.time.*; import java.time.format.DateTimeFormatter; import java.util.*; import java.util.zip.GZIPInputStream; import javax.xml.parsers.SAXParserFactory; import org.xml.sax.*; import org.xml.sax.helpers.DefaultHandler;

/** Streaming XMLTV parser with language-aware title/description selection. */
public final class XmltvGuide {
    private XmltvGuide(){}
    public static String lookup(String url,String channelId,String channelName,int limit)throws Exception{List<EpgEntry>x=lookupEntries(url,channelId,channelName,limit,Locale.getDefault().getLanguage());if(x.isEmpty())return "No EPG available.";StringBuilder s=new StringBuilder();for(EpgEntry e:x){if(s.length()>0)s.append("\n\n");String r=e.range();if(!r.isEmpty())s.append(r).append(" · ");s.append(e.title);if(e.description!=null&&!e.description.isEmpty())s.append("\n").append(e.description);}return s.toString();}
    /** One parsed guide per XMLTV address, shared by all channels, so the (often very large) file is downloaded once instead of once per channel. */
    private static final long TTL_MS=3L*60*60*1000, WINDOW_SEC=24L*60*60;
    private static final int PER_CHANNEL=48, MAX_GUIDES=2;
    private static final class Guide{final long loadedAt;final Map<String,List<EpgEntry>> byChannel;Guide(long t,Map<String,List<EpgEntry>> m){loadedAt=t;byChannel=m;}}
    private static final LinkedHashMap<String,Guide> GUIDES=new LinkedHashMap<String,Guide>(4,0.75f,true){@Override protected boolean removeEldestEntry(Map.Entry<String,Guide> e){return size()>MAX_GUIDES;}};
    public static List<EpgEntry> lookupEntries(String url,String channelId,String channelName,int limit,String preferredLanguage)throws Exception{
        Guide g=guide(url,preferredLanguage);
        List<EpgEntry> rows=null;
        if(channelId!=null&&!channelId.isEmpty())rows=g.byChannel.get(channelId.toLowerCase(Locale.ROOT));
        if(rows==null&&channelName!=null&&!channelName.isEmpty())rows=g.byChannel.get(channelName.toLowerCase(Locale.ROOT));
        List<EpgEntry> out=new ArrayList<>();if(rows==null)return out;long now=System.currentTimeMillis()/1000L;
        for(EpgEntry r:rows){if(out.size()>=limit)break;if(r.endEpoch!=0&&r.endEpoch<now)continue;EpgEntry e=new EpgEntry();e.title=r.title;e.description=r.description;e.startRaw=r.startRaw;e.endRaw=r.endRaw;e.startEpoch=r.startEpoch;e.endEpoch=r.endEpoch;out.add(e);}
        return out;
    }
    private static Guide guide(String url,String preferredLanguage)throws Exception{
        String key=Handler.base(preferredLanguage)+"|"+url;
        synchronized(GUIDES){
            Guide cached=GUIDES.get(key);
            if(cached!=null&&System.currentTimeMillis()-cached.loadedAt<TTL_MS)return cached;
            HttpURLConnection c=(HttpURLConnection)new URL(url).openConnection();c.setConnectTimeout(12000);c.setReadTimeout(30000);c.setRequestProperty("User-Agent","SunnyIPTV-Android");
            try{InputStream in=c.getInputStream();String enc=c.getContentEncoding();if((enc!=null&&enc.toLowerCase(Locale.ROOT).contains("gzip"))||url.toLowerCase(Locale.ROOT).endsWith(".gz"))in=new GZIPInputStream(in);
                try(InputStream stream=in){Handler h=new Handler(null,null,PER_CHANNEL,preferredLanguage);h.collectAll=true;SAXParserFactory.newInstance().newSAXParser().parse(stream,h);Guide g=new Guide(System.currentTimeMillis(),h.byChannel);GUIDES.put(key,g);return g;}
            }finally{c.disconnect();}
        }
    }
    static final class Handler extends DefaultHandler{
        final String id,name,preferred;final int limit;String current="",start="",stop="",field="",fieldLang="";StringBuilder chars=new StringBuilder();List<EpgEntry>rows=new ArrayList<>();boolean collectAll=false;Map<String,List<EpgEntry>>byChannel=new HashMap<>();LinkedHashMap<String,String>titles=new LinkedHashMap<>(),descs=new LinkedHashMap<>();long now=System.currentTimeMillis()/1000L;
        Handler(String i,String n,int l,String p){id=i==null?"":i;name=n==null?"":n;limit=l;preferred=base(p);}
        public void startElement(String u,String l,String q,Attributes a){if("programme".equals(q)){current=a.getValue("channel");start=a.getValue("start");stop=a.getValue("stop");titles.clear();descs.clear();}else if("title".equals(q)||"desc".equals(q)){field=q;fieldLang=base(a.getValue("lang"));chars.setLength(0);}}
        public void characters(char[]ch,int s,int n){if(!field.isEmpty())chars.append(ch,s,n);}
        public void endElement(String u,String l,String q){if(q.equals(field)){String v=chars.toString().trim();if(!v.isEmpty())("title".equals(field)?titles:descs).put(fieldLang,v);field="";}else if("programme".equals(q)&&(collectAll||(matches()&&rows.size()<limit))){long en=parse(stop),st=parse(start);List<EpgEntry> target=rows;if(collectAll){if(current==null||current.isEmpty()||(st>0&&st>now+WINDOW_SEC))return;target=byChannel.computeIfAbsent(current.toLowerCase(Locale.ROOT),k->new ArrayList<>());if(target.size()>=limit)return;}if(en==0||en>=now){EpgEntry e=new EpgEntry();e.startRaw=start==null?"":start;e.endRaw=stop==null?"":stop;e.startEpoch=parse(start);e.endEpoch=parse(stop);e.title=pick(titles);if(e.title.isEmpty())e.title="Programme";e.description=pick(descs);target.add(e);}}}
        boolean matches(){if(!id.isEmpty()&&id.equalsIgnoreCase(current))return true;return !name.isEmpty()&&name.equalsIgnoreCase(current);}
        String pick(LinkedHashMap<String,String>m){if(m.isEmpty())return "";String x=m.get(preferred);if(x!=null)return x;x=m.get("");if(x!=null)return x;return m.values().iterator().next();}
        static String base(String x){if(x==null)return "";String z=x.trim().toLowerCase(Locale.ROOT).replace('_','-');int i=z.indexOf('-');return i>0?z.substring(0,i):z;}
        long parse(String s){try{if(s==null||s.length()<14)return 0;String d=s.substring(0,14);LocalDateTime t=LocalDateTime.parse(d,DateTimeFormatter.ofPattern("yyyyMMddHHmmss"));ZoneOffset off=ZoneOffset.UTC;if(s.length()>=20){String z=s.substring(15,20).replace(" ","");if(z.matches("[+-]\\d{4}"))off=ZoneOffset.of(z.substring(0,3)+":"+z.substring(3));}return t.toEpochSecond(off);}catch(Exception e){return 0;}}
    }
}
