package com.nenotv.player.entitlement;

import android.content.Context;
import android.net.Uri;
import android.os.Build;
import android.os.SystemClock;
import java.io.IOException;
import org.json.JSONObject;

public final class PairingClient {
    private final EntitlementClient client;
    public PairingClient(Context context){client=new EntitlementClient(context);}
    PairingClient(Context context,String base){client=new EntitlementClient(context,base);}

    public static final class Session {
        public final String code,url;
        public final long deadline;
        public final int pollSeconds;
        private final String proof;
        Session(JSONObject response)throws Exception{
            code=response.optString("code","");proof=response.optString("poll_token","");
            url=response.optString("verification_url","");
            int duration=response.optInt("expires_in",0);
            Uri uri=Uri.parse(url);
            if(!code.matches("[A-F0-9]{10}")||!proof.matches("[a-f0-9]{64}")||duration<1||duration>600
                ||!"https".equals(uri.getScheme())||!"nenotv.com".equals(uri.getHost())||uri.getUserInfo()!=null
                ||(uri.getPort()!=-1&&uri.getPort()!=443)||!"/nenotv-pair/".equals(uri.getPath())
                ||!code.equals(uri.getQueryParameter("code")))throw new IOException("Invalid pairing response");
            deadline=SystemClock.elapsedRealtime()+duration*1000L;
            pollSeconds=Math.max(5,Math.min(30,response.optInt("poll_interval",5)));
        }
        public String displayCode(){return code.substring(0,5)+"-"+code.substring(5);}
        public boolean expired(){return SystemClock.elapsedRealtime()>=deadline;}
        JSONObject payload()throws Exception{return new JSONObject().put("code",code).put("poll_token",proof);}
    }

    public Session start()throws Exception{
        JSONObject body=new JSONObject().put("device_name",Build.MANUFACTURER+" "+Build.MODEL);
        return new Session(client.request("pairing/start",body));
    }
    public String status(Session session)throws Exception{
        if(session.expired())return "expired";
        JSONObject response=client.request("pairing/status",session.payload());
        String state=response.optString("state","");
        if(!java.util.Arrays.asList("pending","complete","expired","cancelled").contains(state))throw new IOException("Invalid pairing state");
        if("complete".equals(state)){
            JSONObject entitlement=response.optJSONObject("entitlement");
            if(entitlement==null||!java.util.Arrays.asList("pro","pro_trial","trial").contains(entitlement.optString("level"))
                ||!java.util.Arrays.asList("active","trial_active").contains(entitlement.optString("status")))throw new IOException("Missing active pairing entitlement");
            client.applyEntitlement(response);
        }
        return state;
    }
    public void cancel(Session session)throws Exception{
        if(!session.expired())client.request("pairing/cancel",session.payload());
    }
}
