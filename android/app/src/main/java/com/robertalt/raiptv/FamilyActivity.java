package com.nenotv.player;

import android.app.Activity;
import android.app.AlertDialog;
import android.os.Bundle;
import android.text.InputType;
import android.view.WindowManager;
import android.widget.*;
import com.nenotv.player.storage.FamilyStore;
import com.nenotv.player.storage.SettingsStore;

public class FamilyActivity extends Activity {
    private LinearLayout box;
    private String text(String nl,String en,String de){return FamilyUi.text(this,nl,en,de);}
    @Override public void onCreate(Bundle state){super.onCreate(state);build();}
    private Button button(String label){Button b=new Button(this);b.setText(label);b.setAllCaps(false);box.addView(b,new LinearLayout.LayoutParams(-1,-2));return b;}
    private void build(){
        ScrollView scroll=new ScrollView(this);scroll.setFillViewport(true);scroll.setBackgroundColor(0xFF07090D);
        box=new LinearLayout(this);box.setOrientation(LinearLayout.VERTICAL);int padding=Math.round(18*getResources().getDisplayMetrics().density);box.setPadding(padding,padding,padding,padding);scroll.addView(box);
        TextView title=new TextView(this);title.setText(text("Familiefilter","Family filter","Familienfilter"));title.setTextSize(24);title.setTextColor(0xFFFFFFFF);box.addView(title);
        button(UiText.t(this,"close")).setOnClickListener(v->finish());
        button(text("Privacyverklaring","Privacy policy","Datenschutzerklaerung")).setOnClickListener(v->startActivity(new android.content.Intent(android.content.Intent.ACTION_VIEW,android.net.Uri.parse(SiteEndpoints.privacyUrl(SettingsStore.language(this))))));
        Switch mode=new Switch(this);mode.setText(text("Kindermodus","Child mode","Kindermodus"));mode.setTextColor(0xFFFFFFFF);mode.setChecked(FamilyStore.active(this));box.addView(mode,new LinearLayout.LayoutParams(-1,-2));
        mode.setOnCheckedChangeListener((v,on)->{build();FamilyUi.pin(this,pin->{if(FamilyStore.setActive(this,on,pin))build();else FamilyUi.blocked(this);});});
        button(SettingsStore.hasParentalPin(this)?UiText.t(this,"pin_change"):UiText.t(this,"pin_set")).setOnClickListener(v->{if(SettingsStore.hasParentalPin(this))FamilyUi.pin(this,p->newPin());else newPin();});
        TextView label=new TextView(this);label.setText(text("Toegestane inhoud","Allowed content","Erlaubte Inhalte"));label.setTextColor(0xFFFFFFFF);label.setTextSize(18);box.addView(label);
        java.util.List<FamilyStore.Approval> rows=FamilyStore.list(this);
        if(rows.isEmpty()){TextView empty=new TextView(this);empty.setText(text("Nog geen inhoud toegestaan","No content approved yet","Noch keine Inhalte erlaubt"));empty.setTextColor(0xFFFFFFFF);box.addView(empty);}
        for(FamilyStore.Approval row:rows)button(row.name+"  "+text("Verwijderen","Remove","Entfernen")).setOnClickListener(v->FamilyUi.pin(this,p->{if(FamilyStore.revoke(this,row.key,p))build();else FamilyUi.blocked(this);}));
        scroll.setOnApplyWindowInsetsListener((v,insets)->{v.setPadding(0,insets.getSystemWindowInsetTop(),0,insets.getSystemWindowInsetBottom());return insets;});setContentView(scroll);scroll.requestApplyInsets();UiText.applyDirection(this);
    }
    private void newPin(){
        LinearLayout wrap=new LinearLayout(this);wrap.setOrientation(LinearLayout.VERTICAL);
        EditText first=new EditText(this),second=new EditText(this);
        for(EditText field:new EditText[]{first,second}){field.setInputType(InputType.TYPE_CLASS_NUMBER|InputType.TYPE_NUMBER_VARIATION_PASSWORD);wrap.addView(field);}
        first.setHint(text("Nieuwe PIN (4-8 cijfers)","New PIN (4-8 digits)","Neue PIN (4-8 Ziffern)"));second.setHint(text("Herhaal PIN","Repeat PIN","PIN wiederholen"));
        AlertDialog d=new AlertDialog.Builder(this).setTitle(text("Ouder-PIN","Parent PIN","Eltern-PIN")).setView(wrap).setNegativeButton(UiText.t(this,"cancel"),null).setPositiveButton(UiText.t(this,"save"),null).create();
        d.setOnShowListener(x->{d.getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);d.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v->{String pin=first.getText().toString();if(!pin.matches("[0-9]{4,8}")||!pin.equals(second.getText().toString())){second.setError(text("Controleer beide PIN-codes","Check both PIN codes","Beide PINs pruefen"));return;}SettingsStore.setParentalPin(this,pin);first.setText("");second.setText("");d.dismiss();build();});});d.show();
    }
}
