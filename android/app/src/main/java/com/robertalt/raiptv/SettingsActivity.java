package com.nenotv.player;

import android.app.*;
import android.content.Intent;
import android.os.*;
import android.text.InputType;
import android.widget.*;
import com.nenotv.player.storage.*;

/** Settings as blocks: each block opens one small topic. Options that do nothing in Light are not shown there. */
public class SettingsActivity extends Activity {
    private static final int MAIN = 0, LANGUAGE = 1, DISPLAY = 2, PLAYBACK = 3, TROUBLE = 4, ACCOUNT = 5;
    LinearLayout box;
    android.content.SharedPreferences p;
    int page = MAIN;
    static final String[] LANGS = {"nl", "en", "de", "fr", "es", "it", "pt", "tr", "pl", "ar"};

    String T(String k) { return UiText.t(this, k); }
    String text(String nl, String en, String de) { return FamilyUi.text(this, nl, en, de); }
    boolean pro() { return ProGate.allowed(this); }

    @Override public void onCreate(Bundle x) {
        super.onCreate(x);
        if (FamilyStore.active(this)) { startActivity(new Intent(this, FamilyActivity.class)); finish(); return; }
        SettingsStore.migrateLanguagePreferences(this);
        p = SettingsStore.prefs(this);
        if (x != null) page = x.getInt("page", MAIN);
        build();
    }
    @Override protected void onPostCreate(Bundle saved) { super.onPostCreate(saved); BackCompat.route(this); }
    @Override protected void onSaveInstanceState(Bundle out) { super.onSaveInstanceState(out); out.putInt("page", page); }
    @Override public void onBackPressed() { if (page != MAIN) { page = MAIN; build(); } else super.onBackPressed(); }
    @Override protected void onResume() { super.onResume(); if (p != null) build(); }

    void open(int target) { page = target; build(); }

