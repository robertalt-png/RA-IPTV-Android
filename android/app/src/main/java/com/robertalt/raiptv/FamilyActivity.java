package com.nenotv.player;

import android.app.Activity;
import android.app.AlertDialog;
import android.os.Bundle;
import android.text.InputType;
import android.view.WindowManager;
import android.widget.*;
import com.nenotv.player.storage.FamilyStore;
import com.nenotv.player.storage.SettingsStore;

/** Family filter as three clear levels: Everyone, Family (no 18+), Children (child mode). Free in Light. */
public class FamilyActivity extends Activity {
    private static final int ALL = 0, FAMILY = 1, KIDS = 2;
    private String text(String nl, String en, String de) { return FamilyUi.text(this, nl, en, de); }
    @Override public void onCreate(Bundle state) { super.onCreate(state); build(); }
    @Override protected void onResume() { super.onResume(); build(); }

    private int level() { return FamilyStore.active(this) ? KIDS : SettingsStore.parental(this) ? FAMILY : ALL; }

    private void build() {
        LinearLayout box = Tiles.page(this, text("Familiefilter", "Family filter", "Familienfilter"));
        Tiles.note(this, box, text("Kies wat er op dit apparaat te zien is. Terug naar een ruimere stand kan alleen met de ouder-PIN.",
                "Choose what can be watched on this device. Going back to a less strict level needs the parent PIN.",
                "Wähle, was auf diesem Gerät zu sehen ist. Zurück zu einer offeneren Stufe geht nur mit der Eltern-PIN."));
        int current = level();
        Tiles.Grid levels = new Tiles.Grid(this, box, Tiles.columns(this) == 3 ? 3 : 1);
        levels.add("🌍", text("Iedereen", "Everyone", "Alle"), text("Alles is zichtbaar, ook 18+.", "Everything is visible, including 18+.", "Alles ist sichtbar, auch 18+."), current == ALL, v -> choose(ALL));
        levels.add("🏠", text("Familie", "Family", "Familie"), text("18+ is verborgen.", "18+ is hidden.", "18+ ist ausgeblendet."), current == FAMILY, v -> choose(FAMILY));
        levels.add("🧸", text("Kinderen", "Children", "Kinder"), text("Alleen kindercategorieën en wat jij goedkeurt.", "Only children's categories and what you approve.", "Nur Kinderkategorien und was du erlaubst."), current == KIDS, v -> choose(KIDS));
        levels.finish();

        Tiles.heading(this, box, text("Ouder-PIN", "Parent PIN", "Eltern-PIN"));
        Tiles.Grid pinGrid = new Tiles.Grid(this, box);
        boolean hasPin = SettingsStore.hasParentalPin(this);
        pinGrid.add("🔑", hasPin ? UiText.t(this, "pin_change") : UiText.t(this, "pin_set"),
                hasPin ? text("PIN is ingesteld", "PIN is set", "PIN ist festgelegt") : text("Nodig voor Familie en Kinderen", "Needed for Family and Children", "Nötig für Familie und Kinder"), false,
                v -> { if (SettingsStore.hasParentalPin(this)) FamilyUi.pin(this, p -> newPin(null)); else newPin(null); });
        if (current == KIDS) {
            boolean auto = FamilyStore.autoKids(this);
            pinGrid.add("🧸", text("Kindercategorieën", "Children's categories", "Kinderkategorien"),
                    auto ? text("Automatisch toegestaan · tik om uit te zetten", "Allowed automatically · tap to turn off", "Automatisch erlaubt · zum Ausschalten tippen")
                         : text("Uit · alleen wat jij goedkeurt", "Off · only what you approve", "Aus · nur was du erlaubst"), false,
                    v -> FamilyUi.pin(this, p -> { if (FamilyStore.setAutoKids(this, !auto, p)) build(); else FamilyUi.blocked(this); }));
        }
        pinGrid.finish();

        if (current == KIDS) {
            Tiles.heading(this, box, text("Zelf goedgekeurd", "Approved by you", "Von dir erlaubt"));
            java.util.List<FamilyStore.Approval> rows = FamilyStore.list(this);
            if (rows.isEmpty()) Tiles.note(this, box, text("Nog niets. Houd een titel ingedrukt en kies ‘Toestaan in kindermodus’.",
                    "Nothing yet. Long-press a title and choose ‘Allow in child mode’.", "Noch nichts. Titel gedrückt halten und ‘Im Kindermodus erlauben’ wählen."));
            Tiles.Grid approved = new Tiles.Grid(this, box);
            for (FamilyStore.Approval row : rows)
                approved.add("", row.name, text("Tik om te verwijderen", "Tap to remove", "Zum Entfernen tippen"), false,
                        v -> FamilyUi.pin(this, p -> { if (FamilyStore.revoke(this, row.key, p)) build(); else FamilyUi.blocked(this); }));
            approved.finish();
        }
        Tiles.link(this, box, text("Privacyverklaring", "Privacy policy", "Datenschutzerklärung"), v -> Tiles.open(this, SiteEndpoints.privacyUrl(SettingsStore.language(this))));
        UiText.applyDirection(this);
    }

    private void choose(int target) {
        int current = level();
        if (target == current) return;
        if (!SettingsStore.hasParentalPin(this)) {
            // A level that hides content must be protected, otherwise a child could switch it back.
            if (target == ALL) { apply(target, null); return; }
            newPin(() -> choose(target));
            return;
        }
        // Stricter levels need no PIN; anything less strict (or leaving child mode) does.
        boolean stricter = target > current;
        if (stricter && target == FAMILY) { apply(target, null); return; }
        FamilyUi.pin(this, pin -> apply(target, pin));
    }

    private void apply(int target, String pin) {
        if (target == KIDS) {
            if (pin == null || !FamilyStore.setActive(this, true, pin)) { FamilyUi.blocked(this); return; }
        } else if (FamilyStore.active(this)) {
            if (pin == null || !FamilyStore.setActive(this, false, pin)) { FamilyUi.blocked(this); return; }
        }
        SettingsStore.setParentalEnabled(this, target != ALL);
        build();
    }

    private void newPin(Runnable after) {
        LinearLayout wrap = new LinearLayout(this); wrap.setOrientation(LinearLayout.VERTICAL);
        EditText first = new EditText(this), second = new EditText(this);
        for (EditText field : new EditText[]{first, second}) { field.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_VARIATION_PASSWORD); wrap.addView(field); }
        first.setHint(text("Nieuwe PIN (4-8 cijfers)", "New PIN (4-8 digits)", "Neue PIN (4-8 Ziffern)")); second.setHint(text("Herhaal PIN", "Repeat PIN", "PIN wiederholen"));
        AlertDialog d = new AlertDialog.Builder(this).setTitle(text("Ouder-PIN", "Parent PIN", "Eltern-PIN")).setView(wrap).setNegativeButton(UiText.t(this, "cancel"), null).setPositiveButton(UiText.t(this, "save"), null).create();
        d.setOnShowListener(x -> {
            d.getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
            d.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
                String pin = first.getText().toString();
                if (!pin.matches("[0-9]{4,8}") || !pin.equals(second.getText().toString())) { second.setError(text("Controleer beide PIN-codes", "Check both PIN codes", "Beide PINs prüfen")); return; }
                SettingsStore.setParentalPin(this, pin); first.setText(""); second.setText(""); d.dismiss(); build();
                if (after != null) after.run();
            });
        });
        d.show();
    }
}
