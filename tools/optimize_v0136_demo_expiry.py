from pathlib import Path

root=Path(".")
profile=root/"app/src/main/java/com/robertalt/raiptv/ProfileActivity.java"
main=root/"app/src/main/java/com/robertalt/raiptv/MainActivity.java"
store=root/"app/src/main/java/com/robertalt/raiptv/storage/SecureProfileStore.java"
instr=root/"app/src/androidTest/java/com/nenotv/player/ImportInstrumentation.java"
policy=root/"app/src/main/java/com/robertalt/raiptv/DemoPolicy.java"
for p in (profile,main,store,instr):
    if not p.exists(): raise SystemExit(f"missing {p}")

policy.write_text(r'''package com.nenotv.player;

import android.content.*;
import com.nenotv.player.storage.SettingsStore;

public final class DemoPolicy {
    public static final long DURATION_MS=30L*24L*60L*60L*1000L;
    private DemoPolicy(){}

    static SharedPreferences prefs(Context c){return SettingsStore.prefs(c);}

    public static boolean consumed(Context c){return prefs(c).getBoolean("demo_consumed",false);}
    public static long expiresAt(Context c){return prefs(c).getLong("demo_expires_at",0L);}
    public static boolean expired(Context c){return expiredAt(c,System.currentTimeMillis());}
    public static boolean expiredAt(Context c,long now){
        long exp=expiresAt(c);
        return consumed(c)&&exp>0L&&now>=exp;
    }
    public static boolean canStart(Context c){
        if(!consumed(c))return true;
        long exp=expiresAt(c);
        return exp>System.currentTimeMillis();
    }
    public static long startOrKeep(Context c,long now){
        SharedPreferences sp=prefs(c);
        boolean used=sp.getBoolean("demo_consumed",false);
        long exp=sp.getLong("demo_expires_at",0L);
        if(used&&exp>now)return exp;
        if(used&&exp>0L&&now>=exp)return -1L;
        long next=now+DURATION_MS;
        sp.edit().putBoolean("demo_consumed",true).putLong("demo_started_at",now).putLong("demo_expires_at",next).apply();
        return next;
    }
}''',encoding="utf-8")

s=store.read_text(encoding="utf-8")
if "public void clear()" not in s:
    marker='''    public boolean exists(){return prefs.contains("name");}'''
    if marker not in s: raise SystemExit("SecureProfileStore exists marker missing")
    s=s.replace(marker,marker+'\n    public void clear(){prefs.edit().clear().apply();}')
store.write_text(s,encoding="utf-8")

p=profile.read_text(encoding="utf-8")
old='''        String demo=BuildConfig.NENOTV_DEMO_M3U_URL;
        demoRadio.setText(demo==null||demo.trim().isEmpty()
            ?T("30-day free demo · not configured yet","30 dagen gratis demo · nog niet geconfigureerd","30 Tage kostenlose Demo · noch nicht eingerichtet")
            :T("Try NenoTV free for 30 days","NenoTV 30 dagen gratis proberen","NenoTV 30 Tage kostenlos testen"));
        demoRadio.setEnabled(demo!=null&&!demo.trim().isEmpty());'''
new='''        String demo=BuildConfig.NENOTV_DEMO_M3U_URL;
        boolean configured=demo!=null&&!demo.trim().isEmpty();
        boolean expired=DemoPolicy.expired(this);
        demoRadio.setText(!configured
            ?T("30-day free demo · not configured yet","30 dagen gratis demo · nog niet geconfigureerd","30 Tage kostenlose Demo · noch nicht eingerichtet")
            :expired
                ?T("30-day demo ended · add your own TV source","30 dagen demo afgelopen · voeg uw eigen TV-bron toe","30-Tage-Demo beendet · eigene TV-Quelle hinzufügen")
                :T("Try NenoTV free for 30 days","NenoTV 30 dagen gratis proberen","NenoTV 30 Tage kostenlos testen"));
        demoRadio.setEnabled(configured&&!expired);'''
if old not in p: raise SystemExit("Profile demo label marker missing")
p=p.replace(old,new,1)

old='''        final Profile p=collect();
        if(demoRadio.isChecked()&&(p.m3uUrl==null||p.m3uUrl.trim().isEmpty())){status.setText(T("Demo source is not configured yet.","Demo-bron is nog niet geconfigureerd.","Demo-Quelle ist noch nicht eingerichtet."));return;}
        status.setText(T("Connecting…","Verbinden…","Verbindung wird hergestellt…"));'''
