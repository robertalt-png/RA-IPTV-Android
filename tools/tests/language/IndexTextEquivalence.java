package com.nenotv.player.storage;

import java.util.*;

/** Proves IndexText.metadata/norm equal the former inline regex helpers of SearchIndexStore. */
public final class IndexTextEquivalence {
    private static String safe(String s){return s==null?"":s;}
    private static String legacyMetadata(String s){return safe(s).replaceAll("(?i)(?:https?|rtsp|rtmp)://\\S+","");}
    private static String legacyNorm(String s){return safe(s).toLowerCase(Locale.ROOT).replace('|',' ').replaceAll("\\s+"," ").trim();}
    private static final String[] PARTS={"NL | ","Film ","http://a.b/c?d=e ","HTTPS://X.Y/z ","rtsp://cam:554/s ","RTMP://live/x ","ftp://no ","://x ",
            "\t","\n","  "," | ","Straße ","İSTANBUL ","العربية ","中文 ","4K ","(2024) ","https:// ","http://","   end",""};
    public static void main(String[] args){
        Random r=new Random(7);int checked=0;
        List<String> inputs=new ArrayList<>(Arrays.asList(null,"","   ","http://x","a://b","HTTP://A b"));
        for(int i=0;i<400000;i++){StringBuilder s=new StringBuilder();int n=r.nextInt(8);for(int j=0;j<n;j++)s.append(PARTS[r.nextInt(PARTS.length)]);inputs.add(s.toString());}
        for(String in:inputs){
            String m1=legacyMetadata(in),m2=IndexText.metadata(in);
            String n1=legacyNorm(in),n2=IndexText.norm(in);
            String c1=legacyNorm(legacyMetadata(in)),c2=IndexText.norm(IndexText.metadata(in));
            if(!m1.equals(m2)||!n1.equals(n2)||!c1.equals(c2)){System.err.println("MISMATCH for <"+in+">: "+m1+"|"+m2+" / "+n1+"|"+n2);System.exit(1);}
            checked+=3;
        }
        System.out.println("OK: "+checked+" comparisons, 0 mismatches (IndexText)");
    }
}
