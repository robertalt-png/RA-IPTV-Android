package com.nenotv.player;

import android.app.Activity;
import android.os.Bundle;
import android.text.InputFilter;
import android.widget.*;
import com.nenotv.player.entitlement.EntitlementClient;
import java.util.UUID;

public final class FeedbackActivity extends Activity {
    private String requestId = UUID.randomUUID().toString();
    private boolean sending;
    private String submitted = "";
    private EditText message;
    private Spinner kind;
    private Button send;
    private TextView status;

    private String text(String nl,String en,String de) { return FamilyUi.text(this,nl,en,de); }

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        if (state != null) requestId = state.getString("request_id",requestId);
        if (state != null) submitted = state.getString("submitted","");
        LinearLayout box = Tiles.page(this,"Feedback");
        kind = new Spinner(this);
        kind.setAdapter(new ArrayAdapter<>(this,android.R.layout.simple_spinner_dropdown_item,new String[]{text("Klacht","Complaint","Beschwerde"),text("Opmerking","Comment","Kommentar"),text("Tip","Tip","Tipp")}));
        box.addView(kind);
        message = new EditText(this);
        message.setHint(text("Je bericht","Your message","Deine Nachricht"));
        message.setMinLines(5);
        message.setGravity(android.view.Gravity.TOP);
        message.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        message.setFilters(new InputFilter[]{new InputFilter.LengthFilter(2000)});
        box.addView(message);
        Tiles.note(this,box,text("Je bericht, appversie, Android-versie en apparaatmodel worden naar SunnyIPTV gestuurd. Vermeld geen wachtwoorden, betaalgegevens of playlistlinks.","Your message, app version, Android version and device model are sent to SunnyIPTV. Do not include passwords, payment details or playlist links.","Deine Nachricht, App-Version, Android-Version und das Geraetemodell werden an SunnyIPTV gesendet. Keine Passwoerter, Zahlungsdaten oder Playlistlinks angeben."));
        status = new TextView(this); box.addView(status);
        send = new Button(this); send.setText(text("Versturen","Send","Senden")); box.addView(send);
        if (state != null) { message.setText(state.getString("message","")); kind.setSelection(state.getInt("kind",0)); }
        send.setOnClickListener(v -> submit());
        UiText.applyDirection(this);
    }

    private void submit() {
        String body = message.getText().toString().trim();
        if (sending) return;
        if (body.length() < 3) { message.setError(text("Schrijf een bericht","Write a message","Schreibe eine Nachricht")); return; }
        String category = new String[]{"complaint","comment","tip"}[kind.getSelectedItemPosition()];
        String fingerprint = category + ":" + body;
        if (!submitted.isEmpty() && !submitted.equals(fingerprint)) requestId = UUID.randomUUID().toString();
        submitted = fingerprint;
        sending = true; send.setEnabled(false); message.setEnabled(false); kind.setEnabled(false);
        status.setText(text("Versturen...","Sending...","Wird gesendet..."));
        new Thread(() -> {
            String error = null;
            try { new EntitlementClient(this).feedback(category,body,requestId); }
            catch (Exception e) {
                error = e instanceof EntitlementClient.ServiceException && "account_not_linked".equals(((EntitlementClient.ServiceException)e).code)
                    ? text("Koppel eerst je account via Instellingen > Account.","Link your account in Settings > Account first.","Verknuepfe zuerst dein Konto unter Einstellungen > Konto.")
                    : text("Versturen mislukt. Je bericht staat er nog; probeer opnieuw.","Sending failed. Your message is still here; try again.","Senden fehlgeschlagen. Deine Nachricht bleibt erhalten; versuche es erneut.");
            }
            final String result = error;
            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed()) return;
                sending = false;
                if (result == null) {
                    message.setText(""); requestId = UUID.randomUUID().toString(); submitted = "";
                    status.setText(text("Bedankt, je bericht is ontvangen.","Thank you, your message was received.","Danke, deine Nachricht ist angekommen."));
                } else status.setText(result);
                send.setEnabled(true); message.setEnabled(true); kind.setEnabled(true);
            });
        },"sunny-feedback").start();
    }

    @Override protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);
        out.putString("request_id",requestId); out.putString("message",message.getText().toString()); out.putInt("kind",kind.getSelectedItemPosition());
        out.putString("submitted",submitted);
    }
}
