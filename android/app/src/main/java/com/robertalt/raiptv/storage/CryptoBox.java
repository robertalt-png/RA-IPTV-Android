package com.nenotv.player.storage;

import android.security.keystore.*; import android.util.Base64; import java.nio.charset.StandardCharsets; import java.security.KeyStore; import javax.crypto.*; import javax.crypto.spec.GCMParameterSpec;

public final class CryptoBox {
    private static final String ALIAS="ra_iptv_app_key";
    public CryptoBox(){ensureKey();}
    private void ensureKey(){try{KeyStore ks=KeyStore.getInstance("AndroidKeyStore");ks.load(null);if(!ks.containsAlias(ALIAS)){KeyGenerator g=KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES,"AndroidKeyStore");g.init(new KeyGenParameterSpec.Builder(ALIAS,KeyProperties.PURPOSE_ENCRYPT|KeyProperties.PURPOSE_DECRYPT).setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build());g.generateKey();}}catch(Exception e){throw new IllegalStateException(e);}}
    public String encrypt(String s){try{KeyStore ks=KeyStore.getInstance("AndroidKeyStore");ks.load(null);SecretKey k=(SecretKey)ks.getKey(ALIAS,null);Cipher c=Cipher.getInstance("AES/GCM/NoPadding");c.init(Cipher.ENCRYPT_MODE,k);byte[]out=c.doFinal((s==null?"":s).getBytes(StandardCharsets.UTF_8));return Base64.encodeToString(c.getIV(),Base64.NO_WRAP)+":"+Base64.encodeToString(out,Base64.NO_WRAP);}catch(Exception e){throw new IllegalStateException(e);}}
    public String decrypt(String s){if(s==null||s.isEmpty())return "";try{String[]p=s.split(":",2);KeyStore ks=KeyStore.getInstance("AndroidKeyStore");ks.load(null);SecretKey k=(SecretKey)ks.getKey(ALIAS,null);Cipher c=Cipher.getInstance("AES/GCM/NoPadding");c.init(Cipher.DECRYPT_MODE,k,new GCMParameterSpec(128,Base64.decode(p[0],Base64.NO_WRAP)));return new String(c.doFinal(Base64.decode(p[1],Base64.NO_WRAP)),StandardCharsets.UTF_8);}catch(Exception e){return "";}}
}
