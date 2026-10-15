package com.nenotv.player;

import android.app.Activity;
import android.app.Dialog;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.Window;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import androidx.media3.common.C;
import androidx.media3.common.Format;
import androidx.media3.common.Player;
import androidx.media3.common.TrackSelectionParameters;
import androidx.media3.common.Tracks;
import androidx.media3.exoplayer.ExoPlayer;
import com.nenotv.player.model.MediaEntry;
import com.nenotv.player.storage.SettingsStore;
import java.util.*;

/**
 * Second language (Robert 15-10). With a main and a second language, a film or series does not get subtitles in the main
 * language by itself: a small panel asks "Gesproken taal" (only when the stream has both) and "Ondertiteling: Geen / NL / TR"
 * (only languages the stream has). The choice is remembered per film and per series; without a choice the panel closes
 * after 5 s with the selection shown (audio main language, no subtitles) and does not ask again for that title until the
 * app restarts. With one language nothing changes. Live TV is never asked.
 */
public final class TrackChoice {
    private TrackChoice() {}
    private static final long TIMEOUT_MS = 5000;
    private static final Set<String> askedThisSession = Collections.synchronizedSet(new HashSet<>());

    public static final class Choice {
        public final String audio, text;
        Choice(String audio, String text) { this.audio = audio == null ? "" : audio; this.text = text == null ? "off" : text; }
    }

    /** "series:<id>" for episodes, "vod:<key>" for films, null for live TV or anything without an identity. */
    public static String key(MediaEntry e) {
        if (e == null) return null;
        if ("episode".equals(e.type)) {
            String id = e.seriesId != null && !e.seriesId.trim().isEmpty() ? e.seriesId.trim() : (e.seriesTitle == null ? "" : e.seriesTitle.trim());
            return id.isEmpty() ? null : "series:" + id;
        }
        if ("vod".equals(e.type)) { String k = e.uniqueKey(); return k == null || k.isEmpty() ? null : "vod:" + k; }
        return null;
    }

    public static boolean active(Context c) { return !SettingsStore.secondaryLanguage(c).isEmpty(); }

    private static SharedPreferences prefs(Context c) { return c.getSharedPreferences("sunny_track_choice", Context.MODE_PRIVATE); }

    public static Choice remembered(Context c, MediaEntry e) {
        String k = key(e);
        if (k == null) return null;
        String v = prefs(c).getString(k, null);
        if (v == null) return null;
        int bar = v.indexOf('|');
        return bar < 0 ? null : new Choice(v.substring(0, bar), v.substring(bar + 1));
    }

    static void remember(Context c, MediaEntry e, Choice choice) {
        String k = key(e);
        if (k == null || choice == null) return;
        SharedPreferences p = prefs(c);
        // Keeps the store small: at most 500 titles, oldest dropped first (insertion order of the stored timestamp).
        SharedPreferences.Editor ed = p.edit().putString(k, choice.audio + "|" + choice.text).putLong("t:" + k, System.currentTimeMillis());
        Map<String, ?> all = p.getAll();
        if (all.size() > 1000) {
            String oldest = null; long at = Long.MAX_VALUE;
            for (Map.Entry<String, ?> x : all.entrySet()) if (x.getKey().startsWith("t:") && x.getValue() instanceof Long && (Long) x.getValue() < at) { at = (Long) x.getValue(); oldest = x.getKey().substring(2); }
            if (oldest != null) ed.remove(oldest).remove("t:" + oldest);
        }
        ed.apply();
    }

