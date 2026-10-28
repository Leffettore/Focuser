package app.focuser.android;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.time.LocalDateTime;
import org.junit.Test;

public class RulesTest {
    // 2026-10-26 is a Monday.
    private static final LocalDateTime MON_2200 = LocalDateTime.of(2026, 10, 26, 22, 0);
    private static final LocalDateTime MON_1200 = LocalDateTime.of(2026, 10, 26, 12, 0);
    private static final LocalDateTime TUE_0030 = LocalDateTime.of(2026, 10, 27, 0, 30);

    private static final String DOC = "{\"block_lists\":[{\"enabled\":true,"
            + "\"websites\":[{\"enabled\":true,\"match_type\":{\"Domain\":\"youtube.com\"}}],"
            + "\"exceptions\":[{\"enabled\":true,\"exception_type\":\"LocalFiles\"},"
            + "{\"enabled\":true,\"exception_type\":{\"UrlPath\":\"youtube.com/willmedia\"}},"
            + "{\"enabled\":true,\"exception_type\":{\"Wildcard\":\"youtube.com/watch?v=*&list=wl*\"}}],"
            + "\"schedule\":{\"enabled\":true,\"time_slots\":[{\"day\":\"Mon\",\"start\":\"21:30:00\",\"end\":\"02:00:00\"}]}}]}";

    @Test
    public void blocksInsideSlotAndAfterMidnightWrap() throws Exception {
        Rules rules = new Rules(DOC);
        assertTrue(rules.blocks("https://www.youtube.com/", MON_2200));
        assertTrue(rules.blocks("m.youtube.com", TUE_0030));
        assertFalse(rules.blocks("youtube.com", MON_1200));
    }

    @Test
    public void exceptionsAndOtherSitesAreLetThrough() throws Exception {
        Rules rules = new Rules(DOC);
        assertFalse(rules.blocks("youtube.com/WillMedia/videos", MON_2200));
        assertFalse(rules.blocks("youtube.com/watch?v=abc&list=wl", MON_2200));
        assertFalse(rules.blocks("example.com", MON_2200));
        assertFalse(rules.blocks("notyoutube.com", MON_2200));
    }

    @Test
    public void normalizeStripsSchemeAndPrefix() {
        assertEquals("youtube.com/x", Rules.normalize("HTTPS://m.YouTube.com/x"));
    }
}
