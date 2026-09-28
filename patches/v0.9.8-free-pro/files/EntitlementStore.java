package com.robertalt.raiptv.storage;

import android.content.Context;
import android.content.SharedPreferences;
import java.util.Locale;

public final class EntitlementStore {
    public enum Level { FREE, PRO_TRIAL, PRO }
    private final SharedPreferences prefs;
    public EntitlementStore(Context c){prefs=c.getApplicationContext().getSharedPreferences("nenotv_entitlement",Context.MODE_PRIVATE);}
    public Level level(){
        try{
            Level value=Level.valueOf(prefs.getString("level","FREE").toUpperCase(Locale.ROOT));
            if(value==Level.PRO_TRIAL){
                long end=prefs.getLong("expires_at",0L);
                if(end>0&&System.currentTimeMillis()>=end){prefs.edit().putString("level","FREE").putLong("expires_at",0L).apply();return Level.FREE;}
            }
            return value;
        }catch(Exception e){return Level.FREE;}
    }
    public boolean isPro(){Level v=level();return v==Level.PRO||v==Level.PRO_TRIAL;}
}
