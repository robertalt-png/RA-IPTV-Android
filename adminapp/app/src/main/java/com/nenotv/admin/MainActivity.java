package com.nenotv.admin;

import android.app.Activity;
import android.app.AlertDialog;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.widget.Button;
import android.widget.EditText;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.text.NumberFormat;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends Activity {
    private static final String BASE = "https://nenotv.com/wp-json/nenotv-dashboard/v1/";
    private static final int BG = Color.rgb(11,14,20);
    private static final int CARD = Color.rgb(21,26,36);
    private static final int CARD2 = Color.rgb(29,36,48);
    private static final int TEXT = Color.rgb(245,247,250);
    private static final int MUTED = Color.rgb(164,174,190);
    private static final int ACCENT = Color.rgb(255,77,103);
    private static final int GREEN = Color.rgb(44,190,120);
    private static final int ORANGE = Color.rgb(240,166,52);
    private static final int RED = Color.rgb(238,79,91);

    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Handler handler = new Handler(Looper.getMainLooper());

    private LinearLayout root;
    private LinearLayout content;
    private TextView updated;
    private JSONObject data;
    private int currentTab = 0;
    private int moreSection = 0;
    private int rangeDays = 7;
    private boolean loading = false;

    private final String[] tabs = {"Overzicht","Analytics","App","Meer"};

    private final Runnable autoRefresh = new Runnable() {
        @Override public void run() {
            if (!isFinishing() && SecureStore.getToken(MainActivity.this).length() > 0) {
                requestData(false);
                handler.postDelayed(this, 60000);
            }
        }
    };

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().setStatusBarColor(BG);
        getWindow().setNavigationBarColor(BG);
        if (SecureStore.getToken(this).isEmpty()) showPairing();
        else showShell();
    }

    @Override protected void onResume() {
        super.onResume();
        handler.removeCallbacks(autoRefresh);
        if (!SecureStore.getToken(this).isEmpty()) {
            if (data != null) requestData(false);
            handler.postDelayed(autoRefresh, 60000);
        }
    }

    @Override protected void onPause() {
        handler.removeCallbacks(autoRefresh);
        super.onPause();
    }

    @Override protected void onDestroy() {
        handler.removeCallbacks(autoRefresh);
        io.shutdownNow();
        super.onDestroy();
    }

    private int dp(int v) {
        return (int)(v * getResources().getDisplayMetrics().density + 0.5f);
    }

    private TextView text(String value, int size, int color, boolean bold) {
        TextView t = new TextView(this);
        t.setText(value);
        t.setTextSize(size);
        t.setTextColor(color);
        if (bold) t.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        t.setLineSpacing(0, 1.08f);
        return t;
    }

    private GradientDrawable bg(int color, float radius) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(dp((int)radius));
        return d;
    }

    private void applySystemInsets(View target, int baseLeft, int baseTop, int baseRight, int baseBottom) {
        target.setOnApplyWindowInsetsListener((v, insets) -> {
            int left = 0, top = 0, right = 0, bottom = 0;
            if (Build.VERSION.SDK_INT >= 30) {
                android.graphics.Insets bars = insets.getInsets(WindowInsets.Type.systemBars());
                left = bars.left;
                top = bars.top;
                right = bars.right;
                bottom = bars.bottom;
            } else {
                left = insets.getSystemWindowInsetLeft();
                top = insets.getSystemWindowInsetTop();
                right = insets.getSystemWindowInsetRight();
                bottom = insets.getSystemWindowInsetBottom();
            }
            v.setPadding(dp(baseLeft) + left, dp(baseTop) + top, dp(baseRight) + right, dp(baseBottom) + bottom);
            return insets;
        });
        target.requestApplyInsets();
    }

    private Button button(String label) {
        Button b = new Button(this);
        b.setText(label);
        b.setTextColor(TEXT);
        b.setTextSize(13);
        b.setAllCaps(false);
        b.setBackground(bg(CARD2, 12));
        b.setPadding(dp(12), dp(8), dp(12), dp(8));
        return b;
    }

    private void showPairing() {
        handler.removeCallbacks(autoRefresh);
        root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER);
        root.setBackgroundColor(BG);
        applySystemInsets(root, 26, 30, 26, 30);

        TextView logo = text("N", 44, ACCENT, true);
        logo.setGravity(Gravity.CENTER);
        logo.setBackground(bg(CARD, 20));
        LinearLayout.LayoutParams lpLogo = new LinearLayout.LayoutParams(dp(88), dp(88));
        lpLogo.bottomMargin = dp(22);
        root.addView(logo, lpLogo);

        TextView title = text("NenoTV Admin", 27, TEXT, true);
        title.setGravity(Gravity.CENTER);
        root.addView(title);

        TextView sub = text("Koppel deze telefoon veilig met NenoTV. De app is alleen-lezen.", 15, MUTED, false);
        sub.setGravity(Gravity.CENTER);
        sub.setPadding(0, dp(8), 0, dp(22));
        root.addView(sub, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        EditText code = new EditText(this);
        code.setHint("Koppelcode");
        code.setHintTextColor(MUTED);
        code.setTextColor(TEXT);
        code.setTextSize(20);
        code.setGravity(Gravity.CENTER);
        code.setSingleLine(true);
        code.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS);
        code.setBackground(bg(CARD, 14));
        code.setPadding(dp(16), dp(14), dp(16), dp(14));
        LinearLayout.LayoutParams codeLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(58));
        codeLp.bottomMargin = dp(14);
        root.addView(code, codeLp);

        Button pair = button("Telefoon koppelen");
        pair.setBackground(bg(ACCENT, 14));
        LinearLayout.LayoutParams pairLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(56));
        root.addView(pair, pairLp);

        TextView help = text("Maak de code in WordPress via WooCommerce → NenoTV Admin App. De code is 10 minuten geldig.", 13, MUTED, false);
        help.setGravity(Gravity.CENTER);
        help.setPadding(0, dp(18), 0, 0);
        root.addView(help);

        pair.setOnClickListener(v -> {
            String c = code.getText().toString().trim().toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9]", "");
            if (c.length() < 6) {
                Toast.makeText(this, "Vul de koppelcode in.", Toast.LENGTH_SHORT).show();
                return;
            }
            pair.setEnabled(false);
            pair.setText("Koppelen…");
            io.execute(() -> {
                try {
                    JSONObject response = pair(c);
                    if (!response.optBoolean("ok")) throw new Exception(response.optString("error","Koppelen mislukt"));
                    String token = response.optString("token","");
                    if (token.isEmpty()) throw new Exception("Geen toegangssleutel ontvangen");
                    SecureStore.putToken(this, token);
                    runOnUiThread(() -> {
                        Toast.makeText(this, "NenoTV Admin is gekoppeld.", Toast.LENGTH_SHORT).show();
                        showShell();
                    });
                } catch (Exception e) {
                    runOnUiThread(() -> {
                        pair.setEnabled(true);
                        pair.setText("Telefoon koppelen");
                        Toast.makeText(this, friendly(e), Toast.LENGTH_LONG).show();
                    });
                }
            });
        });

        setContentView(root);
    }

    private void showShell() {
        root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(BG);
        applySystemInsets(root, 0, 0, 0, 0);

        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(dp(18), dp(14), dp(12), dp(10));

        LinearLayout heading = new LinearLayout(this);
        heading.setOrientation(LinearLayout.VERTICAL);
        TextView title = text("NenoTV Admin", 22, TEXT, true);
        updated = text("Verbinden…", 12, MUTED, false);
        heading.addView(title);
        heading.addView(updated);
        header.addView(heading, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        Button refresh = button("Vernieuwen");
        refresh.setOnClickListener(v -> requestData(true));
        header.addView(refresh, new LinearLayout.LayoutParams(dp(110), dp(46)));
        root.addView(header);

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(14), dp(8), dp(14), dp(24));
        scroll.addView(content, new ScrollView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        root.addView(scroll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        LinearLayout navRow = new LinearLayout(this);
        navRow.setOrientation(LinearLayout.HORIZONTAL);
        navRow.setPadding(dp(7), dp(7), dp(7), dp(7));
        navRow.setBackgroundColor(CARD);
        for (int i=0;i<tabs.length;i++) {
            final int index = i;
            Button b = button(tabs[i]);
            b.setTag("tab"+i);
            b.setOnClickListener(v -> {
                currentTab = index;
                render();
                styleTabs(navRow);
            });
            LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(0, dp(48), 1f);
            blp.setMargins(dp(3),0,dp(3),0);
            navRow.addView(b, blp);
        }
        root.addView(navRow, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(62)));
        setContentView(root);
        styleTabs(navRow);
        requestData(true);
        handler.removeCallbacks(autoRefresh);
        handler.postDelayed(autoRefresh, 60000);
    }

    private void styleTabs(LinearLayout row) {
        for (int i=0;i<row.getChildCount();i++) {
            View v=row.getChildAt(i);
            if (v instanceof Button) {
                ((Button)v).setBackground(bg(i==currentTab ? ACCENT : CARD2, 12));
            }
        }
    }

    private JSONObject pair(String code) throws Exception {
        HttpURLConnection c = (HttpURLConnection)new URL(BASE + "admin-pair").openConnection();
        c.setConnectTimeout(10000);
        c.setReadTimeout(10000);
        c.setRequestMethod("POST");
        c.setRequestProperty("Content-Type","application/json; charset=utf-8");
        c.setRequestProperty("Accept","application/json");
        c.setDoOutput(true);
        byte[] body = new JSONObject().put("code", code).toString().getBytes(StandardCharsets.UTF_8);
        c.setFixedLengthStreamingMode(body.length);
        try (OutputStream out=c.getOutputStream()) { out.write(body); }
        int status=c.getResponseCode();
        JSONObject result=new JSONObject(read(status>=200&&status<300 ? c.getInputStream() : c.getErrorStream()));
        c.disconnect();
        return result;
    }

    private JSONObject fetch() throws Exception {
        String token = SecureStore.getToken(this);
        if (token.isEmpty()) throw new SecurityException("Niet gekoppeld");
        URL url = new URL(BASE + "admin-app?days=" + rangeDays);
        HttpURLConnection c=(HttpURLConnection)url.openConnection();
        c.setConnectTimeout(10000);
        c.setReadTimeout(12000);
        c.setRequestMethod("GET");
        c.setRequestProperty("Authorization","Bearer " + token);
        c.setRequestProperty("Accept","application/json");
        c.setRequestProperty("User-Agent","NenoTV-Admin/0.1.2 Android");
        int status=c.getResponseCode();
        String body=read(status>=200&&status<300 ? c.getInputStream() : c.getErrorStream());
        c.disconnect();
        if (status==401 || status==403) throw new SecurityException("Koppeling is verlopen of ingetrokken");
        if (status<200 || status>=300) throw new Exception("Serverfout " + status);
        return new JSONObject(body);
    }

    private String read(InputStream in) throws Exception {
        if (in==null) return "{}";
        StringBuilder b=new StringBuilder();
        try (BufferedReader r=new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            String line;
            while((line=r.readLine())!=null) b.append(line);
        }
        return b.toString();
    }

    private void requestData(boolean userAction) {
        if (loading) return;
        loading=true;
        if (updated!=null) updated.setText("Bijwerken…");
        io.execute(() -> {
            try {
                JSONObject next=fetch();
                runOnUiThread(() -> {
                    data=next;
                    loading=false;
                    if (updated!=null) updated.setText("Bijgewerkt " + clock(next.optString("updated_at","")));
                    render();
                });
            } catch (SecurityException e) {
                runOnUiThread(() -> {
                    loading=false;
                    SecureStore.clear(this);
                    Toast.makeText(this, e.getMessage(), Toast.LENGTH_LONG).show();
                    showPairing();
                });
            } catch (Exception e) {
                runOnUiThread(() -> {
                    loading=false;
                    if (updated!=null) updated.setText("Kon niet vernieuwen");
                    if (userAction) Toast.makeText(this, friendly(e), Toast.LENGTH_LONG).show();
                });
            }
        });
    }

    private String clock(String iso) {
        if (iso==null || iso.length()<16) return "zojuist";
        return iso.substring(11,16);
    }

    private String friendly(Exception e) {
        String m=e.getMessage();
        if (m==null || m.trim().isEmpty()) return "Er ging iets mis.";
        if (m.contains("invalid_or_expired_code")) return "De koppelcode is onjuist of verlopen.";
        if (m.contains("rate_limited")) return "Te veel pogingen. Probeer later opnieuw.";
        return m;
    }

    private void render() {
        if (content==null) return;
        content.removeAllViews();
        if (data==null) {
            addCentered("Gegevens laden…");
            return;
        }
        switch(currentTab) {
            case 1: renderAnalytics(); break;
            case 2: renderApp(); break;
            case 3: renderMore(); break;
            default: renderDashboard();
        }
    }

    private void addCentered(String s) {
        TextView t=text(s,15,MUTED,false);
        t.setGravity(Gravity.CENTER);
        t.setPadding(0,dp(60),0,0);
        content.addView(t);
    }

    private void section(String name) {
        TextView t=text(name,18,TEXT,true);
        t.setPadding(dp(2),dp(15),0,dp(9));
        content.addView(t);
    }

    private LinearLayout box() {
        LinearLayout l=new LinearLayout(this);
        l.setOrientation(LinearLayout.VERTICAL);
        l.setPadding(dp(15),dp(13),dp(15),dp(13));
        l.setBackground(bg(CARD,14));
        LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0,0,0,dp(10));
        content.addView(l,lp);
        return l;
    }

    private void metricPair(String aTitle,String aValue,String aSub,String bTitle,String bValue,String bSub) {
        LinearLayout row=new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams rowLp=new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,ViewGroup.LayoutParams.WRAP_CONTENT);
        rowLp.setMargins(0,0,0,dp(10));
        content.addView(row,rowLp);
        addMetric(row,aTitle,aValue,aSub,0);
        addMetric(row,bTitle,bValue,bSub,1);
    }

    private void addMetric(LinearLayout row,String title,String value,String sub,int side) {
        LinearLayout c=new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setPadding(dp(14),dp(13),dp(14),dp(13));
        c.setBackground(bg(CARD,14));
        TextView t=text(title,12,MUTED,false);
        TextView v=text(value,24,TEXT,true);
        TextView s=text(sub==null?"":sub,12,MUTED,false);
        s.setPadding(0,dp(3),0,0);
        c.addView(t); c.addView(v); if(sub!=null&&!sub.isEmpty())c.addView(s);
        LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(0,ViewGroup.LayoutParams.WRAP_CONTENT,1f);
        if(side==0)lp.rightMargin=dp(5); else lp.leftMargin=dp(5);
        row.addView(c,lp);
    }

    private void statusLine(LinearLayout parent,String label,String state,String detail) {
        LinearLayout row=new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0,dp(7),0,dp(7));
        TextView l=text(label,14,TEXT,true);
        row.addView(l,new LinearLayout.LayoutParams(0,ViewGroup.LayoutParams.WRAP_CONTENT,1f));
        TextView s=text(displayState(state),13,statusColor(state),true);
        s.setGravity(Gravity.END);
        row.addView(s);
        parent.addView(row);
        if(detail!=null&&!detail.isEmpty()) {
            TextView d=text(detail,12,MUTED,false);
            d.setPadding(0,0,0,dp(5));
            parent.addView(d);
        }
    }

    private String displayState(String state) {
        if(state==null)return "—";
        String s=state.toLowerCase(Locale.ROOT);
        if(s.equals("online")||s.equals("live")||s.equals("active")||s.equals("ready")) return "● Online";
        if(s.equals("not_live")||s.equals("shadow")||s.equals("yes")||s.equals("test")) return "● Test";
        if(s.equals("offline")||s.equals("error")||s.equals("failed")) return "● Probleem";
        if(s.equals("prelaunch")) return "● Uitgeschakeld";
        if(s.equals("idle")) return "● Niet actief";
        return state;
    }

    private int statusColor(String state) {
        if(state==null)return MUTED;
        String s=state.toLowerCase(Locale.ROOT);
        if(s.equals("online")||s.equals("live")||s.equals("active")||s.equals("ready"))return GREEN;
        if(s.equals("offline")||s.equals("error")||s.equals("failed"))return RED;
        return ORANGE;
    }

    private void row(LinearLayout parent,String label,String value) {
        LinearLayout r=new LinearLayout(this);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setGravity(Gravity.TOP);
        r.setPadding(0,dp(6),0,dp(6));
        TextView l=text(label,13,MUTED,false);
        TextView v=text(value,13,TEXT,true);
        v.setGravity(Gravity.END);
        r.addView(l,new LinearLayout.LayoutParams(0,ViewGroup.LayoutParams.WRAP_CONTENT,1f));
        r.addView(v,new LinearLayout.LayoutParams(0,ViewGroup.LayoutParams.WRAP_CONTENT,1f));
        parent.addView(r);
    }

    private void renderDashboard() {
        JSONObject site=data.optJSONObject("site");
        JSONObject app=data.optJSONObject("app");
        JSONObject system=data.optJSONObject("system");
        JSONObject today=data.optJSONObject("today");
        JSONObject analytics=data.optJSONObject("analytics");
        JSONObject usage=data.optJSONObject("app_usage");
        JSONObject testers=data.optJSONObject("testers");

        section("Status");
        LinearLayout s=box();
        statusLine(s,"Website",site==null?"offline":site.optString("status","offline"),site==null?"":site.optString("url",""));
        statusLine(s,"Site Bridge / bot",system==null?"offline":system.optString("site_bridge","offline"),"Beheerverbinding met NenoTV");
        statusLine(s,"App Bridge",system==null?"offline":system.optString("app_bridge","offline"),app==null?"":("v"+app.optString("bridge_version","")));
        String player=(usage!=null&&usage.optInt("active_5m",0)>0)?"active":"idle";
        statusLine(s,"Appgebruik",player,(usage==null?0:usage.optInt("active_5m",0))+" echte Android-apparaten actief in 5 min");

        section("Vandaag");
        metricPair("Live bezoekers",String.valueOf(analytics==null?0:analytics.optInt("live_visitors",0)),"laatste 5 min",
                "Bezoekers",String.valueOf(today==null?0:today.optInt("visitors",0)),"vandaag");
        metricPair("App-apparaten 24u",String.valueOf(usage==null?0:usage.optInt("active_24h",0)),"laatste 24 uur",
                "Testers actief",String.valueOf(testers==null?0:testers.optInt("active_today",0)),"vandaag");
        metricPair("Orders",String.valueOf(today==null?0:today.optInt("orders",0)),"vandaag",
                "Omzet",money(today==null?0:today.optDouble("revenue",0),today==null?"EUR":today.optString("currency","EUR")),"vandaag");

        JSONArray alerts=data.optJSONArray("alerts");
        section("Aandacht");
        LinearLayout a=box();
        if(alerts==null||alerts.length()==0) {
            statusLine(a,"Geen open waarschuwingen","online","Alles wat we nu meten staat op groen.");
        } else {
            for(int i=0;i<alerts.length();i++){
                JSONObject x=alerts.optJSONObject(i);
                if(x!=null) statusLine(a,x.optString("message","Waarschuwing"),"error",x.optString("type",""));
            }
        }
    }

    private void rangeButtons() {
        LinearLayout row=new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(0,0,0,dp(10));
        int[] ranges={1,7,28,90};
        for(int d:ranges){
            Button b=button(d==1?"Vandaag":d+" d");
            b.setBackground(bg(d==rangeDays?ACCENT:CARD2,12));
            b.setOnClickListener(v->{ rangeDays=d; requestData(true); });
            LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(0,dp(44),1f);
            lp.setMargins(dp(2),0,dp(2),0);
            row.addView(b,lp);
        }
        content.addView(row);
    }

    private void renderAnalytics() {
        rangeButtons();
        JSONObject detail=data.optJSONObject("analytics_detail");
        JSONObject sum=detail==null?null:detail.optJSONObject("summary");
        JSONObject prev=detail==null?null:detail.optJSONObject("previous");
        section("Bezoekers");
        metricPair("Bezoekers",String.valueOf(sum==null?0:sum.optInt("visitors",0)),rangeLabel(),
                "Sessies",String.valueOf(sum==null?0:sum.optInt("sessions",0)),rangeLabel());
        metricPair("Pageviews",String.valueOf(sum==null?0:sum.optInt("pageviews",0)),"",
                "Landen",String.valueOf(sum==null?0:sum.optInt("countries",0)),"");

        section("Trend");
        LinearLayout trend=box();
        row(trend,"Bezoekers",trendText(sum==null?0:sum.optInt("visitors",0),prev==null?0:prev.optInt("visitors",0)));
        row(trend,"Sessies",trendText(sum==null?0:sum.optInt("sessions",0),prev==null?0:prev.optInt("sessions",0)));
        row(trend,"Pageviews",trendText(sum==null?0:sum.optInt("pageviews",0),prev==null?0:prev.optInt("pageviews",0)));

        section("Per dag");
        LinearLayout daily=box();
        JSONArray days=detail==null?null:detail.optJSONArray("daily");
        if(days==null||days.length()==0) row(daily,"Nog geen data","—");
        else for(int i=0;i<days.length();i++){
            JSONObject x=days.optJSONObject(i);
            if(x!=null) row(daily,shortDay(x.optString("day","")),x.optString("visitors","0")+" bezoekers · "+x.optString("pageviews","0")+" views");
        }

        topList("Top landen",detail==null?null:detail.optJSONArray("countries"),true);
        topList("Top pagina’s",detail==null?null:detail.optJSONArray("pages"),false);
        topList("Bronnen",detail==null?null:detail.optJSONArray("sources"),false);
        topList("Apparaten",detail==null?null:detail.optJSONArray("devices"),false);
    }

    private void topList(String title,JSONArray arr,boolean country) {
        section(title);
        LinearLayout b=box();
        if(arr==null||arr.length()==0){ row(b,"Geen gegevens","—"); return; }
        int max=Math.min(arr.length(),8);
        for(int i=0;i<max;i++){
            JSONObject x=arr.optJSONObject(i);
            if(x==null)continue;
            String label=analyticsLabel(x.optString("label","—"), country);
            row(b,label,x.optString("visitors","0")+" bezoekers · "+x.optString("pageviews","0")+" views");
        }
    }

    private void renderApp() {
        JSONObject app=data.optJSONObject("app");
        JSONObject u=data.optJSONObject("app_usage");
        section("App & player");
        LinearLayout s=box();
        statusLine(s,"App public",app==null?"not_live":app.optString("status","not_live"),"Publieke release staat bewust nog uit.");
        statusLine(s,"App Bridge",app==null?"offline":app.optString("bridge_status","offline"),"Bridge v"+(app==null?"—":app.optString("bridge_version","—")));
        row(s,"Huidige testbuild",app==null?"—":app.optString("test_build_version",app.optString("latest_version","—")));
        row(s,"Laatst echt gezien",app==null?"—":app.optString("latest_seen_version","—"));
        row(s,"Laatste appcontact",app==null?"—":app.optString("last_contact_gmt","—"));
        row(s,"Laatste resultaat",app==null?"—":app.optString("last_outcome","—"));

        section("Gebruik");
        metricPair("Actief 5 min",String.valueOf(u==null?0:u.optInt("active_5m",0)),"apparaten",
                "Actief 24 uur",String.valueOf(u==null?0:u.optInt("active_24h",0)),"apparaten");
        metricPair("Bekende apparaten",String.valueOf(u==null?0:u.optInt("total_devices",0)),"",
                "Gem. latency",(u==null?0:u.optInt("avg_latency_ms",0))+" ms","");
        metricPair("Requests",String.valueOf(u==null?0:u.optInt("requests",0)),"",
                "Errors",String.valueOf(u==null?0:u.optInt("errors",0)),"");
    }

    private void renderMore() {
        LinearLayout selector=new LinearLayout(this);
        selector.setOrientation(LinearLayout.HORIZONTAL);
        selector.setPadding(0,0,0,dp(10));
        String[] labels={"Testers","Commerce","Systeem"};
        for(int i=0;i<labels.length;i++){
            final int idx=i;
            Button b=button(labels[i]);
            b.setBackground(bg(i==moreSection?ACCENT:CARD2,12));
            b.setOnClickListener(v->{ moreSection=idx; render(); });
            LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(0,dp(46),1f);
            lp.setMargins(dp(2),0,dp(2),0);
            selector.addView(b,lp);
        }
        content.addView(selector);
        if(moreSection==1) renderCommerce();
        else if(moreSection==2) renderSystem();
        else renderTesters();
    }

    private void renderTesters() {
        JSONObject t=data.optJSONObject("testers");
        section("Founding Testers");
        metricPair("Aanvragen",String.valueOf(t==null?0:t.optInt("applications",0)),"",
                "In testgroep",String.valueOf(t==null?0:t.optInt("in_test_group",0)),"");
        metricPair("Actief vandaag",String.valueOf(t==null?0:t.optInt("active_today",0)),"",
                "Testsessies",String.valueOf(t==null?0:t.optInt("test_sessions",0)),"");
        metricPair("Testtijd",duration(t==null?0:t.optLong("active_seconds",0)),"totaal",
                "Crashes",String.valueOf(t==null?0:t.optInt("app_crashes",0)),"");
        LinearLayout b=box();
        row(b,"Reserve",String.valueOf(t==null?0:t.optInt("reserve",0)));
        row(b,"Uitgenodigd",String.valueOf(t==null?0:t.optInt("invited",0)));
        row(b,"App gestart",String.valueOf(t==null?0:t.optInt("app_started",0)));
        row(b,"Mid-evaluaties",String.valueOf(t==null?0:t.optInt("mid_evaluations",0)));
        row(b,"Eindevaluaties",String.valueOf(t==null?0:t.optInt("final_evaluations",0)));
        row(b,"Klaar",String.valueOf(t==null?0:t.optInt("completed",0)));
        row(b,"Pro gereserveerd",String.valueOf(t==null?0:t.optInt("reward_reserved",0)));
        row(b,"Aandacht nodig",String.valueOf(t==null?0:t.optInt("attention_needed",0)));
    }

    private void renderCommerce() {
        rangeButtons();
        JSONObject c=data.optJSONObject("commerce_detail");
        JSONObject access=data.optJSONObject("access");
        section("Commerce");
        metricPair("Orders",String.valueOf(c==null?0:c.optInt("orders",0)),rangeDays+" dagen",
                "Omzet",money(c==null?0:c.optDouble("revenue",0),c==null?"EUR":c.optString("currency","EUR")),rangeDays+" dagen");
        metricPair("Mislukt",String.valueOf(c==null?0:c.optInt("failed",0)),"betalingen",
                "Actieve Pro",String.valueOf(access==null?0:access.optInt("active_pro",0)),"");
        LinearLayout b=box();
        statusLine(b,"Mollie",c!=null&&c.optBoolean("mollie_test_mode",true)?"test":"live",c!=null&&c.optBoolean("mollie_test_mode",true)?"Testmodus staat aan":"Live betalingen");
        statusLine(b,"Publieke verkoop",c!=null&&c.optBoolean("public_sales_enabled",false)?"live":"prelaunch",c!=null&&c.optBoolean("public_sales_enabled",false)?"Verkoop is geopend":"Prelaunch · verkoop staat bewust uit");
        row(b,"Actieve trials",String.valueOf(access==null?0:access.optInt("active_trials",0)));
    }

    private void renderSystem() {
        JSONObject s=data.optJSONObject("system");
        section("Systeemgezondheid");
        LinearLayout h=box();
        statusLine(h,"Site Bridge / bot",s==null?"offline":s.optString("site_bridge","offline"),"");
        statusLine(h,"App Bridge",s==null?"offline":s.optString("app_bridge","offline"),"");
        statusLine(h,"Database",s==null?"offline":s.optString("database","offline"),"");
        statusLine(h,"Cache",s==null?"offline":s.optString("cache","offline"),"");
        statusLine(h,"Operations automation",s==null?"offline":s.optString("operations_automation","offline"),"");
        statusLine(h,"Tester automation",s==null?"offline":s.optString("tester_automation","offline"),"");

        section("Versies & modus");
        LinearLayout b=box();
        row(b,"WordPress",s==null?"—":s.optString("wordpress","—"));
        row(b,"PHP",s==null?"—":s.optString("php","—"));
        row(b,"WooCommerce",s==null?"—":s.optString("woocommerce","—"));
        row(b,"Entitlements",s==null?"—":s.optString("entitlement_mode","—"));
        row(b,"Woo prelaunch",s==null?"—":("yes".equalsIgnoreCase(s.optString("woocommerce_coming_soon","yes"))?"aan":"uit"));
        row(b,"Tijdzone",s==null?"—":s.optString("timezone","—"));

        section("Plugins");
        LinearLayout p=box();
        JSONArray plugins=s==null?null:s.optJSONArray("plugins");
        if(plugins==null||plugins.length()==0)row(p,"Geen data","—");
        else for(int i=0;i<plugins.length();i++){
            JSONObject x=plugins.optJSONObject(i);
            if(x!=null)row(p,x.optString("name","Plugin"),"v"+x.optString("version",""));
        }

        Button unpair=button("Koppeling van deze telefoon wissen");
        unpair.setTextColor(RED);
        unpair.setOnClickListener(v -> new AlertDialog.Builder(this)
                .setTitle("Koppeling wissen?")
                .setMessage("De servertoegang blijft actief tot je die in WordPress intrekt. Deze telefoon vergeet wel direct zijn sleutel.")
                .setNegativeButton("Annuleren",null)
                .setPositiveButton("Wissen",(d,w)->{
                    SecureStore.clear(this);
                    showPairing();
                }).show());
        LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,dp(52));
        lp.setMargins(0,dp(8),0,dp(20));
        content.addView(unpair,lp);
    }

    private String rangeLabel() {
        return rangeDays==1 ? "vandaag" : rangeDays+" dagen";
    }

    private String shortDay(String day) {
        if(day==null || day.length()<10) return day==null?"":day;
        return day.substring(8,10)+"-"+day.substring(5,7);
    }

    private String analyticsLabel(String raw, boolean country) {
        if(raw==null || raw.isEmpty()) return "Onbekend";
        if(country && raw.equalsIgnoreCase("ZZ")) return "Onbekend";
        if(raw.equalsIgnoreCase("direct")) return "Direct";
        if(raw.equalsIgnoreCase("desktop")) return "Desktop";
        if(raw.equalsIgnoreCase("mobile")) return "Mobiel";
        if(raw.equals("/")) return "Homepage";
        return raw;
    }

    private String trendText(int current, int previous) {
        if(previous<=0) return current>0 ? "Nieuwe meting · geen vorige periode" : "Geen verandering";
        double pct=((current-previous)*100.0)/previous;
        String sign=pct>0?"+":"";
        return sign+String.format(Locale.US,"%.0f",pct)+"% · vorige periode "+previous;
    }

    private String money(double value,String currency) {
        try {
            NumberFormat f=NumberFormat.getCurrencyInstance(new Locale("nl","NL"));
            if("EUR".equalsIgnoreCase(currency)) return f.format(value);
        } catch(Exception ignored){}
        return String.format(Locale.US,"%.2f %s",value,currency);
    }

    private String duration(long seconds) {
        long h=seconds/3600;
        long m=(seconds%3600)/60;
        if(h>0)return h+"u "+m+"m";
        return m+" min";
    }
}
