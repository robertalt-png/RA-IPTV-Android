package com.nenotv.player;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.InputType;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.TextWatcher;
import android.text.style.ForegroundColorSpan;
import android.text.style.RelativeSizeSpan;
import android.text.style.StyleSpan;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.*;
import com.nenotv.player.core.SourceParse;
import java.io.InputStream;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Easier provider entry on the source screen: paste or scan (QR code or screenshot, read on this device) the details
 * from the provider, recognised by {@link SourceParse}; connection types as cards; show-password; help text.
 * The existing fields and "Connect" (which tests the connection before saving) stay as they are.
 */
final class SourceEntryUi {
    static final int PICK_IMAGE = 4107;
    static final int YELLOW = 0xFFFFD400, TEXT = 0xFFF7F8FA, MUTED = 0xFFA7AFBC, OK = 0xFF5CD6A8, WARN = 0xFFFFC857, ERR = 0xFFFF6676;

    private final Activity a;
    private final EditText server, user, pass, m3u;
    private final RadioButton xtream, m3uRadio, demoRadio;
    private final Runnable updateMode;
    private EditText smart;
    private TextView smartStatus;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private Runnable pending;

    SourceEntryUi(Activity a, EditText server, EditText user, EditText pass, EditText m3u, RadioButton xtream, RadioButton m3uRadio, RadioButton demoRadio, Runnable updateMode) {
        this.a = a; this.server = server; this.user = user; this.pass = pass; this.m3u = m3u;
        this.xtream = xtream; this.m3uRadio = m3uRadio; this.demoRadio = demoRadio; this.updateMode = updateMode;
    }

    String T(String en, String nl, String de) {
        String l = com.nenotv.player.storage.SettingsStore.language(a);
        return "nl".equals(l) ? nl : "de".equals(l) ? de : en;
    }
    int dp(int v) { return Math.round(v * a.getResources().getDisplayMetrics().density); }

