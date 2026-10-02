package com.nenotv.player.net;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

public final class StreamingJsonArrayTest {
    private static int tests;
    private interface Test { void run() throws Exception; }

    private static void expectFailure(Test test) throws Exception {
        boolean failed = false;
        try { test.run(); } catch (Exception expected) { failed = true; }
        if (!failed) throw new AssertionError("Invalid or cancelled input was accepted");
        tests++;
    }

    private static ByteArrayInputStream input(String text) {
        return new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8));
    }

    public static void main(String[] args) throws Exception {
        if (StreamingJsonArray.parse(input("[]"), item -> {}) != 0) throw new AssertionError("Empty list");
        tests++;
        StringBuilder json = new StringBuilder("[");
        for (int i = 0; i < 50000; i++) {
            if (i > 0) json.append(',');
            json.append("{\"id\":").append(i).append(",\"name\":\"Test stream\"}");
        }
        json.append(']');
        ByteArrayInputStream source = input(json.toString());
        int[] received = {0};
        long count = StreamingJsonArray.parse(source, item -> {
            if (item.get("id").getAsInt() != received[0]) throw new AssertionError("Order");
            if (received[0] == 0 && source.available() == 0) throw new AssertionError("Buffered entire list");
            received[0]++;
        });
        if (count != 50000 || received[0] != count) throw new AssertionError("Count");
        tests++;
        for (String bad : new String[]{"[{}", "[{},", "[null]", "[1]", "{}", "[{}]{}", "[{unquoted:1}]"}) {
            expectFailure(() -> StreamingJsonArray.parse(input(bad), item -> {}));
        }
        expectFailure(() -> StreamingJsonArray.parse(input("[{}]"), item -> { throw new IOException("Storage failed"); }));
        Thread.currentThread().interrupt();
        try {
            expectFailure(() -> StreamingJsonArray.parse(input("[{}]"), item -> { throw new AssertionError("Received after cancel"); }));
        } finally { Thread.interrupted(); }
        int[] cancelledCount = {0};
        try {
            expectFailure(() -> StreamingJsonArray.parse(input("[{},{},{}]"), item -> {
                cancelledCount[0]++;
                Thread.currentThread().interrupt();
            }));
        } finally { Thread.interrupted(); }
        if (cancelledCount[0] != 1) throw new AssertionError("Continued after cancel");
        System.out.println(tests + " streaming parser checks passed; 50000 records processed incrementally");
    }
}
