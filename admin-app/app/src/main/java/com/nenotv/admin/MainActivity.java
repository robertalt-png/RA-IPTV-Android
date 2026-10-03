package com.nenotv.admin;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Bundle;
import android.text.InputFilter;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.widget.*;

import org.json.JSONObject;

import java.text.NumberFormat;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class MainActivity extends Activity {
    private final ExecutorService exec = Executors.newSingleThreadExecutor();
    private SecureStore secure;
    private LinearLayout root, content;
    private ProgressBar loading;
    private TextView headerStatus, updated;
    private Spinner range;
    private DashboardData data;
    private int days = 7;
    private final int BG=Color.rgb(7,17,31), TEXT=Color.rgb(247,250,255), MUTED=Color.rgb(145,163,187), GREEN=Color.rgb(41,209,125), ORANGE=Color.rgb(255,181,71), RED=Color.rgb(255,93,102), CYAN=Color.rgb(17,221,232), BLUE=Color.rgb(31,117,255);

    @Override public void onCreate(Bundle b){
        super.onCreate(b);
        getWindow().setStatusBarColor(BG);
        getWindow().setNavigationBarColor(BG);
        secure=new SecureStore(this);
        if(secure.token().isEmpty())showPair();else showApp();
    }

    @Override protected void onDestroy(){
        exec.shutdownNow();
        super.onDestroy();
    }

    private void showPair(){
        ScrollView scroll=new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(BG);

        LinearLayout box=column();
        box.setPadding(dp(28),dp(38),dp(28),dp(28));
        box.setGravity(Gravity.CENTER_HORIZONTAL);

        ImageView logo=new ImageView(this);
        logo.setImageResource(R.drawable.ic_launcher);
        box.addView(logo,new LinearLayout.LayoutParams(dp(116),dp(116)));

        box.addView(title("NenoTV Admin",30));
        TextView intro=text("Eenvoudig beheer van NenoTV",16,MUTED);
        intro.setGravity(Gravity.CENTER);
        intro.setPadding(0,dp(8),0,dp(30));
        box.addView(intro,matchWrap());

        LinearLayout card=card();
        card.setPadding(dp(20),dp(22),dp(20),dp(22));
        card.addView(title("App koppelen",22));

        TextView help=text("Maak in het NenoTV-beheer op de website een koppelcode en voer die hieronder in. Dit hoeft maar één keer.",15,MUTED);
        help.setPadding(0,dp(8),0,dp(18));
        card.addView(help,matchWrap());

        EditText code=new EditText(this);
        code.setTextColor(TEXT);
        code.setHintTextColor(MUTED);
        code.setHint("Koppelcode");
        code.setTextSize(20);
        code.setSingleLine(true);
        code.setGravity(Gravity.CENTER);
        code.setAllCaps(true);
        code.setInputType(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS);
        code.setFilters(new InputFilter[]{new InputFilter.LengthFilter(10)});
        code.setBackgroundResource(R.drawable.button_dark);
        code.setPadding(dp(12),0,dp(12),0);
        card.addView(code,new LinearLayout.LayoutParams(-1,dp(58)));

        Button pair=primaryButton("Koppelen");
        LinearLayout.LayoutParams bp=new LinearLayout.LayoutParams(-1,dp(56));
        bp.topMargin=dp(16);
        card.addView(pair,bp);

        ProgressBar p=new ProgressBar(this);
        p.setVisibility(View.GONE);
        LinearLayout.LayoutParams pp=new LinearLayout.LayoutParams(dp(38),dp(38));
        pp.gravity=Gravity.CENTER;
        pp.topMargin=dp(16);
        card.addView(p,pp);

        TextView error=text("",14,RED);
        error.setGravity(Gravity.CENTER);
        error.setPadding(0,dp(12),0,0);
        card.addView(error,matchWrap());

        box.addView(card,new LinearLayout.LayoutParams(-1,-2));
        scroll.addView(box);
        setContentView(scroll);

        pair.setOnClickListener(v->{
            String c=code.getText().toString().trim().replaceAll("[^A-Za-z0-9]","").toUpperCase(Locale.ROOT);
            if(c.length()!=10){
                error.setText("Voer de 10 tekens van de koppelcode in.");
                return;
            }
            pair.setEnabled(false);
            p.setVisibility(View.VISIBLE);
            error.setText("");
            exec.execute(()->{
                try{
                    String token=ApiClient.pair(c);
                    secure.saveToken(token);
                    runOnUiThread(this::showApp);
                }catch(Exception e){
                    runOnUiThread(()->{
                        pair.setEnabled(true);
                        p.setVisibility(View.GONE);
                        error.setText(friendly(e));
                    });
                }
            });
        });
    }

    private void showApp(){
        root=column();
        root.setBackgroundColor(BG);
        root.setPadding(dp(18),dp(14),dp(18),0);

        LinearLayout top=new LinearLayout(this);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);

        ImageView logo=new ImageView(this);
        logo.setImageResource(R.drawable.ic_launcher);
        top.addView(logo,new LinearLayout.LayoutParams(dp(46),dp(46)));

        LinearLayout names=column();
        TextView name=title("NenoTV Admin",22);
        names.addView(name,matchWrap());
        headerStatus=text("Verbinden…",13,MUTED);
        names.addView(headerStatus,matchWrap());
        LinearLayout.LayoutParams np=new LinearLayout.LayoutParams(0,-2,1f);
        np.leftMargin=dp(12);
        top.addView(names,np);

        Button refresh=darkButton("↻");
        top.addView(refresh,new LinearLayout.LayoutParams(dp(52),dp(44)));
        root.addView(top,new LinearLayout.LayoutParams(-1,-2));

        LinearLayout filter=new LinearLayout(this);
        filter.setOrientation(LinearLayout.HORIZONTAL);
        filter.setGravity(Gravity.CENTER_VERTICAL);
        filter.setPadding(0,dp(18),0,dp(10));

        TextView period=text("Periode",15,MUTED);
        filter.addView(period,new LinearLayout.LayoutParams(0,-2,1f));

        range=new Spinner(this);
        ArrayAdapter<String> adapter=new ArrayAdapter<String>(this,android.R.layout.simple_spinner_dropdown_item,new String[]{"Vandaag","Deze week","Deze maand"}){
            @Override public View getView(int pos,View v,android.view.ViewGroup parent){
                TextView t=(TextView)super.getView(pos,v,parent);
                t.setTextColor(TEXT);
                t.setTextSize(15);
                t.setGravity(Gravity.CENTER);
                t.setBackgroundResource(R.drawable.button_dark);
                t.setPadding(dp(14),0,dp(14),0);
                return t;
            }
        };
        range.setAdapter(adapter);
        range.setSelection(1);
        filter.addView(range,new LinearLayout.LayoutParams(dp(154),dp(48)));
        root.addView(filter,new LinearLayout.LayoutParams(-1,-2));

        loading=new ProgressBar(this);
        loading.setVisibility(View.GONE);
        LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(dp(34),dp(34));
        lp.gravity=Gravity.CENTER;
        root.addView(loading,lp);

        updated=text("",12,MUTED);
        updated.setGravity(Gravity.CENTER);
        updated.setPadding(0,dp(4),0,dp(8));
        root.addView(updated,matchWrap());

        ScrollView scroll=new ScrollView(this);
        content=column();
        content.setPadding(0,0,0,dp(90));
        scroll.addView(content);
        root.addView(scroll,new LinearLayout.LayoutParams(-1,0,1f));

        LinearLayout nav=new LinearLayout(this);
        nav.setOrientation(LinearLayout.HORIZONTAL);
        nav.setGravity(Gravity.CENTER);
        String[] tabs={"Overzicht","Klanten","Bestellingen","Meer"};
        for(String tab:tabs){
            Button b=darkButton(tab);
            LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(0,dp(56),1f);
            p.setMargins(dp(3),dp(4),dp(3),dp(8));
            nav.addView(b,p);
            b.setOnClickListener(v->showTab(tab));
        }
        root.addView(nav,new LinearLayout.LayoutParams(-1,-2));
        setContentView(root);

        refresh.setOnClickListener(v->load());
        range.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener(){
            public void onNothingSelected(android.widget.AdapterView<?> p){}
            public void onItemSelected(android.widget.AdapterView<?> p,View v,int pos,long id){
                days=pos==0?1:pos==1?7:30;
                load();
            }
        });
    }

    private void load(){
        String token=secure.token();
        if(token.isEmpty()){
            showPair();
            return;
        }
        loading.setVisibility(View.VISIBLE);
        headerStatus.setText("Bijwerken…");
        exec.execute(()->{
            try{
                JSONObject json=ApiClient.dashboard(token,days);
                DashboardData d=DashboardData.parse(json);
                runOnUiThread(()->{
                    data=d;
                    loading.setVisibility(View.GONE);
                    headerStatus.setText(d.healthy()?"● Alles werkt goed":"● Aandacht nodig");
                    headerStatus.setTextColor(d.healthy()?GREEN:ORANGE);
                    updated.setText("Bijgewerkt: "+timeLabel(d.updatedAt));
                    showTab("Overzicht");
                });
            }catch(ApiClient.ApiException e){
                runOnUiThread(()->{
                    loading.setVisibility(View.GONE);
                    if(e.status==401||e.status==403){
                        secure.clear();
                        Toast.makeText(this,"Koppeling verlopen. Koppel de app opnieuw.",Toast.LENGTH_LONG).show();
                        showPair();
                    }else{
                        headerStatus.setText("Kan gegevens niet laden");
                        headerStatus.setTextColor(RED);
                        Toast.makeText(this,friendly(e),Toast.LENGTH_LONG).show();
                    }
                });
            }catch(Exception e){
                runOnUiThread(()->{
                    loading.setVisibility(View.GONE);
                    headerStatus.setText("Geen verbinding");
                    headerStatus.setTextColor(RED);
                    Toast.makeText(this,friendly(e),Toast.LENGTH_LONG).show();
                });
            }
        });
    }

    private void showTab(String tab){
        if(content==null)return;
        content.removeAllViews();
        if(data==null){
            content.addView(text("Gegevens worden geladen…",16,MUTED),matchWrap());
            return;
        }
        switch(tab){
            case "Klanten": showCustomers(); break;
            case "Bestellingen": showOrders(); break;
            case "Meer": showMore(); break;
            default: showOverview();
        }
    }

    private void showOverview(){
        TextView heading=title(data.healthy()?"Alles werkt goed":"Er is iets om te bekijken",25);
        heading.setTextColor(data.healthy()?TEXT:ORANGE);
        content.addView(heading,matchWrap());

        TextView sub=text(periodText(),14,MUTED);
        sub.setPadding(0,dp(4),0,dp(14));
        content.addView(sub,matchWrap());

        LinearLayout row1=row();
        row1.addView(metric("Omzet",money(data.revenue),data.failedOrders>0?"Betalingen controleren":"Betaald",data.failedOrders>0?ORANGE:GREEN),half());
        row1.addView(metric("Bestellingen",String.valueOf(data.orders),data.failedOrders+" mislukt",data.failedOrders>0?ORANGE:MUTED),half());
        content.addView(row1,matchWrap());

        LinearLayout row2=row();
        row2.addView(metric("Bezoekers",String.valueOf(data.visitors),data.pageviews+" paginaweergaven",CYAN),half());
        row2.addView(metric("Pro actief",String.valueOf(data.activePro),data.activeTrials+" proefperiodes",BLUE),half());
        content.addView(row2,matchWrap());

        LinearLayout status=card();
        status.setPadding(dp(18),dp(16),dp(18),dp(14));
        status.addView(title("Status",19));
        status.addView(statusRow("Website","online".equals(data.siteStatus)));
        status.addView(statusRow("Betalingen",data.failedOrders==0));
        status.addView(statusRow("Licenties",true));
        status.addView(statusRow("NenoTV-app","online".equals(data.appBridge)));
        status.addView(statusRow("Database","online".equals(data.database)));
        LinearLayout.LayoutParams sp=matchWrap();
        sp.topMargin=dp(10);
        content.addView(status,sp);

        LinearLayout chartCard=card();
        chartCard.setPadding(dp(18),dp(16),dp(18),dp(14));
        chartCard.addView(title("Websitebezoek",19));
        TextView ct=text("Paginaweergaven in de gekozen periode",13,MUTED);
        ct.setPadding(0,dp(2),0,dp(8));
        chartCard.addView(ct,matchWrap());
        TrendView trend=new TrendView(this);
        trend.setPoints(data.daily);
        chartCard.addView(trend,new LinearLayout.LayoutParams(-1,dp(220)));
        LinearLayout.LayoutParams cp=matchWrap();
        cp.topMargin=dp(10);
        content.addView(chartCard,cp);
    }

    private void showCustomers(){
        content.addView(title("Klanten",25),matchWrap());
        TextView sub=text(periodText(),14,MUTED);
        sub.setPadding(0,dp(4),0,dp(14));
        content.addView(sub,matchWrap());

        content.addView(metric("Actieve Pro-klanten",String.valueOf(data.activePro),"Betaalde Pro-toegang",GREEN),matchWrap());

        LinearLayout.LayoutParams m=matchWrap();
        m.topMargin=dp(10);
        content.addView(metric("Actieve proefperiodes",String.valueOf(data.activeTrials),data.trialEnabled?"Proefperiode staat aan":"Proefperiode staat uit",data.trialEnabled?CYAN:MUTED),m);

        TextView note=text("Klantacties blijven voor nu bewust alleen-lezen in deze Admin-app.",14,MUTED);
        note.setPadding(0,dp(18),0,0);
        content.addView(note,matchWrap());
    }

    private void showOrders(){
        content.addView(title("Bestellingen",25),matchWrap());
        TextView sub=text(periodText(),14,MUTED);
        sub.setPadding(0,dp(4),0,dp(14));
        content.addView(sub,matchWrap());

        content.addView(metric("Omzet",money(data.revenue),data.orders+" betaalde bestellingen",GREEN),matchWrap());

        LinearLayout.LayoutParams m=matchWrap();
        m.topMargin=dp(10);
        content.addView(metric("Mislukt",String.valueOf(data.failedOrders),data.failedOrders==0?"Geen problemen":"Controle nodig",data.failedOrders==0?GREEN:ORANGE),m);

        Button open=primaryButton("Bestellingen openen");
        LinearLayout.LayoutParams op=new LinearLayout.LayoutParams(-1,dp(56));
        op.topMargin=dp(16);
        content.addView(open,op);
        open.setOnClickListener(v->startActivity(new Intent(Intent.ACTION_VIEW,Uri.parse(data.ordersUrl))));
    }

    private void showMore(){
        content.addView(title("Meer",25),matchWrap());

        LinearLayout app=card();
        app.setPadding(dp(18),dp(16),dp(18),dp(14));
        app.addView(title("NenoTV-app",19));
        app.addView(line("Test-/bekende versie",blank(data.appVersion)));
        app.addView(line("Laatste versie gezien",blank(data.lastSeenVersion)));
        app.addView(line("Apparaten",String.valueOf(data.appDevices)));
        app.addView(line("Actief 24 uur",String.valueOf(data.active24h)));
        LinearLayout.LayoutParams ap=matchWrap();
        ap.topMargin=dp(14);
        content.addView(app,ap);

        Button advanced=darkButton("Geavanceerd tonen");
        LinearLayout.LayoutParams adp=new LinearLayout.LayoutParams(-1,dp(52));
        adp.topMargin=dp(12);
        content.addView(advanced,adp);

        LinearLayout details=card();
        details.setVisibility(View.GONE);
        details.setPadding(dp(18),dp(16),dp(18),dp(14));
        details.addView(line("Licentiemodus",data.entitlementMode));
        details.addView(line("Mollie",data.mollieTestMode?"Testmodus":"Live"));
        details.addView(line("Publieke verkoop",data.publicSales?"Aan":"Uit"));
        details.addView(line("App-fouten",String.valueOf(data.appErrors)));
        details.addView(line("Gem. reactietijd",data.avgLatencyMs+" ms"));
        details.addView(line("Cache",data.cache));
        LinearLayout.LayoutParams detailsParams=matchWrap();
        detailsParams.topMargin=dp(8);
        content.addView(details,detailsParams);

        advanced.setOnClickListener(v->{
            boolean open=details.getVisibility()!=View.VISIBLE;
            details.setVisibility(open?View.VISIBLE:View.GONE);
            advanced.setText(open?"Geavanceerd verbergen":"Geavanceerd tonen");
        });

        Button disconnect=darkButton("App ontkoppelen");
        LinearLayout.LayoutParams dc=new LinearLayout.LayoutParams(-1,dp(52));
        dc.topMargin=dp(22);
        content.addView(disconnect,dc);
        disconnect.setOnClickListener(v->{
            secure.clear();
            showPair();
        });
    }

    private LinearLayout metric(String label,String value,String note,int accent){
        LinearLayout c=card();
        c.setPadding(dp(16),dp(15),dp(16),dp(15));
        TextView l=text(label,13,MUTED);
        c.addView(l,matchWrap());
        TextView v=text(value,27,TEXT);
        v.setTypeface(Typeface.DEFAULT_BOLD);
        v.setPadding(0,dp(4),0,dp(4));
        c.addView(v,matchWrap());
        TextView n=text(note,12,accent);
        c.addView(n,matchWrap());
        return c;
    }

    private TextView statusRow(String label,boolean ok){
        TextView t=text("●  "+label,15,ok?GREEN:ORANGE);
        t.setPadding(0,dp(9),0,0);
        return t;
    }

    private LinearLayout line(String l,String r){
        LinearLayout x=row();
        TextView a=text(l,14,MUTED);
        TextView b=text(r,14,TEXT);
        b.setGravity(Gravity.END);
        x.addView(a,new LinearLayout.LayoutParams(0,-2,1f));
        x.addView(b,new LinearLayout.LayoutParams(0,-2,1f));
        x.setPadding(0,dp(8),0,0);
        return x;
    }

    private String periodText(){
        return days==1?"Vandaag":days==7?"Deze week":"Deze maand";
    }

    private String money(double v){
        NumberFormat f=NumberFormat.getCurrencyInstance(new Locale("nl","NL"));
        try{
            f.setCurrency(java.util.Currency.getInstance(data==null?"EUR":data.currency));
        }catch(Exception ignored){}
        return f.format(v);
    }

    private String blank(String s){
        return s==null||s.trim().isEmpty()?"—":s;
    }

    private String timeLabel(String s){
        if(s==null||s.isEmpty())return "zojuist";
        return s.replace('T',' ').replaceAll("\\+.*$","").replaceAll("Z$","");
    }

    private String friendly(Exception e){
        String m=e.getMessage();
        if(m==null||m.trim().isEmpty())return "Er ging iets mis.";
        if(m.contains("invalid_or_expired_code"))return "De koppelcode is onjuist of verlopen.";
        return m;
    }

    private LinearLayout column(){
        LinearLayout l=new LinearLayout(this);
        l.setOrientation(LinearLayout.VERTICAL);
        return l;
    }

    private LinearLayout row(){
        LinearLayout l=new LinearLayout(this);
        l.setOrientation(LinearLayout.HORIZONTAL);
        return l;
    }

    private LinearLayout card(){
        LinearLayout l=column();
        l.setBackgroundResource(R.drawable.card);
        return l;
    }

    private TextView title(String s,int size){
        TextView t=text(s,size,TEXT);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        return t;
    }

    private TextView text(String s,int size,int color){
        TextView t=new TextView(this);
        t.setText(s);
        t.setTextColor(color);
        t.setTextSize(size);
        return t;
    }

    private Button primaryButton(String s){
        Button b=new Button(this);
        b.setText(s);
        b.setTextColor(Color.WHITE);
        b.setTextSize(15);
        b.setAllCaps(false);
        b.setTypeface(Typeface.DEFAULT_BOLD);
        b.setBackgroundResource(R.drawable.button_primary);
        return b;
    }

    private Button darkButton(String s){
        Button b=new Button(this);
        b.setText(s);
        b.setTextColor(TEXT);
        b.setTextSize(13);
        b.setAllCaps(false);
        b.setBackgroundResource(R.drawable.button_dark);
        return b;
    }

    private LinearLayout.LayoutParams matchWrap(){
        return new LinearLayout.LayoutParams(-1,-2);
    }

    private LinearLayout.LayoutParams half(){
        LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(0,-2,1f);
        p.setMargins(dp(4),dp(4),dp(4),dp(4));
        return p;
    }

    private int dp(int v){
        return (int)(v*getResources().getDisplayMetrics().density+.5f);
    }
}
