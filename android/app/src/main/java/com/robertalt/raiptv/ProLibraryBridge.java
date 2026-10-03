package com.nenotv.player;

import android.app.Activity;
import com.nenotv.player.model.MediaEntry;
import java.util.*;

public final class ProLibraryBridge {
    private static final String OPTIMIZER="com.nenotv.player.proextras.ProLibraryOptimizer";
    private ProLibraryBridge(){}
    public static boolean isActive(Activity a){return a!=null&&ProGate.allowed(a)&&ProModuleInstaller.isInstalled(a);}
    @SuppressWarnings("unchecked")
    public static List<MediaEntry> optimize(Activity a,List<MediaEntry> input,String section,String preferredLanguage){
        ArrayList<MediaEntry> fallback=new ArrayList<>(input==null?Collections.emptyList():input);
        if(!isActive(a))return fallback;
        try{
            Class<?> c=Class.forName(OPTIMIZER);
            java.lang.reflect.Method m=c.getMethod("optimize",List.class,String.class,String.class);
            Object out=m.invoke(null,fallback,section,preferredLanguage);
            if(out instanceof List)return new ArrayList<>((List<MediaEntry>)out);
        }catch(Throwable ignored){}
        return fallback;
    }
}
