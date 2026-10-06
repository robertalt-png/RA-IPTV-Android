package com.nenotv.player.provider;
import com.nenotv.player.model.*; import java.util.*;
public interface Provider {
    void authenticate() throws Exception;
    List<Category> categories(String type) throws Exception;
    List<MediaEntry> items(String type,String categoryId) throws Exception;
    default List<MediaEntry> seriesEpisodes(MediaEntry series) throws Exception { return Collections.emptyList(); }
    default List<EpgEntry> epgEntries(MediaEntry item,int limit) throws Exception { return Collections.emptyList(); }
    /** Address of the complete XMLTV guide of this source for the G1 guide database, or "" when there is none. */
    default String guideUrl() { return ""; }
    /** G3: addresses that replay a past programme of this live channel, best first; empty when the source has no catch-up. */
    default List<String> catchupUrls(MediaEntry channel,long startEpoch,long endEpoch) throws Exception {
        if(channel==null||!channel.catchup)return Collections.emptyList();
        String live=channel.url!=null&&!channel.url.isEmpty()?channel.url:(channel.candidates.isEmpty()?"":channel.candidates.get(0));
        String[] x=com.nenotv.player.core.CatchupUrls.xtreamParts(live);if(x==null)return Collections.emptyList();
        return com.nenotv.player.core.CatchupUrls.xtream(x[0],x[1],x[2],x[3],startEpoch,endEpoch,XtreamServerZone.get(x[0],x[1],x[2]));
    }
    default MediaDetails details(MediaEntry item) throws Exception {
        MediaDetails d=new MediaDetails(); if(item==null)return d; d.title=item.name; d.year=item.year; d.plot=item.plot; d.rating=item.rating; d.imdbId=item.imdbId; d.tmdbId=item.tmdbId; d.backdrop=item.backdrop; d.poster=item.logo; return d;
    }
    default String epg(MediaEntry item) throws Exception {
        List<EpgEntry>x=epgEntries(item,5);if(x.isEmpty())return "Geen EPG beschikbaar.";
        StringBuilder s=new StringBuilder();for(EpgEntry e:x){if(s.length()>0)s.append("\n\n");String r=e.range();if(!r.isEmpty())s.append(r).append(" · ");s.append(e.title);if(e.description!=null&&!e.description.isEmpty())s.append("\n").append(e.description);}return s.toString();
    }
}
