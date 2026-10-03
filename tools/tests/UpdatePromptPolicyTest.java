package com.nenotv.player;
public final class UpdatePromptPolicyTest {
    static int checks;
    static void expect(boolean actual, boolean expected) { checks++; if(actual!=expected) throw new AssertionError("Check " + checks); }
    static void equal(long actual, long expected) { checks++; if(actual!=expected) throw new AssertionError("Check " + checks + ": " + actual + " != " + expected); }
    public static void main(String[] args) {
        long now=1_800_000_000_000L, day=UpdatePromptPolicy.DAY_MS, grace=UpdatePromptPolicy.GRACE_MS;
        expect(UpdatePromptPolicy.shouldPrompt(94,94),false);
        expect(UpdatePromptPolicy.shouldPrompt(94,93),false);
        expect(UpdatePromptPolicy.shouldPrompt(94,95),true);
        expect(UpdatePromptPolicy.shouldPrompt(94,95),true); // Dismissal cannot suppress the next launch.
        equal(UpdatePromptPolicy.firstAvailableAt(94,0,0,95,null,now),now);
        equal(UpdatePromptPolicy.firstAvailableAt(94,0,0,95,10,now),now-10*day);
        equal(UpdatePromptPolicy.firstAvailableAt(94,0,0,95,-1,now),now);
        equal(UpdatePromptPolicy.firstAvailableAt(94,95,now-59*day,96,0,now),now-59*day);
        equal(UpdatePromptPolicy.firstAvailableAt(94,95,now-59*day,96,null,now),now-59*day);
        equal(UpdatePromptPolicy.firstAvailableAt(94,95,now-10*day,95,20,now),now-20*day);
        equal(UpdatePromptPolicy.firstAvailableAt(95,95,now-grace,96,0,now),now);
        equal(UpdatePromptPolicy.firstAvailableAt(95,95,now-grace,95,60,now),0);
        expect(UpdatePromptPolicy.basicOnly(94,95,now,now+grace-1),false);
        expect(UpdatePromptPolicy.basicOnly(94,95,now,now+grace),true);
        expect(UpdatePromptPolicy.basicOnly(94,95,now,now+grace+day),true);
        expect(UpdatePromptPolicy.basicOnly(95,95,now,now+grace),false);
        expect(UpdatePromptPolicy.basicOnly(96,95,now,now+grace),false);
        expect(UpdatePromptPolicy.basicOnly(94,0,0,now+grace),false);
        expect(UpdatePromptPolicy.basicOnly(94,95,0,now+grace),false);
        expect(UpdatePromptPolicy.basicOnly(94,95,now,now-1),false);
        equal(UpdatePromptPolicy.daysRemaining(now,now),60);
        equal(UpdatePromptPolicy.daysRemaining(now,now+grace-1),1);
        equal(UpdatePromptPolicy.daysRemaining(now,now+grace),0);
        equal(UpdatePromptPolicy.daysRemaining(now,now+grace+day),0);
        equal(UpdatePromptPolicy.daysRemaining(now,now-day),60);
        System.out.println(checks + " update notification/deadline policy checks passed");
    }
}
