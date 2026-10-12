package com.nenotv.player;

import android.content.Context;
import androidx.media3.datasource.DefaultDataSource;
import androidx.media3.datasource.DefaultHttpDataSource;
import androidx.media3.datasource.HttpDataSource;
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory;
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy;
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy;
import com.nenotv.player.model.MediaEntry;

public final class DemoSource {
    public static final String URL = "nenotv://demo/v1";
    public static final int LIVE_COUNT = 0;
    public static final int FILM_COUNT = 3;

    public static final String PLAYLIST = "#EXTM3U\n"
            + "#EXTINF:-1 tvg-id=\"film-46670912\" tvg-logo=\"https://thumb.wikimedia.org/wikipedia/commons/thumb/8/8b/Caminandes%2C_Gran_Dillama_-_Blender_Foundation.webm/500px--Caminandes%2C_Gran_Dillama_-_Blender_Foundation.webm.jpg\" group-title=\"Open films\",Caminandes: Gran Dillama\n"
            + "https://upload.wikimedia.org/wikipedia/commons/transcoded/8/8b/Caminandes%2C_Gran_Dillama_-_Blender_Foundation.webm/Caminandes%2C_Gran_Dillama_-_Blender_Foundation.webm.480p.vp9.webm\n"
            + "#EXTINF:-1 tvg-id=\"film-29654117\" tvg-logo=\"https://thumb.wikimedia.org/wikipedia/commons/thumb/d/d0/Caminandes-_Llama_Drama_-_Short_Movie.ogv/500px--Caminandes-_Llama_Drama_-_Short_Movie.ogv.jpg\" group-title=\"Open films\",Caminandes: Llama Drama\n"
            + "https://upload.wikimedia.org/wikipedia/commons/transcoded/d/d0/Caminandes-_Llama_Drama_-_Short_Movie.ogv/Caminandes-_Llama_Drama_-_Short_Movie.ogv.480p.vp9.webm\n"
            + "#EXTINF:-1 tvg-id=\"film-52325031\" tvg-logo=\"https://thumb.wikimedia.org/wikipedia/commons/thumb/a/ab/Caminandes_3_-_Llamigos_-_Blender_Animated_Short.webm/500px--Caminandes_3_-_Llamigos_-_Blender_Animated_Short.webm.jpg\" group-title=\"Open films\",Caminandes 3 - Llamigos\n"
            + "https://upload.wikimedia.org/wikipedia/commons/transcoded/a/ab/Caminandes_3_-_Llamigos_-_Blender_Animated_Short.webm/Caminandes_3_-_Llamigos_-_Blender_Animated_Short.webm.480p.vp9.webm\n";

    private static final DemoItem[] ITEMS = new DemoItem[] {
            new DemoItem(
                    "film-46670912",
                    "2013",
                    "Blender Foundation",
                    "CC BY-SA 3.0",
                    "https://creativecommons.org/licenses/by-sa/3.0",
                    "https://commons.wikimedia.org/wiki/File:Caminandes,_Gran_Dillama_-_Blender_Foundation.webm"
            ),
            new DemoItem(
                    "film-29654117",
                    "2013",
                    "Pablo Vazquez, Beorn Leonard, and Francesco Siddi. Music and Sound by Jan Morgenstern",
                    "CC BY 3.0",
                    "https://creativecommons.org/licenses/by/3.0",
                    "https://commons.wikimedia.org/wiki/File:Caminandes-_Llama_Drama_-_Short_Movie.ogv"
            ),
            new DemoItem(
                    "film-52325031",
                    "2016",
                    "Blender",
                    "CC BY 3.0",
                    "https://creativecommons.org/licenses/by/3.0",
                    "https://commons.wikimedia.org/wiki/File:Caminandes_3_-_Llamigos_-_Blender_Animated_Short.webm"
            )
    };

    public static void decorate(MediaEntry e) {
        if (e == null) return;
        for (DemoItem item : ITEMS) {
            if (item.id.equals(e.tvgId)) {
                e.type = "vod";
                e.year = item.year;
                e.plot = item.credit + " | " + item.license + " | " + item.licenseUrl
                        + " | Source: " + item.source
                        + " | Commercial reuse allowed with attribution/license terms. Unmodified Wikimedia video derivative.";
                return;
            }
        }
    }

    public static boolean isEntry(MediaEntry e) {
        return e != null && e.url != null && PLAYLIST.contains("\n" + e.url + "\n");
    }

    public static DefaultMediaSourceFactory mediaSourceFactory(Context c, MediaEntry e) {
        DefaultMediaSourceFactory factory = new DefaultMediaSourceFactory(c);
        if (isEntry(e)) {
            DefaultHttpDataSource.Factory http = new DefaultHttpDataSource.Factory()
                    .setUserAgent("SunnyIPTV/0.14.46 (https://sunnyiptv.com; info@sunnyiptv.com) Android Media3");
            factory.setDataSourceFactory(new DefaultDataSource.Factory(c, http));
            factory.setLoadErrorHandlingPolicy(new DefaultLoadErrorHandlingPolicy(6) {
                @Override
                public long getRetryDelayMsFor(LoadErrorHandlingPolicy.LoadErrorInfo info) {
                    Throwable cause = info.exception;
                    while (cause != null) {
                        if (cause instanceof HttpDataSource.InvalidResponseCodeException) {
                            int status = ((HttpDataSource.InvalidResponseCodeException) cause).responseCode;
                            if (status == 429 || status == 503) return 15000L * Math.min(info.errorCount, 4);
                        }
                        cause = cause.getCause();
                    }
                    return super.getRetryDelayMsFor(info);
                }
            });
        }
        return factory;
    }

    private DemoSource() {}

    private static final class DemoItem {
        final String id;
        final String year;
        final String credit;
        final String license;
        final String licenseUrl;
        final String source;

        DemoItem(String id, String year, String credit, String license, String licenseUrl, String source) {
            this.id = id;
            this.year = year;
            this.credit = credit;
            this.license = license;
            this.licenseUrl = licenseUrl;
            this.source = source;
        }
    }
}
