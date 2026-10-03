package com.nenotv.player.proextras;

import android.app.*;
import android.content.*;
import android.graphics.Typeface;
import android.os.Bundle;
import android.view.*;
import android.widget.*;
import com.nenotv.player.ProfileActivity;
import com.nenotv.player.entitlement.SourceSyncClient;
import com.nenotv.player.storage.EntitlementStore;
import com.nenotv.player.storage.SettingsStore;
import com.nenotv.player.storage.SourceStore;
import java.util.List;
import java.util.concurrent.*;

public class ProSourcesActivity extends Activity {
    LinearLayout box;
    SourceStore sources;
    ExecutorService exec=Executors.newSingleThreadExecutor();

    int dp(int v){return Math.round(v*getResources().getDisplayMetrics().density);}
    String L(String en,String nl,String de){String l=SettingsStore.language(this);return "nl".equals(l)?nl:"de".equals(l)?de:en;}

    @Override public void onCreate(Bundle b){
        super.onCreate(b);
        if(!new EntitlementStore(this).isPro()){finish();return;}
        sources=new SourceStore(this);
        setResult(RESULT_OK);
        build();
    }
    @Override protected void onResume(){super.onResume();if(box!=null)render();}

    void build(){
        ScrollView sv=new ScrollView(this);sv.setBackgroundColor(0xFF07090D);
        box=new LinearLayout(this);box.setOrientation(LinearLayout.VERTICAL);box.setPadding(dp(18),dp(18),dp(18),dp(32));sv.addView(box);
        setContentView(sv);render();
    }

    TextView text(String s,int size){TextView v=new TextView(this);v.setText(s);v.setTextColor(0xFFF7F8FA);v.setTextSize(size);return v;}
    Button button(String s){Button b=new Button(this);b.setText(s);b.setAllCaps(false);b.setTextColor(0xFFF7F8FA);b.setBackgroundTintList(android.content.res.ColorStateList.valueOf(0xFF1B2028));return b;}

    void render(){
        box.removeAllViews();
        LinearLayout h=new LinearLayout(this);h.setGravity(Gravity.CENTER_VERTICAL);
        TextView title=text(L("My sources","Mijn bronnen","Meine Quellen"),24);title.setTypeface(null,Typeface.BOLD);h.addView(title,new LinearLayout.LayoutParams(0,-2,1));
        Button close=button(L("Close","Sluiten","Schließen"));close.setOnClickListener(v->finish());h.addView(close);box.addView(h);

        TextView help=text(L(
            "NenoTV Pro can keep multiple IPTV sources. The selected source still uses the normal Light import pipeline.",
            "NenoTV Pro kan meerdere IPTV-bronnen bewaren. De gekozen bron gebruikt nog steeds de normale Light-laadlaag.",
            "NenoTV Pro kann mehrere IPTV-Quellen speichern. Die gewählte Quelle nutzt weiterhin die normale Light-Ladeschicht."),13);
        help.setTextColor(0xFFA7AFBC);help.setPadding(0,dp(6),0,dp(16));box.addView(help);

        LinearLayout topActions=new LinearLayout(this);topActions.setOrientation(LinearLayout.HORIZONTAL);box.addView(topActions);
        Button add=button("＋ "+L("Add source","Bron toevoegen","Quelle hinzufügen"));
        add.setOnClickListener(v->{Intent i=new Intent(this,ProfileActivity.class);i.putExtra("new_source",true);startActivity(i);});
        topActions.addView(add,new LinearLayout.LayoutParams(0,dp(52),1));
        Button sync=button("↻ "+L("Sync My NenoTV","Sync Mijn NenoTV","Mein NenoTV synchronisieren"));
        sync.setOnClickListener(v->syncNow(sync));
        topActions.addView(sync,new LinearLayout.LayoutParams(0,dp(52),1));

        List<SourceStore.Entry> all=sources.list();
        String active=sources.activeId();
        if(all.isEmpty()){
            TextView empty=text(L("No sources saved.","Nog geen bronnen opgeslagen.","Noch keine Quellen gespeichert."),14);
            empty.setPadding(0,dp(18),0,0);box.addView(empty);return;
        }
        for(SourceStore.Entry e:all)addSourceCard(e,e.id.equals(active));
    }

