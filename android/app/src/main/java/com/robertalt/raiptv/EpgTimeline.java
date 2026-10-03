package com.nenotv.player;
import com.nenotv.player.model.EpgEntry;
import java.util.*;
public final class EpgTimeline {
    private EpgTimeline(){}
    public static List<EpgEntry> normalize(List<EpgEntry> rows){
        ArrayList<EpgEntry> out=new ArrayList<>();Set<String> seen=new HashSet<>();
        if(rows!=null)for(EpgEntry e:rows){
            // Missing or reversed timestamps cannot be positioned truthfully on a timeline.
            if(e==null||e.startEpoch<=0||e.endEpoch<=e.startEpoch)continue;
            String k=e.startEpoch+":"+e.endEpoch+":"+e.title;if(seen.add(k))out.add(e);
        }
        out.sort(Comparator.comparingLong(e->e.startEpoch));return out;
    }
    public static EpgEntry now(List<EpgEntry> rows,long epoch){for(EpgEntry e:rows)if(e.startEpoch<=epoch&&epoch<e.endEpoch)return e;return null;}
    public static EpgEntry next(List<EpgEntry> rows,long epoch){for(EpgEntry e:rows)if(e.startEpoch>epoch)return e;return null;}
}
