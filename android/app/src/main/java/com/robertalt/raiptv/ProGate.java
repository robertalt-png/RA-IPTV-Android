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
        if(new com.nenotv.player.storage.UpdateRequirementStore(a).isBasicOnly()){
            String language=com.nenotv.player.storage.SettingsStore.language(a);
            boolean nl="nl".equals(language),de="de".equals(language);
            new AlertDialog.Builder(a)
                .setTitle(nl?"SunnyIPTV werkt tijdelijk als Basic":de?"SunnyIPTV ist vorübergehend Basic":"SunnyIPTV is temporarily Basic")
                .setMessage(nl?"De updatetermijn van 60 dagen is verstreken. Installeer de update om je eerdere toegang automatisch te herstellen, zolang je abonnement of testerrechten nog geldig zijn.":de?"Die 60-Tage-Frist ist abgelaufen. Installiere das Update, um deinen bisherigen Zugang automatisch wiederherzustellen, sofern dein Abonnement oder deine Testerrechte noch gültig sind.":"The 60-day update deadline has passed. Install the update to restore your previous access automatically, provided your subscription or tester rights are still valid.")
                .setNegativeButton(nl?"Verder kijken":de?"Weitersehen":"Keep watching",null)
                .setPositiveButton(nl?"Bijwerken":de?"Aktualisieren":"Update",(dialog,which)->PlayUpdateNotifier.openPlay(a))
                .show();
            return false;
        }
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
        if(f.equals(UiText.t(a,"advanced_subtitles")))return UiText.t(a,"pro_benefit_subtitles");
        if(f.equals(UiText.t(a,"picture_in_picture")))return UiText.t(a,"pro_benefit_pip");
        if(f.equals(UiText.t(a,"manage_source")))return UiText.t(a,"pro_benefit_sources");
        return "";
    }
}
