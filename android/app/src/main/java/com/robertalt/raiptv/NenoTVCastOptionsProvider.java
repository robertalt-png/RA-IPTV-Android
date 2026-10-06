package com.nenotv.player;

import android.content.Context;
import com.google.android.gms.cast.CastMediaControlIntent;
import com.google.android.gms.cast.framework.CastOptions;
import com.google.android.gms.cast.framework.OptionsProvider;
import com.google.android.gms.cast.framework.SessionProvider;
import java.util.List;

public final class NenoTVCastOptionsProvider implements OptionsProvider {
    public static String receiverApplicationId(){String id=BuildConfig.NENOTV_CAST_RECEIVER_ID;return id==null||id.trim().isEmpty()?CastMediaControlIntent.DEFAULT_MEDIA_RECEIVER_APPLICATION_ID:id.trim();}
    @Override public CastOptions getCastOptions(Context context){
        return new CastOptions.Builder()
            .setReceiverApplicationId(com.nenotv.player.storage.ExtraPrivacyStore.allowsSdk(context)?receiverApplicationId():null)
            .setEnableReconnectionService(false)
            .setResumeSavedSession(false)
            .setSessionTransferEnabled(false)
            .setStopReceiverApplicationWhenEndingSession(false)
            .build();
    }
    @Override public List<SessionProvider> getAdditionalSessionProviders(Context context){return null;}
}
