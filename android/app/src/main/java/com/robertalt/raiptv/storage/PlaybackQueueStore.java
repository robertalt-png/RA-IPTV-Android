package com.nenotv.player.storage;

import com.nenotv.player.model.MediaEntry;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;

public final class PlaybackQueueStore {
    private PlaybackQueueStore(){}
    public static final class Payload {
        public final String kind;
        public final ArrayList<MediaEntry> items;
        public final int index;
        Payload(String kind,List<MediaEntry> items,int index){
            this.kind=kind==null?"":kind;
            this.items=new ArrayList<>(items==null?Collections.emptyList():items);
            this.index=index;
        }
    }
    private static final AtomicLong NEXT=new AtomicLong();
    private static final LinkedHashMap<String,Payload> CACHE=new LinkedHashMap<String,Payload>(8,0.75f,true){
        @Override protected boolean removeEldestEntry(Map.Entry<String,Payload> e){return size()>4;}
    };
    public static synchronized String put(String kind,List<MediaEntry> items,int index){
        String token=Long.toHexString(System.nanoTime())+"-"+Long.toHexString(NEXT.incrementAndGet());
        CACHE.put(token,new Payload(kind,items,index));
        return token;
    }
    public static synchronized Payload take(String token){
        if(token==null||token.isEmpty())return null;
        return CACHE.remove(token);
    }
}
