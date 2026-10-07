import com.nenotv.player.core.NumberZap;
import com.nenotv.player.core.Reconnect;

public class ZappingReconnectTest {
    static int checks = 0;
    static void check(String name, boolean ok) {
        if (!ok) throw new AssertionError("FAILED: " + name);
        checks++;
    }

    public static void main(String[] a) {
        NumberZap z = new NumberZap();
        check("leading zero ignored", !z.add(0) && z.isEmpty());
        check("digit accepted", z.add(1) && z.add(2) && "12".equals(z.text()));
        check("zero after first digit", z.add(0) && "120".equals(z.text()));
        check("fourth digit", z.add(5) && z.full());
        check("fifth digit refused", !z.add(7) && "1205".equals(z.text()));
        check("take returns number", z.take() == 1205 && z.isEmpty());
        check("take empty is 0", z.take() == 0);
        check("invalid digit", !z.add(10) && !z.add(-1));

        int[] positions = {0, 0, 0, 0};
        check("position 1 is first", NumberZap.resolve(1, positions) == 0);
        check("position 4 is last", NumberZap.resolve(4, positions) == 3);
        check("position beyond list", NumberZap.resolve(5, positions) == -1);
        check("number 0 invalid", NumberZap.resolve(0, positions) == -1);
        check("shown position", NumberZap.shown(2, positions) == 3);

        int[] provider = {101, 0, 205, 7};
        check("provider number found", NumberZap.resolve(205, provider) == 2);
        check("provider small number", NumberZap.resolve(7, provider) == 3);
        check("position not used when provider numbers exist", NumberZap.resolve(2, provider) == -1);
        check("shown provider number", NumberZap.shown(0, provider) == 101);
        check("shown without number", NumberZap.shown(1, provider) == 0);
        check("empty list", NumberZap.resolve(1, new int[0]) == -1 && NumberZap.resolve(1, null) == -1);

        check("network failure retryable", Reconnect.retryable(Reconnect.IO_NETWORK_CONNECTION_FAILED, 50));
        check("timeout retryable", Reconnect.retryable(Reconnect.IO_NETWORK_CONNECTION_TIMEOUT, 9));
        check("behind live window retryable", Reconnect.retryable(Reconnect.BEHIND_LIVE_WINDOW, 0) && Reconnect.restartAtLiveEdge(Reconnect.BEHIND_LIVE_WINDOW));
        check("http error limited", Reconnect.retryable(Reconnect.IO_BAD_HTTP_STATUS, 2) && !Reconnect.retryable(Reconnect.IO_BAD_HTTP_STATUS, 3));
        check("missing file not retried", !Reconnect.retryable(Reconnect.IO_FILE_NOT_FOUND, 0));
        check("format error not retried", !Reconnect.retryable(4001, 0) && !Reconnect.retryable(3001, 0));
        check("delays grow", Reconnect.delayMs(0) == 2000 && Reconnect.delayMs(1) == 4000 && Reconnect.delayMs(2) == 8000 && Reconnect.delayMs(3) == 15000);
        check("delay capped", Reconnect.delayMs(4) == 30000 && Reconnect.delayMs(40) == 30000 && Reconnect.delayMs(-3) == 2000);
        check("no give up without failure", !Reconnect.giveUp(0, 99_999_999L));
        check("no give up early", !Reconnect.giveUp(1_000L, 1_000L + Reconnect.GIVE_UP_AFTER_MS - 1));
        check("give up after window", Reconnect.giveUp(1_000L, 1_000L + Reconnect.GIVE_UP_AFTER_MS));

        System.out.println("Zapping and reconnect: " + checks + " checks passed");
    }
}
