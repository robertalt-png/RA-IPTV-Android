package com.nenotv.player.storage;

import android.content.Context;
import android.content.SharedPreferences;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import org.json.JSONObject;

/** Device-bound account identity, deliberately separate from paid access. */
public final class AccountLinkStore {
    private final SharedPreferences prefs;
    private final EntitlementStore device;
    private final Context context;
    public AccountLinkStore(Context context){this.context=context.getApplicationContext();prefs=context.getSharedPreferences("nenotv_account_link_v1",Context.MODE_PRIVATE);device=new EntitlementStore(context);}
    private String binding(){
        try{byte[] bytes=MessageDigest.getInstance("SHA-256").digest((device.deviceId()+":"+device.deviceKey()).getBytes(StandardCharsets.UTF_8));StringBuilder hex=new StringBuilder();for(byte b:bytes)hex.append(String.format(java.util.Locale.ROOT,"%02x",b&255));return hex.toString();}
        catch(Exception e){throw new IllegalStateException("Device identity unavailable",e);}
    }
    public static boolean valid(JSONObject link){return link!=null&&"active".equals(link.optString("status"))&&java.util.Arrays.asList("free","paid","account").contains(link.optString("kind"))&&link.optString("account_id").matches("[a-f0-9]{64}");}
    public void apply(JSONObject link)throws IOException{
        if(!valid(link))throw new IOException("Invalid account link");
        String old=prefs.getString("account_id","");
        if(!old.isEmpty()&&!old.equals(link.optString("account_id")))ExtraPrivacyStore.clear(context);
        if(!prefs.edit().putString("account_id",link.optString("account_id")).putString("binding",binding()).putLong("checked_at",System.currentTimeMillis()).commit())throw new IOException("Account link could not be saved");
    }
    public boolean linked(){return prefs.getString("account_id","").matches("[a-f0-9]{64}")&&binding().equals(prefs.getString("binding",""));}
    public String accountId(){return linked()?prefs.getString("account_id",""):"";}
    public boolean recent(){long age=System.currentTimeMillis()-prefs.getLong("checked_at",0);return linked()&&age>=0&&age<15*60*1000L;}
    public void clear(){ExtraPrivacyStore.clear(context);prefs.edit().clear().commit();}
}
