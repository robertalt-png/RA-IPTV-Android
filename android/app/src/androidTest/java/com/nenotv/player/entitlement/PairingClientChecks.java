package com.nenotv.player.entitlement;

import android.content.Context;
import android.content.SharedPreferences;
import java.io.IOException;
import java.util.*;
import org.json.JSONObject;

public final class PairingClientChecks {
    static void check(boolean value,String message){if(!value)throw new AssertionError(message);}
    static JSONObject response()throws Exception{return new JSONObject().put("ok",true).put("code","ABCDEF0123").put("poll_token",String.join("",Collections.nCopies(64,"a"))).put("verification_url","https://nenotv.com/nenotv-pair/?code=ABCDEF0123").put("expires_in",300).put("poll_interval",5);}
    public static PairingClient.Session fixtureSession()throws Exception{return new PairingClient.Session(response().put("code","AB23").put("verification_url","https://sunnyiptv.com/nenotv-pair/?code=AB23&lang=nl"));}
    public static void run(Context context)throws Exception{
        SharedPreferences prefs=context.getSharedPreferences("nenotv_entitlement",Context.MODE_PRIVATE);
        Map<String,?> previous=new HashMap<>(prefs.getAll());
        SharedPreferences accounts=context.getSharedPreferences("nenotv_account_link_v1",Context.MODE_PRIVATE);
        Map<String,?> oldAccount=new HashMap<>(accounts.getAll());
        try{
            prefs.edit().putString("level","PRO").commit();
            check(new PairingClient.Session(response().put("verification_url","https://sunnyiptv.com/nenotv-pair/?code=ABCDEF0123")).displayCode().equals("ABCDE-F0123"),"SunnyIPTV pairing URL rejected");
            for(String url:new String[]{"http://sunnyiptv.com/nenotv-pair/?code=ABCDEF0123","https://sunnyiptv.com.evil.invalid/nenotv-pair/?code=ABCDEF0123","https://sunnyiptv.com:8080/nenotv-pair/?code=ABCDEF0123","https://user@sunnyiptv.com/nenotv-pair/?code=ABCDEF0123","https://sunnyiptv.com/my-account/?code=ABCDEF0123","https://sunnyiptv.com/nenotv-pair/?code=0000000000"}){
                try{new PairingClient.Session(response().put("verification_url",url));throw new AssertionError("Unsafe SunnyIPTV pairing URL accepted");}catch(IOException expected){}
            }
            for(String url:new String[]{"http://nenotv.com/nenotv-pair/?code=ABCDEF0123","https://evil.invalid/nenotv-pair/?code=ABCDEF0123","https://nenotv.com:8080/nenotv-pair/?code=ABCDEF0123","https://user@nenotv.com/nenotv-pair/?code=ABCDEF0123","https://nenotv.com/my-account/?code=ABCDEF0123","https://nenotv.com/nenotv-pair/?code=0000000000"}){
                try{new PairingClient.Session(response().put("verification_url",url));throw new AssertionError("Unsafe pairing URL accepted");}catch(IOException expected){}
            }
            try{new PairingClient.Session(response().put("expires_in",900));throw new AssertionError("Unbounded pairing duration");}catch(IOException expected){}
            try(EntitlementClientChecks.Fixture fixture=new EntitlementClientChecks.Fixture(200,response().toString(),false)){
                PairingClient.Session session=new PairingClient(context,fixture.url()).start();
                check("ABCDE-F0123".equals(session.displayCode())&&!session.expired(),"Invalid short code");
                check("PRO".equals(prefs.getString("level","")),"Starting pairing removed existing entitlement");
            }
            PairingClient.Session session=fixtureSession();
            com.nenotv.player.storage.AccountLinkStore base=new com.nenotv.player.storage.AccountLinkStore(context);
            base.clear();check(!base.linked(),"Missing account linked");
            for(JSONObject bad:new JSONObject[]{account("free").put("account_id","bad"),account("free").put("status","revoked"),account("pro")}){
                try{base.apply(bad);throw new AssertionError("Invalid base account accepted");}catch(IOException expected){}
                check(!base.linked(),"Invalid identity persisted");
            }
            check("AB23".equals(session.displayCode()),"Four-character pairing code displayed incorrectly");
            try{new PairingClient.Session(response().put("code","AB2"));throw new AssertionError("Invalid code length accepted");}catch(IOException expected){}
            try(EntitlementClientChecks.Fixture fixture=new EntitlementClientChecks.Fixture(200,"{\"ok\":true,\"state\":\"pending\"}",false)){
                check("pending".equals(new PairingClient(context,fixture.url()).status(session)),"Pending pairing not retained");
                check("PRO".equals(prefs.getString("level","")),"Pending pairing changed access");
            }
            for(String entitlement:new String[]{"null","{\"level\":\"free\",\"status\":\"active\"}","{\"level\":\"pro\"}"})try(EntitlementClientChecks.Fixture fixture=new EntitlementClientChecks.Fixture(200,"{\"ok\":true,\"state\":\"complete\",\"entitlement\":"+entitlement+"}",false)){
                try{new PairingClient(context,fixture.url()).status(session);throw new AssertionError("Invalid completed pairing accepted");}catch(IOException expected){}
                check("PRO".equals(prefs.getString("level","")),"Invalid completion overwrote access");
            }
            try(EntitlementClientChecks.Fixture fixture=new EntitlementClientChecks.Fixture(200,new JSONObject("{\"ok\":true,\"state\":\"complete\",\"entitlement\":{\"level\":\"pro\",\"status\":\"active\",\"email\":\"paired@example.invalid\",\"max_devices\":5}}").put("account_link",account("paid")).toString(),false)){
                check("complete".equals(new PairingClient(context,fixture.url()).status(session)),"Completion not accepted");
                check("paired@example.invalid".equals(prefs.getString("account_email","")),"Verified account not applied");
            }
            for(String link:new String[]{"null","{\"kind\":\"free\",\"status\":\"revoked\"}","{\"kind\":\"pro\",\"status\":\"active\"}"})try(EntitlementClientChecks.Fixture fixture=new EntitlementClientChecks.Fixture(200,"{\"ok\":true,\"state\":\"complete\",\"account_link\":"+link+",\"entitlement\":{\"level\":\"free\",\"status\":\"active\"}}",false)){
                try{new PairingClient(context,fixture.url()).status(session);throw new AssertionError("Unverified free pairing accepted");}catch(IOException expected){}
                check("PRO".equals(prefs.getString("level","")),"Invalid free completion overwrote access");
            }
            try(EntitlementClientChecks.Fixture fixture=new EntitlementClientChecks.Fixture(200,new JSONObject("{\"ok\":true,\"state\":\"complete\",\"entitlement\":{\"level\":\"free\",\"status\":\"active\",\"max_devices\":0,\"account_scope\":\"\"}}").put("account_link",account("free")).toString(),false)){
                check("complete".equals(new PairingClient(context,fixture.url()).status(session)),"Free account link rejected");
                com.nenotv.player.storage.EntitlementStore access=new com.nenotv.player.storage.EntitlementStore(context);
                check(!access.isPro()&&access.cloudAccountScope().isEmpty(),"Free pairing unlocked Pro or source vault");
                check(new com.nenotv.player.storage.AccountLinkStore(context).recent(),"Verified account not persisted");
            }
            accounts.edit().putLong("checked_at",System.currentTimeMillis()-16*60*1000L).commit();check(base.linked()&&!base.recent(),"Stale login bypassed refresh");
            try(EntitlementClientChecks.Fixture fixture=new EntitlementClientChecks.Fixture(200,new JSONObject().put("ok",true).put("account_link",account("account")).toString(),false)){
                new PairingClient(context,fixture.url()).checkAccount();check(base.recent(),"Account refresh not persisted");check(!new com.nenotv.player.storage.EntitlementStore(context).isPro(),"Account refresh granted Pro");
            }
            accounts.edit().putString("binding","bad").commit();check(!base.linked(),"Device mismatch retained login");
        }finally{
            com.nenotv.player.FamilyChecks.restore(accounts,oldAccount);
            SharedPreferences.Editor editor=prefs.edit().clear();
            for(Map.Entry<String,?> entry:previous.entrySet()){
                Object value=entry.getValue();String key=entry.getKey();
                if(value instanceof String)editor.putString(key,(String)value);
                else if(value instanceof Long)editor.putLong(key,(Long)value);
                else if(value instanceof Integer)editor.putInt(key,(Integer)value);
                else if(value instanceof Boolean)editor.putBoolean(key,(Boolean)value);
            }
            editor.commit();
        }
    }
    static JSONObject account(String kind)throws Exception{return new JSONObject().put("kind",kind).put("status","active").put("account_id",String.join("",Collections.nCopies(64,"a")));}
}
