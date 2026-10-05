package com.nenotv.player;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.graphics.Typeface;
import android.widget.*;
import com.nenotv.player.storage.ExtraPrivacyStore;

public class AgePrivacyActivity extends Activity {
    LinearLayout box;
    int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(0xFF07090D);
        scroll.setOnApplyWindowInsetsListener((v, i) -> { v.setPadding(0, i.getSystemWindowInsetTop(), 0, i.getSystemWindowInsetBottom()); return i; });
        box = new LinearLayout(this); box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(24), dp(24), dp(24), dp(24)); scroll.addView(box);
        ImageView logo = new ImageView(this); logo.setImageResource(R.drawable.sunnyiptv_logo);
        logo.setScaleType(ImageView.ScaleType.FIT_START); logo.setContentDescription(getString(R.string.app_name));
        box.addView(logo, new LinearLayout.LayoutParams(-1, dp(88)));
        TextView title = new TextView(this);
        title.setText(FamilyUi.text(this, "Wat is je leeftijdsgroep?", "What is your age group?", "Was ist deine Altersgruppe?"));
        title.setTextColor(0xFFF7F8FA); title.setTextSize(24); title.setTypeface(null, Typeface.BOLD);
        title.setPadding(0, dp(24), 0, dp(20)); box.addView(title);
        addChoice("under_13", FamilyUi.text(this, "Jonger dan 13 jaar", "Under 13", "Unter 13 Jahren"));
        addChoice("13_plus", FamilyUi.text(this, "13 jaar of ouder", "13 or older", "13 Jahre oder aelter"));
        addChoice("unknown", FamilyUi.text(this, "Liever niet zeggen", "Prefer not to say", "Keine Angabe"));
        setContentView(scroll); scroll.requestApplyInsets(); UiText.applyDirection(this);
    }
    void addChoice(String group, String label) {
        Button button = new Button(this); button.setTag("age_" + group); button.setText(label);
        button.setAllCaps(false); button.setTextSize(18); button.setTextColor(0xFFF7F8FA);
        button.setBackgroundTintList(android.content.res.ColorStateList.valueOf(0xFF1B2028));
        button.setOnClickListener(v -> choose(group));
        LinearLayout.LayoutParams layout = new LinearLayout.LayoutParams(-1, dp(64)); layout.bottomMargin = dp(10);
        box.addView(button, layout);
    }
    void choose(String group) {
        ExtraPrivacyStore.choose(this, group);
        if (!getIntent().getBooleanExtra("settings", false)) {
            startActivity(new Intent(this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP));
        }
        finish();
    }
    @Override public void onBackPressed() { choose("unknown"); }
}
