package com.nenotv.player.storage;

import android.content.SharedPreferences;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import org.json.JSONArray;

final class LibraryKeyMigration {
    private static final Object LOCK=new Object();
    private static final String VERSION="storage_keys_version";
    private static final String[] PREFIXES={"item:","progress:","duration:","watched:","progress_updated:"};
    private static String identity(String old){return old.matches("media2:[0-9a-f]{64}")?old:StoredMediaKey.of(old);}
    static void run(SharedPreferences prefs){synchronized(LOCK){
        if(prefs.getInt(VERSION,0)>=2)return;
        Map<String,?> before=prefs.getAll();SharedPreferences.Editor editor=prefs.edit();
        try{
            for(Map.Entry<String,?> entry:before.entrySet())for(String prefix:PREFIXES){
                String old=entry.getKey();if(!old.startsWith(prefix))continue;
                String target=prefix+identity(old.substring(prefix.length()));if(target.equals(old))break;
                if(before.containsKey(target))throw new IllegalStateException("LIBRARY_KEY_CONFLICT");
                Object value=entry.getValue();
                if(value instanceof String)editor.putString(target,(String)value);
                else if(value instanceof Long)editor.putLong(target,(Long)value);
                else if(value instanceof Boolean)editor.putBoolean(target,(Boolean)value);
                else throw new IllegalStateException("LIBRARY_VALUE_INVALID");
                editor.remove(old);break;
            }
            if(before.containsKey("favorites")){
                Set<String> favorites=new HashSet<>();for(String old:prefs.getStringSet("favorites",java.util.Collections.emptySet()))favorites.add(identity(old));
                editor.putStringSet("favorites",favorites);
            }
            if(before.containsKey("recent")){
                JSONArray recent=new JSONArray(prefs.getString("recent","[]")),next=new JSONArray();
                for(int n=0;n<recent.length();n++)next.put(identity(recent.getString(n)));
                editor.putString("recent",next.toString());
            }
            if(!editor.putInt(VERSION,2).commit())throw new IllegalStateException("LIBRARY_MIGRATION_WRITE_FAILED");
        }catch(Exception failure){throw new IllegalStateException("LIBRARY_KEY_MIGRATION_FAILED",failure);}
    }}
}

