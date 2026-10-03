package com.nenotv.player.core;
public final class LanguageDefaults {
    private LanguageDefaults(){}
    public static final String[] AUDIO={"en","eng"};
    public static final String[] SUBTITLE={"nl","nld","dut"};
    public static boolean isDutch(String s){ if(s==null)return false; s=s.toLowerCase(); return s.equals("nl")||s.equals("nld")||s.equals("dut")||s.contains("dutch")||s.contains("nederlands"); }
    public static boolean isEnglish(String s){ if(s==null)return false; s=s.toLowerCase(); return s.equals("en")||s.equals("eng")||s.contains("english"); }
}
