package com.robertalt.raiptv.storage;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Base64;
import com.robertalt.raiptv.UiText;
import org.json.JSONObject;
import java.security.SecureRandom;
import java.util.Locale;
import java.util.UUID;

public final class EntitlementStore {
    public enum Level { FREE, PRO_TRIAL, PRO }
    private final SharedPreferences prefs;

    public EntitlementStore(Context c){
        prefs=c.getApplicationContext().getSharedPreferences("nenotv_entitlement",Context.MODE_PRIVATE);
        ensureDeviceIdentity();
    }

    private void ensureDeviceIdentity(){
        if(!prefs.contains("device_id")){
            String uuid=UUID.randomUUID().toString();
            String compact=uuid.replace("-","").toUpperCase(Locale.ROOT);
            String pub="NT-"+compact.substring(0,4)+"-"+compact.substring(4,8);
            byte[] key=new byte[32];
            new SecureRandom().nextBytes(key);
            String secret=Base64.encodeToString(key,Base64.NO_WRAP|Base64.URL_SAFE|Base64.NO_PADDING);
            prefs.edit().putString("device_id",uuid).putString("public_device_id",pub).putString("device_key",secret).apply();
        }
    }

    public String deviceId(){return prefs.getString("device_id","");}
    public String publicDeviceId(){return prefs.getString("public_device_id","");}
    public String deviceKey(){return prefs.getString("device_key","");}
    public String accountEmail(){return prefs.getString("account_email","");}
    public int maxDevices(){return prefs.getInt("max_devices",1);}
    public long expiresAt(){return prefs.getLong("expires_at",0L);}

    public Level level(){
        Level value=parse(prefs.getString("level","FREE"));
        if(value==Level.PRO_TRIAL){
            long end=expiresAt();
            if(end>0&&System.currentTimeMillis()>=end){
                prefs.edit().putString("level","FREE").putLong("expires_at",0L).apply();
                return Level.FREE;
            }
        }
        return value;
    }

    private Level parse(String s){
        try{return Level.valueOf(s==null?"FREE":s.toUpperCase(Locale.ROOT));}
        catch(Exception e){return Level.FREE;}
    }

    public boolean isPro(){Level v=level();return v==Level.PRO||v==Level.PRO_TRIAL;}
    public boolean isTrial(){return level()==Level.PRO_TRIAL;}
    public long trialDaysRemaining(){
        if(!isTrial())return 0;
        long left=Math.max(0,expiresAt()-System.currentTimeMillis());
        return Math.max(1,(left+86399999L)/86400000L);
    }
    public String shortBadge(){Level v=level();return v==Level.PRO?"PRO":v==Level.PRO_TRIAL?"TRIAL":"FREE";}
    public String statusLabel(Context c){
        Level v=level();
        return v==Level.PRO?UiText.t(c,"nenotv_pro"):v==Level.PRO_TRIAL?UiText.t(c,"nenotv_pro_trial"):UiText.t(c,"nenotv_free");
    }

    public void applyServer(JSONObject o){
        if(o==null)return;
        String status=o.optString("status",o.optString("level","free")).trim().toLowerCase(Locale.ROOT);
        Level level="pro".equals(status)?Level.PRO:(("pro_trial".equals(status)||"trial".equals(status))?Level.PRO_TRIAL:Level.FREE);
        long expiry=readEpoch(o,"expires_at_ms",1L);
        if(expiry<=0)expiry=readEpoch(o,"expires_at",1000L);
        SharedPreferences.Editor ed=prefs.edit().putString("level",level.name()).putLong("expires_at",expiry);
        if(o.has("email"))ed.putString("account_email",o.optString("email",""));
        if(o.has("account_email"))ed.putString("account_email",o.optString("account_email",""));
        if(o.has("max_devices"))ed.putInt("max_devices",Math.max(1,o.optInt("max_devices",1)));
        ed.apply();
    }

    private long readEpoch(JSONObject o,String key,long multiplier){
        if(!o.has(key))return 0;
        Object v=o.opt(key);
        try{
            long n=v instanceof Number?((Number)v).longValue():Long.parseLong(String.valueOf(v));
            if(n<=0)return 0;
            if(multiplier==1000L&&n>100000000000L)return n;
            return n*multiplier;
        }catch(Exception e){return 0;}
    }
}
