package com.nenotv.player.proextras;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import com.google.android.gms.cast.framework.CastContext;
import com.nenotv.player.ExtraPrivacySession;
import com.nenotv.player.NenoTVCastOptionsProvider;
import com.nenotv.player.storage.ExtraPrivacyStore;

final class ProCastPrivacy {
    private static CastContext context;
    static { ExtraPrivacySession.addListener(ProCastPrivacy::revoke); }
    private ProCastPrivacy() {}
    static CastContext get(Context app) {
        synchronized (ExtraPrivacySession.class) {
            if (!ExtraPrivacyStore.allowsSdk(app)) return null;
            if (context == null) context = CastContext.getSharedInstance(app);
            context.setReceiverApplicationId(NenoTVCastOptionsProvider.receiverApplicationId());
            return context;
        }
    }
    private static void revoke() {
        Runnable stop = () -> {
            if (context == null) return;
            try { context.getSessionManager().endCurrentSession(true); } catch (Exception ignored) {}
            // Google documents a null receiver ID as disabling receiver discovery and launch.
            try { context.setReceiverApplicationId(null); } catch (Exception ignored) {}
        };
        if (Looper.myLooper() == Looper.getMainLooper()) stop.run();
        else new Handler(Looper.getMainLooper()).post(stop);
    }
}
