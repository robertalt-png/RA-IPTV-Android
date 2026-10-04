package com.nenotv.player.storage;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Base64;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Arrays;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/** A cache data key wrapped by Android Keystore avoids a Keystore round trip per index row. */
final class CachePayloadCipher {
    private static final Object KEY_LOCK=new Object();
    private static final String PREFIX="cache1:";
    private static final int MAX_BYTES=8*1024*1024;
    private final SecretKeySpec key;
    private final byte[] aad;

    CachePayloadCipher(Context context,String database){
        aad=("NenoTV cache payload v1|"+database).getBytes(StandardCharsets.UTF_8);
        byte[] raw;
        synchronized(KEY_LOCK){
            SharedPreferences prefs=context.getSharedPreferences("nenotv_cache_keys",Context.MODE_PRIVATE);
            String name=StoredMediaKey.of(database);CryptoBox wrapper=new CryptoBox();
            if(prefs.contains(name)){
                String decoded=wrapper.decrypt(prefs.getString(name,""));
                try{raw=Base64.decode(decoded,Base64.NO_WRAP);}catch(Exception bad){throw new IllegalStateException("CACHE_KEY_UNAVAILABLE");}
                if(raw.length!=32)throw new IllegalStateException("CACHE_KEY_UNAVAILABLE");
            }else{
                raw=new byte[32];new SecureRandom().nextBytes(raw);
                String wrapped=wrapper.encrypt(Base64.encodeToString(raw,Base64.NO_WRAP));
                if(!prefs.edit().putString(name,wrapped).commit())throw new IllegalStateException("CACHE_KEY_WRITE_FAILED");
            }
        }
        key=new SecretKeySpec(raw,"AES");Arrays.fill(raw,(byte)0);
    }
    static boolean encrypted(String value){return value!=null&&value.startsWith(PREFIX);}
    String encrypt(String value)throws Exception{
        byte[] plain=value.getBytes(StandardCharsets.UTF_8);
        if(plain.length>MAX_BYTES)throw new IllegalStateException("CACHE_PAYLOAD_TOO_LARGE");
        Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding");cipher.init(Cipher.ENCRYPT_MODE,key);cipher.updateAAD(aad);
        byte[] iv=cipher.getIV(),encrypted=cipher.doFinal(plain),frame=new byte[iv.length+encrypted.length];
        if(iv.length!=12)throw new IllegalStateException("CACHE_NONCE_INVALID");
        System.arraycopy(iv,0,frame,0,iv.length);System.arraycopy(encrypted,0,frame,iv.length,encrypted.length);
        return PREFIX+Base64.encodeToString(frame,Base64.NO_WRAP);
    }
    String decrypt(String value)throws Exception{
        if(!encrypted(value)||value.length()>((MAX_BYTES+28L)*4/3)+PREFIX.length()+8)throw new IllegalStateException("CACHE_PAYLOAD_INVALID");
        byte[] frame=Base64.decode(value.substring(PREFIX.length()),Base64.NO_WRAP);
        if(frame.length<28||frame.length>MAX_BYTES+28)throw new IllegalStateException("CACHE_PAYLOAD_INVALID");
        Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE,key,new GCMParameterSpec(128,frame,0,12));cipher.updateAAD(aad);
        return new String(cipher.doFinal(frame,12,frame.length-12),StandardCharsets.UTF_8);
    }
}

