package com.nenotv.player;
import com.nenotv.player.model.MediaEntry;
import com.nenotv.player.storage.LibraryStore;
import java.util.*;
public final class EpisodeOrder {
    private EpisodeOrder(){}
    public static List<MediaEntry> sorted(List<MediaEntry> entries){
        List<MediaEntry> out=new ArrayList<>();if(entries!=null)for(MediaEntry e:entries)if(e!=null)out.add(e);
        out.sort(Comparator.comparingInt((MediaEntry e)->e.season).thenComparingInt(e->e.episode).thenComparing(e->e.name==null?"":e.name,String.CASE_INSENSITIVE_ORDER));return out;
    }
    public static MediaEntry next(List<MediaEntry> entries,LibraryStore store){
        List<MediaEntry> sorted=sorted(entries);if(sorted.isEmpty())return null;
        // A recently opened episode stays selected until it is actually completed.
        for(MediaEntry recent:store.recent())for(int i=0;i<sorted.size();i++){
            MediaEntry e=sorted.get(i);if(!recent.uniqueKey().equals(e.uniqueKey()))continue;
            if(!store.watched(e))return e;
            for(int j=i+1;j<sorted.size();j++)if(!store.watched(sorted.get(j)))return sorted.get(j);
            break;
        }
        MediaEntry partial=null;long newest=-1;
        for(MediaEntry e:sorted)if(store.progress(e)>0&&store.progressUpdatedAt(e)>newest){partial=e;newest=store.progressUpdatedAt(e);}
        if(partial!=null)return partial;
        for(MediaEntry e:sorted)if(!store.watched(e))return e;
        return null;
    }
}
