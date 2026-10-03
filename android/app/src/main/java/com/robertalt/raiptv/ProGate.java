package com.nenotv.player;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import com.nenotv.player.storage.EntitlementStore;

public final class ProGate {
    private ProGate(){}
    public static boolean allowed(Activity a){
        return !BuildConfig.LIGHT_BUILD && new EntitlementStore(a).isPro();
    }
    public static boolean require(Activity a,String feature){
        if(allowed(a))return true;
        String f=feature==null||feature.trim().isEmpty()?UiText.t(a,"this_feature"):feature;
        String benefit=benefit(a,f);
        String message=f+" "+UiText.t(a,"pro_unlock_message");
        if(benefit!=null&&!benefit.trim().isEmpty())message=benefit+"\n\n"+message;
        new AlertDialog.Builder(a)
            .setTitle("🔒 "+UiText.t(a,"pro_required"))
            .setMessage(message)
            .setNegativeButton(UiText.t(a,"not_now"),null)
            .setPositiveButton(UiText.t(a,"view_pro"),(dialog,which)->a.startActivity(new Intent(a,AccountActivity.class)))
            .show();
        return false;
    }
    private static String benefit(Activity a,String f){
        if(f.equals(UiText.t(a,"casting")))return UiText.t(a,"pro_benefit_casting");
        if(f.equals(UiText.t(a,"advanced_epg")))return UiText.t(a,"pro_benefit_epg");
        if(f.equals(UiText.t(a,"recording")))return UiText.t(a,"pro_benefit_recording");
        if(f.equals(UiText.t(a,"parental_controls")))return UiText.t(a,"pro_benefit_parental");
        if(f.equals(UiText.t(a,"advanced_subtitles")))return UiText.t(a,"pro_benefit_subtitles");
        if(f.equals(UiText.t(a,"picture_in_picture")))return UiText.t(a,"pro_benefit_pip");
        if(f.equals(UiText.t(a,"manage_source")))return UiText.t(a,"pro_benefit_sources");
        return "";
    }
}