    static GradientDrawable box(int fill, int stroke, int strokeDp, float radius, boolean dashed, float density) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(fill); g.setCornerRadius(radius * density);
        if (dashed) g.setStroke(Math.round(strokeDp * density), stroke, 8 * density, 6 * density); else g.setStroke(Math.round(strokeDp * density), stroke);
        return g;
    }

    /** Adds the paste/scan panel below the intro, turns the type choices into cards and adds show-password, help and trust. */
    void install(LinearLayout container, View intro) {
        float d = a.getResources().getDisplayMetrics().density;
        LinearLayout panel = new LinearLayout(a);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(14), dp(14), dp(14), dp(12));
        panel.setBackground(box(0xFF15181E, YELLOW, 2, 16, true, d));
        TextView title = new TextView(a);
        title.setText("✨ " + T("Paste or scan your provider's details", "Plak of scan de gegevens van je aanbieder", "Daten deines Anbieters einfügen oder scannen"));
        title.setTextColor(TEXT); title.setTextSize(17); title.setTypeface(null, Typeface.BOLD);
        panel.addView(title);
        TextView sub = new TextView(a);
        sub.setText(T("We recognise the link or the text from the email or WhatsApp and fill in the fields.", "Wij herkennen de link of de tekst uit de mail of WhatsApp en vullen de velden in.", "Wir erkennen den Link oder den Text aus der E-Mail oder WhatsApp und füllen die Felder aus."));
        sub.setTextColor(MUTED); sub.setTextSize(14); sub.setPadding(0, dp(2), 0, dp(10));
        panel.addView(sub);
        smart = new EditText(a);
        smart.setTag("source_smart_text");
        smart.setMinLines(2); smart.setMaxLines(6); smart.setGravity(Gravity.TOP | Gravity.START);
        smart.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        smart.setHint(T("Paste the link or text here", "Plak hier de link of tekst", "Link oder Text hier einfügen"));
        smart.setTextColor(TEXT); smart.setHintTextColor(MUTED); smart.setTextSize(14); smart.setTypeface(Typeface.MONOSPACE);
        smart.setPadding(dp(12), dp(10), dp(12), dp(10));
        smart.setBackground(box(0xFF0B0E12, 0xFF2B313B, 1, 12, false, d));
        smart.addTextChangedListener(new TextWatcher() {
            public void beforeTextChanged(CharSequence s, int st, int c, int af) {}
            public void onTextChanged(CharSequence s, int st, int b, int c) {}
            public void afterTextChanged(Editable e) {
                if (pending != null) ui.removeCallbacks(pending);
                pending = () -> { String v = e.toString(); if (v.trim().isEmpty()) say("", MUTED); else apply(SourceParse.parse(v, false), false); };
                ui.postDelayed(pending, 300);
            }
        });
        panel.addView(smart, new LinearLayout.LayoutParams(-1, -2));
        LinearLayout row = new LinearLayout(a);
        row.setOrientation(LinearLayout.HORIZONTAL);
        Button paste = chip("📋 " + T("Paste", "Plakken", "Einfügen"));
        paste.setTag("source_paste");
        paste.setOnClickListener(v -> paste());
        LinearLayout.LayoutParams half = new LinearLayout.LayoutParams(0, dp(48), 1f);
        half.topMargin = dp(10);
        row.addView(paste, half);
        Intent pick = new Intent(Intent.ACTION_GET_CONTENT).setType("image/*").addCategory(Intent.CATEGORY_OPENABLE);
        if (pick.resolveActivity(a.getPackageManager()) != null) {
            Button scan = chip("📷 " + T("QR or screenshot", "QR of screenshot", "QR oder Screenshot"));
            scan.setTag("source_scan");
            scan.setOnClickListener(v -> { try { a.startActivityForResult(pick, PICK_IMAGE); } catch (Exception e) { say(T("No picture app available.", "Geen foto-app beschikbaar.", "Keine Foto-App verfügbar."), ERR); } });
            LinearLayout.LayoutParams h2 = new LinearLayout.LayoutParams(0, dp(48), 1f);
            h2.topMargin = dp(10); h2.leftMargin = dp(10);
            row.addView(scan, h2);
        }
        panel.addView(row);
        smartStatus = new TextView(a);
        smartStatus.setTextSize(14); smartStatus.setTypeface(null, Typeface.BOLD); smartStatus.setPadding(0, dp(8), 0, 0);
        smartStatus.setVisibility(View.GONE);
        smartStatus.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        panel.addView(smartStatus);
        LinearLayout.LayoutParams pp = new LinearLayout.LayoutParams(-1, -2);
        pp.bottomMargin = dp(14);
        container.addView(panel, container.indexOfChild(intro) + 1, pp);

        card(demoRadio, "🎬", null);
        card(xtream, "🔑", T("Server, username and password", "Server, gebruikersnaam en wachtwoord", "Server, Benutzername und Passwort"));
        card(m3uRadio, "🔗", T("One long link to your list", "Eén lange link naar je lijst", "Ein langer Link zu deiner Liste"));
        RadioGroup group = (RadioGroup) xtream.getParent();
        group.setOnCheckedChangeListener((g, id) -> refreshCards());
        refreshCards();

        passwordToggle();
        LinearLayout xf = (LinearLayout) server.getParent();
        TextView help = new TextView(a);
        help.setText("❔ " + T("Where do I find this?", "Waar vind ik dit?", "Wo finde ich das?"));
        help.setTextColor(YELLOW); help.setTextSize(15); help.setPadding(0, dp(12), 0, dp(4)); help.setFocusable(true); help.setClickable(true);
        TextView answer = new TextView(a);
        answer.setText(T("Your provider sends these details by email or WhatsApp after your order. Look for \"Username\", \"Password\" and \"Server\" or \"URL\", or a long link containing get.php. Tip: copy that message and paste it above, everything is filled in for you.",
                "Je aanbieder stuurt deze gegevens per mail of WhatsApp na je bestelling. Zoek naar \"Username\", \"Password\" en \"Server\" of \"URL\", of naar een lange link met get.php erin. Tip: kopieer dat bericht en plak het hierboven, dan wordt alles voor je ingevuld.",
                "Dein Anbieter schickt diese Daten per E-Mail oder WhatsApp nach der Bestellung. Suche nach \"Username\", \"Password\" und \"Server\" oder \"URL\" oder nach einem langen Link mit get.php. Tipp: Kopiere die Nachricht und füge sie oben ein, dann wird alles ausgefüllt."));
        answer.setTextColor(MUTED); answer.setTextSize(14); answer.setVisibility(View.GONE);
        help.setOnClickListener(v -> answer.setVisibility(answer.getVisibility() == View.VISIBLE ? View.GONE : View.VISIBLE));
        xf.addView(help); xf.addView(answer);

        View save = a.findViewById(R.id.saveButton);
        if (save instanceof Button) { save.setBackgroundTintList(android.content.res.ColorStateList.valueOf(YELLOW)); ((Button) save).setTextColor(0xFF111111); ((Button) save).setTypeface(null, Typeface.BOLD); }
        TextView trust = new TextView(a);
        trust.setText("🔒 " + T("Your details are stored encrypted and only used to load your list. SunnyIPTV does not sell channels.", "Je gegevens worden versleuteld bewaard en alleen gebruikt om je lijst te laden. SunnyIPTV verkoopt zelf geen zenders.", "Deine Daten werden verschlüsselt gespeichert und nur zum Laden deiner Liste verwendet. SunnyIPTV verkauft selbst keine Sender."));
        trust.setTextColor(MUTED); trust.setTextSize(13); trust.setGravity(Gravity.CENTER); trust.setPadding(0, dp(10), 0, 0);
        LinearLayout root = (LinearLayout) save.getParent();
        root.addView(trust, root.indexOfChild(save) + 1);
    }

    private Button chip(String label) {
        Button b = new Button(a);
        b.setText(label); b.setAllCaps(false); b.setTextColor(TEXT); b.setTextSize(15);
        b.setBackground(box(0xFF1B2028, 0xFF3A424E, 1, 12, false, a.getResources().getDisplayMetrics().density));
        return b;
    }

    private final java.util.Map<RadioButton, String[]> cardText = new java.util.HashMap<>();
    private void card(RadioButton r, String icon, String subtitle) {
        String label = r.getText().toString();
        String head = label, sub = subtitle;
        if (subtitle == null && label.contains(" · ")) { head = label.substring(0, label.indexOf(" · ")); sub = label.substring(label.indexOf(" · ") + 3); }
        cardText.put(r, new String[]{icon + "  " + head, sub == null ? "" : sub});
        r.setButtonDrawable(null);
        r.setPadding(dp(16), dp(12), dp(16), dp(12));
        ViewGroup.MarginLayoutParams lp = (ViewGroup.MarginLayoutParams) r.getLayoutParams();
        lp.bottomMargin = dp(8); r.setLayoutParams(lp);
        SpannableStringBuilder s = new SpannableStringBuilder(cardText.get(r)[0]);
        s.setSpan(new StyleSpan(Typeface.BOLD), 0, s.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        if (!cardText.get(r)[1].isEmpty()) {
            int at = s.length(); s.append("\n").append(cardText.get(r)[1]);
            s.setSpan(new ForegroundColorSpan(MUTED), at, s.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            s.setSpan(new RelativeSizeSpan(0.85f), at, s.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        r.setText(s);
    }
    private void refreshCards() {
        float d = a.getResources().getDisplayMetrics().density;
        for (RadioButton r : cardText.keySet())
            r.setBackground(r.isChecked() ? box(0xFF1F2530, YELLOW, 2, 14, false, d) : box(0xFF12161C, 0xFF2B313B, 1, 14, false, d));
    }

    private void passwordToggle() {
        LinearLayout parent = (LinearLayout) pass.getParent();
        int index = parent.indexOfChild(pass);
        ViewGroup.MarginLayoutParams old = (ViewGroup.MarginLayoutParams) pass.getLayoutParams();
        parent.removeView(pass);
        LinearLayout row = new LinearLayout(a);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.addView(pass, new LinearLayout.LayoutParams(0, old.height, 1f));
        Button eye = chip(T("Show", "Tonen", "Anzeigen"));
        eye.setTag("source_show_password");
        eye.setOnClickListener(v -> {
            boolean hidden = (pass.getInputType() & InputType.TYPE_TEXT_VARIATION_PASSWORD) != 0;
            pass.setInputType(InputType.TYPE_CLASS_TEXT | (hidden ? InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD : InputType.TYPE_TEXT_VARIATION_PASSWORD));
            pass.setSelection(pass.getText().length());
            eye.setText(hidden ? T("Hide", "Verbergen", "Verbergen") : T("Show", "Tonen", "Anzeigen"));
        });
        LinearLayout.LayoutParams ep = new LinearLayout.LayoutParams(-2, old.height);
        ep.leftMargin = dp(8);
        row.addView(eye, ep);
        LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(-1, -2);
        rp.topMargin = old.topMargin;
        parent.addView(row, index, rp);
    }

    private void say(String msg, int color) {
        smartStatus.setText(msg); smartStatus.setTextColor(color);
        smartStatus.setVisibility(msg.isEmpty() ? View.GONE : View.VISIBLE);
    }

    void apply(SourceParse.Result r, boolean fromImage) {
        String note = fromImage ? " " + T("Read from an image: check the password (0/O and 1/l can be confused).", "Van een afbeelding gelezen: controleer het wachtwoord (0/O en 1/l worden soms verwisseld).", "Aus einem Bild gelesen: Prüfe das Passwort (0/O und 1/l werden manchmal verwechselt).") : "";
        if (SourceParse.XTREAM.equals(r.type)) {
            xtream.setChecked(true); updateMode.run();
            if (!r.server.isEmpty()) server.setText(r.server);
            user.setText(r.username); pass.setText(r.password);
            say("✓ " + (r.server.isEmpty() ? T("Username and password recognised. Add the server address.", "Gebruikersnaam en wachtwoord herkend. Vul nog het serveradres in.", "Benutzername und Passwort erkannt. Ergänze die Serveradresse.")
                    : T("Recognised: Xtream Codes. Check the fields and tap Connect.", "Herkend: Xtream Codes. Controleer de velden en tik op Verbinden.", "Erkannt: Xtream Codes. Prüfe die Felder und tippe auf Verbinden.")) + note, fromImage ? WARN : OK);
            if (r.server.isEmpty()) server.requestFocus();
        } else if (SourceParse.M3U.equals(r.type)) {
            m3uRadio.setChecked(true); updateMode.run();
            m3u.setText(r.m3u);
            say("✓ " + T("Recognised: M3U link. Tap Connect.", "Herkend: M3U-link. Tik op Verbinden.", "Erkannt: M3U-Link. Tippe auf Verbinden.") + note, fromImage ? WARN : OK);
        } else if (SourceParse.UNSUPPORTED.equals(r.type)) {
            say(T("This is a MAC or Stalker portal, which is not supported. Ask your provider for Xtream Codes or an M3U link.", "Dit is een MAC- of Stalker-portaal; dat wordt niet ondersteund. Vraag je aanbieder om Xtream Codes of een M3U-link.", "Das ist ein MAC- oder Stalker-Portal, das nicht unterstützt wird. Frag deinen Anbieter nach Xtream Codes oder einem M3U-Link."), ERR);
        } else {
            say(T("Nothing recognised. Fill in the fields below yourself.", "Niets herkend. Vul de velden hieronder zelf in.", "Nichts erkannt. Fülle die Felder unten selbst aus."), WARN);
        }
    }

    private void paste() {
        ClipboardManager cm = (ClipboardManager) a.getSystemService(Context.CLIPBOARD_SERVICE);
        ClipData clip = cm == null ? null : cm.getPrimaryClip();
        CharSequence text = clip != null && clip.getItemCount() > 0 ? clip.getItemAt(0).coerceToText(a) : null;
        if (text == null || text.toString().trim().isEmpty()) { say(T("The clipboard is empty. Copy the message from your provider first.", "Het klembord is leeg. Kopieer eerst het bericht van je aanbieder.", "Die Zwischenablage ist leer. Kopiere zuerst die Nachricht deines Anbieters."), WARN); return; }
        smart.setText(text);
        if (pending != null) ui.removeCallbacks(pending);
        apply(SourceParse.parse(text.toString(), false), false);
    }

    /** Called from the activity's onActivityResult. QR first (exact); otherwise on-device text recognition. */
    void onImage(Uri uri) {
        if (uri == null) return;
        say(T("Reading image…", "Afbeelding lezen…", "Bild wird gelesen…"), MUTED);
        worker.execute(() -> {
            Bitmap bmp = null;
            try {
                bmp = decode(uri, 1600);
                String qr = bmp == null ? null : readQr(bmp);
                if (qr != null) { final String q = qr; ui.post(() -> { smart.setText(q); if (pending != null) ui.removeCallbacks(pending); apply(SourceParse.parse(q, false), false); }); return; }
                if (bmp == null) throw new IllegalStateException("image");
                final Bitmap image = bmp;
                ui.post(() -> readText(image));
            } catch (Throwable e) { ui.post(() -> say(T("This image could not be read.", "Deze afbeelding kon niet gelezen worden.", "Dieses Bild konnte nicht gelesen werden."), ERR)); }
        });
    }

    private Bitmap decode(Uri uri, int max) throws Exception {
        BitmapFactory.Options o = new BitmapFactory.Options(); o.inJustDecodeBounds = true;
        try (InputStream in = a.getContentResolver().openInputStream(uri)) { BitmapFactory.decodeStream(in, null, o); }
        int sample = 1; while (Math.max(o.outWidth, o.outHeight) / sample > max) sample *= 2;
        BitmapFactory.Options o2 = new BitmapFactory.Options(); o2.inSampleSize = sample;
        try (InputStream in = a.getContentResolver().openInputStream(uri)) { return BitmapFactory.decodeStream(in, null, o2); }
    }

    static String readQr(Bitmap bmp) {
        int w = bmp.getWidth(), h = bmp.getHeight(); int[] px = new int[w * h];
        bmp.getPixels(px, 0, w, 0, 0, w, h);
        try {
            java.util.Map<com.google.zxing.DecodeHintType, Object> hints = new java.util.EnumMap<>(com.google.zxing.DecodeHintType.class);
            hints.put(com.google.zxing.DecodeHintType.TRY_HARDER, Boolean.TRUE);
            return new com.google.zxing.qrcode.QRCodeReader().decode(new com.google.zxing.BinaryBitmap(new com.google.zxing.common.HybridBinarizer(new com.google.zxing.RGBLuminanceSource(w, h, px))), hints).getText();
        } catch (Exception none) { return null; }
    }

    private void readText(Bitmap bmp) {
        say(T("Recognising text…", "Tekst herkennen…", "Text wird erkannt…"), MUTED);
        try {
            com.google.mlkit.vision.text.TextRecognition.getClient(com.google.mlkit.vision.text.latin.TextRecognizerOptions.DEFAULT_OPTIONS)
                    .process(com.google.mlkit.vision.common.InputImage.fromBitmap(bmp, 0))
                    .addOnSuccessListener(t -> { smart.setText(t.getText()); if (pending != null) ui.removeCallbacks(pending); apply(SourceParse.parse(t.getText(), true), true); })
                    .addOnFailureListener(e -> say(T("Text recognition is not available on this device yet. Paste the text instead.", "Tekstherkenning is op dit apparaat nog niet beschikbaar. Plak de tekst in plaats daarvan.", "Texterkennung ist auf diesem Gerät noch nicht verfügbar. Füge den Text stattdessen ein."), WARN));
        } catch (Throwable unavailable) {
            say(T("Text recognition is not available on this device. Paste the text instead.", "Tekstherkenning is op dit apparaat niet beschikbaar. Plak de tekst in plaats daarvan.", "Texterkennung ist auf diesem Gerät nicht verfügbar. Füge den Text stattdessen ein."), WARN);
        }
    }

    void close() { worker.shutdownNow(); ui.removeCallbacksAndMessages(null); }
}
