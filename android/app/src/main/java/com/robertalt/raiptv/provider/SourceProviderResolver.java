package com.nenotv.player.provider;

import android.content.Context;
import com.nenotv.player.model.MediaEntry;
import com.nenotv.player.model.Profile;
import com.nenotv.player.storage.SourceStore;
import java.io.IOException;
import java.util.HashMap;
import java.util.Objects;

public final class SourceProviderResolver {
    public interface Factory { Provider create(Profile profile,String language); }
    private static final class Cached {
        final String id;final Profile profile;final Provider provider;
        Cached(String id,Profile profile,Provider provider){this.id=id;this.profile=profile;this.provider=provider;}
    }
    private final SourceStore sources;
    private final Context app;
    private final Factory factory;
    private final HashMap<String,Cached> cache=new HashMap<>();

    public SourceProviderResolver(Context context,Factory factory){app=context.getApplicationContext();sources=new SourceStore(context);this.factory=factory;}
    private static String key(String id,String language){return id.length()+":"+id+"|"+language;}

    private SourceStore.Entry enabled(String id)throws IOException{
        for(SourceStore.Entry entry:sources.list())if(id.equals(entry.id)&&entry.enabled)return entry;
        cache.entrySet().removeIf(entry->entry.getValue().id.equals(id));throw new IOException("SOURCE_UNAVAILABLE");
    }

    private static boolean same(Profile a,Profile b){
        return a.type==b.type&&Objects.equals(a.server,b.server)&&Objects.equals(a.username,b.username)
            &&Objects.equals(a.password,b.password)&&Objects.equals(a.m3uUrl,b.m3uUrl)
            &&Objects.equals(a.epgUrl,b.epgUrl)&&Objects.equals(a.bridgeUrl,b.bridgeUrl)
            &&Objects.equals(a.bridgeToken,b.bridgeToken);
    }

    public synchronized String namespace(MediaEntry item,Provider provider,boolean allowSecondary)throws Exception{
        return namespace(item,provider,allowSecondary,com.nenotv.player.storage.SettingsStore.primaryLanguage(app));
    }
    public synchronized String namespace(MediaEntry item,Provider provider,boolean allowSecondary,String language)throws Exception{
        String id=item==null?"":item.sourceId;
        if(id==null||id.isEmpty())return "";
        if(!allowSecondary)throw new IOException("SOURCE_UNAVAILABLE");
        SourceStore.Entry entry=enabled(id);Cached hit=cache.get(key(id,language));
        if(hit==null||hit.provider!=provider||!same(hit.profile,entry.profile))throw new IOException("SOURCE_CHANGED");
        return profileNamespace(entry.profile);
    }
    public static String profileNamespace(Profile p)throws Exception{
        org.json.JSONArray data=new org.json.JSONArray();
        for(String value:new String[]{p.type.name(),p.server,p.username,p.password,p.m3uUrl,p.epgUrl,p.bridgeUrl,p.bridgeToken})data.put(value);
        byte[] digest=java.security.MessageDigest.getInstance("SHA-256").digest(data.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
        StringBuilder out=new StringBuilder();for(byte b:digest)out.append(String.format(java.util.Locale.ROOT,"%02x",b&255));
        return out.toString();
    }

    public synchronized Provider resolve(MediaEntry item,Provider primary,boolean allowSecondary)throws Exception{
        return resolve(item,primary,allowSecondary,com.nenotv.player.storage.SettingsStore.primaryLanguage(app));
    }
    public synchronized Provider resolve(MediaEntry item,Provider primary,boolean allowSecondary,String language)throws Exception{
        String id=item==null?"":item.sourceId;
        if(id==null||id.isEmpty())return primary;
        SourceStore.Entry entry=enabled(id);
        if(!allowSecondary)throw new IOException("SOURCE_UNAVAILABLE");
        String key=key(id,language);Cached hit=cache.get(key);
        if(hit!=null&&same(hit.profile,entry.profile))return hit.provider;
        cache.remove(key);
        Provider provider=factory.create(entry.profile,language);provider.authenticate();
        // A source can be changed or revoked while authentication is in flight.
        SourceStore.Entry current=enabled(id);
        if(!same(entry.profile,current.profile))throw new IOException("SOURCE_CHANGED");
        if(cache.size()>=20)cache.clear();
        cache.put(key,new Cached(id,entry.profile,provider));
        return provider;
    }
}
