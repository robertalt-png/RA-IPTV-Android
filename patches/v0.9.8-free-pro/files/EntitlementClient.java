package com.robertalt.raiptv.entitlement;

import android.content.Context;
import com.robertalt.raiptv.BuildConfig;
import com.robertalt.raiptv.storage.EntitlementStore;
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
        String base=BuildConfig.NENOTV_API_BASE.trim();
        while(base.endsWith("/"))base=base.substring(0,base.length()-1);
        HttpURLConnection c=(HttpURLConnection)new URL(base+"/wp-json/nenotv/v1/"+path).openConnection();
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
        if(text.trim().isEmpty())throw new IOException("HTTP "+code);
        JSONObject out=new JSONObject(text);
        if(code<200||code>=300||!out.optBoolean("ok",true))throw new IOException(out.optString("message",out.optString("error","HTTP "+code)));
        JSONObject entitlement=out.optJSONObject("entitlement");
        store.applyServer(entitlement==null?out:entitlement);
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
