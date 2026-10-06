package com.nenotv.player;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

public final class ExtraPrivacySessionTest {
    private static void check(boolean ok, String message) { if (!ok) throw new AssertionError(message); }
    public static void main(String[] args) throws Exception {
        AtomicInteger stopped = new AtomicInteger(), calls = new AtomicInteger();
        Runnable listener = stopped::incrementAndGet;
        Runnable failure = () -> { throw new IllegalStateException("QA"); };
        ExtraPrivacySession.addListener(failure);
        ExtraPrivacySession.addListener(listener);
        ExtraPrivacySession.addListener(listener);
        long old = ExtraPrivacySession.generation();
        check(ExtraPrivacySession.run(old, calls::incrementAndGet), "Current request blocked");
        ExtraPrivacySession.invalidate();
        check(stopped.get() == 1, "Cleanup skipped or registered twice");
        check(!ExtraPrivacySession.run(old, calls::incrementAndGet) && calls.get() == 1, "Old callback ran after revocation");
        long next = ExtraPrivacySession.generation();
        check(ExtraPrivacySession.run(next, calls::incrementAndGet), "New session cannot restart");
        check(!ExtraPrivacySession.run(old, calls::incrementAndGet), "Old request revived in new session");
        ExtraPrivacySession.removeListener(listener);
        ExtraPrivacySession.removeListener(failure);
        ExtraPrivacySession.invalidate();
        check(stopped.get() == 1, "Removed owner retained");
        CountDownLatch running = new CountDownLatch(1), release = new CountDownLatch(1);
        long concurrent = ExtraPrivacySession.generation();
        Thread worker = new Thread(() -> ExtraPrivacySession.run(concurrent, () -> {
            running.countDown();
            try { check(release.await(5, TimeUnit.SECONDS), "Worker release timed out"); }
            catch (InterruptedException error) { throw new AssertionError(error); }
        }));
        worker.start();
        check(running.await(5, TimeUnit.SECONDS), "Worker did not start");
        Thread revoke = new Thread(ExtraPrivacySession::invalidate);
        revoke.start(); release.countDown();
        worker.join(5000); revoke.join(5000);
        check(!worker.isAlive() && !revoke.isAlive(), "Revocation deadlocked");
        check(!ExtraPrivacySession.run(concurrent, calls::incrementAndGet), "Concurrent old request survived");
        System.out.println("Extra privacy session: 10 checks passed");
    }
}