    void build() {
        String title = page == LANGUAGE ? T("language") : page == DISPLAY ? text("Weergave", "Display", "Anzeige") : page == PLAYBACK ? T("playback")
                : page == TROUBLE ? text("Problemen oplossen", "Troubleshooting", "Fehlerbehebung") : page == ACCOUNT ? text("Account", "Account", "Konto") : T("settings");
        box = Tiles.page(this, title);
        if (page != MAIN) Tiles.link(this, box, "← " + text("Terug naar instellingen", "Back to settings", "Zurück zu den Einstellungen"), v -> open(MAIN));
        Tiles.Grid g = new Tiles.Grid(this, box);
        switch (page) {
            case LANGUAGE:
                choice(g, T("app_language"), "language", labels(LANGS), LANGS);
                choice(g, T("audio_pref"), "audio", prefixed(new String[]{T("follow_app_language"), T("original")}), prefixedValues("auto", "original"));
                choice(g, T("subtitle_pref"), "subtitles", prefixed(new String[]{T("follow_app_language"), T("off")}), prefixedValues("auto", "off"));
                g.finish();
                Tiles.note(this, box, text("Zenders, films en series in je taal staan bovenaan.", "Channels, movies and series in your language come first.", "Sender, Filme und Serien in deiner Sprache stehen oben."));
                break;
            case DISPLAY:
                displaySize(g);
                choice(g, T("start_screen"), "start_screen", new String[]{T("home"), T("last_tab"), T("live_tv"), T("epg"), T("movies"), T("series")}, new String[]{"home", "last", "live", "epg", "vod", "series"});
                choice(g, T("default_sort"), "sort", new String[]{T("provider"), "A–Z", "Z–A", T("favorites_first"), T("recent_first")}, new String[]{"provider", "az", "za", "favorites", "recent"});
                g.finish();
                break;
            case PLAYBACK:
                if (pro()) {
                    onOff(g, T("picture_in_picture"), "pip", true);
                    onOff(g, T("autoplay"), "autoplay_next", true);
                    choice(g, T("player"), "player", new String[]{T("automatic"), "VLC", "Media3"}, new String[]{"auto", "vlc", "media3"});
                    g.add("💬", T("advanced_subtitles"), T("external_subtitle_bridge"), false, v -> editSubtitleBridge());
                    g.finish();
                } else {
                    g.add("🔒", "SunnyIPTV Pro", text("Beeld-in-beeld, volgende aflevering automatisch, externe ondertitels en opnemen", "Picture-in-picture, next episode automatically, external subtitles and recording", "Bild-in-Bild, nächste Folge automatisch, externe Untertitel und Aufnahme"), false, v -> ProGate.require(this, T("picture_in_picture")));
                    g.finish();
                    Tiles.note(this, box, text("Audio- en ondertiteltaal stel je in onder Taal.", "Set audio and subtitle language under Language.", "Audio- und Untertitelsprache stellst du unter Sprache ein."));
                }
                break;
            case TROUBLE:
                g.add("🔄", T("reindex"), text("Haalt je zenders, films en series opnieuw op", "Downloads your channels, movies and series again", "Lädt Sender, Filme und Serien neu"), false, v -> { p.edit().putBoolean("force_reindex", true).apply(); Toast.makeText(this, T("reindex_started"), Toast.LENGTH_SHORT).show(); });
                g.add("🧹", T("clear_cache"), text("Maakt opslagruimte vrij voor afbeeldingen", "Frees space used by images", "Gibt Speicher für Bilder frei"), false, v -> { MediaRowAdapter.clearArtworkCache(); MainActivity.clearHeroCache(); Toast.makeText(this, T("cache_cleared"), Toast.LENGTH_SHORT).show(); });
                g.add("📶", text("Verbindingstest", "Connection test", "Verbindungstest"), text("Is je verbinding snel genoeg?", "Is your connection fast enough?", "Ist deine Verbindung schnell genug?"), false, v -> startActivity(new Intent(this, NetworkTestActivity.class)));
                g.finish();
                break;
            case ACCOUNT:
                g.add("👤", T("account_and_pro"), text("Status, Pro en dit apparaat", "Status, Pro and this device", "Status, Pro und dieses Gerät"), false, v -> startActivity(new Intent(this, AccountActivity.class)));
                g.add("🎂", text("Leeftijdsgroep", "Age group", "Altersgruppe"), text("Bepaalt welke extra diensten mogen", "Decides which extra services may be used", "Bestimmt, welche Zusatzdienste erlaubt sind"), false, v -> startActivity(new Intent(this, AgePrivacyActivity.class).putExtra("settings", true)));
                g.add("🚪", text("Uitloggen", "Sign out", "Abmelden"), "", false, v -> signOut());
                g.finish();
                break;
            default:
                g.add("🌐", T("language"), SettingsStore.displayLanguage(this, SettingsStore.language(this)), false, v -> open(LANGUAGE));
                g.add("🖥", text("Weergave", "Display", "Anzeige"), text("Startscherm, sortering, grootte", "Start screen, sorting, size", "Startbildschirm, Sortierung, Größe"), false, v -> open(DISPLAY));
                g.add("▶", T("playback"), pro() ? text("Speler en ondertitels", "Player and subtitles", "Player und Untertitel") : "🔒 Pro", false, v -> open(PLAYBACK));
                g.add("👪", text("Familiefilter", "Family filter", "Familienfilter"), text("Iedereen, Familie of Kinderen", "Everyone, Family or Children", "Alle, Familie oder Kinder"), false, v -> startActivity(new Intent(this, FamilyActivity.class)));
                g.add("🔧", text("Problemen oplossen", "Troubleshooting", "Fehlerbehebung"), text("Lijst vernieuwen, verbindingstest", "Refresh list, connection test", "Liste erneuern, Verbindungstest"), false, v -> open(TROUBLE));
                g.add("👤", text("Account", "Account", "Konto"), text("Mijn account, uitloggen", "My account, sign out", "Mein Konto, abmelden"), false, v -> open(ACCOUNT));
                g.finish();
                Tiles.note(this, box, footer());
        }
        UiText.applyDirection(this);
    }

    String footer() {
        String plan = new EntitlementStore(this).statusLabel(this);
        String line = "SunnyIPTV " + BuildConfig.VERSION_NAME + " · " + plan;
        try {
            SecureProfileStore profiles = new SecureProfileStore(this);
            if (profiles.exists()) {
                com.nenotv.player.model.Profile profile = profiles.load();
                String key = ProfileCacheKey.of(profile);
                try (SearchIndexStore index = new SearchIndexStore(this)) {
                    line += "\n" + T("live_tv") + ": " + index.countSection(key, "live") + " · " + T("movies") + ": " + index.countSection(key, "vod") + " · " + T("series") + ": " + index.countSection(key, "series");
                }
            }
        } catch (Exception ignored) {}
        return line;
    }

