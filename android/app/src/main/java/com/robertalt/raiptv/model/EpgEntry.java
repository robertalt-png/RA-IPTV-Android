package com.nenotv.player.model;

import java.io.Serializable;
import java.time.*;
import java.time.format.DateTimeFormatter;

public class EpgEntry implements Serializable {
    public String title="", description="", startRaw="", endRaw="";
    public long startEpoch=0, endEpoch=0;
    public boolean isNow(){ long n=System.currentTimeMillis()/1000L; return startEpoch>0&&endEpoch>0&&startEpoch<=n&&n<endEpoch; }
    public String range(){
        if(startEpoch>0){
            ZoneId z=ZoneId.systemDefault();
            String a=Instant.ofEpochSecond(startEpoch).atZone(z).toLocalTime().format(DateTimeFormatter.ofPattern("HH:mm"));
            if(endEpoch>0){String b=Instant.ofEpochSecond(endEpoch).atZone(z).toLocalTime().format(DateTimeFormatter.ofPattern("HH:mm"));return a+"–"+b;}
            return a;
        }
        String a=timeFromRaw(startRaw), b=timeFromRaw(endRaw);return b.isEmpty()?a:(a+"–"+b);
    }
    private String timeFromRaw(String s){
        if(s==null)return ""; String x=s.trim();
        if(x.length()>=16&&x.charAt(10)==' ')return x.substring(11,16);
        if(x.length()>=12&&x.matches("^\\d{12,}.*"))return x.substring(8,10)+":"+x.substring(10,12);
        return "";
    }
}
