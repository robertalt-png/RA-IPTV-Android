package com.robertalt.raiptv.storage;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.SystemClock;
import android.util.Base64;
import org.json.JSONObject;
import java.security.SecureRandom;
import java.util.Locale;
import java.util.UUID;

public final class EntitlementStore {
    public enum Level { TRIAL_REQUIRED, PRO_TRIAL, TRIAL_EXPIRED, PRO }
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
    public String serverStatus(){return prefs.getString("server_status","trial_required");}

    public Level level(){
        String raw=prefs.getString("level","TRIAL_REQUIRED");
        if(raw==null)return Level.TRIAL_REQUIRED;
        raw=raw.trim().toUpperCase(Locale.ROOT);
        if("FREE".equals(raw))return Level.TRIAL_REQUIRED;
        try{return Level.valueOf(raw);}catch(Exception e){return Level.TRIAL_REQUIRED;}
    }

    private long trustedNow(){
        long server=prefs.getLong("server_time_ms",0L);
        long elapsed=prefs.getLong("server_elapsed_ms",0L);
        long nowElapsed=SystemClock.elapsedRealtime();
        if(server<=0L||elapsed<=0L||nowElapsed<elapsed)return 0L;
        return server+(nowElapsed-elapsed);
    }

    public boolean needsServerValidation(){
        return level()==Level.PRO_TRIAL && trustedNow()<=0L;
    }

    public boolean trialExpiredByTrustedClock(){
        Level v=level();
        if(v==Level.TRIAL_EXPIRED)return true;
        if(v!=Level.PRO_TRIAL)return false;
        long end=expiresAt(),now=trustedNow();
        return end>0L&&now>0L&&now>=end;
    }

    public boolean isPaidPro(){return level()==Level.PRO;}
    public boolean isTrial(){return level()==Level.PRO_TRIAL;}
    public boolean isTrialExpired(){return level()==Level.TRIAL_EXPIRED||trialExpiredByTrustedClock();}
    public boolean isPro(){
        Level v=level();
        if(v==Level.PRO)return true;
        return v==Level.PRO_TRIAL&&!needsServerValidation()&&!trialExpiredByTrustedClock();
    }
    public boolean canPlay(){return isPro();}

    public long trialDaysRemaining(){
        if(level()!=Level.PRO_TRIAL)return 0L;
        long now=trustedNow();
        if(now<=0L)return 0L;
        long left=Math.max(0L,expiresAt()-now);
        return left<=0L?0L:Math.max(1L,(left+86399999L)/86400000L);
    }

    public void applyServer(JSONObject o){
        if(o==null)return;
        String status=o.optString("status","").trim().toLowerCase(Locale.ROOT);
        String levelRaw=o.optString("level","").trim().toLowerCase(Locale.ROOT);
        Level level;
        if("pro".equals(levelRaw)||"pro".equals(status)||"active".equals(status)){
            level=Level.PRO;
        }else if("trial_expired".equals(status)||"expired".equals(o.optString("trial_state",""))){
            level=Level.TRIAL_EXPIRED;
        }else if("pro_trial".equals(levelRaw)||"trial_active".equals(status)||"pro_trial".equals(status)||"trial".equals(status)||"active".equals(o.optString("trial_state",""))){
            level=Level.PRO_TRIAL;
        }else{
            level=Level.TRIAL_REQUIRED;
        }

        long expiry=readEpoch(o,"expires_at_ms",1L);
        if(expiry<=0L)expiry=readEpoch(o,"expires_at",1000L);
        long serverTime=readEpoch(o,"server_time_ms",1L);
        if(serverTime<=0L)serverTime=System.currentTimeMillis();

        SharedPreferences.Editor ed=prefs.edit()
            .putString("level",level.name())
            .putString("server_status",status.isEmpty()?levelRaw:status)
            .putLong("expires_at",expiry)
            .putLong("server_time_ms",serverTime)
            .putLong("server_elapsed_ms",SystemClock.elapsedRealtime());
        if(o.has("email"))ed.putString("account_email",o.optString("email",""));
        if(o.has("account_email"))ed.putString("account_email",o.optString("account_email",""));
        if(o.has("max_devices"))ed.putInt("max_devices",Math.max(1,o.optInt("max_devices",1)));
        ed.apply();
    }

    private long readEpoch(JSONObject o,String key,long multiplier){
        if(!o.has(key))return 0L;
        Object v=o.opt(key);
        try{
            long n=v instanceof Number?((Number)v).longValue():Long.parseLong(String.valueOf(v));
            if(n<=0L)return 0L;
            if(multiplier==1000L&&n>100000000000L)return n;
            return n*multiplier;
        }catch(Exception e){return 0L;}
    }
}
