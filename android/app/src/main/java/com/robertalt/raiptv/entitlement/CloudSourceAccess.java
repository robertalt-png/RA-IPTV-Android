package com.nenotv.player.entitlement;

import android.content.Context;
import com.nenotv.player.storage.AccountLinkStore;
import com.nenotv.player.storage.EntitlementStore;

/** Source ownership belongs to the basic account, independently of Pro features. */
public final class CloudSourceAccess {
    private final Context context;
    private final String scope;
    private final boolean account;

    public CloudSourceAccess(Context context) {
        this.context = context.getApplicationContext();
        String identity = new AccountLinkStore(context).accountId();
        account = !identity.isEmpty();
        // Retain authenticated legacy Pro synchronisation until older links are renewed.
        scope = account ? identity : new EntitlementStore(context).cloudAccountScope();
    }
    public String scope() { return scope; }
    public String route(String path) { return (account ? "account/" : "") + path; }
    public boolean valid() {
        if (scope.isEmpty()) return false;
        if (account) return scope.equals(new AccountLinkStore(context).accountId());
        EntitlementStore store = new EntitlementStore(context);
        return new AccountLinkStore(context).accountId().isEmpty()
                && store.isPro() && scope.equals(store.cloudAccountScope());
    }
}
