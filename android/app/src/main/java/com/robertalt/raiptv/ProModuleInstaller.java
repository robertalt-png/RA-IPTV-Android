package com.nenotv.player;

import android.app.Activity;
import android.content.Intent;
import android.widget.Toast;
import com.google.android.play.core.splitinstall.*;
import com.nenotv.player.storage.EntitlementStore;
import java.util.Collections;

public final class ProModuleInstaller {
    private static final String MODULE="proextras";
    private static final String PRO_PLAYER="com.nenotv.player.proextras.ProPlayerActivity";
    private static final String PRO_SOURCES="com.nenotv.player.proextras.ProSourcesActivity";
    private static final String PRO_NETWORK="com.nenotv.player.proextras.ProNetworkActivity";
    private ProModuleInstaller(){}

    public static boolean isInstalled(Activity a){
        try{Class.forName(PRO_PLAYER,false,a.getClassLoader());return true;}
        catch(Throwable t){return false;}
    }

    public static void request(Activity a){
        try{
            SplitInstallManager m=SplitInstallManagerFactory.create(a);
            if(isInstalled(a))return;
            SplitInstallRequest r=SplitInstallRequest.newBuilder().addModule(MODULE).build();
            m.startInstall(r)
              .addOnSuccessListener(id->Toast.makeText(a,"SunnyIPTV Pro Media Pack wordt gedownload…",Toast.LENGTH_LONG).show())
              .addOnFailureListener(e->Toast.makeText(a,"SunnyIPTV Pro Media Pack kon niet worden gestart.",Toast.LENGTH_LONG).show());
        }catch(Throwable t){
            Toast.makeText(a,"SunnyIPTV Pro Media Pack is beschikbaar via Google Play.",Toast.LENGTH_LONG).show();
        }
    }

    public static void syncEntitlement(Activity a){
        try{
            SplitInstallManager m=SplitInstallManagerFactory.create(a);
            boolean entitled=new EntitlementStore(a).isPro();
            boolean installed=isInstalled(a);
            if(entitled&&!installed){
                request(a);
            }else if(!entitled&&installed){
                if(new com.nenotv.player.storage.UpdateRequirementStore(a).isBasicOnly())return;
                m.deferredUninstall(Collections.singletonList(MODULE));
            }
        }catch(Throwable ignored){}
    }

    public static Intent sourcesIntent(Activity a){
        if(com.nenotv.player.storage.FamilyStore.active(a))return new Intent(a,FamilyActivity.class);
        boolean pro=new EntitlementStore(a).isPro();
        if(pro&&isInstalled(a)){
            Intent i=new Intent();i.setClassName(a,PRO_SOURCES);return i;
        }
        if(pro&&!isInstalled(a))request(a);
        return new Intent(a,ProfileActivity.class);
    }

    public static Intent networkIntent(Activity a){
        if(com.nenotv.player.storage.FamilyStore.active(a))return new Intent(a,FamilyActivity.class);
        boolean pro=new EntitlementStore(a).isPro();
        if(pro&&isInstalled(a)){Intent i=new Intent();i.setClassName(a,PRO_NETWORK);return i;}
        if(pro&&!isInstalled(a))request(a);
        return new Intent(android.provider.Settings.ACTION_WIRELESS_SETTINGS);
    }

    public static Intent playerIntent(Activity a){
        boolean pro=new EntitlementStore(a).isPro();
        if(pro&&isInstalled(a)){
            Intent i=new Intent();
            i.setClassName(a,PRO_PLAYER);
            return i;
        }
        if(pro&&!isInstalled(a))request(a);
        return new Intent(a,PlayerActivity.class);
    }
}
