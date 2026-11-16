package com.ferrellsl.wxradar;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.List;

/** Checks of the parts ported from the Windows app that don't need a phone. */
public class PortTest {
    @Test public void json() {
        Json j = Json.parse("{\"a\": {\"b\": [1, 2.5, \"x\\u00e9\\n\"]}, \"t\": true, \"n\": null, \"neg\": -3e2}");
        assertTrue(j.isObject());
        assertEquals(2.5, j.get("a").get("b").at(1).num(), 1e-12);
        assertEquals("xé\n", j.get("a").get("b").at(2).str());
        assertTrue(j.get("t").bool());
        assertTrue(j.get("n").isNull());
        assertEquals(-300, j.get("neg").num(), 1e-12);
        assertTrue(j.get("missing").get("deeper").at(3).isNull());   // (chains never fail)
        assertTrue(Json.parse("{\"a\": ").isNull());
        assertTrue(Json.parse("[1, 2] x").isNull());
        assertEquals("🌧", Json.parse("\"\\ud83c\\udf27\"").str());   // (a surrogate pair)
    }

    @Test public void alerts() {
        String doc = "{\"features\": [{\"properties\": {\"event\": \"Tornado Warning\", \"headline\": \"h\", \"severity\": \"Extreme\"},"
                   + "\"geometry\": {\"type\": \"Polygon\", \"coordinates\": [[[-100, 30], [-99, 30], [-99, 31], [-100, 30]]]}},"
                   + "{\"properties\": {\"event\": \"Flood Watch\"}, \"geometry\": null}]}";
        List<Nws.Alert> list = Nws.alerts(Json.parse(doc));
        assertEquals(2, list.size());
        assertEquals(0xFF0000, list.get(0).color);
        assertEquals(1, list.get(0).rings.size());
        assertEquals(4, list.get(0).rings.get(0).lon.length);
        assertEquals(-99, list.get(0).rings.get(0).lon[1], 0);
        assertEquals(0, list.get(1).rings.size());
        assertEquals(0x2E8B57, list.get(1).color);
        assertEquals(0xD2B48C, Nws.alertColor("Something New"));
    }

    @Test public void times() {
        assertEquals(1792597500000L, Weather.parseIso("2026-10-21T15:45:00Z"));
        assertEquals(1792597500000L, Weather.parseIso("2026-10-21T08:45:00-07:00"));
        assertEquals(1792597500000L, Weather.parseIso("2026-10-21T15:45:00.000Z"));
        assertEquals(0, Weather.parseIso("soon"));
        assertEquals("3 PM", Words.hourOf("2026-10-21T15:00:00-07:00"));
        assertEquals("12 AM", Words.hourOf("2026-10-21T00:00:00-07:00"));
        assertEquals("Chance Showers & T-storms", Words.brief("Chance Showers And Thunderstorms"));
        assertEquals("N", Words.compass(359));
        assertEquals("SW", Words.compass(225));
        assertEquals("Mesa%2C%20AZ", Words.escape("Mesa, AZ"));
    }

    @Test public void satelliteTimes() {
        String caps = "<Layer><Name>other</Name><Dimension name=\"time\">x</Dimension></Layer>"
                    + "<Layer><Name>goes_longwave_imagery</Name><Dimension name=\"time\" default=\"current\">2026-10-21T15:40:00Z, 2026-10-21T15:45:00Z,\n2026-10-21T15:50:00Z</Dimension>";
        List<String> t = Weather.satelliteTimes(caps);
        assertEquals(3, t.size());
        assertEquals("2026-10-21T15:50:00Z", t.get(2));
    }

    @Test public void pixels() {
        // clouds: clear warm ground goes transparent, the coldest tops near-solid white
        int[] px = { 0xFF323232, 0xFFFFFFFF, 0x00000000 };
        Pixels.cloudify(px);
        assertEquals(0, px[0]);
        assertEquals(235, px[1] >>> 24);
        assertEquals(0xFFFFFF, px[1] & 0xFFFFFF);
        assertEquals(0, px[2]);
        // dark mode: white land goes near black, a pale blue sea dark blue
        int[] map = { 0xFFF2EFE9, 0xFFAAD3DF };
        Pixels.darken(map);
        assertTrue(((map[0] >> 16) & 255) < 50);
        assertTrue((map[1] & 255) > ((map[1] >> 16) & 255));
        // strength: a red (heavy rain) tile is strong, a clear one empty
        int[] red = new int[256 * 256], clear = new int[256 * 256];
        java.util.Arrays.fill(red, 0xFFFF0000);
        byte[] s = Pixels.strength(red, false);
        assertEquals(64 * 64, s.length);
        assertEquals(255, s[0] & 255);
        assertEquals(0, Pixels.strength(clear, false)[100]);
        assertEquals(128 * 128, Pixels.strength(clear, true).length);
    }

    /** A blob of rain moving 2 cells right and 1 down each frame: the field says so. */
    @Test public void motion() {
        final int gw = 60, gh = 40, frames = 6;
        Motion.Input in = new Motion.Input();
        in.which = Motion.RADAR;
        in.gw = gw;
        in.gh = gh;
        in.reach = 4;
        in.cellPx = 10;
        in.frames = new byte[frames][];
        in.pairs = new boolean[frames];
        for (int f = 0; f < frames; ++f) {
            byte[] g = new byte[gw * gh];
            for (int y = 0; y < gh; ++y)
                for (int x = 0; x < gw; ++x) {
                    double dx = x - (15 + 2 * f), dy = y - (12 + f);
                    double v = 230 * Math.exp(-(dx * dx + dy * dy) / 40.0) + ((x * 7 + y * 13) % 5) * 4 * Math.exp(-(dx * dx + dy * dy) / 60.0);
                    g[y * gw + x] = (byte) Math.min(255, (int) v);
                }
            in.frames[f] = g;
            in.pairs[f] = f + 1 < frames;
        }
        Motion.Field m = Motion.workOut(in);
        // the blocks over the blob
        int bi = (15 + 2 * 2) / 3, bj = (12 + 2) / 3;
        int k = bj * m.bw + bi;
        assertEquals(20, m.vx[k], 4);
        assertEquals(10, m.vy[k], 4);
        assertEquals(30, m.blockPx, 1e-6);
        assertFalse(m.vmax < 1);
    }
}
