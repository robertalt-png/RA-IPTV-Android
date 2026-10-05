package com.nenotv.player;
import android.app.Activity;
import android.os.Bundle;
import android.content.Intent;
import android.net.Uri;
public final class SetupReturnActivity extends Activity {
    @Override public void onCreate(Bundle state){
        super.onCreate(state);
        if(com.nenotv.player.storage.FamilyStore.active(this)){FamilyUi.blocked(this);finish();return;}
        Uri uri=getIntent().getData();
        if(uri!=null&&"nenotv".equals(uri.getScheme())&&"setup".equals(uri.getHost())){
            if(!new com.nenotv.player.storage.EntitlementStore(this).isPro())startActivity(new Intent(this,PairingActivity.class).addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT));
            else if(new com.nenotv.player.storage.SecureProfileStore(this).exists())startActivity(new Intent(this,MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP|Intent.FLAG_ACTIVITY_SINGLE_TOP));
            else startActivity(new Intent(this,ProfileActivity.class).putExtra("website_first",true).putExtra("device_entry","device".equals(uri.getQueryParameter("entry"))));
        }
        finish();
    }
}
