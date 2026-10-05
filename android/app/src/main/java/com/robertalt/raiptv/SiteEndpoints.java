package com.nenotv.player;

public final class SiteEndpoints {
    public static final String WEBSITE = "https://sunnyiptv.com";
    public static final String DEMO_URL = WEBSITE + "/sunnyiptv-demo.m3u";

    private SiteEndpoints() {}

    public static String privacyUrl(String language) {
        return WEBSITE + ("nl".equals(language) ? "/language/nl/privacybeleid/"
                : "de".equals(language) ? "/language/de/datenschutz/" : "/privacy/");
    }

    public static boolean isPairingHost(String host) {
        return "sunnyiptv.com".equals(host) || "nenotv.com".equals(host);
    }

    public static boolean isDemoUrl(String url) {
        return DEMO_URL.equals(url)
                || "https://sunnyiptv.com/nenotv-demo.m3u".equals(url)
                || "https://nenotv.com/nenotv-demo.m3u".equals(url)
                || "nenotv://demo/v1".equals(url);
    }
}
