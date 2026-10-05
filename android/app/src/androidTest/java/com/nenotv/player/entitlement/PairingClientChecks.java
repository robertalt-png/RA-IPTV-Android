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
            try(EntitlementClientChecks.Fixture fixture=new EntitlementClientChecks.Fixture(200,"{\"ok\":true,\"state\":\"complete\",\"entitlement\":{\"level\":\"pro\",\"status\":\"active\",\"email\":\"paired@example.invalid\",\"max_devices\":5}}",false)){
                check("complete".equals(new PairingClient(context,fixture.url()).status(session)),"Completion not accepted");
                check("paired@example.invalid".equals(prefs.getString("account_email","")),"Verified account not applied");
            }
        }finally{
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
}
