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
            if(!new com.nenotv.player.storage.AccountLinkStore(this).linked())startActivity(new Intent(this,PairingActivity.class).putExtra("first_run",true).addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT));
            else startActivity(new Intent(this,WebsiteSetupActivity.class).addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT));
        }
        finish();
    }
}
