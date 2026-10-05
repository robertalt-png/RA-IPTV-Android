package com.nenotv.player.proextras;

import android.app.*;
import android.content.*;
import android.net.*;
import android.os.Bundle;
import android.graphics.Typeface;
import android.view.*;
import android.widget.*;
import com.nenotv.player.storage.*;
import com.nenotv.player.model.Profile;
import java.net.*;
import java.util.concurrent.*;

public class ProNetworkActivity extends Activity {
    LinearLayout box; TextView vpnState,latencyState; ExecutorService exec=Executors.newSingleThreadExecutor();
    int dp(int v){return Math.round(v*getResources().getDisplayMetrics().density);}
    String L(String en,String nl,String de){String l=SettingsStore.language(this);return "nl".equals(l)?nl:"de".equals(l)?de:en;}
    TextView t(String s,int z){TextView v=new TextView(this);v.setText(s);v.setTextColor(0xFFF7F8FA);v.setTextSize(z);return v;}
    Button b(String s){Button v=new Button(this);v.setText(s);v.setAllCaps(false);v.setTextColor(0xFFF7F8FA);v.setBackgroundTintList(android.content.res.ColorStateList.valueOf(0xFF1B2028));return v;}

    @Override public void onCreate(Bundle x){
        super.onCreate(x);
        if(!new EntitlementStore(this).isPro()){finish();return;}
        ScrollView sv=new ScrollView(this);sv.setBackgroundColor(0xFF07090D);
        box=new LinearLayout(this);box.setOrientation(LinearLayout.VERTICAL);box.setPadding(dp(18),dp(18),dp(18),dp(34));sv.addView(box);
        LinearLayout h=new LinearLayout(this);h.setGravity(Gravity.CENTER_VERTICAL);
        TextView title=t(L("Network & Privacy","Netwerk & Privacy","Netzwerk & Datenschutz"),24);title.setTypeface(null,Typeface.BOLD);h.addView(title,new LinearLayout.LayoutParams(0,-2,1));
        Button close=b(L("Close","Sluiten","Schließen"));close.setOnClickListener(v->finish());h.addView(close);box.addView(h);
        TextView help=t(L("Check whether Android routes SunnyIPTV through a VPN and measure connection latency. Credentials are never included in this test.",
                "Controleer of Android SunnyIPTV via een VPN routeert en meet de verbindingslatentie. Inloggegevens worden nooit in deze test opgenomen.",
                "Prüfe, ob Android SunnyIPTV über ein VPN leitet, und miss die Verbindungslatenz. Zugangsdaten werden nie in diesen Test aufgenommen."),13);
        help.setTextColor(0xFFA7AFBC);help.setPadding(0,dp(6),0,dp(18));box.addView(help);
        vpnState=t("",18);vpnState.setPadding(dp(14),dp(14),dp(14),dp(14));vpnState.setBackgroundColor(0xFF151A21);box.addView(vpnState,new LinearLayout.LayoutParams(-1,-2));
        latencyState=t("",14);latencyState.setPadding(dp(14),dp(14),dp(14),dp(14));box.addView(latencyState,new LinearLayout.LayoutParams(-1,-2));
        Button test=b(L("Run connection test","Verbinding testen","Verbindung testen"));test.setOnClickListener(v->runTest(test));box.addView(test,new LinearLayout.LayoutParams(-1,dp(52)));
        Button settings=b(L("Open Android VPN settings","Open Android VPN-instellingen","Android-VPN-Einstellungen öffnen"));settings.setOnClickListener(v->openVpn());box.addView(settings,new LinearLayout.LayoutParams(-1,dp(52)));
        setContentView(sv);refreshVpn();
    }

    void refreshVpn(){
        boolean vpn=false;
        try{
            ConnectivityManager cm=(ConnectivityManager)getSystemService(CONNECTIVITY_SERVICE);
            Network n=cm.getActiveNetwork();NetworkCapabilities caps=n==null?null:cm.getNetworkCapabilities(n);
            vpn=caps!=null&&caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN);
        }catch(Exception ignored){}
        vpnState.setText(vpn?L("VPN detected · active route","VPN gedetecteerd · actieve route","VPN erkannt · aktive Route"):
                L("No active VPN route detected","Geen actieve VPN-route gedetecteerd","Keine aktive VPN-Route erkannt"));
    }

    void runTest(Button button){
        button.setEnabled(false);latencyState.setText(L("Testing…","Testen…","Test läuft…"));
        exec.execute(()->{
            StringBuilder out=new StringBuilder();
            out.append(testUrl("https://sunnyiptv.com","SunnyIPTV"));
            try{
                Profile p=new SecureProfileStore(this).load();
                String raw=p.type==Profile.Type.XTREAM?p.server:p.m3uUrl;
                if(raw!=null&&!raw.trim().isEmpty()){
                    URL u=new URL(raw);String host=u.getProtocol()+"://"+u.getHost()+(u.getPort()>0?":"+u.getPort():"");
                    if(!u.getHost().isEmpty())out.append("\n").append(testUrl(host,L("TV provider","TV-aanbieder","TV-Anbieter")));
                }
            }catch(Exception ignored){}
            runOnUiThread(()->{latencyState.setText(out.toString());button.setEnabled(true);refreshVpn();});
        });
    }

    String testUrl(String url,String label){
        long start=System.nanoTime();
        try{
            HttpURLConnection c=(HttpURLConnection)new URL(url).openConnection();c.setConnectTimeout(5000);c.setReadTimeout(5000);c.setInstanceFollowRedirects(false);c.setRequestMethod("HEAD");c.setRequestProperty("User-Agent","SunnyIPTV-Network-Test");
            int code=c.getResponseCode();long ms=TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-start);c.disconnect();
            return label+": "+ms+" ms · HTTP "+code;
        }catch(Exception e){return label+": "+L("unreachable","onbereikbaar","nicht erreichbar");}
    }

    void openVpn(){
        try{startActivity(new Intent("android.settings.VPN_SETTINGS"));}catch(Exception e){try{startActivity(new Intent(android.provider.Settings.ACTION_WIRELESS_SETTINGS));}catch(Exception ignored){}}
    }

    @Override protected void onDestroy(){exec.shutdownNow();super.onDestroy();}
}