    /** One tile showing the current value; tapping opens a simple list to choose from. */
    void choice(Tiles.Grid g, String label, String key, String[] labels, String[] values) {
        String cur = "start_screen".equals(key) ? SettingsStore.startScreen(this) : "language".equals(key) ? SettingsStore.language(this) : p.getString(key, values[0]);
        int at = 0;
        for (int i = 0; i < values.length; i++) if (values[i].equals(cur)) at = i;
        final int selected = at;
        g.add("", label, labels[at], false, v -> new AlertDialog.Builder(this).setTitle(label).setSingleChoiceItems(labels, selected, (d, w) -> {
            d.dismiss();
            if (values[w].equals(cur)) return;
            if ("language".equals(key)) { SettingsStore.setPrimaryLanguage(this, values[w]); recreate(); }
            else { p.edit().putString(key, values[w]).apply(); build(); }
        }).setNegativeButton(T("cancel"), null).show());
    }

    void onOff(Tiles.Grid g, String label, String key, boolean def) {
        boolean on = p.getBoolean(key, def);
        g.add("", label, on ? text("Aan", "On", "An") : text("Uit", "Off", "Aus"), false, v -> { p.edit().putBoolean(key, !on).apply(); build(); });
    }

    /** "Compact view" and "hero size" were two technical switches; one simple choice replaces them. */
    void displaySize(Tiles.Grid g) {
        String[] labels = {text("Compact", "Compact", "Kompakt"), text("Normaal", "Normal", "Normal"), text("Groot", "Large", "Groß")};
        boolean compact = SettingsStore.compact(this);
        String hero = SettingsStore.hero(this);
        int at = !compact ? 2 : "small".equals(hero) ? 0 : 1;
        g.add("", text("Grootte", "Size", "Größe"), labels[at], false, v -> new AlertDialog.Builder(this).setTitle(text("Grootte", "Size", "Größe")).setSingleChoiceItems(labels, at, (d, w) -> {
            d.dismiss();
            p.edit().putBoolean("compact", w != 2).putString("hero_size", w == 0 ? "small" : w == 1 ? "normal" : "large").apply();
            build();
        }).setNegativeButton(T("cancel"), null).show());
    }

    void signOut() {
        new AlertDialog.Builder(this).setTitle(text("Uitloggen?", "Sign out?", "Abmelden?"))
                .setMessage(text("Je tv-bron en instellingen blijven op dit apparaat staan. Om de app weer te gebruiken koppel je dit apparaat opnieuw.",
                        "Your TV source and settings stay on this device. To use the app again, link this device again.",
                        "Deine TV-Quelle und Einstellungen bleiben auf diesem Gerät. Um die App wieder zu nutzen, verbindest du dieses Gerät erneut."))
                .setNegativeButton(T("cancel"), null)
                .setPositiveButton(text("Uitloggen", "Sign out", "Abmelden"), (d, w) -> {
                    new AccountLinkStore(this).clear();
                    ExtraPrivacyStore.clear(this);
                    startActivity(new Intent(this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK));
                    finish();
                }).show();
    }

    void editSubtitleBridge() {
        SecureProfileStore store = new SecureProfileStore(this);
        if (!store.exists()) { Toast.makeText(this, T("profile_required"), Toast.LENGTH_SHORT).show(); return; }
        com.nenotv.player.model.Profile profile = store.load();
        LinearLayout wrap = new LinearLayout(this); wrap.setOrientation(LinearLayout.VERTICAL); wrap.setPadding(Tiles.dp(this, 18), 0, Tiles.dp(this, 18), 0);
        EditText url = new EditText(this); url.setHint(T("bridge_url")); url.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI); url.setText(profile.bridgeUrl);
        EditText token = new EditText(this); token.setHint(T("bridge_token_optional")); token.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD); token.setText(profile.bridgeToken);
        wrap.addView(url); wrap.addView(token);
        new AlertDialog.Builder(this).setTitle(T("external_subtitle_bridge")).setMessage(T("bridge_help")).setView(wrap).setNegativeButton(T("cancel"), null)
                .setPositiveButton(T("save"), (d, w) -> { profile.bridgeUrl = url.getText().toString().trim(); profile.bridgeToken = token.getText().toString(); store.save(profile); Toast.makeText(this, T("saved"), Toast.LENGTH_SHORT).show(); }).show();
    }

    String[] labels(String[] codes) { String[] out = new String[codes.length]; for (int i = 0; i < codes.length; i++) out[i] = SettingsStore.displayLanguage(this, codes[i]); return out; }
    String[] prefixed(String[] first) { String[] names = labels(LANGS); String[] out = new String[first.length + names.length]; System.arraycopy(first, 0, out, 0, first.length); System.arraycopy(names, 0, out, first.length, names.length); return out; }
    String[] prefixedValues(String a, String b) { String[] out = new String[LANGS.length + 2]; out[0] = a; out[1] = b; System.arraycopy(LANGS, 0, out, 2, LANGS.length); return out; }
}
