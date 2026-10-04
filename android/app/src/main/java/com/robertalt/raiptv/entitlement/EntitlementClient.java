package com.nenotv.player.entitlement;

import android.content.Context;
import com.nenotv.player.BuildConfig;
import com.nenotv.player.storage.EntitlementStore;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

public final class EntitlementClient {
    private final EntitlementStore store;
    private final String base;
    public EntitlementClient(Context c){this(c,"https://nenotv.com");}
    EntitlementClient(Context c,String base){store=new EntitlementStore(c);this.base=base;}

    public JSONObject refresh() throws Exception {
        if(store.isPro()&&!store.cloudAccountScope().isEmpty()){
            try{return post("catalog/access",new JSONObject().put("account_scope",store.cloudAccountScope()));}
            catch(ServiceException e){if(!java.util.Arrays.asList("pro_not_live","device_not_linked","pro_inactive","source_account_changed","device_key_mismatch","invalid_device").contains(e.code))throw e;}
            catch(MissingRoute e){/* Older websites continue through the original bridge. */}
        }
        return post("entitlement/refresh",new JSONObject());
    }
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
        JSONObject out=request(path,body);
        applyEntitlement(out);
        return out;
    }

    void applyEntitlement(JSONObject out)throws IOException{
        JSONObject entitlement=out.optJSONObject("entitlement");
        if(entitlement==null)entitlement=out;
        String level=entitlement.optString("level","");
        if(!java.util.Arrays.asList("free","pro","pro_trial","trial").contains(level))
            throw new IOException("Unexpected entitlement response");
        store.applyServer(entitlement);
    }

    JSONObject request(String path,JSONObject body)throws Exception{
        return request(path,body,65536);
    }

    JSONObject request(String path,JSONObject body,int responseLimit)throws Exception{
        body.put("device_id",store.deviceId());
        body.put("public_device_id",store.publicDeviceId());
        body.put("device_key",store.deviceKey());
        body.put("platform","android");
        body.put("app_version",BuildConfig.VERSION_NAME);

        JSONObject out;
        try{
            out=postUrl(base+"/wp-json/nenotv/v1/"+path,body,responseLimit);
        }catch(MissingRoute e){
            // Retry only an absent REST route, never a possibly consumed activation.
            out=postUrl(base+"/index.php?rest_route=/nenotv/v1/"+path,body,responseLimit);
        }

        return out;
    }

    private static final class MissingRoute extends IOException {}

    public static final class ServiceException extends IOException {
        public final String code;
        ServiceException(String code,int status){
            super("NenoTV account request failed (HTTP "+status+")");
            this.code=code.matches("[a-z_]{1,64}")?code:"account_unavailable";
        }
    }

    private JSONObject postUrl(String url,JSONObject body,int responseLimit) throws Exception {
        HttpURLConnection c=(HttpURLConnection)new URL(url).openConnection();
        try{
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
        String text=read(in,responseLimit);
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

        if(code==404&&"rest_no_route".equals(out.optString("code")))throw new MissingRoute();
        if(code<200||code>=300||!out.optBoolean("ok",false))
            throw new ServiceException(out.optString("error","account_unavailable"),code);
        return out;
        }finally{c.disconnect();}
    }

    private String read(InputStream in,int responseLimit)throws IOException{
        if(in==null)return "";
        try(InputStream r=in;ByteArrayOutputStream b=new ByteArrayOutputStream()){
            byte[] chunk=new byte[4096];
            int count;
            while((count=r.read(chunk,0,Math.min(chunk.length,responseLimit-b.size()+1)))!=-1){
                if(count>responseLimit-b.size())throw new IOException("Account response too large");
                b.write(chunk,0,count);
            }
            return new String(b.toByteArray(),StandardCharsets.UTF_8);
        }
    }
}
