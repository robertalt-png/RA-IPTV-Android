package com.nenotv.player.storage;

import android.content.Context;
import java.io.PrintWriter;
import java.io.StringWriter;

public final class CrashGuard {
    private CrashGuard(){}
    private static volatile boolean installed=false;
    /** Installs one handler per process; later calls (every screen used to call this) do nothing. */
    public static synchronized void install(Context c){
        if(installed)return;installed=true;
        final Context app=c.getApplicationContext();
        final Thread.UncaughtExceptionHandler previous=Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((thread,error)->{
            try{StringWriter sw=new StringWriter();error.printStackTrace(new PrintWriter(sw));String txt=sw.toString();if(txt.length()>12000)txt=txt.substring(0,12000);app.getSharedPreferences("crash_guard",Context.MODE_PRIVATE).edit().putLong("at",System.currentTimeMillis()).putString("type",error.getClass().getSimpleName()).putString("trace",txt).commit();}catch(Throwable ignored){}
            if(previous!=null)previous.uncaughtException(thread,error);
        });
    }
    public static String lastType(Context c){return c.getSharedPreferences("crash_guard",Context.MODE_PRIVATE).getString("type","");}
    public static String consumeLastType(Context c){
        android.content.SharedPreferences p=c.getSharedPreferences("crash_guard",Context.MODE_PRIVATE);
        String type=p.getString("type",""); long at=p.getLong("at",0L);
        p.edit().remove("type").remove("trace").remove("at").apply();
        if(at>0L && System.currentTimeMillis()-at>24L*60L*60L*1000L)return "";
        return type==null?"":type;
    }
    public static void clear(Context c){c.getSharedPreferences("crash_guard",Context.MODE_PRIVATE).edit().clear().apply();}
}
