package com.nenotv.player.storage;

import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;

public final class ParentPinCodec {
    private ParentPinCodec(){}
    private static byte[] derive(String pin,byte[] salt)throws Exception{
        PBEKeySpec spec=new PBEKeySpec(pin.toCharArray(),salt,120000,256);
        try{return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded();}
        finally{spec.clearPassword();}
    }
    public static String encode(String pin){
        if(pin==null||!pin.matches("[0-9]{4,8}"))throw new IllegalArgumentException("Invalid PIN");
        try{byte[] salt=new byte[16];new SecureRandom().nextBytes(salt);return "pbkdf2-v1:"+Base64.getEncoder().encodeToString(salt)+":"+Base64.getEncoder().encodeToString(derive(pin,salt));}
        catch(Exception failure){throw new IllegalStateException("PIN storage unavailable",failure);}
    }
    public static boolean verify(String pin,String encoded){
        if(pin==null||!pin.matches("[0-9]{4,8}")||encoded==null)return false;
        try{String[] fields=encoded.split(":",-1);if(fields.length!=3||!"pbkdf2-v1".equals(fields[0]))return false;byte[] salt=Base64.getDecoder().decode(fields[1]),expected=Base64.getDecoder().decode(fields[2]);return salt.length==16&&expected.length==32&&MessageDigest.isEqual(expected,derive(pin,salt));}
        catch(Exception invalid){return false;}
    }
}
