package com.nenotv.player.entitlement;

import android.content.Context;
import com.nenotv.player.BuildConfig;
import com.nenotv.player.storage.EntitlementStore;
import org.json.JSONObject;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

public final class EntitlementClient {
    private final EntitlementStore store;
    public EntitlementClient(Context c){store=new EntitlementStore(c);}

    public JSONObject refresh() throws Exception {return post("entitlement/refresh",new JSONObject());}
    public JSONObject startTrial(String email) throws Exception {
        JSONObject body=new JSONObject();
        body.put("email",email==null?"":email.trim());
        return post("entitlement/trial",body);
    }
    public JSONObject claim(String email,String orderId) throws Exception {
        JSONObject body=new JSONObject();
        body.put("email",email==null?"":email.trim());
        body.put("order_id",orderId==null?"":orderId.trim());
        return post("entitlement/claim",body);
    }
    public JSONObject redeemToken(String token) throws Exception {
        JSONObject body=new JSONObject();
        body.put("activation_token",token==null?"":token.trim());
        return post("entitlement/redeem",body);
    }

    private JSONObject post(String path,JSONObject body) throws Exception {
        body.put("device_id",store.deviceId());
        body.put("public_device_id",store.publicDeviceId());
        body.put("device_key",store.deviceKey());
        body.put("platform","android");
        body.put("app_version",BuildConfig.VERSION_NAME);

        String base="https://nenotv.com";
        while(base.endsWith("/"))base=base.substring(0,base.length()-1);

        Exception firstError=null;
        JSONObject out=null;
        try{
            out=postUrl(base+"/wp-json/nenotv/v1/"+path,body);
        }catch(Exception e){
            firstError=e;
        }

        if(out==null){
            try{
                out=postUrl(base+"/index.php?rest_route=/nenotv/v1/"+path,body);
            }catch(Exception e){
                String m=e.getMessage();
                if(m==null||m.trim().isEmpty())m=firstError==null?null:firstError.getMessage();
                throw new IOException(m==null||m.trim().isEmpty()?"NenoTV account service unavailable":m);
            }
        }

        JSONObject entitlement=out.optJSONObject("entitlement");
        store.applyServer(entitlement==null?out:entitlement);
        return out;
    }

    private JSONObject postUrl(String url,JSONObject body) throws Exception {
        HttpURLConnection c=(HttpURLConnection)new URL(url).openConnection();
        c.setInstanceFollowRedirects(false);
        c.setConnectTimeout(9000);
        c.setReadTimeout(12000);
        c.setRequestMethod("POST");
        c.setDoOutput(true);
        c.setRequestProperty("Content-Type","application/json; charset=utf-8");
        c.setRequestProperty("Accept","application/json");
        c.setRequestProperty("User-Agent","NenoTV/"+BuildConfig.VERSION_NAME+" Android");
        byte[] bytes=body.toString().getBytes(StandardCharsets.UTF_8);
        c.setFixedLengthStreamingMode(bytes.length);
        try(OutputStream os=c.getOutputStream()){os.write(bytes);}

        int code=c.getResponseCode();
        InputStream in=code>=200&&code<300?c.getInputStream():c.getErrorStream();
        String text=read(in);
        String trimmed=text==null?"":text.trim();
        String contentType=c.getHeaderField("Content-Type");

        if(code>=300&&code<400)throw new IOException("REST redirect");
        if(trimmed.isEmpty())throw new IOException("HTTP "+code);
        if(trimmed.startsWith("<")||(contentType!=null&&contentType.toLowerCase(java.util.Locale.ROOT).contains("text/html")))
            throw new IOException("Unexpected HTML response");

        JSONObject out;
        try{
            out=new JSONObject(trimmed);
        }catch(Exception e){
            throw new IOException("Unexpected server response");
        }

        if(code<200||code>=300||!out.optBoolean("ok",true))
            throw new IOException(out.optString("message",out.optString("error","HTTP "+code)));
        return out;
    }

    private String read(InputStream in)throws IOException{
        if(in==null)return "";
        try(BufferedReader r=new BufferedReader(new InputStreamReader(in,StandardCharsets.UTF_8))){
            StringBuilder b=new StringBuilder();
            String s;
            while((s=r.readLine())!=null)b.append(s);
            return b.toString();
        }
    }
}
