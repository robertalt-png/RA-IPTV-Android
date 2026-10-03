package com.nenotv.player.provider;
import com.nenotv.player.model.*; import java.util.*;
public interface Provider {
    void authenticate() throws Exception;
    List<Category> categories(String type) throws Exception;
    List<MediaEntry> items(String type,String categoryId) throws Exception;
    default List<MediaEntry> seriesEpisodes(MediaEntry series) throws Exception { return Collections.emptyList(); }
    default List<EpgEntry> epgEntries(MediaEntry item,int limit) throws Exception { return Collections.emptyList(); }
    default MediaDetails details(MediaEntry item) throws Exception {
        MediaDetails d=new MediaDetails(); if(item==null)return d; d.title=item.name; d.year=item.year; d.plot=item.plot; d.rating=item.rating; d.imdbId=item.imdbId; d.tmdbId=item.tmdbId; d.backdrop=item.backdrop; d.poster=item.logo; return d;
    }
    default String epg(MediaEntry item) throws Exception {
        List<EpgEntry>x=epgEntries(item,5);if(x.isEmpty())return "Geen EPG beschikbaar.";
        StringBuilder s=new StringBuilder();for(EpgEntry e:x){if(s.length()>0)s.append("\n\n");String r=e.range();if(!r.isEmpty())s.append(r).append(" · ");s.append(e.title);if(e.description!=null&&!e.description.isEmpty())s.append("\n").append(e.description);}return s.toString();
    }
}
