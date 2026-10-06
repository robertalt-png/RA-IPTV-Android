package com.nenotv.player.storage;

import android.content.Context;
import android.content.SharedPreferences;
import com.nenotv.player.model.MediaEntry;
import com.nenotv.player.provider.PlaybackSourceRoute;
import com.nenotv.player.provider.SourceProviderResolver;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Device-local, deny-by-default approvals. No URLs or credentials are stored here. */
public final class FamilyStore {
    private FamilyStore(){}
    private static SharedPreferences prefs(Context c){return c.getSharedPreferences("sunnyiptv_family_v1",Context.MODE_PRIVATE);}
    public static boolean active(Context c){return prefs(c).getBoolean("active",false);}
    public static long revision(Context c){return prefs(c).getLong("revision",0);}
    private static final Map<String,Map<String,?>> sourceSnapshots=new java.util.HashMap<>();
    private static final Map<String,String> namespaces=new java.util.HashMap<>();
    private static synchronized String scope(Context c,MediaEntry e)throws Exception{
        String id=safe(e.sourceId);boolean pro=new EntitlementStore(c).isPro();
        if(!id.isEmpty()&&!pro)throw new IllegalStateException("Source unavailable");
        String cacheKey=id+":"+pro;
        Map<String,?> snapshot=new java.util.HashMap<>(c.getSharedPreferences(id.isEmpty()?"profile":"nenotv_sources_v1",Context.MODE_PRIVATE).getAll());
        if(snapshot.equals(sourceSnapshots.get(cacheKey))&&namespaces.containsKey(cacheKey))return namespaces.get(cacheKey);
        String namespace=SourceProviderResolver.profileNamespace(PlaybackSourceRoute.resolve(c,e,pro).profile());
        Map<String,?> after=new java.util.HashMap<>(c.getSharedPreferences(id.isEmpty()?"profile":"nenotv_sources_v1",Context.MODE_PRIVATE).getAll());
        if(!snapshot.equals(after))throw new IllegalStateException("Source changed");
        if(namespaces.size()>30){namespaces.clear();sourceSnapshots.clear();}
        sourceSnapshots.put(cacheKey,snapshot);namespaces.put(cacheKey,namespace);return namespace;
    }
    private static String key(Context c,MediaEntry e)throws Exception{
        if(e==null||e.uniqueKey().isEmpty())throw new IllegalArgumentException("Missing media");
        // Include source, title and stream location: reused provider IDs must not inherit approval.
        org.json.JSONArray identity=new org.json.JSONArray();
        identity.put(scope(c,e)).put(e.uniqueKey()).put(e.name).put(e.url).put(e.directSource).put(e.seriesTitle);
        org.json.JSONArray streams=new org.json.JSONArray();if(e.candidates!=null)for(String candidate:e.candidates)streams.put(candidate);identity.put(streams);
        return "allow:"+StoredMediaKey.of(identity.toString());
    }
    public static boolean approved(Context c,MediaEntry e){
        try{if(e==null||"series".equals(e.type))return false;String record=prefs(c).getString(key(c,e),"");if(record.isEmpty())return false;JSONObject row=new JSONObject(record);return safe(e.name).equals(row.optString("name"))&&scope(c,e).equals(row.optString("scope"));}
        catch(Exception unavailable){return false;}
    }
    public static boolean allowed(Context c,MediaEntry e){
        if(e==null)return false;
        if(!active(c))return true;
        if(autoKidsAllowed(c,e))return true;
        if(!"series".equals(e.type))return approved(c,e);
        // A series is only a navigation container; every episode needs its own approval.
        try{
            String namespace=scope(c,e);
            if(e.seriesId==null||e.seriesId.isEmpty())return false;
            for(Map.Entry<String,?> record:prefs(c).getAll().entrySet())if(record.getKey().startsWith("allow:")&&record.getValue() instanceof String){
                JSONObject row=new JSONObject((String)record.getValue());
                if(namespace.equals(row.optString("scope"))&&safe(e.sourceId).equals(row.optString("source"))&&e.seriesId.equals(row.optString("series"))&&safe(e.name).equals(row.optString("seriesTitle")))return true;
            }
        }catch(Exception invalid){return false;}
        return false;
    }
    private static String safe(String x){return x==null?"":x;}
    // ---- Automatic children's categories in child mode (on by default, parent can switch it off with the PIN) ----
    private static final java.util.Set<String> KIDS_WORDS=new java.util.HashSet<>(java.util.Arrays.asList("kids","kid","kinder","kinderen","kinderfilm","kinderfilms","kinderseries","jeugd","junior","children","childrens","cartoon","cartoons","enfants","bambini","infantil","infantiles","niños","ninos","çocuk","cocuk","dzieci"));
    /** True for provider category names such as "NL | Kids" or "Kinderfilms"; never for adult-labelled names. */
    public static boolean isKidsName(String name){
        if(name==null||name.isEmpty()||SettingsStore.isAdultLabel(name))return false;
        for(String w:name.toLowerCase(java.util.Locale.ROOT).split("[^\\p{L}\\p{Nd}]+"))if(KIDS_WORDS.contains(w))return true;
        return false;
    }
    public static boolean autoKids(Context c){return prefs(c).getBoolean("auto_kids",true);}
    public static boolean setAutoKids(Context c,boolean on,String pin){if(!SettingsStore.verifyParentalPin(c,pin))return false;return edit(c).putBoolean("auto_kids",on).commit();}
    /** Remembers which category IDs are children's categories, so the player can check them after a restart. */
    public static void rememberKidsCategories(Context c,java.util.Collection<String> ids){
        if(ids==null||ids.isEmpty())return;java.util.Set<String> all=new java.util.HashSet<>(prefs(c).getStringSet("kids_category_ids",new java.util.HashSet<>()));
        if(all.addAll(ids))prefs(c).edit().putStringSet("kids_category_ids",all).apply();
    }
    private static boolean autoKidsAllowed(Context c,MediaEntry e){
        if(!autoKids(c)||!safe(e.sourceId).isEmpty()||SettingsStore.isAdultLabel(e.name))return false;
        if(isKidsName(e.group))return true;
        String id=safe(e.categoryId);return !id.isEmpty()&&prefs(c).getStringSet("kids_category_ids",java.util.Collections.emptySet()).contains(id);
    }
    private static SharedPreferences.Editor edit(Context c){return prefs(c).edit().putLong("revision",revision(c)+1);}
    public static boolean setActive(Context c,boolean on,String pin){
        if(!SettingsStore.verifyParentalPin(c,pin))return false;
        synchronized(com.nenotv.player.ExtraPrivacySession.class){
            if(!edit(c).putBoolean("active",on).commit())return false;
            com.nenotv.player.ExtraPrivacySession.invalidate();
        }
        SettingsStore.lockAdults();return true;
    }
    public static boolean approve(Context c,MediaEntry e,String pin){
        if(e==null||"series".equals(e.type)||!SettingsStore.verifyParentalPin(c,pin))return false;
        try{
            JSONObject row=new JSONObject().put("name",e.name).put("scope",scope(c,e)).put("source",safe(e.sourceId)).put("series","episode".equals(e.type)?safe(e.seriesId):"").put("seriesTitle",safe(e.seriesTitle));
            return edit(c).putString(key(c,e),row.toString()).commit();
        }catch(Exception unavailable){return false;}
    }
    public static boolean revoke(Context c,String key,String pin){
        return key!=null&&key.startsWith("allow:")&&SettingsStore.verifyParentalPin(c,pin)&&edit(c).remove(key).commit();
    }
    public static String approvalKey(Context c,MediaEntry e){try{return key(c,e);}catch(Exception unavailable){return "";}}
    public static final class Approval {
        public final String key,name;
        Approval(String k,String n){key=k;name=n;}
    }
    public static List<Approval> list(Context c){
        List<Approval> rows=new ArrayList<>();
        for(Map.Entry<String,?> e:prefs(c).getAll().entrySet())if(e.getKey().startsWith("allow:")&&e.getValue() instanceof String){
            try{rows.add(new Approval(e.getKey(),new JSONObject((String)e.getValue()).optString("name")));}catch(Exception ignored){}
        }
        rows.sort((a,b)->a.name.compareToIgnoreCase(b.name));return rows;
    }
}
