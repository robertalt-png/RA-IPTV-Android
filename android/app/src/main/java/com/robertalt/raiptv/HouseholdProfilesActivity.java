package com.nenotv.player;

import android.app.Activity;
import android.app.AlertDialog;
import android.os.Bundle;
import android.view.Gravity;
import android.widget.*;
import com.nenotv.player.storage.EntitlementStore;
import com.nenotv.player.storage.HouseholdProfileStore;

public final class HouseholdProfilesActivity extends Activity {
    HouseholdProfileStore profiles;LinearLayout box;
    String T(String key){return UiText.t(this,key);}
    int dp(int size){return Math.round(size*getResources().getDisplayMetrics().density);}
    Button button(String label){Button v=new Button(this);v.setText(label);v.setAllCaps(false);v.setTextColor(0xFFF7F8FA);v.setBackgroundTintList(android.content.res.ColorStateList.valueOf(0xFF1B2028));v.setMinHeight(dp(52));return v;}
    @Override public void onCreate(Bundle state){
        super.onCreate(state);if(!new EntitlementStore(this).isPro()){finish();return;}
        profiles=new HouseholdProfileStore(this);
        ScrollView scroll=new ScrollView(this);scroll.setBackgroundColor(0xFF07090D);
        box=new LinearLayout(this);box.setOrientation(LinearLayout.VERTICAL);box.setPadding(dp(18),dp(18),dp(18),dp(30));scroll.addView(box);
        setContentView(scroll);ScreenInsets.browsing(this);UiText.applyDirection(this);render();
    }
    String name(HouseholdProfileStore.Viewer viewer){return viewer.name.isEmpty()?T("default_viewer"):viewer.name;}
    void render(){
        box.removeAllViews();
        LinearLayout header=new LinearLayout(this);header.setGravity(Gravity.CENTER_VERTICAL);
        TextView title=new TextView(this);title.setText(T("household_profiles"));title.setTextSize(24);title.setTextColor(0xFFF7F8FA);header.addView(title,new LinearLayout.LayoutParams(0,-2,1));
        Button close=button(T("close"));close.setOnClickListener(v->finish());header.addView(close);box.addView(header);
        try{
            String active=profiles.activeId();
            for(HouseholdProfileStore.Viewer viewer:profiles.list()){
                LinearLayout row=new LinearLayout(this);row.setGravity(Gravity.CENTER_VERTICAL);
                Button select=button(name(viewer));select.setTextColor(viewer.id.equals(active)?0xFFFFD400:0xFFF7F8FA);select.setSelected(viewer.id.equals(active));
                select.setTag("viewer:"+viewer.id);
                select.setOnClickListener(v->{try{profiles.select(viewer.id);render();}catch(Exception error){Toast.makeText(this,T("viewer_storage_error"),Toast.LENGTH_LONG).show();}});row.addView(select,new LinearLayout.LayoutParams(0,-2,1));
                Button more=button("...");more.setContentDescription(T("viewer_options"));more.setOnClickListener(v->options(viewer));row.addView(more,new LinearLayout.LayoutParams(dp(64),-2));box.addView(row);
            }
            Button add=button(T("add_viewer"));add.setEnabled(profiles.list().size()<HouseholdProfileStore.LIMIT);add.setOnClickListener(v->edit(null));box.addView(add,new LinearLayout.LayoutParams(-1,-2));
        }catch(Exception error){TextView message=new TextView(this);message.setText(T("viewer_storage_error"));message.setTextColor(0xFFF7F8FA);box.addView(message);}
    }
    void options(HouseholdProfileStore.Viewer viewer){
        String[] options=HouseholdProfileStore.DEFAULT_ID.equals(viewer.id)?new String[]{T("rename_viewer")}:new String[]{T("rename_viewer"),T("delete_viewer")};
        new AlertDialog.Builder(this).setTitle(name(viewer)).setItems(options,(d,index)->{
            if(index==0)edit(viewer);
            else new AlertDialog.Builder(this).setTitle(T("delete_viewer")).setMessage(T("delete_viewer_confirm"))
                .setNegativeButton(T("cancel"),null).setPositiveButton(T("delete_viewer"),(x,w)->{try{profiles.remove(viewer.id);render();}catch(Exception error){Toast.makeText(this,T("viewer_storage_error"),Toast.LENGTH_LONG).show();}}).show();
        }).show();
    }
    void edit(HouseholdProfileStore.Viewer viewer){
        EditText input=new EditText(this);input.setSingleLine(true);input.setHint(T("viewer_name"));input.setFilters(new android.text.InputFilter[]{new android.text.InputFilter.LengthFilter(40)});
        if(viewer!=null)input.setText(name(viewer));
        AlertDialog dialog=new AlertDialog.Builder(this).setTitle(T(viewer==null?"add_viewer":"rename_viewer")).setView(input).setNegativeButton(T("cancel"),null).setPositiveButton(T("save"),null).create();
        dialog.setOnShowListener(d->dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v->{
            try{if(viewer==null)profiles.add(input.getText().toString());else profiles.rename(viewer.id,input.getText().toString());dialog.dismiss();render();}
            catch(IllegalArgumentException error){input.setError(T("viewer_name_invalid"));}
            catch(Exception error){input.setError(T("viewer_storage_error"));}
        }));dialog.show();
    }
}
