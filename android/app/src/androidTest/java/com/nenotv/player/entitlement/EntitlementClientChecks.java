package com.nenotv.player.entitlement;

import android.content.Context;
import android.content.SharedPreferences;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

public final class EntitlementClientChecks {
    static void check(boolean value,String message){if(!value)throw new AssertionError(message);}
    public static void run(Context context)throws Exception{
        SharedPreferences prefs=context.getSharedPreferences("nenotv_entitlement",Context.MODE_PRIVATE);
        Map<String,?> previous=new HashMap<>(prefs.getAll());
        try{
            for(String response:new String[]{"{\"ok\":false,\"message\":\"secret-must-not-appear\"}","{\"ok\":true}","{\"ok\":true,\"entitlement\":{\"level\":\"unexpected\"}}","{\"entitlement\":{\"level\":\"free\"}}"}){
                prefs.edit().putString("level","PRO").putString("account_email","existing@example.invalid").commit();
                try(Fixture fixture=new Fixture(200,response,false)){
                    boolean failed=false;
                    try{new EntitlementClient(context,fixture.url()).redeemToken("qa-secret");}
                    catch(IOException e){failed=true;check(!e.getMessage().contains("secret"),"Server message leaked into account error");}
                    check(failed,"Invalid entitlement was accepted");
                    check("PRO".equals(prefs.getString("level","")),"Invalid response removed existing access");
                    check(fixture.calls.get()==1,"Failed activation was automatically repeated");
                }
            }
            try(Fixture fixture=new Fixture(503,"{\"ok\":false}",false)){
                try{new EntitlementClient(context,fixture.url()).redeemToken("qa-secret");throw new AssertionError("Server failure accepted");}catch(IOException expected){}
                check(fixture.calls.get()==1,"Server failure retried consumed activation");
            }
            String success="{\"ok\":true,\"entitlement\":{\"level\":\"pro\",\"status\":\"active\",\"email\":\"paired@example.invalid\",\"max_devices\":5}}";
            for(boolean fallback:new boolean[]{false,true})try(Fixture fixture=new Fixture(200,success,fallback)){
                new EntitlementClient(context,fixture.url()).redeemToken("qa-secret");
                check("paired@example.invalid".equals(prefs.getString("account_email",""))&&prefs.getInt("max_devices",0)==5,"Activation did not apply verified entitlement");
                check(fixture.calls.get()==(fallback?2:1),"Unexpected activation request count");
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
    static final class Fixture implements AutoCloseable{
        final ServerSocket socket;final Thread worker;final AtomicInteger calls=new AtomicInteger();
        Fixture(int status,String json,boolean missingRoute)throws Exception{
            socket=new ServerSocket(0,8,InetAddress.getByName("127.0.0.1"));
            worker=new Thread(()->{
                while(!socket.isClosed())try(Socket connection=socket.accept()){
                    BufferedReader reader=new BufferedReader(new InputStreamReader(connection.getInputStream(),StandardCharsets.UTF_8));
                    String line;int length=0;reader.readLine();
                    while((line=reader.readLine())!=null&&!line.isEmpty())if(line.toLowerCase(Locale.ROOT).startsWith("content-length:"))length=Integer.parseInt(line.substring(15).trim());
                    for(int i=0;i<length;i++)if(reader.read()<0)break;
                    int call=calls.incrementAndGet();boolean absent=missingRoute&&call==1;
                    byte[] body=(absent?"{\"code\":\"rest_no_route\"}":json).getBytes(StandardCharsets.UTF_8);
                    OutputStream out=connection.getOutputStream();
                    out.write(("HTTP/1.1 "+(absent?404:status)+" Test\r\nContent-Type: application/json\r\nContent-Length: "+body.length+"\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));out.write(body);out.flush();
                }catch(Exception ignored){}
            },"account-fixture");worker.setDaemon(true);worker.start();
        }
        String url(){return "http://127.0.0.1:"+socket.getLocalPort();}
        public void close()throws Exception{socket.close();worker.join(1000);}
    }
}
