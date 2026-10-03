package com.nenotv.player;

import android.content.*;
import com.nenotv.player.storage.SettingsStore;

public final class DemoPolicy {
    public static final long DURATION_MS=30L*24L*60L*60L*1000L;
    private DemoPolicy(){}

    static SharedPreferences prefs(Context c){return SettingsStore.prefs(c);}

    public static boolean isDemo(com.nenotv.player.model.Profile p){
        return p!=null&&p.type==com.nenotv.player.model.Profile.Type.M3U&&
            (DemoSource.URL.equals(p.m3uUrl)||BuildConfig.NENOTV_DEMO_M3U_URL.equals(p.m3uUrl));
    }
    public static boolean blockPlayback(Context c){
        com.nenotv.player.storage.SecureProfileStore store=new com.nenotv.player.storage.SecureProfileStore(c);
        return store.exists()&&isDemo(store.load())&&expired(c);
    }
    public static boolean consumed(Context c){return prefs(c).getBoolean("demo_consumed",false);}
    public static long expiresAt(Context c){return prefs(c).getLong("demo_expires_at",0L);}
    public static boolean expired(Context c){return expiredAt(c,System.currentTimeMillis());}
    public static boolean expiredAt(Context c,long now){
        long exp=expiresAt(c);
        return consumed(c)&&exp>0L&&now>=exp;
    }
    public static boolean canStart(Context c){
        if(!consumed(c))return true;
        long exp=expiresAt(c);
        return exp>System.currentTimeMillis();
    }
    public static long startOrKeep(Context c,long now){
        SharedPreferences sp=prefs(c);
        boolean used=sp.getBoolean("demo_consumed",false);
        long exp=sp.getLong("demo_expires_at",0L);
        if(used&&exp>now)return exp;
        if(used&&exp>0L&&now>=exp)return -1L;
        long next=now+DURATION_MS;
        if(!sp.edit().putBoolean("demo_consumed",true).putLong("demo_started_at",now).putLong("demo_expires_at",next).commit())throw new IllegalStateException("DEMO_SAVE_FAILED");
        return next;
    }
}