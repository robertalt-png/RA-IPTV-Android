package com.nenotv.player.provider;

import com.nenotv.player.core.CatchupUrls;
import com.nenotv.player.core.XtreamUrls;
import com.nenotv.player.net.HttpText;
import java.time.ZoneId;
import java.util.concurrent.ConcurrentHashMap;
import org.json.JSONObject;

/** The time zone an Xtream panel uses for catch-up start times (player_api server_info.timezone), cached per panel. */
public final class XtreamServerZone {
    private XtreamServerZone() {}
    private static final ConcurrentHashMap<String, ZoneId> ZONES = new ConcurrentHashMap<>();

    static void remember(String server, JSONObject playerApi) {
        if (playerApi == null) return;
        JSONObject info = playerApi.optJSONObject("server_info");
        ZoneId z = CatchupUrls.zone(info == null ? "" : info.optString("timezone", ""));
        if (z != null) ZONES.put(XtreamUrls.base(server), z);
    }

    /** Never null: falls back to the device zone when the panel does not say. Call off the main thread. */
    static ZoneId get(String server, String user, String pass) {
        String key = XtreamUrls.base(server);
        ZoneId cached = ZONES.get(key);
        if (cached != null) return cached;
        try { remember(server, new JSONObject(HttpText.get(XtreamUrls.api(server, user, pass, "", "")))); } catch (Exception ignored) {}
        cached = ZONES.get(key);
        return cached != null ? cached : ZoneId.systemDefault();
    }
}