    /** Called where a player builds its track parameters: applies a remembered choice, or keeps subtitles off until one is made. */
    public static void adjust(Context c, MediaEntry e, TrackSelectionParameters.Builder ts) {
        if (!active(c) || key(e) == null) return;
        Choice r = remembered(c, e);
        if (r != null) { apply(c, ts, r); return; }
        if (SettingsStore.subtitlesWaitForChoice(c)) ts.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true);
    }

    static void apply(Context c, TrackSelectionParameters.Builder ts, Choice choice) {
        if (!choice.audio.isEmpty() && "auto".equals(SettingsStore.audio(c))) ts.setPreferredAudioLanguages(SettingsStore.languageCodes(choice.audio));
        if (!"auto".equals(SettingsStore.subtitles(c))) return; // an explicit subtitle setting in Settings stays in charge
        if ("off".equals(choice.text)) ts.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true);
        else { ts.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false); ts.setPreferredTextLanguages(SettingsStore.languageCodes(choice.text)); }
    }

    static void apply(Context c, ExoPlayer exo, Choice choice) {
        if (exo == null) return;
        TrackSelectionParameters.Builder ts = exo.getTrackSelectionParameters().buildUpon();
        apply(c, ts, choice);
        exo.setTrackSelectionParameters(ts.build());
    }

    /** Which of the viewer's two languages the stream offers for a track type, in preference order. */
    static List<String> offered(Tracks tracks, int type, String[] wanted) {
        ArrayList<String> out = new ArrayList<>();
        for (String lang : wanted) {
            Set<String> codes = new HashSet<>(Arrays.asList(SettingsStore.languageCodes(lang)));
            outer:
            for (Tracks.Group g : tracks.getGroups()) {
                if (g.getType() != type) continue;
                for (int i = 0; i < g.length; i++) {
                    Format f = g.getTrackFormat(i);
                    String l = f.language == null ? "" : f.language.toLowerCase(Locale.ROOT);
                    int dash = l.indexOf('-');
                    if (dash > 0) l = l.substring(0, dash);
                    if (codes.contains(l)) { out.add(lang); break outer; }
                }
            }
        }
        return out;
    }

    /** Adds a one-time listener to a player: when the tracks are known, ask once (see class comment). */
    public static void watch(Activity a, ExoPlayer exo, MediaEntry e) {
        if (a == null || exo == null || !active(a)) return;
        final String k = key(e);
        if (k == null || remembered(a, e) != null || askedThisSession.contains(k)) return;
        exo.addListener(new Player.Listener() {
            boolean done;
            @Override public void onTracksChanged(Tracks tracks) {
                if (done || tracks == null || tracks.getGroups().isEmpty()) return;
                done = true;
                exo.removeListener(this);
                if (a.isFinishing() || askedThisSession.contains(k)) return;
                String[] wanted = {SettingsStore.primaryLanguage(a), SettingsStore.secondaryLanguage(a)};
                List<String> audio = "auto".equals(SettingsStore.audio(a)) ? offered(tracks, C.TRACK_TYPE_AUDIO, wanted) : Collections.emptyList();
                List<String> text = "auto".equals(SettingsStore.subtitles(a)) ? offered(tracks, C.TRACK_TYPE_TEXT, wanted) : Collections.emptyList();
                if (audio.size() < 2 && text.isEmpty()) return; // nothing to choose
                askedThisSession.add(k);
                show(a, exo, e, audio, text);
            }
        });
    }

    private static String label(Activity a, String code) {
        if ("off".equals(code)) return FamilyUi.text(a, "Geen", "None", "Keine");
        return SettingsStore.displayLanguage(a, code);
    }

    private static void show(Activity a, ExoPlayer exo, MediaEntry e, List<String> audio, List<String> text) {
        final Dialog d = new Dialog(a);
        final Handler h = new Handler(Looper.getMainLooper());
        final String[] pick = {audio.isEmpty() ? "" : audio.get(0), "off"};
        final boolean[] touched = {false};
        int pad = Math.round(16 * a.getResources().getDisplayMetrics().density);
        LinearLayout box = new LinearLayout(a);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(pad, pad, pad, pad);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xF010141A);
        bg.setCornerRadius(pad);
        box.setBackground(bg);
        if (audio.size() >= 2) row(a, box, FamilyUi.text(a, "Gesproken taal", "Spoken language", "Gesprochene Sprache"), audio, pick, 0, touched);
        if (!text.isEmpty()) {
            ArrayList<String> options = new ArrayList<>();
            options.add("off");
            options.addAll(text);
            row(a, box, FamilyUi.text(a, "Ondertiteling", "Subtitles", "Untertitel"), options, pick, 1, touched);
        }
        Button ok = new Button(a);
        ok.setText("OK");
        ok.setAllCaps(false);
        ok.setTextColor(0xFF07090D);
        ok.setBackgroundTintList(android.content.res.ColorStateList.valueOf(0xFFFFD600));
        ok.setOnClickListener(v -> { touched[0] = true; d.dismiss(); });
        box.addView(ok, new LinearLayout.LayoutParams(-1, -2));
        d.setContentView(box);
        d.setOnDismissListener(x -> {
            h.removeCallbacksAndMessages(null);
            Choice choice = new Choice(pick[0], pick[1]);
            apply(a, exo, choice);
            if (touched[0]) remember(a, e, choice); // a timeout is not a choice: asked again after an app restart
        });
        Window w = d.getWindow();
        if (w != null) {
            w.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            w.setGravity(Gravity.BOTTOM);
            w.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
        }
        d.show();
        if (w != null) w.setLayout(Math.min(a.getResources().getDisplayMetrics().widthPixels, Math.round(560 * a.getResources().getDisplayMetrics().density)), -2);
        ok.requestFocus();
        h.postDelayed(() -> { if (!touched[0] && d.isShowing() && !a.isFinishing()) d.dismiss(); }, TIMEOUT_MS);
    }

    private static void row(Activity a, LinearLayout box, String title, List<String> options, String[] pick, int slot, boolean[] touched) {
        TextView t = new TextView(a);
        t.setText(title);
        t.setTextColor(0xFFA7AFBC);
        t.setTextSize(13);
        box.addView(t);
        LinearLayout line = new LinearLayout(a);
        line.setOrientation(LinearLayout.HORIZONTAL);
        ArrayList<Button> buttons = new ArrayList<>();
        for (String code : options) {
            Button b = new Button(a);
            b.setText(label(a, code));
            b.setAllCaps(false);
            b.setTag(code);
            b.setOnClickListener(v -> {
                touched[0] = true;
                pick[slot] = code;
                for (Button x : buttons) paint(x, code.equals(x.getTag()));
            });
            buttons.add(b);
            line.addView(b, new LinearLayout.LayoutParams(0, -2, 1));
        }
        for (Button x : buttons) paint(x, pick[slot].equals(x.getTag()));
        box.addView(line, new LinearLayout.LayoutParams(-1, -2));
    }

    private static void paint(Button b, boolean on) {
        b.setTextColor(on ? 0xFF07090D : 0xFFF7F8FA);
        b.setBackgroundTintList(android.content.res.ColorStateList.valueOf(on ? 0xFFFFD600 : 0xFF232A35));
    }
}
