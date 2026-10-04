package com.nenotv.player;

import android.content.Context;
import com.nenotv.player.model.MediaEntry;
import com.nenotv.player.storage.SearchIndexStore;
import java.util.Arrays;
import java.util.Collections;

final class XtreamImportChecks {
    static void check(boolean value,String message){if(!value)throw new AssertionError(message);}
    static MediaEntry item(String id,String type){MediaEntry e=new MediaEntry();e.id=id;e.type=type;e.name="NL | "+id;e.group="NL | Films";return e;}
    static void run(Context context)throws Exception{
        String profile="xtream-import-qa-"+System.nanoTime();
        try(SearchIndexStore store=new SearchIndexStore(context)){
            String session=store.beginSectionImport();
            MediaEntry first=item("first","vod"),second=item("second","vod");
            store.importBatch(session,profile,"vod",Collections.singletonList(first),true);
            check(store.countSection(profile,"vod")==1,"Partial import not immediately visible");
            check(store.importCount(session,profile,"vod")==1,"Partial import not durable");
            check(!store.isComplete(profile,"vod"),"Partial import marked complete");
            check(store.countLanguage(profile,"vod","nl")==1,"Language classification missing");
            store.checkpointImport(profile,"vod",session,"cat-1",1);
            check(session.equals(store.beginSectionImport(store.importProgress(profile,"vod").session)),"Resume replaced staging session");
            store.importBatch(session,profile,"vod",Arrays.asList(first,second),true);
            check(store.countSection(profile,"vod")==2,"Repeated batch duplicated visible titles");
            check(store.importCount(session,profile,"vod")==2,"Repeated batch duplicated staged titles");
            store.finishSectionImport(session,profile,"vod");
            check(store.isComplete(profile,"vod")&&store.countSection(profile,"vod")==2,"Completion lost partial titles");
            String refresh=store.beginSectionImport();
            store.importBatch(refresh,profile,"vod",Collections.singletonList(item("replacement","vod")),false);
            check(store.countSection(profile,"vod")==2,"Refresh replaced previous library early");
            store.abortSectionImport(refresh);
            check(store.countSection(profile,"vod")==2,"Failed refresh lost previous library");
            String invalid=store.beginSectionImport();
            boolean failed=false;
            try{store.importBatch(invalid,profile,"vod",Arrays.asList(item("new","vod"),item("wrong","live")),true);}catch(IllegalArgumentException expected){failed=true;}
            check(failed&&store.countSection(profile,"vod")==2&&store.importCount(invalid,profile,"vod")==0,"Invalid batch was partially committed");
            store.abortSectionImport(invalid);
            store.clearImportProgress(profile,"vod");
            MediaEntry a=item("same-id","vod"),b=item("same-id","vod"),primary=item("same-id","vod");
            a.sourceId="source-a";b.sourceId="source-b";
            String multiple=store.beginSectionImport();
            store.importBatch(multiple,profile,"vod",Arrays.asList(primary,a,b),true);
            check(store.importCount(multiple,profile,"vod")==3,"Equal provider IDs overwrote staged sources");
            store.finishSectionImport(multiple,profile,"vod");
            java.util.List<MediaEntry> loaded=store.sectionPage(profile,"vod",0,10);
            java.util.Set<String> identities=new java.util.HashSet<>();for(MediaEntry e:loaded)identities.add(e.uniqueKey());
            check(loaded.size()==3&&identities.contains(primary.uniqueKey())&&identities.contains(a.uniqueKey())&&identities.contains(b.uniqueKey()),"Index lost equal provider IDs or source tags");
        }
    }
}
