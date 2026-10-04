package com.nenotv.player.provider;

import android.content.Context;
import com.nenotv.player.model.MediaEntry;
import com.nenotv.player.model.Profile;
import com.nenotv.player.storage.SecureProfileStore;
import com.nenotv.player.storage.SourceStore;
import java.io.IOException;

/** Local source snapshot; resolving playback never imports or authenticates a playlist. */
public final class PlaybackSourceRoute {
    private final Context app;
    private final String sourceId, signature;
    private final Profile profile;
    private java.util.Map<String,?> verifiedStorage;

    private PlaybackSourceRoute(Context context,String id,Profile p,java.util.Map<String,?> storage)throws Exception{
        app=context.getApplicationContext();sourceId=id;profile=p;
        signature=SourceProviderResolver.profileNamespace(p);
        verifiedStorage=storage;
    }
    private static java.util.Map<String,?> storage(Context context,String id){
        return new java.util.HashMap<>(context.getSharedPreferences(id.isEmpty()?"profile":"nenotv_sources_v1",Context.MODE_PRIVATE).getAll());
    }
    private static Profile read(Context context,String id,boolean allowSecondary)throws IOException{
        if(id.isEmpty()){
            SecureProfileStore primary=new SecureProfileStore(context);
            if(primary.exists())return primary.load();
        }else if(allowSecondary){
            for(SourceStore.Entry source:new SourceStore(context).list())
                if(id.equals(source.id)&&source.enabled)return source.profile;
        }
        throw new IOException("SOURCE_UNAVAILABLE");
    }
    public static PlaybackSourceRoute resolve(Context context,MediaEntry item,boolean allowSecondary)throws Exception{
        if(item==null)throw new IOException("SOURCE_UNAVAILABLE");
        String id=item.sourceId==null?"":item.sourceId;
        java.util.Map<String,?> before=storage(context,id);
        Profile p=read(context,id,allowSecondary);
        if(!before.equals(storage(context,id)))throw new IOException("SOURCE_CHANGED");
        return new PlaybackSourceRoute(context,id,p,before);
    }
    public synchronized boolean isCurrent(boolean allowSecondary){
        if(!sourceId.isEmpty()&&!allowSecondary)return false;
        try{
            java.util.Map<String,?> before=storage(app,sourceId);
            if(before.equals(verifiedStorage))return true;
            if(!signature.equals(SourceProviderResolver.profileNamespace(read(app,sourceId,allowSecondary))))return false;
            if(!before.equals(storage(app,sourceId)))return false;
            verifiedStorage=before;return true;
        }
        catch(Exception unavailable){return false;}
    }
    public Profile profile(){
        Profile p=new Profile();p.type=profile.type;p.name=profile.name;p.server=profile.server;
        p.username=profile.username;p.password=profile.password;p.m3uUrl=profile.m3uUrl;
        p.epgUrl=profile.epgUrl;p.bridgeUrl=profile.bridgeUrl;p.bridgeToken=profile.bridgeToken;
        return p;
    }
}

