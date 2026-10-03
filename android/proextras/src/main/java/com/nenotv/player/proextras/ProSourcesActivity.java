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
        try{renderSources();}
        catch(IllegalStateException error){
            box.removeAllViews();
            box.addView(text(L("Saved sources cannot be read. No sources have been deleted.","Opgeslagen bronnen kunnen niet worden gelezen. Er zijn geen bronnen verwijderd.","Gespeicherte Quellen sind nicht lesbar. Es wurden keine Quellen gelöscht."),16),new LinearLayout.LayoutParams(-1,-2));
            Button close=button(L("Close","Sluiten","Schließen"));close.setOnClickListener(v->finish());box.addView(close,new LinearLayout.LayoutParams(-1,-2));
        }
    }

    void renderSources(){
        box.removeAllViews();
        LinearLayout h=new LinearLayout(this);h.setGravity(Gravity.CENTER_VERTICAL);
        TextView title=text(L("My sources","Mijn bronnen","Meine Quellen"),24);title.setTypeface(null,Typeface.BOLD);h.addView(title,new LinearLayout.LayoutParams(0,-2,1));
        Button close=button(L("Close","Sluiten","Schließen"));close.setOnClickListener(v->finish());h.addView(close);box.addView(h);

        Switch download=new Switch(this);
        download.setText(L("Download account sources automatically","Accountbronnen automatisch ophalen","Kontoquellen automatisch abrufen"));
        download.setTextColor(0xFFF7F8FA);download.setChecked(sources.automaticDownloadEnabled());
        download.setOnCheckedChangeListener((v,on)->{sources.setAutomaticDownloadEnabled(on);if(on)com.nenotv.player.entitlement.AutomaticSourceDownload.check(this,()->runOnUiThread(()->{if(!isFinishing()&&!isDestroyed())render();}));});
        box.addView(download,new LinearLayout.LayoutParams(-1,-2));

        Switch smartMerge=new Switch(this);smartMerge.setText(L("Smart Merge · add secondary sources after the active source loads","Smart Merge · voeg secundaire bronnen toe nadat de actieve bron geladen is","Smart Merge · weitere Quellen nach der aktiven Quelle hinzufügen"));smartMerge.setTextColor(0xFFF7F8FA);smartMerge.setChecked(SettingsStore.prefs(this).getBoolean("pro_smart_merge",false));smartMerge.setOnCheckedChangeListener((v,on)->SettingsStore.prefs(this).edit().putBoolean("pro_smart_merge",on).apply());box.addView(smartMerge,new LinearLayout.LayoutParams(-1,dp(56)));
        Switch smartEpg=new Switch(this);smartEpg.setText(L("Smart EPG · use extra EPG sources as fallback","Smart EPG · gebruik extra EPG-bronnen als fallback","Smart EPG · zusätzliche EPG-Quellen als Fallback"));smartEpg.setTextColor(0xFFF7F8FA);smartEpg.setChecked(SettingsStore.prefs(this).getBoolean("pro_smart_epg",false));smartEpg.setOnCheckedChangeListener((v,on)->SettingsStore.prefs(this).edit().putBoolean("pro_smart_epg",on).apply());box.addView(smartEpg,new LinearLayout.LayoutParams(-1,dp(56)));

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
                    if(isFinishing()||isDestroyed())return;
                    Toast.makeText(this,L("Sources synchronized with My NenoTV.","Bronnen gesynchroniseerd met Mijn NenoTV.","Quellen mit Mein NenoTV synchronisiert."),Toast.LENGTH_LONG).show();
                    render();
                });
            }catch(Exception ex){
                if(ex instanceof com.nenotv.player.entitlement.EntitlementClient.ServiceException&&"source_revision_conflict".equals(((com.nenotv.player.entitlement.EntitlementClient.ServiceException)ex).code)){
                    runOnUiThread(()->{
                        if(isFinishing()||isDestroyed())return;
                        render();
                        new android.app.AlertDialog.Builder(this)
                            .setTitle(L("Sources changed elsewhere","Bronnen elders gewijzigd","Quellen auf anderem Gerät geändert"))
                            .setMessage(L("Your local changes have not been synchronized.","Uw lokale wijzigingen zijn niet gesynchroniseerd.","Deine lokalen Änderungen wurden nicht synchronisiert."))
                            .setNegativeButton(L("Keep local","Lokaal behouden","Lokal behalten"),(d,w)->{})
                            .setPositiveButton(L("Use cloud version","Cloudversie gebruiken","Cloud-Version verwenden"),(d,w)->useCloudSources())
                            .show();
                    });
                }else showSyncFailure(ex);
            }
        });
    }

    void showSyncFailure(Exception error){
        String message=L("Sync unavailable","Sync niet beschikbaar","Synchronisierung nicht verfügbar");
        if("LOCAL_SOURCES_CHANGED_RETRY_SYNC".equals(error.getMessage()))message=L("Sources changed during sync. Sync again.","Bronnen gewijzigd tijdens synchronisatie. Synchroniseer opnieuw.","Quellen während der Synchronisierung geändert. Erneut synchronisieren.");
        final String safeMessage=message;
        runOnUiThread(()->{if(isFinishing()||isDestroyed())return;Toast.makeText(this,safeMessage,Toast.LENGTH_LONG).show();render();});
    }

    void useCloudSources(){
        exec.execute(()->{
            try{new SourceSyncClient(this).pull();runOnUiThread(()->{if(!isFinishing()&&!isDestroyed())render();});}
            catch(Exception error){showSyncFailure(error);}
        });
    }

    void menu(SourceStore.Entry e){
        String[] opts={
            L("Smart EPG sources","Smart EPG-bronnen","Smart-EPG-Quellen"),
            e.enabled?L("Disable","Uitschakelen","Deaktivieren"):L("Enable","Inschakelen","Aktivieren"),
            L("Move up","Omhoog","Nach oben"),
            L("Move down","Omlaag","Nach unten"),
            L("Delete","Verwijderen","Löschen")
        };
        new AlertDialog.Builder(this).setTitle(e.profile.name).setItems(opts,(d,w)->{
            if(w==0){editSmartEpg(e);return;}
            else if(w==1)sources.setEnabled(e.id,!e.enabled);
            else if(w==2)sources.move(e.id,-1);
            else if(w==3)sources.move(e.id,1);
            else if(w==4)new AlertDialog.Builder(this).setMessage(L("Delete this source?","Deze bron verwijderen?","Diese Quelle löschen?"))
                .setPositiveButton(L("Delete","Verwijderen","Löschen"),(x,y)->{sources.remove(e.id);render();})
                .setNegativeButton(L("Cancel","Annuleren","Abbrechen"),null).show();
            render();
        }).show();
    }

    void editSmartEpg(SourceStore.Entry e){
        com.nenotv.player.storage.SmartEpgStore epg=new com.nenotv.player.storage.SmartEpgStore(this);
        EditText input=new EditText(this);input.setText(android.text.TextUtils.join("\n",epg.urls(e.id)));input.setHint(L("One XMLTV/EPG URL per line","Eén XMLTV/EPG-URL per regel","Eine XMLTV/EPG-URL pro Zeile"));input.setMinLines(5);input.setGravity(Gravity.TOP);input.setInputType(android.text.InputType.TYPE_CLASS_TEXT|android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        new AlertDialog.Builder(this).setTitle(L("Smart EPG sources","Smart EPG-bronnen","Smart-EPG-Quellen")).setMessage(L("NenoTV tries these only when the normal provider EPG has no usable programme data.","NenoTV probeert deze alleen als de normale provider-EPG geen bruikbare programmagegevens heeft.","NenoTV verwendet diese nur, wenn die normale Provider-EPG keine brauchbaren Programmdaten liefert.")).setView(input)
            .setNegativeButton(L("Cancel","Annuleren","Abbrechen"),null)
            .setPositiveButton(L("Save","Opslaan","Speichern"),(d,w)->{
                java.util.ArrayList<String> urls=new java.util.ArrayList<>();for(String line:input.getText().toString().split("\\r?\\n")){String u=line.trim();if(u.startsWith("https://")||u.startsWith("http://"))urls.add(u);}
                epg.setUrls(e.id,urls);sources.touchSync();SettingsStore.prefs(this).edit().putBoolean("pro_smart_epg",!urls.isEmpty()).apply();
                Toast.makeText(this,L("Smart EPG saved","Smart EPG opgeslagen","Smart EPG gespeichert"),Toast.LENGTH_SHORT).show();render();
            }).show();
    }

    @Override protected void onDestroy(){exec.shutdownNow();super.onDestroy();}
}