new='''        final Profile p=collect();
        if(demoRadio.isChecked()&&(p.m3uUrl==null||p.m3uUrl.trim().isEmpty())){status.setText(T("Demo source is not configured yet.","Demo-bron is nog niet geconfigureerd.","Demo-Quelle ist noch nicht eingerichtet."));return;}
        if(demoRadio.isChecked()&&DemoPolicy.expired(this)){status.setText(T("Your 30-day demo has ended. Add your own M3U or Xtream source.","Uw 30 dagen demo is afgelopen. Voeg uw eigen M3U- of Xtream-bron toe.","Ihre 30-Tage-Demo ist beendet. Fügen Sie eine eigene M3U- oder Xtream-Quelle hinzu."));return;}
        status.setText(T("Connecting…","Verbinden…","Verbindung wird hergestellt…"));'''
if old not in p: raise SystemExit("Profile connect marker missing")
p=p.replace(old,new,1)

old='''                if(demoRadio.isChecked()){
                    long now=System.currentTimeMillis();
                    SettingsStore.prefs(this).edit().putLong("demo_started_at",now).putLong("demo_expires_at",now+30L*24L*60L*60L*1000L).apply();
                }
                store.save(p);'''
new='''                if(demoRadio.isChecked()){
                    long expiry=DemoPolicy.startOrKeep(this,System.currentTimeMillis());
                    if(expiry<0L)throw new IllegalStateException(T("Your 30-day demo has ended.","Uw 30 dagen demo is afgelopen.","Ihre 30-Tage-Demo ist beendet."));
                }
                store.save(p);'''
if old not in p: raise SystemExit("Profile demo start marker missing")
p=p.replace(old,new,1)
profile.write_text(p,encoding="utf-8")

m=main.read_text(encoding="utf-8")
old='''        if(!profiles.exists())startActivityForResult(new Intent(this,ProfileActivity.class),10);else openProfile();'''
new='''        if(expireDemoProfileIfNeeded()){startActivityForResult(new Intent(this,ProfileActivity.class),10);return;}
        if(!profiles.exists())startActivityForResult(new Intent(this,ProfileActivity.class),10);else openProfile();'''
if old not in m: raise SystemExit("Main profile-open marker missing")
m=m.replace(old,new,1)
anchor='''    @Override public void onConfigurationChanged(android.content.res.Configuration newConfig){'''
method='''    boolean expireDemoProfileIfNeeded(){
        if(!profiles.exists()||!DemoPolicy.expired(this))return false;
        try{
            Profile p=profiles.load();
            if(!"NenoTV Demo".equals(p.name))return false;
            profiles.clear();
            String l=SettingsStore.language(this);
            String msg="nl".equals(l)?"Uw 30 dagen demo is afgelopen. Voeg uw eigen M3U- of Xtream-bron toe.":"de".equals(l)?"Ihre 30-Tage-Demo ist beendet. Fügen Sie eine eigene M3U- oder Xtream-Quelle hinzu.":"Your 30-day demo has ended. Add your own M3U or Xtream source.";
            Toast.makeText(this,msg,Toast.LENGTH_LONG).show();
            return true;
        }catch(Exception ignored){return false;}
    }

'''
if method.strip() not in m:
    if anchor not in m: raise SystemExit("Main method anchor missing")
    m=m.replace(anchor,method+anchor,1)
main.write_text(m,encoding="utf-8")

i=instr.read_text(encoding="utf-8")
if "import com.nenotv.player.storage.SettingsStore;" not in i:
    i=i.replace("import com.nenotv.player.storage.SearchIndexStore;","import com.nenotv.player.storage.SearchIndexStore;\\nimport com.nenotv.player.storage.SettingsStore;")
anchor='''            store.upsert(PROFILE, Collections.singletonList(entry("movie", "vod")));
'''
if anchor not in i: raise SystemExit("Instrumentation anchor missing")
if "DemoPolicy.startOrKeep" not in i:
    test='''            android.content.Context demoContext=getTargetContext();
            android.content.SharedPreferences demoPrefs=SettingsStore.prefs(demoContext);
            demoPrefs.edit().remove("demo_consumed").remove("demo_started_at").remove("demo_expires_at").commit();
            long demoNow=1700000000000L;
            long demoExpiry=DemoPolicy.startOrKeep(demoContext,demoNow);
            require(demoExpiry==demoNow+DemoPolicy.DURATION_MS,"Demo duration incorrect");
            require(DemoPolicy.startOrKeep(demoContext,demoNow+1000L)==demoExpiry,"Demo restart extended expiry");
            require(!DemoPolicy.expiredAt(demoContext,demoExpiry-1L),"Demo expired too early");
            require(DemoPolicy.expiredAt(demoContext,demoExpiry),"Demo expiry not enforced");
            require(DemoPolicy.startOrKeep(demoContext,demoExpiry+1L)<0L,"Expired demo restarted");
'''
    i=i.replace(anchor,anchor+test,1)
instr.write_text(i,encoding="utf-8")
print("Applied v0.13.6 one-time 30-day demo policy")
