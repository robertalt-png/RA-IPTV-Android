package com.nenotv.player;

import android.content.Context;
import android.content.SharedPreferences;
import com.nenotv.player.model.MediaEntry;
import com.nenotv.player.model.Profile;
import com.nenotv.player.storage.*;
import java.util.*;

/** Runs on disposable QA devices; restores all touched preference stores. */
public final class FamilyChecks {
    private static void check(boolean ok,String message){if(!ok)throw new AssertionError(message);}
    private static MediaEntry media(String id,String name){MediaEntry e=new MediaEntry();e.id=id;e.name=name;e.type="vod";e.url="https://example.invalid/"+id+".mp4";e.candidates.add(e.url);return e;}
    static void restore(SharedPreferences prefs,Map<String,?> values){
        SharedPreferences.Editor edit=prefs.edit().clear();
        for(Map.Entry<String,?> row:values.entrySet()){
            Object v=row.getValue();String k=row.getKey();
            if(v instanceof String)edit.putString(k,(String)v);else if(v instanceof Boolean)edit.putBoolean(k,(Boolean)v);
            else if(v instanceof Integer)edit.putInt(k,(Integer)v);else if(v instanceof Long)edit.putLong(k,(Long)v);
            else if(v instanceof Float)edit.putFloat(k,(Float)v);else if(v instanceof Set<?>)edit.putStringSet(k,new HashSet<>((Set<String>)v));
        }
        check(edit.commit(),"QA preferences could not be restored");
    }
    public static int run(Context c)throws Exception{
        Map<String,Map<String,?>> previous=new LinkedHashMap<>();
        for(String name:new String[]{"profile","nenotv_entitlement","nenotv_settings","sunnyiptv_family_v1"}){
            SharedPreferences p=c.getSharedPreferences(name,Context.MODE_PRIVATE);previous.put(name,new HashMap<>(p.getAll()));check(p.edit().clear().commit(),"QA clear failed");
        }
        int checks=0;
        try{
            Profile profile=new Profile();profile.type=Profile.Type.M3U;profile.m3uUrl="https://example.invalid/family-test.m3u";new SecureProfileStore(c).save(profile);
            SettingsStore.setParentalPin(c,"2468");
            check(SettingsStore.verifyParentalPin(c,"2468"),"Correct PIN rejected");checks++;
            check(!SettingsStore.verifyParentalPin(c,"9999"),"Wrong PIN accepted");checks++;
            check(SettingsStore.prefs(c).getString("parental_pin_hash","").startsWith("pbkdf2-v1:"),"PIN not salted");checks++;
            MediaEntry allowed=media("approved","Allowed movie"),other=media("other","Unapproved movie");
            check(FamilyStore.allowed(c,other),"Adult mode changed");checks++;
            check(!FamilyStore.setActive(c,true,"9999"),"Wrong PIN enabled mode");checks++;
            check(FamilyStore.setActive(c,true,"2468"),"Free child mode unavailable");checks++;
            check(!FamilyStore.allowed(c,null)&&!FamilyStore.allowed(c,other),"Empty allowlist not closed");checks++;
            check(!FamilyStore.approve(c,allowed,"9999"),"Approval without PIN");checks++;
            check(FamilyStore.approve(c,allowed,"2468")&&FamilyStore.allowed(c,allowed),"Approval not usable");checks++;
            check(!FamilyStore.allowed(c,other),"Approval allowed unrelated item");checks++;
            String old=allowed.name;allowed.name="Changed movie";check(!FamilyStore.allowed(c,allowed),"Reused ID inherited approval");checks++;allowed.name=old;
            String url=allowed.url;allowed.url+="?changed";check(!FamilyStore.allowed(c,allowed),"Changed URL inherited approval");checks++;allowed.url=url;
            allowed.candidates.add("https://example.invalid/other.mp4");check(!FamilyStore.allowed(c,allowed),"Changed candidate inherited approval");checks++;allowed.candidates.remove(allowed.candidates.size()-1);
            allowed.sourceId="other-source";check(!FamilyStore.allowed(c,allowed),"Secondary source inherited primary approval");checks++;allowed.sourceId="";
            profile.m3uUrl="https://example.invalid/other-source.m3u";new SecureProfileStore(c).save(profile);check(!FamilyStore.allowed(c,allowed),"Changed profile inherited approval");checks++;
            profile.m3uUrl="https://example.invalid/family-test.m3u";new SecureProfileStore(c).save(profile);check(FamilyStore.allowed(c,allowed),"Original profile approval lost");checks++;
            check(!FamilyStore.setActive(c,false,"9999")&&FamilyStore.active(c),"Mode disabled without PIN");checks++;
            SettingsStore.unlockAdults(c,"2468");check(!SettingsStore.adultsAllowed(c),"Legacy unlock bypassed child mode");checks++;
            MediaEntry series=media("series","Allowed series");series.type="series";series.seriesId="s1";
            MediaEntry episode=media("ep1","Episode 1");episode.type="episode";episode.seriesId="s1";episode.seriesTitle=series.name;
            check(!FamilyStore.approve(c,series,"2468"),"Whole series allowed future episodes");checks++;
            check(FamilyStore.approve(c,episode,"2468")&&FamilyStore.allowed(c,series)&&FamilyStore.allowed(c,episode),"Approved episode inaccessible");checks++;
            MediaEntry second=media("ep2","Episode 2");second.type="episode";second.seriesId="s1";second.seriesTitle=series.name;check(!FamilyStore.allowed(c,second),"Next episode inherited approval");checks++;
            series.name="Different series";check(!FamilyStore.allowed(c,series),"Reused series ID exposed different series");checks++;
            String key=FamilyStore.approvalKey(c,allowed);
            check(!FamilyStore.revoke(c,key,"9999")&&FamilyStore.allowed(c,allowed),"Revoked without PIN");checks++;
            check(FamilyStore.revoke(c,key,"2468")&&!FamilyStore.allowed(c,allowed),"Revocation not immediate");checks++;
            check(FamilyStore.approve(c,allowed,"2468"),"Reapproval failed");checks++;
            c.getSharedPreferences("sunnyiptv_family_v1",Context.MODE_PRIVATE).edit().putString(key,"broken-json").commit();check(!FamilyStore.allowed(c,allowed),"Corrupt approval failed open");checks++;
            for(int n=0;n<5;n++)SettingsStore.verifyParentalPin(c,"0000");check(!SettingsStore.verifyParentalPin(c,"2468"),"Guessing not throttled");checks++;
            SettingsStore.prefs(c).edit().putLong("parental_pin_locked_until",System.currentTimeMillis()-1).commit();check(SettingsStore.verifyParentalPin(c,"2468"),"Cooldown did not recover");checks++;
            check(FamilyStore.setActive(c,false,"2468")&&FamilyStore.allowed(c,other),"Parent could not leave mode");checks++;
            check(!ParentPinCodec.verify("2468","pbkdf2-v1:bad:bad"),"Malformed PIN hash accepted");checks++;
            String a=ParentPinCodec.encode("2468"),b=ParentPinCodec.encode("2468");check(!a.equals(b)&&ParentPinCodec.verify("2468",a),"Salt not random");checks++;
            check(!ParentPinCodec.verify("123",a)&&!ParentPinCodec.verify(null,a),"Invalid PIN accepted");checks++;
            return checks;
        }finally{for(Map.Entry<String,Map<String,?>> e:previous.entrySet())restore(c.getSharedPreferences(e.getKey(),Context.MODE_PRIVATE),e.getValue());SettingsStore.lockAdults();}
    }
}
