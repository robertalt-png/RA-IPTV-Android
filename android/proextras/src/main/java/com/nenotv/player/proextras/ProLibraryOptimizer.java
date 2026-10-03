package com.nenotv.player.proextras;

import com.nenotv.player.ContentLanguage;
import com.nenotv.player.model.MediaEntry;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * Pro-only ordering policy. The base app owns loading; this class only reorders the
 * already loaded rows, so Light startup/import performance is unchanged.
 */
public final class ProLibraryOptimizer {
    private ProLibraryOptimizer(){}

    public static List<MediaEntry> optimize(List<MediaEntry> input,String section,String preferredLanguage){
        ArrayList<MediaEntry> out=new ArrayList<>(input==null?Collections.emptyList():input);
        final String preferred=ContentLanguage.preferenceCode(preferredLanguage);
        // List.sort is stable: provider/source order is retained within the same language rank.
        out.sort(Comparator.comparingInt(e->ContentLanguage.rank(e,preferred)));
        return out;
    }
}
