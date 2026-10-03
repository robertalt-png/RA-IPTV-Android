package com.nenotv.player;
public final class UpdatePromptPolicyTest {
    static void expect(boolean actual, boolean expected) { if(actual!=expected) throw new AssertionError(); }
    public static void main(String[] args) {
        long now=200000000L;
        expect(UpdatePromptPolicy.shouldPrompt(94,94,0,0,now),false);
        expect(UpdatePromptPolicy.shouldPrompt(94,93,0,0,now),false);
        expect(UpdatePromptPolicy.shouldPrompt(94,95,0,0,now),true);
        expect(UpdatePromptPolicy.shouldPrompt(94,95,95,now-1000,now),false);
        expect(UpdatePromptPolicy.shouldPrompt(94,96,95,now-1000,now),true);
        expect(UpdatePromptPolicy.shouldPrompt(94,95,95,now-UpdatePromptPolicy.REMIND_AFTER_MS,now),true);
        expect(UpdatePromptPolicy.shouldPrompt(94,95,95,now+1000,now),true);
        System.out.println("7 update notification policy checks passed");
    }
}
