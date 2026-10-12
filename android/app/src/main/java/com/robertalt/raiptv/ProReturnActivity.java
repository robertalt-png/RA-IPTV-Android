package com.nenotv.player;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import com.nenotv.player.storage.AccountLinkStore;

/** Navigation only: approval still needs the private device proof. */
public final class ProReturnActivity extends Activity {
    @Override public void onCreate(Bundle saved){
        super.onCreate(saved);
        boolean stop=getIntent().getData()!=null&&"1".equals(getIntent().getData().getQueryParameter("stop"));
        boolean home=getIntent().getData()!=null&&"1".equals(getIntent().getData().getQueryParameter("home"));
        Class<?> target=stop?(home?MainActivity.class:AccountActivity.class):PairingActivity.class;
        startActivity(new Intent(this,target).putExtra("pro_activation",true).putExtra("trial_activation",home).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP|Intent.FLAG_ACTIVITY_SINGLE_TOP));
        finish();
    }
}
