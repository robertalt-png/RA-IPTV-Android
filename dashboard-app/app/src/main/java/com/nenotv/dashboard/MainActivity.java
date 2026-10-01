package com.nenotv.dashboard;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Space;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.text.NumberFormat;
import java.util.Currency;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends Activity {
    private static final String API_URL = "https://nenotv.com/wp-json/nenotv-dashboard/v1/admin-app";
    private static final String PREFS = "nenotv_dashboard";
    private static final String PREF_TOKEN = "dashboard_token";

    private static final int BG = Color.rgb(7, 12, 22);
    private static final int SURFACE = Color.rgb(14, 22, 36);
    private static final int CARD = Color.rgb(20, 31, 49);
    private static final int CARD_2 = Color.rgb(24, 38, 58);
    private static final int TEXT = Color.rgb(244, 247, 251);
    private static final int MUTED = Color.rgb(148, 163, 184);
    private static final int GREEN = Color.rgb(52, 211, 153);
    private static final int ORANGE = Color.rgb(251, 191, 36);
    private static final int RED = Color.rgb(248, 113, 113);
    private static final int ACCENT = Color.rgb(45, 212, 191);
    private static final int BLUE = Color.rgb(96, 165, 250);

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final String[] tabNames = {"Overzicht", "Bezoekers", "App", "Testers", "Commerce", "Systeem"};

    private SharedPreferences prefs;
    private JSONObject data;
    private LinearLayout content;
    private LinearLayout tabStrip;
    private TextView updatedView;
    private Button refreshButton;
    private int selectedTab = 0;
    private boolean loading = false;

    private final Runnable autoRefresh = new Runnable() {
        @Override public void run() {
            if (!isFinishing() && getToken().length() > 0) refresh();
            handler.postDelayed(this, 30000);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        getWindow().setStatusBarColor(BG);
        getWindow().setNavigationBarColor(BG);

        if (getToken().isEmpty()) showPairing(null);
        else {
            showAdminShell();
            refresh();
        }
        handler.postDelayed(autoRefresh, 30000);
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (prefs != null && !getToken().isEmpty() && content != null) refresh();
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        executor.shutdownNow();
        super.onDestroy();
    }

    private String getToken() {
        return prefs == null ? "" : prefs.getString(PREF_TOKEN, "").trim();
    }

    private void showPairing(String error) {
        content = null;
        tabStrip = null;

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(BG);

        LinearLayout box = vertical();
        box.setGravity(Gravity.CENTER_HORIZONTAL);
        box.setPadding(dp(28), dp(56), dp(28), dp(34));
        scroll.addView(box, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView brand = text("NenoTV", 36, TEXT, true);
        box.addView(brand);
        TextView title = text("Admin", 23, ACCENT, true);
        title.setPadding(0, dp(1), 0, dp(14));
        box.addView(title);

        TextView desc = text("Privé controlekamer voor website, app, testers, analytics en commerce.", 15, MUTED, false);
        desc.setGravity(Gravity.CENTER);
        box.addView(desc);

        addSpace(box, 30);

        EditText token = new EditText(this);
        token.setTextColor(TEXT);
        token.setHintTextColor(MUTED);
        token.setHint("Admin-code plakken");
        token.setSingleLine(true);
        token.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD);
        token.setPadding(dp(16), 0, dp(16), 0);
        token.setBackground(roundRect(CARD, 14));
        box.addView(token, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(56)));

        if (error != null && !error.isEmpty()) {
            TextView e = text(error, 13, RED, false);
            e.setPadding(0, dp(10), 0, 0);
            box.addView(e);
        }

        Button pair = primaryButton("Koppelen");
        LinearLayout.LayoutParams pairLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(54));
        pairLp.topMargin = dp(18);
        box.addView(pair, pairLp);
        pair.setOnClickListener(v -> {
            String value = token.getText().toString().trim();
            if (value.length() < 20) {
                showPairing("De admin-code lijkt niet compleet.");
                return;
            }
            prefs.edit().putString(PREF_TOKEN, value).apply();
            showAdminShell();
            refresh();
        });

        TextView privacy = text("Alleen lezen. De app kan niets aanpassen of verwijderen.", 12, MUTED, false);
        privacy.setGravity(Gravity.CENTER);
        privacy.setPadding(dp(12), dp(24), dp(12), 0);
        box.addView(privacy);

        setContentView(scroll);
    }

    private void showAdminShell() {
        LinearLayout root = vertical();
        root.setBackgroundColor(BG);

        LinearLayout header = horizontal();
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(dp(16), dp(14), dp(16), dp(10));
        root.addView(header, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        LinearLayout titleBox = vertical();
        header.addView(titleBox, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        titleBox.addView(text("NenoTV Admin", 24, TEXT, true));
        updatedView = text("Verbinden…", 12, MUTED, false);
        updatedView.setPadding(0, dp(2), 0, 0);
        titleBox.addView(updatedView);

        refreshButton = primaryButton("↻");
        refreshButton.setTextSize(22);
        header.addView(refreshButton, new LinearLayout.LayoutParams(dp(50), dp(44)));
        refreshButton.setOnClickListener(v -> refresh());

        HorizontalScrollView tabsScroll = new HorizontalScrollView(this);
        tabsScroll.setHorizontalScrollBarEnabled(false);
        tabsScroll.setFillViewport(false);
        tabStrip = horizontal();
        tabStrip.setPadding(dp(12), dp(2), dp(12), dp(8));
        tabsScroll.addView(tabStrip, new HorizontalScrollView.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        root.addView(tabsScroll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(54)));

        for (int i = 0; i < tabNames.length; i++) {
            final int index = i;
            Button b = tabButton(tabNames[i], i == selectedTab);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(40));
            if (i > 0) lp.leftMargin = dp(7);
            tabStrip.addView(b, lp);
            b.setOnClickListener(v -> {
                selectedTab = index;
                refreshTabs();
                renderCurrentTab();
            });
        }

        View divider = new View(this);
        divider.setBackgroundColor(Color.rgb(29, 41, 58));
        root.addView(divider, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1)));

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        content = vertical();
        content.setPadding(dp(16), dp(14), dp(16), dp(34));
        scroll.addView(content, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        root.addView(scroll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        setContentView(root);
        renderCurrentTab();
    }

    private void refreshTabs() {
        if (tabStrip == null) return;
        for (int i = 0; i < tabStrip.getChildCount(); i++) {
            View v = tabStrip.getChildAt(i);
            if (v instanceof Button) styleTab((Button) v, i == selectedTab);
        }
    }

    private void refresh() {
        if (loading) return;
        String token = getToken();
        if (token.isEmpty()) {
            showPairing(null);
            return;
        }

        loading = true;
        if (refreshButton != null) refreshButton.setEnabled(false);
        if (updatedView != null) updatedView.setText("Bijwerken…");

        executor.execute(() -> {
            HttpURLConnection connection = null;
            try {
                URL url = new URL(API_URL + "?days=7&fresh=" + System.currentTimeMillis());
                connection = (HttpURLConnection) url.openConnection();
                connection.setUseCaches(false);
                connection.setConnectTimeout(10000);
                connection.setReadTimeout(12000);
                connection.setRequestMethod("GET");
                connection.setRequestProperty("Accept", "application/json");
                connection.setRequestProperty("Authorization", "Bearer " + token);
                connection.setRequestProperty("Cache-Control", "no-cache, no-store, max-age=0");
                connection.setRequestProperty("Pragma", "no-cache");
                connection.setRequestProperty("User-Agent", "NenoTV-Admin/0.2.0 Android");

                int code = connection.getResponseCode();
                InputStream stream = code >= 200 && code < 300 ? connection.getInputStream() : connection.getErrorStream();
                String body = readAll(stream);

                if (code == 401 || code == 403) {
                    runOnUiThread(() -> {
                        loading = false;
                        prefs.edit().remove(PREF_TOKEN).apply();
                        showPairing("Deze admin-code is niet geldig.");
                    });
                    return;
                }
                if (code < 200 || code >= 300) throw new Exception("HTTP " + code);

                JSONObject next = new JSONObject(body);
                runOnUiThread(() -> {
                    data = next;
                    loading = false;
                    if (refreshButton != null) refreshButton.setEnabled(true);
                    if (updatedView != null) {
                        String stamp = next.optString("updated_at", "");
                        updatedView.setText(stamp.isEmpty() ? "Zojuist bijgewerkt" : "Bijgewerkt · " + simplifyTime(stamp));
                    }
                    renderCurrentTab();
                });
            } catch (Exception e) {
                runOnUiThread(() -> {
                    loading = false;
                    if (refreshButton != null) refreshButton.setEnabled(true);
                    if (updatedView != null) updatedView.setText("Kan niet bijwerken");
                    renderLoadError();
                });
            } finally {
                if (connection != null) connection.disconnect();
            }
        });
    }

    private void renderCurrentTab() {
        if (content == null) return;
        content.removeAllViews();

        if (data == null) {
            TextView loadingText = text("NenoTV gegevens laden…", 15, MUTED, false);
            loadingText.setGravity(Gravity.CENTER);
            loadingText.setPadding(0, dp(60), 0, dp(30));
            content.addView(loadingText);
            return;
        }

        switch (selectedTab) {
            case 1: renderAnalytics(); break;
            case 2: renderApp(); break;
            case 3: renderTesters(); break;
            case 4: renderCommerce(); break;
            case 5: renderSystem(); break;
            default: renderOverview();
        }
    }

    private void renderLoadError() {
        if (content == null) return;
        content.removeAllViews();
        LinearLayout box = panel();
        TextView title = text("Geen verbinding", 18, RED, true);
        box.addView(title);
        TextView body = text("De app kon de NenoTV API niet bereiken. Controleer je internetverbinding en tik op vernieuwen.", 14, MUTED, false);
        body.setPadding(0, dp(8), 0, 0);
        box.addView(body);
        content.addView(box);
    }

    private void renderOverview() {
        JSONObject site = obj(data, "site");
        JSONObject app = obj(data, "app");
        JSONObject today = obj(data, "today");
        JSONObject analytics = obj(data, "analytics");
        JSONObject testers = obj(data, "testers");
        JSONObject usage = obj(data, "app_usage");
        JSONObject commerce = obj(data, "commerce_detail");
        JSONObject system = obj(data, "system");

        boolean siteOnline = "online".equalsIgnoreCase(site.optString("status"));
        boolean appBridge = "online".equalsIgnoreCase(app.optString("bridge_status"));
        boolean siteBridge = "online".equalsIgnoreCase(system.optString("site_bridge"));

        TextView headline = text("Controlekamer", 21, TEXT, true);
        content.addView(headline);
        TextView sub = text("Alles wat nu op NenoTV gebeurt, in één overzicht.", 13, MUTED, false);
        sub.setPadding(0, dp(3), 0, dp(12));
        content.addView(sub);

        LinearLayout health = panel();
        LinearLayout healthTop = horizontal();
        healthTop.setGravity(Gravity.CENTER_VERTICAL);
        TextView healthTitle = text(overallHealthy(siteOnline, appBridge, siteBridge) ? "Alles operationeel" : "Aandacht nodig",
                17, overallHealthy(siteOnline, appBridge, siteBridge) ? GREEN : ORANGE, true);
        healthTop.addView(healthTitle, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView dot = pill(overallHealthy(siteOnline, appBridge, siteBridge) ? "LIVE" : "CHECK",
                overallHealthy(siteOnline, appBridge, siteBridge) ? GREEN : ORANGE);
        healthTop.addView(dot);
        health.addView(healthTop);
        addStatusLine(health, "Website", siteOnline ? "Online" : "Offline", siteOnline ? GREEN : RED);
        addStatusLine(health, "Site Bridge", siteBridge ? "Online" : "Offline", siteBridge ? GREEN : RED);
        addStatusLine(health, "App Bridge", appBridge ? "Online" : "Offline", appBridge ? GREEN : RED);
        addStatusLine(health, "Player/app", app.optString("status", "not_live").equals("live") ? "Publiek live" : "Test / prelaunch", ORANGE);
        content.addView(health);

        addSectionTitle("Nu");
        addMetricPair(
                metric("Live bezoekers", num(analytics.optInt("live_visitors")), "laatste 5 minuten", ACCENT),
                metric("App-contacten", num(usage.optInt("active_5m")), "laatste 5 minuten", BLUE)
        );
        addMetricPair(
                metric("Testers actief", num(testers.optInt("active_today")), "vandaag", TEXT),
                metric("Sessies tests", num(testers.optInt("test_sessions")), "totaal", TEXT)
        );

        addSectionTitle("Vandaag");
        addMetricPair(
                metric("Bezoekers", num(today.optInt("visitors")), num(analytics.optInt("today_sessions")) + " sessies", TEXT),
                metric("Pageviews", num(today.optInt("pageviews")), analytics.optString("top_country_name", "—"), TEXT)
        );
        addMetricPair(
                metric("Orders", num(today.optInt("orders")), "betaald", TEXT),
                metric("Omzet", money(today.optDouble("revenue"), today.optString("currency", "EUR")), "vandaag", TEXT)
        );

        JSONArray alerts = data.optJSONArray("alerts");
        LinearLayout alert = panel();
        if (alerts == null || alerts.length() == 0) {
            TextView ok = text("✓ Geen open waarschuwingen", 15, GREEN, true);
            alert.addView(ok);
        } else {
            alert.setBackground(roundRect(Color.rgb(52, 38, 16), 16));
            alert.addView(text("Waarschuwingen", 16, ORANGE, true));
            for (int i = 0; i < alerts.length(); i++) {
                JSONObject a = alerts.optJSONObject(i);
                if (a == null) continue;
                TextView row = text("• " + a.optString("message", "Controle nodig"), 14, TEXT, false);
                row.setPadding(0, dp(7), 0, 0);
                alert.addView(row);
            }
        }
        content.addView(alert);

        Button siteButton = secondaryButton("NenoTV website openen");
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(50));
        lp.topMargin = dp(12);
        content.addView(siteButton, lp);
        siteButton.setOnClickListener(v -> openUrl("https://nenotv.com/"));

        TextView note = text("App-contacten zijn apparaten die de NenoTV backend recent hebben geraakt. Echte kijktijd van reguliere gebruikers wordt later als aparte player-telemetrie toegevoegd.", 11, MUTED, false);
        note.setPadding(dp(3), dp(13), dp(3), 0);
        content.addView(note);
    }

    private void renderAnalytics() {
        JSONObject today = obj(data, "today");
        JSONObject analytics = obj(data, "analytics");
        JSONObject detail = obj(data, "analytics_detail");
        JSONObject summary = obj(detail, "summary");

        addPageTitle("Bezoekers", "NenoTV Analytics · websiteverkeer");

        addMetricPair(
                metric("Live", num(analytics.optInt("live_visitors")), "5 minuten", ACCENT),
                metric("Vandaag", num(today.optInt("visitors")), "bezoekers", TEXT)
        );
        addMetricPair(
                metric("Sessies", num(analytics.optInt("today_sessions")), "vandaag", TEXT),
                metric("Pageviews", num(today.optInt("pageviews")), "vandaag", TEXT)
        );

        addSectionTitle("Laatste 7 dagen");
        addMetricPair(
                metric("Bezoekers", num(summary.optInt("visitors")), "uniek", TEXT),
                metric("Pageviews", num(summary.optInt("pageviews")), num(summary.optInt("countries")) + " landen", TEXT)
        );

        JSONArray daily = detail.optJSONArray("daily");
        if (daily != null && daily.length() > 0) {
            LinearLayout chartPanel = panel();
            chartPanel.addView(text("Verkeer per dag", 15, TEXT, true));
            BarsView bars = new BarsView(daily);
            LinearLayout.LayoutParams chartLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(150));
            chartLp.topMargin = dp(10);
            chartPanel.addView(bars, chartLp);
            content.addView(chartPanel);
        }

        addListSection("Toplanden", detail.optJSONArray("countries"), "visitors", "bezoekers");
        addListSection("Populaire pagina’s", detail.optJSONArray("pages"), "pageviews", "views");
        addListSection("Verkeersbronnen", detail.optJSONArray("sources"), "visitors", "bezoekers");
        addListSection("Apparaten", detail.optJSONArray("devices"), "visitors", "bezoekers");
        addListSection("Browsers", detail.optJSONArray("browsers"), "visitors", "bezoekers");
    }

    private void renderApp() {
        JSONObject app = obj(data, "app");
        JSONObject usage = obj(data, "app_usage");

        addPageTitle("App & player", "Backend, app-contacten en technische gezondheid");

        boolean bridge = "online".equalsIgnoreCase(app.optString("bridge_status"));
        LinearLayout status = panel();
        addStatusLine(status, "App Bridge", bridge ? "Online" : "Offline", bridge ? GREEN : RED);
        addStatusLine(status, "Publieke app", "live".equals(app.optString("status")) ? "Live" : "Nog niet live", "live".equals(app.optString("status")) ? GREEN : ORANGE);
        addKeyValue(status, "Laatste versie", emptyDash(app.optString("latest_version")));
        addKeyValue(status, "Platform", emptyDash(app.optString("platform")));
        addKeyValue(status, "Laatste contact", emptyDash(app.optString("last_contact_gmt")));
        addKeyValue(status, "Laatste resultaat", emptyDash(app.optString("last_outcome")));
        content.addView(status);

        addSectionTitle("Gebruik");
        addMetricPair(
                metric("Actief 5 min", num(usage.optInt("active_5m")), "backendcontact", ACCENT),
                metric("Actief 24 uur", num(usage.optInt("active_24h")), "apparaten", TEXT)
        );
        addMetricPair(
                metric("Bekende apparaten", num(usage.optInt("total_devices")), "totaal", TEXT),
                metric("API requests", num(usage.optInt("requests")), "totaal", TEXT)
        );
        addMetricPair(
                metric("API errors", num(usage.optInt("errors")), "totaal", usage.optInt("errors") > 0 ? ORANGE : GREEN),
                metric("Latency", num(usage.optInt("avg_latency_ms")) + " ms", "gemiddeld", TEXT)
        );

        LinearLayout note = panel();
        note.addView(text("Player-telemetrie", 15, TEXT, true));
        TextView p = text("Deze versie toont nu betrouwbare backendcontacten en tester-kijktijd. Exacte live kijktijd van alle toekomstige gebruikers voegen we toe zodra de publieke player-telemetrie in de app wordt ingeschakeld.", 13, MUTED, false);
        p.setPadding(0, dp(7), 0, 0);
        note.addView(p);
        content.addView(note);
    }

    private void renderTesters() {
        JSONObject t = obj(data, "testers");

        addPageTitle("Founding Testers", "Aanvragen, activiteit, voortgang en kwaliteit");

        LinearLayout status = panel();
        addStatusLine(status, "Testgroep", t.optBoolean("test_open") ? "Open" : "Nog gesloten", t.optBoolean("test_open") ? GREEN : ORANGE);
        addKeyValue(status, "Aanmeldingen", num(t.optInt("applications")));
        addKeyValue(status, "In testgroep", num(t.optInt("in_test_group")));
        addKeyValue(status, "Reserve", num(t.optInt("reserve")));
        addKeyValue(status, "Uitgenodigd", num(t.optInt("invited")));
        content.addView(status);

        addSectionTitle("Gebruik");
        addMetricPair(
                metric("Actief vandaag", num(t.optInt("active_today")), "testers", TEXT),
                metric("App gestart", num(t.optInt("app_started")), "testers", TEXT)
        );
        addMetricPair(
                metric("Sessies", num(t.optInt("test_sessions")), "testgebruik", TEXT),
                metric("Testtijd", duration(t.optLong("active_seconds")), "actief", TEXT)
        );
        addMetricPair(
                metric("Crashes", num(t.optInt("app_crashes")), "gemeld", t.optInt("app_crashes") > 0 ? ORANGE : GREEN),
                metric("Aandacht", num(t.optInt("attention_needed")), "testers", t.optInt("attention_needed") > 0 ? ORANGE : GREEN)
        );

        addSectionTitle("Voortgang");
        LinearLayout progress = panel();
        addKeyValue(progress, "Mid-evaluaties", num(t.optInt("mid_evaluations")));
        addKeyValue(progress, "Eindevaluaties", num(t.optInt("final_evaluations")));
        addKeyValue(progress, "Klaar voor review", num(t.optInt("ready_review")));
        addKeyValue(progress, "Voltooid", num(t.optInt("completed")));
        addKeyValue(progress, "Pro gereserveerd", num(t.optInt("reward_reserved")));
        addKeyValue(progress, "Pro geactiveerd", num(t.optInt("reward_activated")));
        content.addView(progress);
    }

    private void renderCommerce() {
        JSONObject today = obj(data, "today");
        JSONObject c = obj(data, "commerce_detail");
        JSONObject access = obj(data, "access");

        addPageTitle("Commerce", "WooCommerce, Mollie en Pro-toegang");

        addMetricPair(
                metric("Omzet vandaag", money(today.optDouble("revenue"), today.optString("currency", "EUR")), "betaald", TEXT),
                metric("Orders vandaag", num(today.optInt("orders")), "betaald", TEXT)
        );
        addMetricPair(
                metric("Omzet 7 dagen", money(c.optDouble("revenue"), c.optString("currency", "EUR")), "betaald", TEXT),
                metric("Orders 7 dagen", num(c.optInt("orders")), "betaald", TEXT)
        );

        LinearLayout status = panel();
        addStatusLine(status, "Mollie", c.optBoolean("mollie_test_mode") ? "Testmodus" : "Live", c.optBoolean("mollie_test_mode") ? ORANGE : GREEN);
        addStatusLine(status, "Publieke verkoop", c.optBoolean("public_sales_enabled") ? "Open" : "Gesloten", c.optBoolean("public_sales_enabled") ? GREEN : ORANGE);
        addKeyValue(status, "Mislukte betalingen", num(c.optInt("failed")));
        addKeyValue(status, "Actieve trials", num(access.optInt("active_trials")));
        addKeyValue(status, "Actieve Pro", num(access.optInt("active_pro")));
        addKeyValue(status, "Trialfunctie", access.optBoolean("trial_enabled") ? "Aan" : "Nog uit");
        content.addView(status);

        TextView note = text("De verkoop staat bewust nog dicht zolang Light/Pro en de publieke app in test zijn.", 12, MUTED, false);
        note.setPadding(dp(3), dp(10), dp(3), 0);
        content.addView(note);
    }

    private void renderSystem() {
        JSONObject s = obj(data, "system");

        addPageTitle("Systeem", "WordPress, bridges, plugins en launch-status");

        LinearLayout status = panel();
        addStatusLine(status, "Site Bridge", "online".equals(s.optString("site_bridge")) ? "Online" : "Offline",
                "online".equals(s.optString("site_bridge")) ? GREEN : RED);
        addStatusLine(status, "App Bridge", "online".equals(s.optString("app_bridge")) ? "Online" : "Offline",
                "online".equals(s.optString("app_bridge")) ? GREEN : RED);
        addKeyValue(status, "WordPress", emptyDash(s.optString("wordpress")));
        addKeyValue(status, "PHP", emptyDash(s.optString("php")));
        addKeyValue(status, "WooCommerce", emptyDash(s.optString("woocommerce")));
        addKeyValue(status, "Tijdzone", emptyDash(s.optString("timezone")));
        addKeyValue(status, "Entitlements", emptyDash(s.optString("entitlement_mode")));
        addKeyValue(status, "Woo coming soon", emptyDash(s.optString("woocommerce_coming_soon")));
        addKeyValue(status, "Actieve plugins", num(s.optInt("active_plugins")));
        content.addView(status);

        JSONArray plugins = s.optJSONArray("plugins");
        if (plugins != null) {
            addSectionTitle("Actieve plugins");
            LinearLayout list = panel();
            for (int i = 0; i < plugins.length(); i++) {
                JSONObject p = plugins.optJSONObject(i);
                if (p == null) continue;
                addKeyValue(list, p.optString("name", "Plugin"), p.optString("version", ""));
            }
            content.addView(list);
        }

        Button reset = secondaryButton("Admin-koppeling wissen");
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(50));
        lp.topMargin = dp(14);
        content.addView(reset, lp);
        reset.setOnClickListener(v -> {
            prefs.edit().remove(PREF_TOKEN).apply();
            data = null;
            showPairing(null);
        });
    }

    private boolean overallHealthy(boolean site, boolean appBridge, boolean siteBridge) {
        return site && appBridge && siteBridge;
    }

    private void addPageTitle(String title, String subtitle) {
        content.addView(text(title, 22, TEXT, true));
        TextView sub = text(subtitle, 13, MUTED, false);
        sub.setPadding(0, dp(3), 0, dp(13));
        content.addView(sub);
    }

    private void addSectionTitle(String title) {
        TextView t = text(title, 14, MUTED, true);
        t.setPadding(dp(2), dp(12), 0, dp(8));
        content.addView(t);
    }

    private Metric metric(String label, String value, String sub, int color) {
        return new Metric(label, value, sub, color);
    }

    private void addMetricPair(Metric a, Metric b) {
        LinearLayout row = horizontal();
        LinearLayout.LayoutParams rowLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rowLp.bottomMargin = dp(10);
        content.addView(row, rowLp);

        row.addView(metricCard(a), new LinearLayout.LayoutParams(0, dp(112), 1f));
        LinearLayout.LayoutParams right = new LinearLayout.LayoutParams(0, dp(112), 1f);
        right.leftMargin = dp(10);
        row.addView(metricCard(b), right);
    }

    private LinearLayout metricCard(Metric m) {
        LinearLayout card = vertical();
        card.setPadding(dp(14), dp(13), dp(14), dp(12));
        card.setBackground(roundRect(CARD, 15));

        TextView label = text(m.label, 12, MUTED, true);
        card.addView(label);

        TextView value = text(m.value, 25, m.color, true);
        value.setPadding(0, dp(5), 0, 0);
        card.addView(value);

        TextView sub = text(m.sub, 11, MUTED, false);
        sub.setPadding(0, dp(3), 0, 0);
        card.addView(sub);
        return card;
    }

    private LinearLayout panel() {
        LinearLayout p = vertical();
        p.setPadding(dp(15), dp(14), dp(15), dp(14));
        p.setBackground(roundRect(CARD, 16));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(10);
        p.setLayoutParams(lp);
        return p;
    }

    private void addStatusLine(LinearLayout parent, String label, String value, int color) {
        LinearLayout row = horizontal();
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(9), 0, 0);
        TextView l = text(label, 13, MUTED, false);
        row.addView(l, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView v = text("● " + value, 13, color, true);
        row.addView(v);
        parent.addView(row);
    }

    private void addKeyValue(LinearLayout parent, String label, String value) {
        LinearLayout row = horizontal();
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(9), 0, 0);
        TextView l = text(label, 13, MUTED, false);
        row.addView(l, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView v = text(value, 13, TEXT, true);
        v.setGravity(Gravity.END);
        row.addView(v, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        parent.addView(row);
    }

    private void addListSection(String title, JSONArray items, String valueKey, String suffix) {
        if (items == null || items.length() == 0) return;
        addSectionTitle(title);
        LinearLayout list = panel();
        for (int i = 0; i < items.length(); i++) {
            JSONObject item = items.optJSONObject(i);
            if (item == null) continue;
            String label = item.optString("label", "—");
            String value = item.optString(valueKey, "0") + " " + suffix;
            addKeyValue(list, label, value);
        }
        content.addView(list);
    }

    private TextView pill(String value, int color) {
        TextView t = text(value, 10, color, true);
        t.setGravity(Gravity.CENTER);
        t.setPadding(dp(10), dp(5), dp(10), dp(5));
        GradientDrawable g = roundRect(Color.argb(38, Color.red(color), Color.green(color), Color.blue(color)), 20);
        g.setStroke(dp(1), Color.argb(100, Color.red(color), Color.green(color), Color.blue(color)));
        t.setBackground(g);
        return t;
    }

    private Button tabButton(String label, boolean active) {
        Button b = new Button(this);
        b.setAllCaps(false);
        b.setText(label);
        b.setTextSize(13);
        b.setTypeface(Typeface.create("sans-serif", Typeface.BOLD));
        b.setPadding(dp(15), 0, dp(15), 0);
        b.setMinWidth(0);
        b.setMinimumWidth(0);
        b.setMinHeight(0);
        b.setMinimumHeight(0);
        styleTab(b, active);
        return b;
    }

    private void styleTab(Button b, boolean active) {
        b.setTextColor(active ? Color.rgb(4, 24, 26) : MUTED);
        b.setBackground(roundRect(active ? ACCENT : SURFACE, 20));
    }

    private Button primaryButton(String value) {
        Button b = new Button(this);
        b.setAllCaps(false);
        b.setText(value);
        b.setTextColor(Color.rgb(4, 24, 26));
        b.setTextSize(14);
        b.setTypeface(Typeface.create("sans-serif", Typeface.BOLD));
        b.setBackground(roundRect(ACCENT, 14));
        b.setMinWidth(0);
        b.setMinimumWidth(0);
        return b;
    }

    private Button secondaryButton(String value) {
        Button b = new Button(this);
        b.setAllCaps(false);
        b.setText(value);
        b.setTextColor(TEXT);
        b.setTextSize(14);
        b.setTypeface(Typeface.create("sans-serif", Typeface.BOLD));
        GradientDrawable g = roundRect(CARD_2, 14);
        g.setStroke(dp(1), Color.rgb(55, 72, 94));
        b.setBackground(g);
        return b;
    }

    private TextView text(String value, int sp, int color, boolean bold) {
        TextView t = new TextView(this);
        t.setText(value);
        t.setTextSize(sp);
        t.setTextColor(color);
        t.setTypeface(Typeface.create("sans-serif", bold ? Typeface.BOLD : Typeface.NORMAL));
        return t;
    }

    private LinearLayout vertical() {
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.VERTICAL);
        return l;
    }

    private LinearLayout horizontal() {
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.HORIZONTAL);
        l.setBaselineAligned(false);
        return l;
    }

    private GradientDrawable roundRect(int color, int radiusDp) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(dp(radiusDp));
        return g;
    }

    private void addSpace(LinearLayout parent, int dpValue) {
        Space s = new Space(this);
        parent.addView(s, new LinearLayout.LayoutParams(1, dp(dpValue)));
    }

    private JSONObject obj(JSONObject parent, String key) {
        JSONObject o = parent == null ? null : parent.optJSONObject(key);
        return o == null ? new JSONObject() : o;
    }

    private String num(int value) {
        return NumberFormat.getIntegerInstance(new Locale("nl", "NL")).format(value);
    }

    private String money(double value, String code) {
        try {
            NumberFormat n = NumberFormat.getCurrencyInstance(new Locale("nl", "NL"));
            n.setCurrency(Currency.getInstance(code));
            return n.format(value);
        } catch (Exception e) {
            return String.format(new Locale("nl", "NL"), "€ %.2f", value);
        }
    }

    private String duration(long seconds) {
        if (seconds < 60) return seconds + " sec";
        long minutes = seconds / 60;
        if (minutes < 60) return minutes + " min";
        long hours = minutes / 60;
        long rest = minutes % 60;
        return rest == 0 ? hours + " u" : hours + " u " + rest + " m";
    }

    private String simplifyTime(String iso) {
        String s = iso.replace('T', ' ');
        int plus = s.indexOf('+');
        if (plus > 0) s = s.substring(0, plus);
        if (s.length() >= 16) return s.substring(5, 16);
        return s;
    }

    private String emptyDash(String value) {
        return value == null || value.trim().isEmpty() ? "—" : value;
    }

    private void openUrl(String url) {
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
        } catch (Exception ignored) { }
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private static String readAll(InputStream stream) throws Exception {
        if (stream == null) return "";
        BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8));
        StringBuilder b = new StringBuilder();
        String line;
        while ((line = reader.readLine()) != null) b.append(line);
        reader.close();
        return b.toString();
    }

    private static final class Metric {
        final String label;
        final String value;
        final String sub;
        final int color;
        Metric(String label, String value, String sub, int color) {
            this.label = label;
            this.value = value;
            this.sub = sub;
            this.color = color;
        }
    }

    private final class BarsView extends View {
        private final JSONArray rows;
        private final Paint barPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

        BarsView(JSONArray rows) {
            super(MainActivity.this);
            this.rows = rows;
            barPaint.setColor(ACCENT);
            textPaint.setColor(MUTED);
            textPaint.setTextSize(dp(10));
            setBackground(roundRect(SURFACE, 12));
            setPadding(dp(8), dp(10), dp(8), dp(8));
        }

        @Override protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            int n = rows == null ? 0 : rows.length();
            if (n == 0) return;

            int max = 1;
            for (int i = 0; i < n; i++) {
                JSONObject r = rows.optJSONObject(i);
                if (r != null) max = Math.max(max, r.optInt("pageviews", 0));
            }

            float left = getPaddingLeft();
            float top = getPaddingTop() + dp(10);
            float right = getWidth() - getPaddingRight();
            float bottom = getHeight() - getPaddingBottom() - dp(22);
            float areaW = right - left;
            float gap = dp(6);
            float barW = Math.max(dp(8), (areaW - gap * (n + 1)) / n);

            for (int i = 0; i < n; i++) {
                JSONObject r = rows.optJSONObject(i);
                if (r == null) continue;
                int val = r.optInt("pageviews", 0);
                float h = (bottom - top) * ((float) val / max);
                float x = left + gap + i * (barW + gap);
                canvas.drawRoundRect(x, bottom - h, x + barW, bottom, dp(4), dp(4), barPaint);

                String day = r.optString("day", "");
                String label = day.length() >= 10 ? day.substring(8, 10) : day;
                canvas.drawText(label, x + 1, getHeight() - dp(7), textPaint);
            }
        }
    }
}
