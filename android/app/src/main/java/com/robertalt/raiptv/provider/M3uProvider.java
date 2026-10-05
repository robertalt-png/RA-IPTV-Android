package com.nenotv.player.provider;
import com.nenotv.player.core.M3uParser; import com.nenotv.player.core.XmltvGuide; import com.nenotv.player.model.*; import com.nenotv.player.net.HttpText; import java.util.*;
public class M3uProvider implements Provider {
    private final Profile p; private final String language; private List<MediaEntry> all=new ArrayList<>();
    public M3uProvider(Profile p){this(p,java.util.Locale.getDefault().getLanguage());}
    public M3uProvider(Profile p,String language){this.p=p;this.language=language==null?"en":language;}
    @Override public void authenticate() throws Exception { M3uParser.Result r=M3uParser.parse(HttpText.get(p.m3uUrl));all=new ArrayList<>(r.items);if(com.nenotv.player.DemoSource.URL.equals(p.m3uUrl)||com.nenotv.player.SiteEndpoints.isDemoUrl(p.m3uUrl))for(MediaEntry e:all)com.nenotv.player.DemoSource.decorate(e);if(p.epgUrl.isEmpty()&&!r.epgUrl.isEmpty())p.epgUrl=r.epgUrl;if(all.isEmpty())throw new Exception("EMPTY_PLAYLIST"); }
    @Override public List<Category> categories(String type){ LinkedHashSet<String>s=new LinkedHashSet<>();for(MediaEntry e:all)if(type.equals(e.type))s.add(e.group);List<Category>o=new ArrayList<>();for(String g:s)o.add(new Category(g,g,type));return o; }
    @Override public List<MediaEntry> items(String type,String cat){ List<MediaEntry>o=new ArrayList<>();for(MediaEntry e:all)if(type.equals(e.type)&&(cat==null||cat.isEmpty()||cat.equals("all")||cat.equals(e.group)))o.add(e);return o; }
    @Override public List<EpgEntry> epgEntries(MediaEntry item,int limit)throws Exception {if(p.epgUrl==null||p.epgUrl.isEmpty())return Collections.emptyList();return XmltvGuide.lookupEntries(p.epgUrl,item.tvgId,item.tvgName,limit,language);}
    @Override public String epg(MediaEntry item)throws Exception {return Provider.super.epg(item);}
}
