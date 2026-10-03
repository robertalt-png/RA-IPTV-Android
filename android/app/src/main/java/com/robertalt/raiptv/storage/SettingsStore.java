package com.nenotv.player.storage;
import android.content.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

public final class SettingsStore {
    private static volatile boolean adultUnlocked=false;
    private SettingsStore(){}
    public static SharedPreferences prefs(Context c){return c.getSharedPreferences("nenotv_settings",Context.MODE_PRIVATE);}
    public static boolean hasLanguageProfile(Context c){return prefs(c).getBoolean("language_profile_configured",false)&&supportedLanguage(prefs(c).getString("primary_language",""));}
    public static String languageSetting(Context c){if(hasLanguageProfile(c))return prefs(c).getString("primary_language","en");return prefs(c).getString("language","auto");}
    public static String language(Context c){String x=languageSetting(c);if(!"auto".equals(x)&&supportedLanguage(x))return x;String d=Locale.getDefault().getLanguage();return supportedLanguage(d)?d:"en";}
    public static String primaryLanguage(Context c){return hasLanguageProfile(c)?prefs(c).getString("primary_language",language(c)):language(c);}
    public static void setPrimaryLanguage(Context c,String code){String x=supportedLanguage(code)?code.toLowerCase(Locale.ROOT):"en";prefs(c).edit().putBoolean("language_profile_configured",true).putInt("language_profile_version",1).putString("primary_language",x).putString("language",x).putString("content_language","auto").putString("audio","auto").putString("subtitles","auto").apply();}
    public static Locale appLocale(Context c){try{return Locale.forLanguageTag(primaryLanguage(c));}catch(Exception e){return Locale.ENGLISH;}}
    public static String contentLanguageSetting(Context c){return hasLanguageProfile(c)?"auto":prefs(c).getString("content_language","auto");}
    public static String contentLanguage(Context c){if(hasLanguageProfile(c))return primaryLanguage(c);String x=contentLanguageSetting(c);return "auto".equals(x)?language(c):(supportedLanguage(x)?x:language(c));}
    public static void migrateLanguagePreferences(Context c){SharedPreferences p=prefs(c);if(p.getBoolean("language_pref_migrated_063",false))return;String a=p.getString("audio","auto"),s=p.getString("subtitles","auto");SharedPreferences.Editor e=p.edit().putBoolean("language_pref_migrated_063",true);if("en".equals(a)&&"nl".equals(s)){e.putString("audio","auto");e.putString("subtitles","auto");}e.apply();}
    public static String resolvedSubtitleLanguage(Context c){String x=subtitles(c);if("auto".equals(x))x=primaryLanguage(c);return supportedLanguage(x)?x:primaryLanguage(c);}
    public static boolean supportedLanguage(String x){return Arrays.asList("nl","en","de","fr","es","it","pt","tr","pl","ar").contains(x);}
    public static String[] languageCodes(String x){if(x==null)return new String[0];switch(x){case "nl":return new String[]{"nl","nld","dut"};case "en":return new String[]{"en","eng"};case "de":return new String[]{"de","deu","ger"};case "fr":return new String[]{"fr","fra","fre"};case "es":return new String[]{"es","spa"};case "it":return new String[]{"it","ita"};case "pt":return new String[]{"pt","por"};case "tr":return new String[]{"tr","tur"};case "pl":return new String[]{"pl","pol"};case "ar":return new String[]{"ar","ara"};default:return new String[0];}}
    public static String[] audioLanguageCodes(Context c){String a=audio(c);if("original".equals(a))return new String[0];if("auto".equals(a))a=primaryLanguage(c);return languageCodes(a);}
    public static String[] subtitleLanguageCodes(Context c){String x=subtitles(c);if("off".equals(x))return new String[0];if("auto".equals(x))x=primaryLanguage(c);return languageCodes(x);}
    public static String csv(String[] a){return a==null||a.length==0?"":android.text.TextUtils.join(",",a);}
    public static String displayLanguage(Context c,String code){if(code==null||code.trim().isEmpty()||"und".equalsIgnoreCase(code))return "";try{Locale l=Locale.forLanguageTag(code);String n=l.getDisplayLanguage(appLocale(c));return n==null||n.trim().isEmpty()?code.toUpperCase(Locale.ROOT):n.substring(0,1).toUpperCase(appLocale(c))+n.substring(1);}catch(Exception e){return code.toUpperCase(Locale.ROOT);}}
    public static boolean compact(Context c){return prefs(c).getBoolean("compact",true);}
    public static String hero(Context c){return prefs(c).getString("hero_size","normal");}
    public static String player(Context c){return prefs(c).getString("player","auto");}
    public static String audio(Context c){return prefs(c).getString("audio","auto");}
    public static String subtitles(Context c){return prefs(c).getString("subtitles","auto");}
    public static String buffer(Context c){return prefs(c).getString("buffer","stable");}
    public static boolean pip(Context c){return prefs(c).getBoolean("pip",true);}
    public static boolean autoplay(Context c){return prefs(c).getBoolean("autoplay_next",true);}
    public static int bufferMs(Context c){String b=buffer(c);return "max".equals(b)?5000:"normal".equals(b)?1200:2500;}
    public static String sort(Context c){return prefs(c).getString("sort","provider");}
    public static String startScreen(Context c){return prefs(c).getString("start_screen","home");}
    public static String lastSection(Context c){return prefs(c).getString("last_section","home");}
    public static void setLastSection(Context c,String s){if(s!=null&&!s.isEmpty())prefs(c).edit().putString("last_section",s).apply();}
    public static boolean parental(Context c){return prefs(c).getBoolean("parental_enabled",false);}
    public static boolean hasParentalPin(Context c){return !prefs(c).getString("parental_pin_hash","").isEmpty();}
    public static void setParentalEnabled(Context c,boolean enabled){prefs(c).edit().putBoolean("parental_enabled",enabled).apply();if(!enabled)adultUnlocked=false;}
    public static void setParentalPin(Context c,String pin){prefs(c).edit().putString("parental_pin_hash",hash(pin)).apply();adultUnlocked=false;}
    public static boolean unlockAdults(Context c,String pin){if(!parental(c)){adultUnlocked=true;return true;}boolean ok=hash(pin).equals(prefs(c).getString("parental_pin_hash",""));if(ok)adultUnlocked=true;return ok;}
    public static void lockAdults(){adultUnlocked=false;}
    public static boolean adultsAllowed(Context c){return !parental(c)||adultUnlocked;}
    public static boolean isAdultLabel(String s){if(s==null)return false;String n=(" "+s.toLowerCase(java.util.Locale.ROOT).replaceAll("[^a-z0-9+]+"," ")+" ");return n.contains(" adult ")||n.contains(" adults ")||n.contains(" xxx ")||n.contains(" 18+ ")||n.contains(" erot")||n.contains(" porn")||n.contains(" sex ")||n.contains(" volwassenen ");}
    private static String hash(String s){try{MessageDigest md=MessageDigest.getInstance("SHA-256");byte[] b=md.digest(("nenotv-parental-v1|"+(s==null?"":s)).getBytes(StandardCharsets.UTF_8));StringBuilder out=new StringBuilder();for(byte x:b)out.append(String.format(java.util.Locale.ROOT,"%02x",x));return out.toString();}catch(Exception e){return Integer.toHexString((s==null?"":s).hashCode());}}
}
