package com.nenotv.player.storage;

import com.nenotv.player.model.MediaEntry;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

public final class StoredMediaKey {
    private StoredMediaKey(){}
    public static String of(MediaEntry item){return of(item.uniqueKey());}
    public static String of(String identity){
        try{
            byte[] digest=MessageDigest.getInstance("SHA-256").digest(identity.getBytes(StandardCharsets.UTF_8));
            char[] hex="0123456789abcdef".toCharArray(),out=new char[digest.length*2];
            for(int i=0;i<digest.length;i++){out[i*2]=hex[(digest[i]&255)>>>4];out[i*2+1]=hex[digest[i]&15];}
            return "media2:"+new String(out);
        }catch(java.security.NoSuchAlgorithmException impossible){throw new IllegalStateException(impossible);}
    }
}

