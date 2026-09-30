package com.nenotv.dashboard;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Bundle;
import android.text.InputType;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
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
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends Activity {
    private static final String API_URL = "https://nenotv.com/wp-json/nenotv-dashboard/v1/summary";
    private static final String PREFS = "nenotv_dashboard";
    private static final String PREF_TOKEN = "dashboard_token";

    private static final int BG = Color.rgb(8, 13, 25);
    private static final int CARD = Color.rgb(18, 27, 45);
    private static final int TEXT = Color.rgb(244, 247, 251);
    private static final int MUTED = Color.rgb(148, 163, 184);
    private static final int GREEN = Color.rgb(52, 211, 153);
    private static final int ORANGE = Color.rgb(251, 191, 36);
    private static final int RED = Color.rgb(248, 113, 113);
    private static final int ACCENT = Color.rgb(45, 212, 191);

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Map<String, String> links = new HashMap<>();
    private SharedPreferences prefs;
    private TextView updatedView;
    private TextView alertView;
    private Card siteCard;
    private Card appCard;
    private Card visitorsCard;
    private Card pageviewsCard;
    private Card revenueCard;
    private Card ordersCard;
    private Card trialsCard;
    private Card proCard;
    private Button refreshButton;
    private boolean loading;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        getWindow().setStatusBarColor(BG);
        getWindow().setNavigationBarColor(BG);
        if (getToken().isEmpty()) showPairing(null);
        else {
            showDashboard();
            refresh();
        }
    }

    @Override
    protected void onDestroy() {
        executor.shutdownNow();
        super.onDestroy();
    }

    private String getToken() {
        return prefs.getString(PREF_TOKEN, "").trim();
    }

    private void showPairing(String error) {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(BG);

        LinearLayout box = vertical();
        box.setGravity(Gravity.CENTER_HORIZONTAL);
        box.setPadding(dp(28), dp(54), dp(28), dp(32));
        scroll.addView(box, new ScrollView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        box.addView(text("NenoTV", 34, TEXT, true));
        TextView title = text("Dashboard", 22, TEXT, true);
        title.setPadding(0, dp(2), 0, dp(12));
        box.addView(title);

        TextView desc = text("Eenmalig koppelen met je privé-dashboard.", 15, MUTED, false);
        desc.setGravity(Gravity.CENTER);
        box.addView(desc);

        addSpace(box, 28);

        EditText token = new EditText(this);
        token.setTextColor(TEXT);
        token.setHintTextColor(MUTED);
        token.setHint("Dashboardcode plakken");
        token.setSingleLine(true);
        token.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD);
        token.setPadding(dp(16), 0, dp(16), 0);
        token.setBackground(roundRect(CARD, 14));
        box.addView(token, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(56)));

        if (error != null && !error.isEmpty()) {
            TextView errorView = text(error, 13, RED, false);
            errorView.setPadding(0, dp(10), 0, 0);
            box.addView(errorView);
        }

        Button pair = button("Koppelen");
        LinearLayout.LayoutParams pairLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(54));
        pairLp.topMargin = dp(18);
        box.addView(pair, pairLp);
        pair.setOnClickListener(v -> {
            String value = token.getText().toString().trim();
            if (value.length() < 20) {
                showPairing("De dashboardcode lijkt niet compleet.");
                return;
            }
            prefs.edit().putString(PREF_TOKEN, value).apply();
            showDashboard();
            refresh();
        });

        TextView privacy = text("Alleen samengevatte cijfers. Geen klantgegevens of IPTV-informatie.", 12, MUTED, false);
        privacy.setGravity(Gravity.CENTER);
        privacy.setPadding(dp(12), dp(22), dp(12), 0);
        box.addView(privacy);

        setContentView(scroll);
    }

    private void showDashboard() {
        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(BG);
        scroll.setFillViewport(true);

        LinearLayout root = vertical();
        root.setPadding(dp(18), dp(18), dp(18), dp(28));
        scroll.addView(root, new ScrollView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        LinearLayout header = horizontal();
        header.setGravity(Gravity.CENTER_VERTICAL);
        root.addView(header, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        LinearLayout titleWrap = vertical();
        header.addView(titleWrap, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        titleWrap.addView(text("NenoTV Dashboard", 24, TEXT, true));
        updatedView = text("Bijwerken…", 12, MUTED, false);
        updatedView.setPadding(0, dp(3), 0, 0);
        titleWrap.addView(updatedView);

        refreshButton = button("Vernieuwen");
        refreshButton.setTextSize(13);
        header.addView(refreshButton, new LinearLayout.LayoutParams(dp(112), dp(44)));
        refreshButton.setOnClickListener(v -> refresh());

        addSpace(root, 10);

        LinearLayout statusRow = horizontal();
        addRow(root, statusRow);
        siteCard = addCard(statusRow, "Website", "—", "Status", "site");
        appCard = addCard(statusRow, "App", "—", "Status", "app_monitor");

        TextView todayTitle = text("Vandaag", 14, MUTED, true);
        todayTitle.setPadding(dp(2), dp(4), 0, dp(8));
        root.addView(todayTitle);

        LinearLayout row1 = horizontal();
        addRow(root, row1);
        visitorsCard = addCard(row1, "Bezoekers", "—", "Koko Analytics", "analytics");
        pageviewsCard = addCard(row1, "Pageviews", "—", "Koko Analytics", "analytics");

        LinearLayout row2 = horizontal();
        addRow(root, row2);
        revenueCard = addCard(row2, "Omzet", "—", "Betaalde orders", "orders");
        ordersCard = addCard(row2, "Bestellingen", "—", "Betaald vandaag", "orders");

        LinearLayout row3 = horizontal();
        addRow(root, row3);
        trialsCard = addCard(row3, "Trials", "—", "Actief", "entitlements");
        proCard = addCard(row3, "Pro", "—", "Actief", "entitlements");

        alertView = text("Controleren…", 14, MUTED, false);
        alertView.setPadding(dp(14), dp(14), dp(14), dp(14));
        alertView.setBackground(roundRect(CARD, 14));
        root.addView(alertView, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        Button websiteButton = button("NenoTV openen");
        LinearLayout.LayoutParams webLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(52));
        webLp.topMargin = dp(14);
        root.addView(websiteButton, webLp);
        websiteButton.setOnClickListener(v -> openLink("website"));

        TextView pairing = text("Koppeling wijzigen", 12, MUTED, false);
        pairing.setGravity(Gravity.CENTER);
        pairing.setPadding(0, dp(14), 0, dp(4));
        pairing.setOnClickListener(v -> {
            prefs.edit().remove(PREF_TOKEN).apply();
            showPairing(null);
        });
        root.addView(pairing);

        setContentView(scroll);
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
            try {
                HttpURLConnection connection = (HttpURLConnection) new URL(API_URL).openConnection();
                connection.setConnectTimeout(10000);
                connection.setReadTimeout(10000);
                connection.setRequestMethod("GET");
                connection.setRequestProperty("Accept", "application/json");
                connection.setRequestProperty("Authorization", "Bearer " + token);
                connection.setRequestProperty("User-Agent", "NenoTV-Dashboard/0.1.2 Android");

                int code = connection.getResponseCode();
                InputStream stream = code >= 200 && code < 300 ? connection.getInputStream() : connection.getErrorStream();
                String body = readAll(stream);
                connection.disconnect();

                if (code == 401 || code == 403) {
                    runOnUiThread(() -> {
                        loading = false;
                        prefs.edit().remove(PREF_TOKEN).apply();
                        showPairing("Deze dashboardcode is niet geldig.");
                    });
                    return;
                }
                if (code < 200 || code >= 300) throw new Exception("HTTP " + code);

                JSONObject data = new JSONObject(body);
                runOnUiThread(() -> applyData(data));
            } catch (Exception e) {
                runOnUiThread(this::showLoadError);
            }
        });
    }

    private void applyData(JSONObject data) {
        loading = false;
        if (refreshButton != null) refreshButton.setEnabled(true);

        JSONObject site = data.optJSONObject("site");
        JSONObject app = data.optJSONObject("app");
        JSONObject today = data.optJSONObject("today");
        JSONObject access = data.optJSONObject("access");
        JSONObject analytics = data.optJSONObject("analytics");
        JSONObject linkData = data.optJSONObject("links");
        JSONArray alerts = data.optJSONArray("alerts");

        links.clear();
        if (linkData != null) {
            for (String key : new String[]{"website", "analytics", "orders", "app_monitor", "entitlements"}) {
                String value = linkData.optString(key, "");
                if (!value.isEmpty()) links.put(key, value);
            }
        }

        boolean siteOnline = site != null && "online".equalsIgnoreCase(site.optString("status"));
        siteCard.set(siteOnline ? "Online" : "Storing", siteOnline ? "nenotv.com" : "Niet bereikbaar", siteOnline ? GREEN : RED);

        boolean appLive = app != null && "live".equalsIgnoreCase(app.optString("status"));
        boolean bridgeOnline = app != null && "online".equalsIgnoreCase(app.optString("bridge_status"));
        if (appLive) {
            appCard.set("Live", bridgeOnline ? "Backend online" : "Backend storing", bridgeOnline ? GREEN : RED);
        } else {
            appCard.set("Nog niet live", bridgeOnline ? "Backend online" : "Backend storing", bridgeOnline ? ORANGE : RED);
        }

        int visitors = today == null ? 0 : today.optInt("visitors", 0);
        int pageviews = today == null ? 0 : today.optInt("pageviews", 0);
        double revenue = today == null ? 0d : today.optDouble("revenue", 0d);
        int orders = today == null ? 0 : today.optInt("orders", 0);
        String currency = today == null ? "EUR" : today.optString("currency", "EUR");

        int liveVisitors = analytics == null ? 0 : analytics.optInt("live_visitors", 0);
        String topCountry = analytics == null ? "" : analytics.optString("top_country_name", "");
        visitorsCard.set(number(visitors), "Live · " + number(liveVisitors), TEXT);
        pageviewsCard.set(number(pageviews), topCountry.isEmpty() ? "Topland · —" : "Topland · " + topCountry, TEXT);
        revenueCard.set(money(revenue, currency), "Betaalde orders", TEXT);
        ordersCard.set(number(orders), "Betaald vandaag", TEXT);

        int trials = access == null ? 0 : access.optInt("active_trials", 0);
        int pro = access == null ? 0 : access.optInt("active_pro", 0);
        boolean trialEnabled = access != null && access.optBoolean("trial_enabled", false);
        trialsCard.set(number(trials), trialEnabled ? "Actief" : "Nog niet live", trialEnabled ? TEXT : MUTED);
        proCard.set(number(pro), "Actief", TEXT);

        if (alerts == null || alerts.length() == 0) {
            alertView.setText("✓ Alles ziet er goed uit");
            alertView.setTextColor(GREEN);
            alertView.setBackground(roundRect(Color.rgb(12, 45, 38), 14));
        } else {
            StringBuilder b = new StringBuilder();
            for (int i = 0; i < alerts.length(); i++) {
                JSONObject a = alerts.optJSONObject(i);
                if (a == null) continue;
                if (b.length() > 0) b.append("\n");
                b.append("• ").append(a.optString("message", "Controle nodig"));
            }
            alertView.setText(b.length() == 0 ? "Controle nodig" : b.toString());
            alertView.setTextColor(ORANGE);
            alertView.setBackground(roundRect(Color.rgb(51, 38, 10), 14));
        }

        String updated = data.optString("updated_at", "");
        updatedView.setText(updated.isEmpty() ? "Zojuist bijgewerkt" : "Bijgewerkt · " + simplifyTime(updated));
    }

    private void showLoadError() {
        loading = false;
        if (refreshButton != null) refreshButton.setEnabled(true);
        if (updatedView != null) updatedView.setText("Kan niet bijwerken");
        if (siteCard != null) siteCard.set("Offline?", "Controleer verbinding", RED);
        if (alertView != null) {
            alertView.setText("Geen verbinding met NenoTV. Tik op Vernieuwen om opnieuw te proberen.");
            alertView.setTextColor(RED);
            alertView.setBackground(roundRect(Color.rgb(52, 20, 25), 14));
        }
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

    private void addRow(LinearLayout parent, LinearLayout row) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(10);
        parent.addView(row, lp);
    }

    private Card addCard(LinearLayout row, String label, String value, String sub, String linkKey) {
        Card card = new Card(label, value, sub, linkKey);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(126), 1f);
        if (row.getChildCount() > 0) lp.leftMargin = dp(10);
        row.addView(card.root, lp);
        return card;
    }

    private TextView text(String value, int sp, int color, boolean bold) {
        TextView t = new TextView(this);
        t.setText(value);
        t.setTextSize(sp);
        t.setTextColor(color);
        t.setTypeface(Typeface.create("sans-serif", bold ? Typeface.BOLD : Typeface.NORMAL));
        return t;
    }

    private Button button(String value) {
        Button b = new Button(this);
        b.setAllCaps(false);
        b.setText(value);
        b.setTextColor(Color.rgb(5, 20, 22));
        b.setTextSize(14);
        b.setTypeface(Typeface.create("sans-serif", Typeface.BOLD));
        b.setBackground(roundRect(ACCENT, 14));
        return b;
    }

    private android.graphics.drawable.GradientDrawable roundRect(int color, int radiusDp) {
        android.graphics.drawable.GradientDrawable g = new android.graphics.drawable.GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(dp(radiusDp));
        return g;
    }

    private void addSpace(LinearLayout parent, int dpValue) {
        Space s = new Space(this);
        parent.addView(s, new LinearLayout.LayoutParams(1, dp(dpValue)));
    }

    private void openLink(String key) {
        String url = links.get(key);
        if (url == null || url.isEmpty()) {
            if ("website".equals(key) || "site".equals(key)) url = "https://nenotv.com/";
            else return;
        }
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
        } catch (Exception ignored) { }
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

    private String number(int value) {
        return NumberFormat.getIntegerInstance(new Locale("nl", "NL")).format(value);
    }

    private String money(double value, String currency) {
        try {
            NumberFormat f = NumberFormat.getCurrencyInstance(new Locale("nl", "NL"));
            f.setCurrency(java.util.Currency.getInstance(currency));
            return f.format(value);
        } catch (Exception e) {
            return String.format(new Locale("nl", "NL"), "%.2f %s", value, currency);
        }
    }

    private String simplifyTime(String iso) {
        int t = iso.indexOf('T');
        if (t >= 0 && iso.length() >= t + 6) return iso.substring(t + 1, t + 6);
        return iso;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private final class Card {
        final LinearLayout root;
        final TextView value;
        final TextView sub;

        Card(String labelText, String valueText, String subText, String linkKey) {
            root = vertical();
            root.setPadding(dp(16), dp(14), dp(16), dp(14));
            root.setGravity(Gravity.CENTER_VERTICAL);
            root.setBackground(roundRect(CARD, 16));
            root.setClickable(true);
            root.setFocusable(true);
            root.setOnClickListener(v -> openLink(linkKey));

            root.addView(text(labelText, 13, MUTED, true));
            value = text(valueText, 25, TEXT, true);
            value.setPadding(0, dp(8), 0, dp(2));
            root.addView(value);

            sub = text(subText, 12, MUTED, false);
            sub.setSingleLine(true);
            root.addView(sub);
        }

        void set(String valueText, String subText, int valueColor) {
            value.setText(valueText);
            value.setTextColor(valueColor);
            sub.setText(subText);
        }
    }
}
