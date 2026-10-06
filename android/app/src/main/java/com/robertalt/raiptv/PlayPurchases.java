package com.nenotv.player;

import android.app.Activity;
import com.android.billingclient.api.*;
import com.nenotv.player.entitlement.EntitlementClient;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * SunnyIPTV Pro through Google Play Billing. The app never grants Pro itself: every purchase goes to
 * the server (entitlement/play), which verifies it with Google, acknowledges it and returns the entitlement.
 * A purchase the server could not confirm yet is sent again by {@link #restore()} the next time.
 */
public final class PlayPurchases implements PurchasesUpdatedListener {
    public static final List<String> SUBS = Arrays.asList("sunnyiptv_pro_solo", "sunnyiptv_pro_multi");
    public static final List<String> INAPP = Arrays.asList("sunnyiptv_pro_solo_lifetime", "sunnyiptv_pro_multi_lifetime");

    public interface Listener {
        /** Products in display order, or an empty list with an error key (UiText). */
        void products(List<ProductDetails> products, String error);
        /** Result of a purchase or restore: "purchase_done", "purchase_pending", "purchase_cancelled", "purchase_unconfirmed" or "play_unavailable". */
        void purchase(String result);
    }

    private final Activity activity;
    private final Listener listener;
    private final ExecutorService exec = Executors.newSingleThreadExecutor();
    private final BillingClient client;
    private boolean ready;

    public PlayPurchases(Activity activity, Listener listener) {
        this.activity = activity;
        this.listener = listener;
        client = BillingClient.newBuilder(activity.getApplicationContext())
                .setListener(this)
                .enablePendingPurchases(PendingPurchasesParams.newBuilder().enableOneTimeProducts().build())
                .build();
    }

    private void connected(Runnable then) {
        if (ready && client.isReady()) { then.run(); return; }
        client.startConnection(new BillingClientStateListener() {
            @Override public void onBillingSetupFinished(BillingResult result) {
                ready = result.getResponseCode() == BillingClient.BillingResponseCode.OK;
                if (ready) then.run();
                else ui(() -> listener.products(Collections.emptyList(), "play_unavailable"));
            }
            @Override public void onBillingServiceDisconnected() { ready = false; }
        });
    }

    private void ui(Runnable r) { if (!activity.isFinishing() && !activity.isDestroyed()) activity.runOnUiThread(r); }

    /** Subscriptions first (Solo, Multi), then the one-time lifetime products. */
    public void loadProducts() {
        connected(() -> query(BillingClient.ProductType.SUBS, SUBS, subs ->
                query(BillingClient.ProductType.INAPP, INAPP, inapp -> {
                    List<ProductDetails> all = new ArrayList<>(subs);
                    all.addAll(inapp);
                    ui(() -> listener.products(all, all.isEmpty() ? "pro_coming_soon" : ""));
                })));
    }

    private interface Found { void found(List<ProductDetails> list); }

    private void query(String type, List<String> ids, Found then) {
        List<QueryProductDetailsParams.Product> products = new ArrayList<>();
        for (String id : ids) products.add(QueryProductDetailsParams.Product.newBuilder().setProductId(id).setProductType(type).build());
        client.queryProductDetailsAsync(QueryProductDetailsParams.newBuilder().setProductList(products).build(), (result, details) -> {
            List<ProductDetails> list = new ArrayList<>();
            if (result.getResponseCode() == BillingClient.BillingResponseCode.OK && details != null) list.addAll(details.getProductDetailsList());
            list.sort(Comparator.comparingInt(d -> ids.indexOf(d.getProductId())));
            then.found(list);
        });
    }

    /** Price line for a product: "€8,99 / jaar" or "€17,99 · levenslang". */
    public static String price(ProductDetails d, String perYear, String lifetime) {
        if (BillingClient.ProductType.SUBS.equals(d.getProductType())) {
            List<ProductDetails.SubscriptionOfferDetails> offers = d.getSubscriptionOfferDetails();
            if (offers == null || offers.isEmpty()) return "";
            List<ProductDetails.PricingPhase> phases = offers.get(0).getPricingPhases().getPricingPhaseList();
            if (phases.isEmpty()) return "";
            ProductDetails.PricingPhase base = phases.get(phases.size() - 1);
            return base.getFormattedPrice() + ("P1Y".equals(base.getBillingPeriod()) ? " / " + perYear : "");
        }
        ProductDetails.OneTimePurchaseOfferDetails one = d.getOneTimePurchaseOfferDetails();
        return one == null ? "" : one.getFormattedPrice() + " · " + lifetime;
    }

    public static int devices(ProductDetails d) { return d.getProductId().contains("multi") ? 5 : 1; }

    public void buy(ProductDetails d) {
        connected(() -> {
            BillingFlowParams.ProductDetailsParams.Builder p = BillingFlowParams.ProductDetailsParams.newBuilder().setProductDetails(d);
            if (BillingClient.ProductType.SUBS.equals(d.getProductType())) {
                List<ProductDetails.SubscriptionOfferDetails> offers = d.getSubscriptionOfferDetails();
                if (offers == null || offers.isEmpty()) { ui(() -> listener.purchase("play_unavailable")); return; }
                p.setOfferToken(offers.get(0).getOfferToken());
            }
            BillingFlowParams params = BillingFlowParams.newBuilder().setProductDetailsParamsList(Collections.singletonList(p.build())).build();
            activity.runOnUiThread(() -> {
                BillingResult r = client.launchBillingFlow(activity, params);
                if (r.getResponseCode() != BillingClient.BillingResponseCode.OK) listener.purchase("play_unavailable");
            });
        });
    }

    @Override public void onPurchasesUpdated(BillingResult result, List<Purchase> purchases) {
        int code = result.getResponseCode();
        if (code == BillingClient.BillingResponseCode.USER_CANCELED) { ui(() -> listener.purchase("purchase_cancelled")); return; }
        if (code == BillingClient.BillingResponseCode.ITEM_ALREADY_OWNED) { restore(); return; }
        if (code != BillingClient.BillingResponseCode.OK || purchases == null) { ui(() -> listener.purchase("purchase_unconfirmed")); return; }
        confirm(purchases, true);
    }

    /** Sends purchases Google still lists (paid but maybe not yet confirmed by our server) again. Silent when there are none. */
    public void restore() {
        connected(() -> client.queryPurchasesAsync(QueryPurchasesParams.newBuilder().setProductType(BillingClient.ProductType.SUBS).build(), (r1, subs) ->
                client.queryPurchasesAsync(QueryPurchasesParams.newBuilder().setProductType(BillingClient.ProductType.INAPP).build(), (r2, inapp) -> {
                    List<Purchase> all = new ArrayList<>();
                    if (subs != null) all.addAll(subs);
                    if (inapp != null) all.addAll(inapp);
                    if (!all.isEmpty()) confirm(all, false);
                })));
    }

    private void confirm(List<Purchase> purchases, boolean report) {
        exec.execute(() -> {
            String outcome = "";
            for (Purchase p : purchases) {
                if (p.getPurchaseState() == Purchase.PurchaseState.PENDING) { if (outcome.isEmpty()) outcome = "purchase_pending"; continue; }
                if (p.getPurchaseState() != Purchase.PurchaseState.PURCHASED) continue;
                for (String product : p.getProducts()) {
                    if (!SUBS.contains(product) && !INAPP.contains(product)) continue;
                    try { new EntitlementClient(activity).play(product, p.getPurchaseToken()); outcome = "purchase_done"; }
                    catch (Exception notYet) { if (!"purchase_done".equals(outcome)) outcome = "purchase_unconfirmed"; }
                }
            }
            final String shown = outcome;
            if (report || "purchase_done".equals(shown)) ui(() -> listener.purchase(shown.isEmpty() ? "purchase_unconfirmed" : shown));
        });
    }

    public void close() { exec.shutdown(); try { client.endConnection(); } catch (Exception ignored) {} }
}
