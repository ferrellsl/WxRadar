package com.ferrellsl.wxradar;

/**
 * WxRadar: how the weather is moving (a port of the Windows app's workOutMotion).
 *
 * Between two radar pictures 5 minutes apart, the rain is drawn where it was on its way:
 * the earlier picture slid forward along the storms' motion, the later one slid back, and
 * the two blended - true in-between pictures, not a cross-fade.  The clouds move the same
 * way, with a motion of their own.
 *
 * The view is cut into cells (about 2 km for the radar, 4 km for the clouds); each frame
 * becomes a grid of strength; for each block of 6 x 6 cells with something in it, the shift
 * that best matches it in the next frame is found (all the loop's pairs), and those shifts,
 * weighted by how much they rest on, are averaged into one motion field for the view.
 * Rain blocks are weighted by their rain; cloud blocks by their edges, and their shifts are
 * refined to a fraction of a cell.  Blocks with nothing take their neighbors' motion.
 */
public final class Motion {
    public static final int RADAR = 0, CLOUDS = 1;

    /** What a computation works from (made on the main thread, used on another). */
    public static final class Input {
        public int which = RADAR;
        public int job;
        public Object view;                  // the view it is for (compared with equals)
        public int gw, gh, reach;            // cells across, down; the farthest move looked for, in cells
        public float cellPx;
        public byte[][] frames;              // per frame, gw x gh strengths (null: none)
        public boolean[] pairs;              // pairs[f]: frames f and f + 1 can be compared
    }

    /** The answer: per block, screen pixels per 5 minutes. */
    public static final class Field {
        public int which;
        public int job;
        public Object view;
        public int bw, bh;                   // blocks across, down
        public float blockPx;                // a block's size on screen
        public float vmax = 1;               // the largest move, screen pixels per 5 minutes
        public float[] vx, vy;
    }

    private Motion() { }

    private static int cell(byte[] g, int gw, int gh, int x, int y) {
        return (x < 0 || y < 0 || x >= gw || y >= gh) ? 0 : g[y * gw + x] & 255;
    }

    private static int sad(byte[] a, byte[] b, int gw, int gh, int x0, int y0, int dx, int dy, int stop) {
        final int B = 6;
        int s = 0;
        for (int j = 0; j < B && s < stop; ++j)
            for (int i = 0; i < B; ++i)
                s += Math.abs(cell(a, gw, gh, x0 + i, y0 + j) - cell(b, gw, gh, x0 + i + dx, y0 + j + dy));
        return s;
    }

    private static float refine(int l, int c, int r) {
        int d = l - 2 * c + r;
        return d > 0 ? Math.max(-0.5f, Math.min(0.5f, 0.5f * (l - r) / d)) : 0.0f;
    }

