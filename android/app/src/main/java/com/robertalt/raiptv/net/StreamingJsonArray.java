package com.nenotv.player.net;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.Strictness;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.InterruptedIOException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.zip.GZIPInputStream;

public final class StreamingJsonArray {
    private static final Gson GSON = new Gson();
    private static final long MAX_BYTES = 100L * 1024 * 1024;
    private static final long MAX_TIME_NS = 120L * 1_000_000_000;

    public interface Receiver { void accept(JsonObject item) throws Exception; }

    private StreamingJsonArray() {}

    public static void checkCancelled() throws InterruptedIOException {
        if (Thread.currentThread().isInterrupted()) throw new InterruptedIOException("import_cancelled");
    }

    public static long read(String address, Receiver receiver) throws Exception {
        final NetworkBudget budget = new NetworkBudget();
        URL url = new URL(address);
        for (int redirects = 0; redirects <= 5; redirects++) {
            checkCancelled();
            budget.check();
            HttpURLConnection connection = (HttpURLConnection) url.openConnection();
            try {
                connection.setConnectTimeout(15000);
                connection.setReadTimeout(15000);
                connection.setInstanceFollowRedirects(false);
                connection.setRequestProperty("Accept", "application/json");
                connection.setRequestProperty("Accept-Encoding", "gzip");
                connection.setRequestProperty("User-Agent", "SunnyIPTV/0.13.2");
                int status = connection.getResponseCode();
                if (status == 301 || status == 302 || status == 303 || status == 307 || status == 308) {
                    String location = connection.getHeaderField("Location");
                    if (location == null) throw new IOException("redirect_without_location");
                    URL next = new URL(url, location);
                    if (!"http".equals(next.getProtocol()) && !"https".equals(next.getProtocol())) throw new IOException("invalid_redirect");
                    if ("https".equals(url.getProtocol()) && !"https".equals(next.getProtocol())) throw new IOException("insecure_redirect");
                    url = next;
                    continue;
                }
                if (status < 200 || status >= 300) throw new IOException("HTTP_" + status);
                try (InputStream raw = connection.getInputStream()) {
                    InputStream input = "gzip".equalsIgnoreCase(connection.getContentEncoding()) ? new GZIPInputStream(raw) : raw;
                    try (InputStream limited = new BoundedInput(input, budget)) {
                        return parse(limited, item->{
                            long started=System.nanoTime();
                            try{receiver.accept(item);}finally{budget.processingNs+=System.nanoTime()-started;}
                        });
                    }
                }
            } finally {
                connection.disconnect();
            }
        }
        throw new IOException("too_many_redirects");
    }

    public static long parse(InputStream input, Receiver receiver) throws Exception {
        try (JsonReader reader = new JsonReader(new InputStreamReader(input, StandardCharsets.UTF_8))) {
            reader.setStrictness(Strictness.STRICT);
            reader.beginArray();
            long count = 0;
            while (reader.hasNext()) {
                checkCancelled();
                JsonObject item = GSON.fromJson(reader, JsonObject.class);
                if (item == null) throw new IOException("invalid_import_item");
                receiver.accept(item);
                count++;
            }
            reader.endArray();
            if (reader.peek() != JsonToken.END_DOCUMENT) throw new IOException("trailing_import_data");
            checkCancelled();
            return count;
        }
    }

    private static final class NetworkBudget {
        final long started=System.nanoTime();
        long processingNs;
        void check() throws IOException {
            checkCancelled();
            if(System.nanoTime()-started-processingNs>MAX_TIME_NS)throw new IOException("import_timeout");
        }
    }
    private static final class BoundedInput extends FilterInputStream {
        private final NetworkBudget budget;
        private long bytes;

        BoundedInput(InputStream input, NetworkBudget budget) { super(input); this.budget = budget; }

        private void check(int count) throws IOException {
            checkCancelled();
            budget.check();
            if (count > 0) bytes += count;
            if (bytes > MAX_BYTES) throw new IOException("response_too_large");
        }

        @Override public int read() throws IOException {
            check(0);
            int value = in.read();
            check(value < 0 ? 0 : 1);
            return value;
        }

        @Override public int read(byte[] buffer, int offset, int length) throws IOException {
            check(0);
            int count = in.read(buffer, offset, length);
            check(count);
            return count;
        }
    }
}
