package com.ferrellsl.wxradar;

/**
 * WxRadar: what is done to a tile's pixels (Android's ARGB ints, not premultiplied).
 * The same arithmetic as the Windows app, which works on premultiplied BGRA.
 */
public final class Pixels {
    private Pixels() { }

    /**
     * Infrared satellite pictures: the warmer (darker) the less cloud.  Clear ground,
     * darker than about 100 of 255, goes fully transparent; the coldest tops (brightest)
     * are solid white; between, a see-through grey.
     */
    public static void cloudify(int[] px) {
        for (int i = 0; i < px.length; ++i) {
            int c = px[i];
            int a = c >>> 24;
            int grey = a != 0 ? (((c >> 16) & 255) + ((c >> 8) & 255) + (c & 255)) / 3 : 0;
            int alpha = (grey - 100) * 255 / 120;
            alpha = alpha < 0 ? 0 : Math.min(alpha, 235);
            alpha = alpha * a / 255;
            int shade = Math.min(255, 170 + grey / 3);                 // (thicker = whiter)
            px[i] = alpha == 0 ? 0 : (alpha << 24) | (shade << 16) | (shade << 8) | shade;
        }
    }

    /**
     * A map tile for dark mode.  Its lightness is turned around and its hue and saturation
     * kept - each channel moved by the same amount, so that the brightest and darkest
     * channels swap places (land goes near black, water dark blue, parks dark green, labels
     * light) - then the contrast is softened, so the labels don't glare and the radar
     * stands out.
     */
    public static void darken(int[] px) {
        for (int i = 0; i < px.length; ++i) {
            int c = px[i];
            int a = c >>> 24;
            if (a == 0) continue;
            int r = ((c >> 16) & 255) * a / 255, g = ((c >> 8) & 255) * a / 255, b = (c & 255) * a / 255;   // (premultiplied)
            int hi = Math.max(r, Math.max(g, b)), lo = Math.min(r, Math.min(g, b));
            int shift = a - hi - lo;
            int base = (24 * a + 255 / 2) / 255;
            r = Math.min(a, base + (r + shift) * 150 / 255);
            g = Math.min(a, base + (g + shift) * 150 / 255);
            b = Math.min(a, base + (b + shift) * 150 / 255);
            px[i] = (a << 24) | ((r * 255 / a) << 16) | ((g * 255 / a) << 8) | (b * 255 / a);
        }
    }

    /**
     * What the motion is worked out from.  Radar: rain strength, roughly - none where the
     * tile is clear, more the redder it is (light rain is blue-green, heavy yellow to red);
     * 4 x 4 pixels a cell (64 x 64).  Clouds: how solid they are; 2 x 2 pixels a cell
     * (128 x 128: the satellite's pixels are coarser).  px: a 256 x 256 tile.
     */
    public static byte[] strength(int[] px, boolean clouds) {
        final int side = clouds ? 128 : 64, k = 256 / side;
        byte[] out = new byte[side * side];
        for (int cy = 0; cy < side; ++cy)
            for (int cx = 0; cx < side; ++cx) {
                int sum = 0;
                for (int j = 0; j < k; ++j)
                    for (int i = 0; i < k; ++i) {
                        int c = px[(cy * k + j) * 256 + cx * k + i];
                        int a = c >>> 24;
                        sum += clouds ? a : (a + ((c >> 16) & 255) * a / 255) / 2;   // (alpha + premultiplied red)
                    }
                out[cy * side + cx] = (byte) (sum / (k * k));
            }
        return out;
    }
}
