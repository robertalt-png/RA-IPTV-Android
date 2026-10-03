package com.nenotv.admin;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

public final class DashboardData {
    public int rangeDays;
    public String updatedAt = "";
    public double revenue;
    public int orders;
    public int failedOrders;
    public int visitors;
    public int pageviews;
    public int sessions;
    public int activePro;
    public int activeTrials;
    public boolean trialEnabled;
    public int appDevices;
    public int active24h;
    public int appErrors;
    public int avgLatencyMs;
    public String appVersion = "";
    public String lastSeenVersion = "";
    public String appBridge = "offline";
    public String siteStatus = "offline";
    public String database = "offline";
    public String cache = "offline";
    public String entitlementMode = "shadow";
    public boolean mollieTestMode;
    public boolean publicSales;
    public int alerts;
    public String currency = "EUR";
    public String ordersUrl = "https://nenotv.com/wp-admin/admin.php?page=wc-orders";
    public final List<DayPoint> daily = new ArrayList<>();

    public static final class DayPoint {
        public final String day;
        public final int visitors;
        public final int pageviews;
        public DayPoint(String day, int visitors, int pageviews) {
            this.day = day;
            this.visitors = visitors;
            this.pageviews = pageviews;
        }
    }

    public static DashboardData parse(JSONObject j) {
        DashboardData d = new DashboardData();
        d.rangeDays = j.optInt("range_days", 7);
        d.updatedAt = j.optString("updated_at", "");

        JSONObject site = j.optJSONObject("site");
        if (site != null) d.siteStatus = site.optString("status", "offline");

        JSONObject access = j.optJSONObject("access");
        if (access != null) {
            d.activePro = access.optInt("active_pro", 0);
            d.activeTrials = access.optInt("active_trials", 0);
            d.trialEnabled = access.optBoolean("trial_enabled", false);
        }

        JSONObject detail = j.optJSONObject("analytics_detail");
        if (detail != null) {
            JSONObject summary = detail.optJSONObject("summary");
            if (summary != null) {
                d.visitors = summary.optInt("visitors", 0);
                d.pageviews = summary.optInt("pageviews", 0);
                d.sessions = summary.optInt("sessions", 0);
            }
            JSONArray daily = detail.optJSONArray("daily");
            if (daily != null) for (int i=0;i<daily.length();i++) {
                JSONObject p = daily.optJSONObject(i);
                if (p != null) d.daily.add(new DayPoint(p.optString("day"), p.optInt("visitors"), p.optInt("pageviews")));
            }
        }

        JSONObject commerce = j.optJSONObject("commerce_detail");
        if (commerce != null) {
            d.revenue = commerce.optDouble("revenue", 0);
            d.orders = commerce.optInt("orders", 0);
            d.failedOrders = commerce.optInt("failed", 0);
            d.currency = commerce.optString("currency", "EUR");
            d.mollieTestMode = commerce.optBoolean("mollie_test_mode", false);
            d.publicSales = commerce.optBoolean("public_sales_enabled", false);
        }

        JSONObject usage = j.optJSONObject("app_usage");
        if (usage != null) {
            d.appDevices = usage.optInt("total_devices", 0);
            d.active24h = usage.optInt("active_24h", 0);
            d.appErrors = usage.optInt("errors", 0);
            d.avgLatencyMs = usage.optInt("avg_latency_ms", 0);
        }

        JSONObject app = j.optJSONObject("app");
        if (app != null) {
            d.appBridge = app.optString("bridge_status", "offline");
            d.appVersion = app.optString("latest_version", "");
            d.lastSeenVersion = app.optString("latest_seen_version", "");
        }

        JSONObject system = j.optJSONObject("system");
        if (system != null) {
            d.database = system.optString("database", "offline");
            d.cache = system.optString("cache", "offline");
            d.entitlementMode = system.optString("entitlement_mode", "shadow");
        }

        JSONArray alerts = j.optJSONArray("alerts");
        d.alerts = alerts == null ? 0 : alerts.length();
        JSONObject links = j.optJSONObject("links");
        if (links != null) d.ordersUrl = links.optString("orders", d.ordersUrl);
        return d;
    }

    public boolean healthy() {
        return alerts == 0 && "online".equals(siteStatus) && "online".equals(appBridge) && "online".equals(database);
    }
}
