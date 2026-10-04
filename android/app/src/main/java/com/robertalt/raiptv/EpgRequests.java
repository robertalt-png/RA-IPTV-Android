package com.nenotv.player;

import com.nenotv.player.model.MediaEntry;
import com.nenotv.player.model.EpgEntry;
import com.nenotv.player.provider.Provider;
import com.nenotv.player.provider.SourceProviderResolver;
import com.nenotv.player.storage.EpgStore;
import java.util.List;
import java.util.function.BooleanSupplier;

/** Immutable source context shared by the list, grid and guide views. */
public final class EpgRequests {
    public static final class Cache {
        private static final class Snapshot {
            final List<EpgEntry> rows;final long expires;
            Snapshot(List<EpgEntry> rows,long expires){this.rows=java.util.Collections.unmodifiableList(new java.util.ArrayList<>(rows.subList(0,Math.min(50,rows.size()))));this.expires=expires;}
        }
        private final int limit;private final long ttl,failureTtl;private final java.util.function.LongSupplier clock;
        private final java.util.LinkedHashMap<String,Snapshot> entries=new java.util.LinkedHashMap<>(16,0.75f,true);
        public Cache(){this(128,EpgStore.TTL_MS,30000,android.os.SystemClock::elapsedRealtime);}
        Cache(int limit,long ttl,long failureTtl,java.util.function.LongSupplier clock){
            if(limit<1||ttl<1||failureTtl<1)throw new IllegalArgumentException("EPG_CACHE_LIMIT");
            this.limit=limit;this.ttl=ttl;this.failureTtl=failureTtl;this.clock=clock;
        }
        public synchronized List<EpgEntry> get(String key){Snapshot value=entries.get(key);if(value==null)return null;if(clock.getAsLong()>=value.expires){entries.remove(key);return null;}return value.rows;}
        public synchronized void put(String key,List<EpgEntry> rows){save(key,rows,ttl);}
        public synchronized void failed(String key){save(key,java.util.Collections.emptyList(),failureTtl);}
        private void save(String key,List<EpgEntry> rows,long ttl){entries.put(key,new Snapshot(rows,clock.getAsLong()+ttl));while(entries.size()>limit)entries.remove(entries.keySet().iterator().next());}
        public synchronized void clear(){entries.clear();}
    }
    private final EpgStore store;
    private final Provider primary;
    private final String profile;
    private final String language;
    private final SourceProviderResolver resolver;
    private final BooleanSupplier secondaryAllowed;

    public EpgRequests(EpgStore store,Provider primary,String profile,SourceProviderResolver resolver,BooleanSupplier secondaryAllowed){
        this.store=store;this.primary=primary;this.profile=profile==null?"":profile;
        this.language=store.language();
        this.resolver=resolver;this.secondaryAllowed=secondaryAllowed;
    }
    private String profile(MediaEntry item){return profile+"|epg_lang:"+language+(item.sourceId==null||item.sourceId.isEmpty()?"":"|"+item.sourceId);}
    public String key(MediaEntry item){return profile(item)+"|"+item.uniqueKey();}
    public String text(MediaEntry item)throws Exception{
        StringBuilder out=new StringBuilder();
        for(EpgEntry entry:load(item)){
            if(out.length()>0)out.append("\n\n");String range=entry.range();
            if(!range.isEmpty())out.append(range).append("\n");out.append(entry.title);
            if(entry.description!=null&&!entry.description.isEmpty())out.append("\n").append(entry.description);
        }
        return out.toString();
    }
    public List<EpgEntry> load(MediaEntry item)throws Exception{
        if(store.isClosed())throw new java.io.IOException("EPG_STORE_CLOSED");
        Provider provider=resolver.resolve(item,primary,secondaryAllowed.getAsBoolean(),language);
        String namespace=resolver.namespace(item,provider,secondaryAllowed.getAsBoolean(),language);
        List<EpgEntry> rows=store.getOrFetch(provider,profile(item)+"|"+namespace,item,language);
        if(!namespace.equals(resolver.namespace(item,provider,secondaryAllowed.getAsBoolean(),language)))throw new java.io.IOException("SOURCE_CHANGED");
        return rows;
    }
}
