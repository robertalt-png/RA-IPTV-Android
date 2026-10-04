package com.nenotv.player.provider;

import android.content.Context;
import com.nenotv.player.model.MediaEntry;
import com.nenotv.player.model.Profile;
import com.nenotv.player.storage.SourceStore;
import java.io.IOException;
import java.util.HashMap;
import java.util.Objects;

public final class SourceProviderResolver {
    public interface Factory { Provider create(Profile profile); }
    private static final class Cached {
        final Profile profile;final Provider provider;
        Cached(Profile profile,Provider provider){this.profile=profile;this.provider=provider;}
    }
    private final SourceStore sources;
    private final Factory factory;
    private final HashMap<String,Cached> cache=new HashMap<>();

    public SourceProviderResolver(Context context,Factory factory){sources=new SourceStore(context);this.factory=factory;}

    private SourceStore.Entry enabled(String id)throws IOException{
        for(SourceStore.Entry entry:sources.list())if(id.equals(entry.id)&&entry.enabled)return entry;
        cache.remove(id);throw new IOException("SOURCE_UNAVAILABLE");
    }

    private static boolean same(Profile a,Profile b){
        return a.type==b.type&&Objects.equals(a.server,b.server)&&Objects.equals(a.username,b.username)
            &&Objects.equals(a.password,b.password)&&Objects.equals(a.m3uUrl,b.m3uUrl)
            &&Objects.equals(a.epgUrl,b.epgUrl)&&Objects.equals(a.bridgeUrl,b.bridgeUrl)
            &&Objects.equals(a.bridgeToken,b.bridgeToken);
    }

    public synchronized Provider resolve(MediaEntry item,Provider primary,boolean allowSecondary)throws Exception{
        String id=item==null?"":item.sourceId;
        if(id==null||id.isEmpty())return primary;
        SourceStore.Entry entry=enabled(id);
        if(!allowSecondary)throw new IOException("SOURCE_UNAVAILABLE");
        Cached hit=cache.get(id);
        if(hit!=null&&same(hit.profile,entry.profile))return hit.provider;
        cache.remove(id);
        Provider provider=factory.create(entry.profile);provider.authenticate();
        // A source can be changed or revoked while authentication is in flight.
        SourceStore.Entry current=enabled(id);
        if(!same(entry.profile,current.profile))throw new IOException("SOURCE_CHANGED");
        if(cache.size()>=20)cache.clear();
        cache.put(id,new Cached(entry.profile,provider));
        return provider;
    }
}
