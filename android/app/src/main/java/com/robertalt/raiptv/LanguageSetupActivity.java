package com.nenotv.player;

import android.app.*;
import android.content.*;
import android.graphics.Typeface;
import android.os.*;
import android.view.*;
import android.widget.*;
import com.nenotv.player.storage.SettingsStore;
import java.util.*;

/** First-run single source of truth for NenoTV's interface/content/EPG/player language. */
public class LanguageSetupActivity extends Activity {
    static final String[] CODES={"nl","en","de","fr","es","it","pt","tr","pl","ar"};
    int dp(int v){return Math.round(v*getResources().getDisplayMetrics().density);}
    @Override public void onCreate(Bundle b){super.onCreate(b);build();}
    String nativeName(String code){try{Locale l=Locale.forLanguageTag(code);String x=l.getDisplayLanguage(l);return x.substring(0,1).toUpperCase(l)+x.substring(1);}catch(Exception e){return code.toUpperCase(Locale.ROOT);}}
    void build(){
        ScrollView sv=new ScrollView(this);sv.setBackgroundColor(0xFF07090D);sv.setFillViewport(true);sv.setOnApplyWindowInsetsListener((v,i)->{v.setPadding(0,i.getSystemWindowInsetTop(),0,i.getSystemWindowInsetBottom());return i;});
        LinearLayout box=new LinearLayout(this);box.setOrientation(LinearLayout.VERTICAL);box.setPadding(dp(24),dp(28),dp(24),dp(32));sv.addView(box,new ScrollView.LayoutParams(-1,-1));
        ImageView brand=new ImageView(this);brand.setImageResource(R.drawable.sunnyiptv_logo);brand.setContentDescription(getString(R.string.app_name));brand.setScaleType(ImageView.ScaleType.FIT_START);box.addView(brand,new LinearLayout.LayoutParams(-1,dp(88)));
        TextView title=new TextView(this);title.setText("Kies je taal  ·  Choose your language");title.setTextColor(0xFFF7F8FA);title.setTextSize(24);title.setTypeface(null,Typeface.BOLD);title.setPadding(0,dp(28),0,dp(8));box.addView(title);
        TextView help=new TextView(this);help.setText("Deze taal wordt de standaard voor de interface, Live TV, TV-gids, films, series, metadata, audio en ondertiteling wanneer beschikbaar. Je kunt dit later wijzigen in Instellingen.");help.setTextColor(0xFFA7AFBC);help.setTextSize(14);help.setPadding(0,0,0,dp(18));box.addView(help);
        String suggested=SettingsStore.language(this);
        for(String code:CODES){Button bt=new Button(this);String mark=code.equals(suggested)?"★  ":"";bt.setText(mark+nativeName(code));bt.setAllCaps(false);bt.setTextSize(18);bt.setTextColor(0xFFF7F8FA);bt.setGravity(Gravity.START|Gravity.CENTER_VERTICAL);bt.setPadding(dp(18),0,dp(12),0);bt.setBackgroundTintList(android.content.res.ColorStateList.valueOf(code.equals(suggested)?0xFFF04455:0xFF1B2028));bt.setOnClickListener(v->choose(code));box.addView(bt,new LinearLayout.LayoutParams(-1,dp(58)));}
        setContentView(sv);sv.requestApplyInsets();
    }
    void choose(String code){SettingsStore.setPrimaryLanguage(this,code);Intent i=new Intent(this,MainActivity.class);i.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP|Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_CLEAR_TASK);startActivity(i);finish();}
    @Override public void onBackPressed(){}
}