    /** (on a thread: only its input) */
    public static Field workOut(Input in) {
        final boolean clouds = in.which == CLOUDS;
        final int B = 6, S = 3, R = in.reach, gw = in.gw, gh = in.gh;
        final int bw = gw / S + 2, bh = gh / S + 2;          // (one more each way: the edges)
        double[] sx = new double[bw * bh], sy = new double[bw * bh], sw = new double[bw * bh];
        for (int f = 0; f + 1 < in.frames.length; ++f) {
            if (!in.pairs[f] || in.frames[f] == null || in.frames[f + 1] == null) continue;
            final byte[] A = in.frames[f], Bn = in.frames[f + 1];
            for (int bj = 0; bj < bh; ++bj)
                for (int bi = 0; bi < bw; ++bi) {
                    final int x0 = bi * S - 1, y0 = bj * S - 1;     // the block: 6 x 6 cells around its own 3 x 3
                    int signal = 0;
                    if (clouds) {
                        // the clouds' edges and texture: what can be followed
                        for (int j = 0; j < B; ++j)
                            for (int i = 0; i < B; ++i) {
                                int c = cell(A, gw, gh, x0 + i, y0 + j);
                                signal += Math.abs(cell(A, gw, gh, x0 + i + 1, y0 + j) - c) + Math.abs(cell(A, gw, gh, x0 + i, y0 + j + 1) - c);
                            }
                        if (signal < B * B * 12) continue;
                    } else {
                        for (int j = 0; j < B; ++j)
                            for (int i = 0; i < B; ++i) signal += cell(A, gw, gh, x0 + i, y0 + j);
                        if (signal < B * B * 40) continue;           // (too little rain to follow)
                    }
                    int best = Integer.MAX_VALUE, bx = 0, by = 0;
                    for (int dy = -R; dy <= R; ++dy)
                        for (int dx = -R; dx <= R; ++dx) {
                            int bias = (Math.abs(dx) + Math.abs(dy)) * 8;   // (all else equal, the shorter move)
                            int s = bias + sad(A, Bn, gw, gh, x0, y0, dx, dy, best - bias);
                            if (s < best) { best = s; bx = dx; by = dy; }
                        }
                    float fx = bx, fy = by;
                    if (clouds) {
                        // to a fraction of a cell: the bottom of a parabola through the best and its neighbors
                        final int M = Integer.MAX_VALUE;
                        int c = sad(A, Bn, gw, gh, x0, y0, bx, by, M);
                        if (Math.abs(bx) < R) fx += refine(sad(A, Bn, gw, gh, x0, y0, bx - 1, by, M), c, sad(A, Bn, gw, gh, x0, y0, bx + 1, by, M));
                        if (Math.abs(by) < R) fy += refine(sad(A, Bn, gw, gh, x0, y0, bx, by - 1, M), c, sad(A, Bn, gw, gh, x0, y0, bx, by + 1, M));
                    }
                    int k = bj * bw + bi;
                    sx[k] += (double) signal * fx;
                    sy[k] += (double) signal * fy;
                    sw[k] += signal;
                }
        }
        Field m = new Field();
        m.which = in.which;
        m.job = in.job;
        m.view = in.view;
        m.bw = bw;
        m.bh = bh;
        m.blockPx = S * in.cellPx;
        m.vx = new float[bw * bh];
        m.vy = new float[bw * bh];
        boolean[] known = new boolean[bw * bh];
        for (int k = 0; k < known.length; ++k)
            if (sw[k] > 0) { m.vx[k] = (float) (sx[k] / sw[k]); m.vy[k] = (float) (sy[k] / sw[k]); known[k] = true; }
        // blocks with nothing to follow: their neighbors' motion, spreading out
        for (int pass = 0; pass < 64; ++pass) {
            boolean any = false;
            boolean[] now = known.clone();
            for (int j = 0; j < bh; ++j)
                for (int i = 0; i < bw; ++i) {
                    int k = j * bw + i;
                    if (known[k]) continue;
                    float ax = 0, ay = 0;
                    int n = 0;
                    for (int dj = -1; dj <= 1; ++dj)
                        for (int di = -1; di <= 1; ++di) {
                            int x = i + di, y = j + dj;
                            if (x < 0 || y < 0 || x >= bw || y >= bh || !known[y * bw + x]) continue;
                            ax += m.vx[y * bw + x]; ay += m.vy[y * bw + x]; ++n;
                        }
                    if (n > 0) { m.vx[k] = ax / n; m.vy[k] = ay / n; now[k] = true; any = true; }
                }
            known = now;
            if (!any) break;
        }
        // smoothed, then in screen pixels
        for (int pass = 0; pass < 3; ++pass) {
            float[] nx = m.vx.clone(), ny = m.vy.clone();
            for (int j = 0; j < bh; ++j)
                for (int i = 0; i < bw; ++i) {
                    float ax = 0, ay = 0;
                    int n = 0;
                    for (int dj = -1; dj <= 1; ++dj)
                        for (int di = -1; di <= 1; ++di) {
                            int x = i + di, y = j + dj;
                            if (x < 0 || y < 0 || x >= bw || y >= bh) continue;
                            ax += m.vx[y * bw + x]; ay += m.vy[y * bw + x]; ++n;
                        }
                    nx[j * bw + i] = ax / n;
                    ny[j * bw + i] = ay / n;
                }
            m.vx = nx;
            m.vy = ny;
        }
        m.vmax = 1;
        for (int k = 0; k < m.vx.length; ++k) {
            m.vx[k] *= in.cellPx;
            m.vy[k] *= in.cellPx;
            m.vmax = Math.max(m.vmax, Math.max(Math.abs(m.vx[k]), Math.abs(m.vy[k])));
        }
        return m;
    }
}
