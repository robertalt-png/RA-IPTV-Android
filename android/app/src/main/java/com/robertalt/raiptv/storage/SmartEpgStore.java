package com.nenotv.player.storage;

import android.content.Context;
import android.content.SharedPreferences;
import org.json.JSONArray;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Encrypted extra EPG URL registry keyed by NenoTV source id. */
public final class SmartEpgStore {
    private final SharedPreferences prefs;
    private final CryptoBox crypto;
    public SmartEpgStore(Context c){prefs=c.getApplicationContext().getSharedPreferences("nenotv_smart_epg_v1",Context.MODE_PRIVATE);crypto=new CryptoBox();}

    public synchronized List<String> urls(String sourceId){
        if(sourceId==null||sourceId.trim().isEmpty())return Collections.emptyList();
        String raw=crypto.decrypt(prefs.getString("u_"+sourceId,""));
        if(raw.isEmpty())return Collections.emptyList();
        ArrayList<String> out=new ArrayList<>();
        try{
            JSONArray a=new JSONArray(raw);
            for(int i=0;i<a.length();i++){String u=a.optString(i,"").trim();if(valid(u)&&!out.contains(u))out.add(u);}
        }catch(Exception ignored){}
        return out;
    }

    public synchronized void setUrls(String sourceId,List<String> urls){
        if(sourceId==null||sourceId.trim().isEmpty())return;
        JSONArray a=new JSONArray();int n=0;
        if(urls!=null)for(String x:urls){
            String u=x==null?"":x.trim();if(!valid(u))continue;
            boolean dup=false;for(int i=0;i<a.length();i++)if(u.equals(a.optString(i))){dup=true;break;}
            if(!dup){a.put(u);if(++n>=8)break;}
        }
        if(a.length()==0)prefs.edit().remove("u_"+sourceId).commit();
        else prefs.edit().putString("u_"+sourceId,crypto.encrypt(a.toString())).commit();
    }

    public synchronized void setFromJson(String sourceId,JSONArray a){
        ArrayList<String> urls=new ArrayList<>();
        if(a!=null)for(int i=0;i<a.length();i++)urls.add(a.optString(i,""));
        setUrls(sourceId,urls);
    }

    public synchronized JSONArray toJson(String sourceId){
        JSONArray a=new JSONArray();for(String u:urls(sourceId))a.put(u);return a;
    }

    private static boolean valid(String u){return u!=null&&(u.startsWith("https://")||u.startsWith("http://"));}
}
