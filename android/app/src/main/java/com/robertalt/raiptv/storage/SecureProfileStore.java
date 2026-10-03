package com.nenotv.player.storage;

import android.content.*; import com.nenotv.player.model.Profile;

public class SecureProfileStore {
    private final SharedPreferences prefs; private final CryptoBox crypto;
    public SecureProfileStore(Context c){prefs=c.getSharedPreferences("profile",Context.MODE_PRIVATE);crypto=new CryptoBox();}
    public boolean exists(){return prefs.contains("name");}
    public void clear(){if(!prefs.edit().clear().commit())throw new IllegalStateException("PROFILE_CLEAR_FAILED");}
    public void save(Profile p){if(!prefs.edit().putString("type",p.type.name()).putString("name",p.name).putString("server",crypto.encrypt(p.server)).putString("username",crypto.encrypt(p.username)).putString("password",crypto.encrypt(p.password)).putString("m3u",crypto.encrypt(p.m3uUrl)).putString("epg",crypto.encrypt(p.epgUrl)).putString("bridge",crypto.encrypt(p.bridgeUrl)).putString("bridgeToken",crypto.encrypt(p.bridgeToken)).commit())throw new IllegalStateException("PROFILE_SAVE_FAILED");}
    public Profile load(){Profile p=new Profile();try{p.type=Profile.Type.valueOf(prefs.getString("type","XTREAM"));}catch(Exception ignored){}p.name=prefs.getString("name","My IPTV");p.server=crypto.decrypt(prefs.getString("server",""));p.username=crypto.decrypt(prefs.getString("username",""));p.password=crypto.decrypt(prefs.getString("password",""));p.m3uUrl=crypto.decrypt(prefs.getString("m3u",""));p.epgUrl=crypto.decrypt(prefs.getString("epg",""));p.bridgeUrl=crypto.decrypt(prefs.getString("bridge",""));p.bridgeToken=crypto.decrypt(prefs.getString("bridgeToken",""));return p;}
}
