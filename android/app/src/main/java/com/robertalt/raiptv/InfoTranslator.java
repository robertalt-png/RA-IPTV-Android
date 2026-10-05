package com.nenotv.player;

import android.content.Context;

public final class InfoTranslator {
    public interface Callback { void done(String text); }
    private static volatile Context appContext;
    private InfoTranslator(){}

    public static void init(Context context){
        if(context!=null)appContext=context.getApplicationContext();
    }

    public static Context context(){ return appContext; }

    public static void translate(String text,String target,Callback cb){
        String fallback=text==null?"":text;
        if(appContext==null||!com.nenotv.player.storage.ExtraPrivacyStore.allowsSdk(appContext)||!new com.nenotv.player.storage.EntitlementStore(appContext).isPro()){if(cb!=null)cb.done(fallback);return;}
        try{
            Class<?> impl=Class.forName("com.nenotv.player.proextras.ProInfoTranslator");
            java.lang.reflect.Method m=impl.getMethod(
                "translate",String.class,String.class,InfoTranslator.Callback.class);
            m.invoke(null,text,target,cb);
        }catch(Throwable ignored){
            if(cb!=null)cb.done(fallback);
        }
    }
}
