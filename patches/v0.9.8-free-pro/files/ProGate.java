package com.robertalt.raiptv;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import com.robertalt.raiptv.storage.EntitlementStore;

public final class ProGate {
    private ProGate(){}
    public static boolean allowed(Activity a){
        return new EntitlementStore(a).isPro();
    }
    public static boolean require(Activity a,String feature){
        if(allowed(a))return true;
        String f=feature==null||feature.trim().isEmpty()?UiText.t(a,"this_feature"):feature;
        new AlertDialog.Builder(a)
            .setTitle(UiText.t(a,"pro_required"))
            .setMessage(f+" "+UiText.t(a,"pro_unlock_message"))
            .setNegativeButton(UiText.t(a,"not_now"),null)
            .setPositiveButton(UiText.t(a,"view_pro"),(dialog,which)->a.startActivity(new Intent(a,AccountActivity.class)))
            .show();
        return false;
    }
}
