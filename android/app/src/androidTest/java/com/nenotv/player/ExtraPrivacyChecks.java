package com.nenotv.player;

import android.content.Context;
import android.content.SharedPreferences;
import com.nenotv.player.storage.*;
import java.lang.reflect.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/** Synthetic pending clients only: no Google initialization or model download. */
public final class ExtraPrivacyChecks {
    private static void check(boolean ok, String message) { if (!ok) throw new AssertionError(message); }
    public static void run(Context c) throws Exception {
        Map<String,Map<String,?>> before = new HashMap<>();
        for (String name : new String[]{ExtraPrivacyStore.PREFS, "sunnyiptv_family_v1", "nenotv_settings", "nenotv_entitlement", "nenotv_account_link_v1"})
            before.put(name, new HashMap<>(c.getSharedPreferences(name, Context.MODE_PRIVATE).getAll()));
        AtomicInteger revoked = new AtomicInteger();
        Runnable observer = revoked::incrementAndGet;
        ExtraPrivacySession.addListener(observer);
        try {
            c.getSharedPreferences("sunnyiptv_family_v1", Context.MODE_PRIVATE).edit().clear().commit();
            c.getSharedPreferences("nenotv_entitlement", Context.MODE_PRIVATE).edit().putString("level", "PRO").putLong("expires_at", 0L).commit();
            InfoTranslator.init(c);
            SettingsStore.setParentalPin(c, "2468");
            AccountLinkStore account = new AccountLinkStore(c);
            String first = String.join("", Collections.nCopies(64, "a"));
            String second = String.join("", Collections.nCopies(64, "b"));
            for (String change : new String[]{"under_13", "unknown", "family", "logout", "account_logout", "account_switch"}) {
                FamilyStore.setActive(c, false, "2468");
                account.apply(new org.json.JSONObject().put("status", "active").put("kind", "account").put("account_id", first));
                ExtraPrivacyStore.choose(c, "13_plus");
                check(NenoTVCastOptionsProvider.receiverApplicationId().equals(new NenoTVCastOptionsProvider().getCastOptions(c).getReceiverApplicationId()), "Adult Cast receiver unavailable");
                long same = ExtraPrivacySession.generation();
                account.apply(new org.json.JSONObject().put("status", "active").put("kind", "account").put("account_id", first));
                check(same == ExtraPrivacySession.generation() && ExtraPrivacyStore.allowsSdk(c), "Same-account refresh revoked privacy choice");
                long old = ExtraPrivacySession.generation(); int count = revoked.get();
                Object request = pendingRequest();
                if ("family".equals(change)) check(FamilyStore.setActive(c, true, "2468"), "Family transition rejected");
                else if ("logout".equals(change)) ExtraPrivacyStore.clear(c);
                else if ("account_logout".equals(change)) account.clear();
                else if ("account_switch".equals(change)) account.apply(new org.json.JSONObject().put("status", "active").put("kind", "account").put("account_id", second));
                else ExtraPrivacyStore.choose(c, change);
                check(revoked.get() == count + 1 && !ExtraPrivacyStore.allowsSdk(c), "Privacy change did not revoke " + change);
                String receiver = new NenoTVCastOptionsProvider().getCastOptions(c).getReceiverApplicationId();
                check(receiver == null || receiver.isEmpty(), "Restricted Cast options discover receivers");
                check(!ExtraPrivacySession.run(old, () -> { throw new AssertionError("Old task ran"); }), "Old generation accepted");
                if (request != null) verifyRequest(request);
            }
        } finally {
            ExtraPrivacySession.removeListener(observer);
            ExtraPrivacySession.invalidate();
            for (Map.Entry<String,Map<String,?>> row : before.entrySet())
                FamilyChecks.restore(c.getSharedPreferences(row.getKey(), Context.MODE_PRIVATE), row.getValue());
            SettingsStore.lockAdults();
        }
    }
    private static Object[] pendingRequest() throws Exception {
        Class<?> impl;
        try { impl = Class.forName("com.nenotv.player.proextras.ProInfoTranslator"); }
        catch (ClassNotFoundException light) { return null; }
        Class<?> type = Class.forName(impl.getName() + "$Request");
        Constructor<?> constructor = type.getDeclaredConstructor(String.class, String.class, InfoTranslator.Callback.class);
        constructor.setAccessible(true);
        AtomicInteger callbacks = new AtomicInteger(), closed = new AtomicInteger();
        AtomicReference<String> result = new AtomicReference<>();
        Object request = constructor.newInstance("Original QA", "nl", (InfoTranslator.Callback) text -> { callbacks.incrementAndGet(); result.set(text); });
        for (String name : new String[]{"identifier", "translator"}) {
            Field field = type.getDeclaredField(name); field.setAccessible(true);
            Object client = Proxy.newProxyInstance(field.getType().getClassLoader(), new Class<?>[]{field.getType()}, (proxy, method, args) -> {
                if ("close".equals(method.getName())) { closed.incrementAndGet(); return null; }
                throw new AssertionError("Unexpected SDK call " + method.getName());
            });
            field.set(request, client);
        }
        Field pending = impl.getDeclaredField("pending"); pending.setAccessible(true);
        synchronized (ExtraPrivacySession.class) { ((Set<Object>) pending.get(null)).add(request); }
        return new Object[]{request, callbacks, closed, result};
    }
    private static void verifyRequest(Object value) throws Exception {
        Object[] test = (Object[]) value; Object request = test[0];
        AtomicInteger callbacks = (AtomicInteger) test[1], closed = (AtomicInteger) test[2];
        check(callbacks.get() == 1 && closed.get() == 2 && "Original QA".equals(((AtomicReference<?>) test[3]).get()), "Pending translation not closed exactly once");
        Method advance = request.getClass().getDeclaredMethod("advance", Runnable.class); advance.setAccessible(true);
        advance.invoke(request, (Runnable) () -> { throw new AssertionError("Cancelled translation continued"); });
        Method finish = request.getClass().getDeclaredMethod("finish", String.class); finish.setAccessible(true);
        finish.invoke(request, "Late result");
        check(callbacks.get() == 1 && closed.get() == 2, "Late callback closed or delivered twice");
    }
}
