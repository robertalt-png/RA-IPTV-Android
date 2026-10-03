package com.nenotv.player;

import android.content.Context;
import android.content.SharedPreferences;
import com.nenotv.player.storage.*;
import com.nenotv.player.model.MediaEntry;
import java.util.*;

final class HouseholdProfileChecks {
    static void check(boolean value,String message){if(!value)throw new AssertionError(message);}
    static void restore(SharedPreferences prefs,Map<String,?> values){
        SharedPreferences.Editor editor=prefs.edit().clear();
        for(Map.Entry<String,?> entry:values.entrySet()){
            Object value=entry.getValue();String key=entry.getKey();
            if(value instanceof String)editor.putString(key,(String)value);
            else if(value instanceof Long)editor.putLong(key,(Long)value);
            else if(value instanceof Integer)editor.putInt(key,(Integer)value);
            else if(value instanceof Boolean)editor.putBoolean(key,(Boolean)value);
            else if(value instanceof Set)editor.putStringSet(key,new HashSet<>((Set<String>)value));
        }
        editor.commit();
    }
    static void run(Context context)throws Exception{
        SharedPreferences profiles=context.getSharedPreferences("nenotv_viewers",Context.MODE_PRIVATE);
        SharedPreferences legacy=context.getSharedPreferences("library",Context.MODE_PRIVATE);
        SharedPreferences settings=SettingsStore.prefs(context);
        Map<String,?> oldProfiles=new HashMap<>(profiles.getAll()),oldLibrary=new HashMap<>(legacy.getAll()),oldSettings=new HashMap<>(settings.getAll());
        List<String> created=new ArrayList<>();
        try{
            profiles.edit().clear().commit();
            HouseholdProfileStore viewers=new HouseholdProfileStore(context);
            check(viewers.list().size()==1&&viewers.activeId().equals("default"),"Default household profile missing");
            LibraryStore original=new LibraryStore(context);
            MediaEntry movie=new MediaEntry();movie.id="qa-household-movie";movie.type="movie";movie.name="QA";
            if(!original.isFavorite(movie))original.toggleFavorite(movie);
            original.saveProgress(movie,40000,100000,true);original.recent(movie);
            SettingsStore.setPrimaryLanguage(context,"nl");settings.edit().putString("sort","favorites").putString("qa_import_cursor","keep-device-state").commit();
            String second=viewers.add("Second viewer");created.add(second);viewers.select(second);
            LibraryStore separate=new LibraryStore(context);
            check(!separate.isFavorite(movie)&&separate.progress(movie)==0&&separate.recent().isEmpty(),"Viewer inherited another viewer's library");
            separate.saveProgress(movie,60000,100000,true);separate.toggleFavorite(movie);
            SettingsStore.setPrimaryLanguage(context,"de");settings.edit().putString("sort","recent").commit();
            check(original.progress(movie)==40000&&original.isFavorite(movie),"Active player changed its library ownership");
            check(viewers.rename(second,"Renamed viewer")&&new LibraryStore(context).progress(movie)==60000,"Rename lost progress or changed identity");
            viewers.select("default");check(new LibraryStore(context).progress(movie)==40000,"Default profile migration lost existing library");
            check(SettingsStore.language(context).equals("nl")&&SettingsStore.sort(context).equals("favorites"),"Viewer settings not restored");
            check(settings.getString("qa_import_cursor","").equals("keep-device-state"),"Profile switch changed import/device state");
            viewers.select(second);check(SettingsStore.language(context).equals("de")&&SettingsStore.sort(context).equals("recent"),"Second viewer settings not isolated");viewers.select("default");
            check(!viewers.remove("default")&&!viewers.select("invalid-id"),"Default profile deleted or arbitrary ID selected");
            for(String name:new String[]{""," ","12345678901234567890123456789012345678901","bad\nname","Renamed viewer"}){
                boolean rejected=false;try{created.add(viewers.add(name));}catch(IllegalArgumentException expected){rejected=true;}
                check(rejected,"Invalid or duplicate viewer name accepted");
            }
            while(viewers.list().size()<HouseholdProfileStore.LIMIT)created.add(viewers.add("Viewer "+viewers.list().size()));
            boolean limited=false;try{viewers.add("One too many");}catch(IllegalStateException expected){limited=true;}
            check(limited,"Household profile limit ignored");
            viewers.select(second);check(viewers.remove(second)&&viewers.activeId().equals("default"),"Deleting active viewer did not return to default");
            check(context.getSharedPreferences(HouseholdProfileStore.libraryName(second),Context.MODE_PRIVATE).getAll().isEmpty(),"Deleted viewer's encrypted library remained");
            profiles.edit().putString("profiles","not-json").commit();
            boolean failed=false;try{viewers.add("Do not overwrite");}catch(IllegalStateException expected){failed=true;}
            check(failed&&"not-json".equals(profiles.getString("profiles","")),"Corrupt profile registry overwritten");
        }finally{
            for(String id:created)context.deleteSharedPreferences(HouseholdProfileStore.libraryName(id));
            restore(profiles,oldProfiles);restore(legacy,oldLibrary);restore(settings,oldSettings);
        }
    }
}
