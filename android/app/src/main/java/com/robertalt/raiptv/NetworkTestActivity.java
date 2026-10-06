package com.nenotv.player;

import android.app.Activity;
import android.content.Intent;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.os.Bundle;
import android.widget.LinearLayout;
import android.widget.TextView;
import com.nenotv.player.model.Profile;
import com.nenotv.player.storage.SecureProfileStore;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/** Free connection test: is SunnyIPTV and the TV provider reachable, how fast, and is a VPN active. No credentials are sent. */
public class NetworkTestActivity extends Activity {
    private final ExecutorService exec = Executors.newSingleThreadExecutor();
    private TextView result;
    private String text(String nl, String en, String de) { return FamilyUi.text(this, nl, en, de); }

    @Override public void onCreate(Bundle saved) {
        super.onCreate(saved);
        if (com.nenotv.player.storage.FamilyStore.active(this)) { FamilyUi.blocked(this); finish(); return; }
        LinearLayout box = Tiles.page(this, text("Verbindingstest", "Connection test", "Verbindungstest"));
        Tiles.note(this, box, text("Haperend beeld? Test of SunnyIPTV en je tv-aanbieder goed bereikbaar zijn. Je inloggegevens worden niet verstuurd.",
                "Stuttering picture? Test whether SunnyIPTV and your TV provider are reachable. Your login details are not sent.",
                "Ruckelndes Bild? Teste, ob SunnyIPTV und dein TV-Anbieter erreichbar sind. Deine Zugangsdaten werden nicht gesendet."));
        Tiles.Grid grid = new Tiles.Grid(this, box);
        grid.add("▶", text("Test starten", "Start test", "Test starten"), "", false, v -> run());
        grid.add("🛡", text("VPN-instellingen", "VPN settings", "VPN-Einstellungen"), vpn() ? text("VPN staat aan", "VPN is on", "VPN ist an") : text("Geen VPN actief", "No VPN active", "Kein VPN aktiv"), false, v -> openVpn());
        grid.finish();
        result = new TextView(this);
        result.setTextColor(Tiles.TEXT);
        result.setTextSize(16);
        result.setPadding(0, Tiles.dp(this, 14), 0, 0);
        result.setAccessibilityLiveRegion(android.view.View.ACCESSIBILITY_LIVE_REGION_POLITE);
        box.addView(result);
        UiText.applyDirection(this);
        run();
    }

    private boolean vpn() {
        try {
            ConnectivityManager cm = (ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);
            Network n = cm.getActiveNetwork();
            NetworkCapabilities caps = n == null ? null : cm.getNetworkCapabilities(n);
            return caps != null && caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN);
        } catch (Exception e) { return false; }
    }

    private void run() {
        result.setText(text("Bezig met testen…", "Testing…", "Test läuft…"));
        exec.execute(() -> {
            StringBuilder out = new StringBuilder();
            out.append(probe("https://sunnyiptv.com", "SunnyIPTV"));
            try {
                SecureProfileStore store = new SecureProfileStore(this);
                if (store.exists()) {
                    Profile p = store.load();
                    String raw = p.type == Profile.Type.XTREAM ? p.server : p.m3uUrl;
                    if (raw != null && !raw.trim().isEmpty()) {
                        URL u = new URL(raw.trim());
                        if (!u.getHost().isEmpty()) {
                            String host = u.getProtocol() + "://" + u.getHost() + (u.getPort() > 0 ? ":" + u.getPort() : "");
                            out.append("\n").append(probe(host, text("Je tv-aanbieder", "Your TV provider", "Dein TV-Anbieter")));
                        }
                    }
                }
            } catch (Exception ignored) {}
            if (vpn()) out.append("\n").append(text("Let op: er is een VPN actief. Dat kan het beeld vertragen.", "Note: a VPN is active. This can slow down the picture.", "Hinweis: Ein VPN ist aktiv. Das kann das Bild verlangsamen."));
            runOnUiThread(() -> { if (!isFinishing()) result.setText(out.toString()); });
        });
    }

    private String probe(String url, String label) {
        long start = System.nanoTime();
        try {
            HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
            c.setConnectTimeout(5000); c.setReadTimeout(5000); c.setInstanceFollowRedirects(false); c.setRequestMethod("HEAD");
            c.setRequestProperty("User-Agent", "SunnyIPTV-Network-Test");
            c.getResponseCode();
            long ms = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
            c.disconnect();
            String verdict = ms < 300 ? text("goed", "good", "gut") : ms < 1000 ? text("redelijk", "fair", "mittel") : text("traag", "slow", "langsam");
            return "✔ " + label + ": " + verdict + " (" + ms + " ms)";
        } catch (Exception e) {
            return "✖ " + label + ": " + text("niet bereikbaar", "not reachable", "nicht erreichbar");
        }
    }

    private void openVpn() {
        try { startActivity(new Intent("android.settings.VPN_SETTINGS")); }
        catch (Exception e) { try { startActivity(new Intent(android.provider.Settings.ACTION_WIRELESS_SETTINGS)); } catch (Exception ignored) {} }
    }

    @Override protected void onDestroy() { exec.shutdownNow(); super.onDestroy(); }
}