    void addSourceCard(SourceStore.Entry e,boolean active){
        LinearLayout card=new LinearLayout(this);card.setOrientation(LinearLayout.VERTICAL);card.setPadding(dp(14),dp(12),dp(14),dp(12));card.setBackgroundColor(0xFF151A21);
        LinearLayout.LayoutParams cp=new LinearLayout.LayoutParams(-1,-2);cp.topMargin=dp(10);box.addView(card,cp);

        String type=e.profile.type==com.nenotv.player.model.Profile.Type.XTREAM?"Xtream":"M3U";
        TextView n=text((active?"● ":"")+e.profile.name,17);n.setTypeface(null,Typeface.BOLD);if(active)n.setTextColor(0xFFFFD400);card.addView(n);
        TextView meta=text(type+" · "+(e.enabled?L("active","actief","aktiv"):L("disabled","uitgeschakeld","deaktiviert")),12);meta.setTextColor(0xFFA7AFBC);card.addView(meta);

        LinearLayout row=new LinearLayout(this);row.setOrientation(LinearLayout.HORIZONTAL);row.setPadding(0,dp(8),0,0);card.addView(row);
        if(!active){
            Button use=button(L("Use","Gebruiken","Verwenden"));
            use.setOnClickListener(v->{if(sources.setActive(e.id)){setResult(RESULT_OK);render();}});
            row.addView(use,new LinearLayout.LayoutParams(0,dp(48),1));
        }
        Button edit=button(L("Edit","Bewerken","Bearbeiten"));
        edit.setOnClickListener(v->{Intent i=new Intent(this,ProfileActivity.class);i.putExtra("source_id",e.id);startActivity(i);});
        row.addView(edit,new LinearLayout.LayoutParams(0,dp(48),1));
        Button more=button("⋮");more.setOnClickListener(v->menu(e));row.addView(more,new LinearLayout.LayoutParams(dp(58),dp(48)));
    }

    void syncNow(Button button){
        button.setEnabled(false);button.setText(L("Syncing…","Synchroniseren…","Synchronisieren…"));
        exec.execute(()->{
            try{
                new SourceSyncClient(this).sync();
                runOnUiThread(()->{
                    Toast.makeText(this,L("Sources synchronized with My NenoTV.","Bronnen gesynchroniseerd met Mijn NenoTV.","Quellen mit Mein NenoTV synchronisiert."),Toast.LENGTH_LONG).show();
                    render();
                });
            }catch(Exception ex){
                String m=ex.getMessage()==null?L("Sync unavailable","Sync niet beschikbaar","Synchronisierung nicht verfügbar"):ex.getMessage();
                runOnUiThread(()->{Toast.makeText(this,m,Toast.LENGTH_LONG).show();render();});
            }
        });
    }

    void menu(SourceStore.Entry e){
        String[] opts={
            e.enabled?L("Disable","Uitschakelen","Deaktivieren"):L("Enable","Inschakelen","Aktivieren"),
            L("Move up","Omhoog","Nach oben"),
            L("Move down","Omlaag","Nach unten"),
            L("Delete","Verwijderen","Löschen")
        };
        new AlertDialog.Builder(this).setTitle(e.profile.name).setItems(opts,(d,w)->{
            if(w==0)sources.setEnabled(e.id,!e.enabled);
            else if(w==1)sources.move(e.id,-1);
            else if(w==2)sources.move(e.id,1);
            else if(w==3)new AlertDialog.Builder(this).setMessage(L("Delete this source?","Deze bron verwijderen?","Diese Quelle löschen?"))
                .setPositiveButton(L("Delete","Verwijderen","Löschen"),(x,y)->{sources.remove(e.id);render();})
                .setNegativeButton(L("Cancel","Annuleren","Abbrechen"),null).show();
            render();
        }).show();
    }

    @Override protected void onDestroy(){exec.shutdownNow();super.onDestroy();}
}
