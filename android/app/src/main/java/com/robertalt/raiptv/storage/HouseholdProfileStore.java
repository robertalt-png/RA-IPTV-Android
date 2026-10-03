package com.nenotv.player.storage;

import android.content.Context;
import android.content.SharedPreferences;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Viewer identities are separate from provider accounts and never duplicate their imports. */
public final class HouseholdProfileStore {
    public static final String DEFAULT_ID="default";
    public static final int LIMIT=8;
    private static final Object LOCK=new Object();
    private final Context app;
    private final SharedPreferences prefs;
    public static final class Viewer {
        public final String id,name;
        Viewer(String id,String name){this.id=id;this.name=name;}
    }
    public HouseholdProfileStore(Context context){
        app=context.getApplicationContext();prefs=app.getSharedPreferences("nenotv_viewers",Context.MODE_PRIVATE);
    }
    public List<Viewer> list(){synchronized(LOCK){return read();}}
    private List<Viewer> read(){
        List<Viewer> out=new ArrayList<>();
        try{
            JSONArray rows=new JSONArray(prefs.getString("profiles","[]"));
            java.util.HashSet<String> ids=new java.util.HashSet<>();
            for(int i=0;i<rows.length();i++){
                JSONObject row=rows.getJSONObject(i);String id=row.getString("id"),name=row.getString("name");
                if(!validId(id)||!ids.add(id)||(name.trim().isEmpty()&&!DEFAULT_ID.equals(id))||name.length()>40||rows.length()>LIMIT)throw new IllegalStateException("PROFILE_STORE_UNREADABLE");
                out.add(new Viewer(id,name));
            }
        }catch(Exception error){throw new IllegalStateException("PROFILE_STORE_UNREADABLE");}
        if(out.stream().noneMatch(v->DEFAULT_ID.equals(v.id)))out.add(0,new Viewer(DEFAULT_ID,""));
        if(out.size()>LIMIT)throw new IllegalStateException("PROFILE_STORE_UNREADABLE");
        return out;
    }
    public String activeId(){synchronized(LOCK){
        String active=prefs.getString("active",DEFAULT_ID);
        for(Viewer v:read())if(v.id.equals(active))return active;
        return DEFAULT_ID;
    }}
    public boolean select(String id){synchronized(LOCK){
        for(Viewer v:read())if(v.id.equals(id)){if(!prefs.edit().putString("active",id).commit())throw new IllegalStateException("PROFILE_SAVE_FAILED");return true;}
        return false;
    }}
    public String add(String name){synchronized(LOCK){
        String label=cleanName(name);List<Viewer> viewers=read();
        if(viewers.size()>=LIMIT)throw new IllegalStateException("PROFILE_LIMIT");
        for(Viewer v:viewers)if(v.name.equalsIgnoreCase(label))throw new IllegalArgumentException("PROFILE_NAME_EXISTS");
        String id=UUID.randomUUID().toString();viewers.add(new Viewer(id,label));write(viewers);return id;
    }}
    public boolean rename(String id,String name){synchronized(LOCK){
        String label=cleanName(name);List<Viewer> viewers=read();int index=-1;
        for(int i=0;i<viewers.size();i++){
            Viewer v=viewers.get(i);if(v.id.equals(id))index=i;
            else if(v.name.equalsIgnoreCase(label))throw new IllegalArgumentException("PROFILE_NAME_EXISTS");
        }
        if(index<0)return false;viewers.set(index,new Viewer(id,label));write(viewers);return true;
    }}
    public boolean remove(String id){synchronized(LOCK){
        if(DEFAULT_ID.equals(id)||!validId(id))return false;
        List<Viewer> viewers=read();boolean removed=viewers.removeIf(v->v.id.equals(id));
        if(!removed)return false;
        if(id.equals(activeId())&&!prefs.edit().putString("active",DEFAULT_ID).commit())throw new IllegalStateException("PROFILE_SAVE_FAILED");
        write(viewers);
        app.getSharedPreferences(libraryName(id),Context.MODE_PRIVATE).edit().clear().commit();
        return true;
    }}
    private void write(List<Viewer> viewers){
        JSONArray rows=new JSONArray();
        try{for(Viewer v:viewers)rows.put(new JSONObject().put("id",v.id).put("name",v.name));}
        catch(Exception error){throw new IllegalStateException("PROFILE_SAVE_FAILED");}
        if(!prefs.edit().putString("profiles",rows.toString()).commit())throw new IllegalStateException("PROFILE_SAVE_FAILED");
    }
    private static String cleanName(String name){
        String label=name==null?"":name.trim();
        if(label.isEmpty()||label.length()>40||label.codePoints().anyMatch(Character::isISOControl))throw new IllegalArgumentException("PROFILE_NAME_INVALID");
        return label;
    }
    private static boolean validId(String id){return DEFAULT_ID.equals(id)||(id!=null&&id.matches("[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}"));}
    public static String libraryName(String id){
        if(!validId(id))throw new IllegalArgumentException("PROFILE_ID_INVALID");
        return DEFAULT_ID.equals(id)?"library":"library_viewer_"+id;
    }
}
