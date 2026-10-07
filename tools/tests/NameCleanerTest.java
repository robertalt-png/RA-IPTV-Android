package com.nenotv.player.core;

public final class NameCleanerTest {
    static int checks = 0;
    static void eq(String in, String want) { String got = NameCleaner.clean(in); if (!got.equals(want)) throw new AssertionError("'" + in + "' -> '" + got + "', want '" + want + "'"); checks++; }
    public static void main(String[] args) {
        eq("NPO 1 HD ⏺rec", "NPO 1 HD");
        eq("NPO 1 HD 🟠rec", "NPO 1 HD");
        eq("RTL 4 HD 🔴 REC", "RTL 4 HD");
        eq("SBS 6 ʳᵉᶜ", "SBS 6");
        eq("Veronica [REC]", "Veronica");
        eq("Ziggo Sport (rec)", "Ziggo Sport");
        eq("NL: NPO 2 ⏺️", "NL: NPO 2");
        eq("Discovery Channel", "Discovery Channel");
        eq("Recordings TV", "Recordings TV");
        eq("Rec Room Live", "Rec Room Live");
        eq("Wrecked", "Wrecked");
        eq("⏺rec", "⏺rec");
        eq(null, "");
        System.out.println("Name cleaner: " + checks + " checks passed");
    }
}
