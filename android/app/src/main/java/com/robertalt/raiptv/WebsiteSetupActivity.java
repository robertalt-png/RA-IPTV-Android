package com.nenotv.player;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.net.Uri;
import android.os.*;
import android.view.Gravity;
import android.view.View;
import android.widget.*;
import com.nenotv.player.entitlement.WebsiteSetupJob;
import com.nenotv.player.storage.*;

/** Provider setup is part of the base app, not a Pro entitlement flow. */
public final class WebsiteSetupActivity extends Activity {
    private final Handler handler = new Handler(Looper.getMainLooper());
    private WebsiteSetupJob job;
    private TextView status;
    private ProgressBar progress;
    private Button retry;
    private boolean resumed, finished;
    private String language;
    private String t(String nl, String en, String de) { return "nl".equals(language) ? nl : "de".equals(language) ? de : en; }
    private int dp(int n) { return Math.round(n * getResources().getDisplayMetrics().density); }
    public static String websiteUrl(android.content.Context context) {
        return "https://sunnyiptv.com/sunnyiptv-setup/?lang=" + Uri.encode(SettingsStore.language(context));
    }
    private Button button(String title) {
        Button b = new Button(this); b.setText(title); b.setAllCaps(false); b.setMinHeight(dp(56)); return b;
    }
    @Override public void onCreate(Bundle saved) {
        super.onCreate(saved); language = SettingsStore.language(this);
        if (FamilyStore.active(this)) { FamilyUi.blocked(this); finish(); return; }
        if (!new AccountLinkStore(this).linked()) { startActivity(new Intent(this, PairingActivity.class).putExtra("first_run",true)); finish(); return; }
        ScrollView scroll = new ScrollView(this); scroll.setBackgroundColor(0xFF07090D);
        LinearLayout box = new LinearLayout(this); box.setOrientation(LinearLayout.VERTICAL); box.setGravity(Gravity.CENTER_HORIZONTAL); box.setPadding(dp(20),dp(28),dp(20),dp(20)); scroll.addView(box);
        TextView title = new TextView(this); title.setText(t("Mijn SunnyIPTV","My SunnyIPTV","Mein SunnyIPTV")); title.setTextColor(0xFFF7F8FA); title.setTextSize(24); box.addView(title);
        status = new TextView(this); status.setTextColor(0xFFF7F8FA); status.setTextSize(18); status.setGravity(Gravity.CENTER); status.setPadding(0,dp(24),0,dp(24)); status.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE); box.addView(status,new LinearLayout.LayoutParams(-1,-2));
        progress = new ProgressBar(this); box.addView(progress,new LinearLayout.LayoutParams(dp(44),dp(44)));
        Button website = button(t("Vul je tv-aanbieder in","Enter your TV provider","TV-Anbieter eingeben")); website.setTextColor(0xFF07090D); website.setBackgroundTintList(android.content.res.ColorStateList.valueOf(0xFFFFD600));
        website.setOnClickListener(v -> { try { startActivity(new Intent(Intent.ACTION_VIEW,Uri.parse(websiteUrl(this)))); } catch (Exception e) { status.setText(t("Open Mijn SunnyIPTV op je telefoon.","Open My SunnyIPTV on your phone.","Mein SunnyIPTV auf dem Handy oeffnen.")); } });
        LinearLayout.LayoutParams wide = new LinearLayout.LayoutParams(-1,-2); wide.topMargin=dp(24); box.addView(website,wide);
        retry = button(t("Opnieuw proberen","Retry","Erneut versuchen")); retry.setOnClickListener(v -> restart()); box.addView(retry,new LinearLayout.LayoutParams(-1,-2));
        Button manual = button(t("Demo of invoeren op dit apparaat","Demo or enter on this device","Demo oder auf diesem Geraet eingeben")); manual.setOnClickListener(v -> startActivity(new Intent(this,ProfileActivity.class))); box.addView(manual,new LinearLayout.LayoutParams(-1,-2));
        setContentView(scroll); ScreenInsets.browsing(this); UiText.applyDirection(this); job = WebsiteSetupJob.start(this,false);
    }
    private void restart() {
        if (job != null && job.state == WebsiteSetupJob.State.CHOICE) {
            new AlertDialog.Builder(this).setMessage(t("Gebruik de lijst van Mijn SunnyIPTV in plaats van je lokale instellingen?","Use the My SunnyIPTV list instead of your local settings?","Liste von Mein SunnyIPTV statt lokaler Einstellungen verwenden?"))
                    .setNegativeButton(android.R.string.cancel,null).setPositiveButton(t("Gebruik website-lijst","Use website list","Website-Liste verwenden"),(dialog,which)->{ job=WebsiteSetupJob.start(this,true); handler.post(tick); }).show();
        } else { job=WebsiteSetupJob.start(this,false); handler.post(tick); }
    }
    private final Runnable tick = new Runnable() { public void run() {
        if (!resumed || finished || job == null) return;
        if (FamilyStore.active(WebsiteSetupActivity.this)) { finish(); return; }
        WebsiteSetupJob.State s = job.state;
        progress.setVisibility(s==WebsiteSetupJob.State.WAITING || s==WebsiteSetupJob.State.PREPARING || s==WebsiteSetupJob.State.IMPORTING ? View.VISIBLE : View.GONE);
        retry.setVisibility(s==WebsiteSetupJob.State.CHOICE || s==WebsiteSetupJob.State.FAILED || s==WebsiteSetupJob.State.EXPIRED ? View.VISIBLE : View.GONE);
        switch (s) {
            case WAITING: status.setText(t("Vul je tv-aanbieder in op Mijn SunnyIPTV.","Enter your TV provider on My SunnyIPTV.","TV-Anbieter in Mein SunnyIPTV eingeben.")); break;
            case PREPARING: status.setText(t("Mijn SunnyIPTV maakt je complete lijst klaar.","My SunnyIPTV is preparing your complete list.","Mein SunnyIPTV bereitet deine komplette Liste vor.")); break;
            case IMPORTING: status.setText(t("Je complete lijst wordt ontvangen en opgeslagen.","Receiving and saving your complete list.","Komplette Liste wird empfangen und gespeichert.")); break;
            case CHOICE: status.setText(t("Je lokale instellingen zijn behouden. Kies opnieuw proberen om de website-lijst te gebruiken.","Your local settings are preserved. Retry to choose the website list.","Lokale Einstellungen bleiben erhalten. Erneut versuchen fuer die Website-Liste.")); break;
            case EXPIRED: case FAILED: status.setText(t("De lijst is nog niet geladen. Controleer je aanbiedergegevens op Mijn SunnyIPTV en probeer opnieuw.","The list has not loaded. Check your provider details on My SunnyIPTV and retry.","Liste nicht geladen. Anbieterdaten in Mein SunnyIPTV pruefen und erneut versuchen.")); break;
            case READY:
                finished=true; setResult(RESULT_OK); startActivity(new Intent(WebsiteSetupActivity.this,MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP|Intent.FLAG_ACTIVITY_SINGLE_TOP)); finish(); return;
        }
        handler.postDelayed(this,750);
    }};
    @Override protected void onResume() { super.onResume(); resumed=true; handler.post(tick); }
    @Override protected void onPause() { resumed=false; handler.removeCallbacks(tick); super.onPause(); }
    @Override protected void onDestroy() { handler.removeCallbacksAndMessages(null); super.onDestroy(); }
}
