package com.nenotv.player;

import android.app.Activity;
import android.app.AlertDialog;
import android.text.InputType;
import android.view.WindowManager;
import android.widget.EditText;
import android.widget.Toast;
import com.nenotv.player.storage.FamilyStore;
import com.nenotv.player.storage.SettingsStore;

public final class FamilyUi {
    private FamilyUi(){}
    public interface PinAction {void run(String pin);}
    public static String text(Activity a,String nl,String en,String de){String l=SettingsStore.language(a);return "nl".equals(l)?nl:"de".equals(l)?de:en;}
    public static void pin(Activity a,PinAction action){
        if(!SettingsStore.hasParentalPin(a)){
            Toast.makeText(a,text(a,"Stel eerst een ouder-PIN in","Set a parent PIN first","Zuerst eine Eltern-PIN festlegen"),Toast.LENGTH_LONG).show();
            a.startActivity(new android.content.Intent(a,FamilyActivity.class));return;
        }
        EditText input=new EditText(a);input.setHint("PIN");input.setInputType(InputType.TYPE_CLASS_NUMBER|InputType.TYPE_NUMBER_VARIATION_PASSWORD);
        AlertDialog dialog=new AlertDialog.Builder(a).setTitle(text(a,"Ouder-PIN","Parent PIN","Eltern-PIN")).setView(input).setNegativeButton(UiText.t(a,"cancel"),null).setPositiveButton(UiText.t(a,"unlock"),null).create();
        dialog.setOnShowListener(d->{dialog.getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v->{String pin=input.getText().toString();if(SettingsStore.verifyParentalPin(a,pin)){input.setText("");dialog.dismiss();action.run(pin);}else input.setError(text(a,"Onjuiste PIN of tijdelijk geblokkeerd","Incorrect PIN or temporarily locked","Falsche PIN oder voruebergehend gesperrt"));});});
        dialog.show();
    }
    public static void parentAction(Activity a,Runnable action){if(FamilyStore.active(a))pin(a,p->action.run());else action.run();}
    public static void blocked(Activity a){Toast.makeText(a,text(a,"Niet toegestaan in kindermodus","Not allowed in child mode","Im Kindermodus nicht erlaubt"),Toast.LENGTH_SHORT).show();}
}
