package com.ferrellsl.wxradar;

import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.BlendMode;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.RecordingCanvas;
import android.graphics.RenderEffect;
import android.graphics.RenderNode;
import android.graphics.RuntimeShader;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * WxRadar: the map - drawn onto a Canvas (a port
 * of the Windows app's map drawing, loop and motion).
 *
 * The map: Web Mercator ("world" coordinates 0..256 across the whole Earth at zoom 0), zoom
 * is fractional; tiles are drawn at the nearest whole zoom level and scaled, and while a tile
 * loads a piece of an ancestor stands in.
 */
public final class MapRenderer implements Weather.Viewer {
    static final long FRAME_MS = 125;           // each picture's time on screen while playing (8 a second)...
    static final long FADE_MS = 90;             // ...the radar's last part of it fading into the next
    static final long LAST_FRAME_MS = 1500;     // the newest one stays longer (then a cut back to the oldest)
    /** Tiles come out about 170 dp across (256 / 1.5): the size they have on Windows at 150%. */
    static final double TILE_DETAIL = Math.log(1.5) / Math.log(2);

    private final Weather wx;
    private final Runnable invalidate;
    public float ui = 1;                        // pixels per dp

    // the view
    public double camX = 128, camY = 128, zoom = 4;
    public int width, height;

    // the loop
    public int frame = Weather.FRAME_COUNT - 1;  // shown now: 0 oldest .. FRAME_COUNT-1 newest
    public boolean playing = true;
    private long frameShownAt;
    public boolean darkMap;
    public String status = "";

    // pins: home, and the point the forecast is for
    public Weather.Place pinHome, pinPicked;
    public int pinPickedColor = 0xE67E22;

    // the phone's map controls
    public static final int HIT_RADAR = 1, HIT_CLOUDS = 2, HIT_WARNINGS = 3, HIT_ZOOM_IN = 4, HIT_ZOOM_OUT = 5,
            HIT_PLAY = 6, HIT_FRAMES = 7, HIT_LAYERS = 8, HIT_CREDITS = 9;
    public static final class Hit {
        public final RectF r;
        public final int what;
        Hit(RectF r, int what) { this.r = r; this.what = what; }
    }
    public final List<Hit> hits = new ArrayList<>();
    public boolean layersOpen;
    private final RectF framesBar = new RectF();

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint bitmapPaint = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Rect srcRect = new Rect();
    private final RectF dstRect = new RectF();

    public MapRenderer(Weather wx, Runnable invalidate) {
        this.wx = wx;
        this.invalidate = invalidate;
        textPaint.setTypeface(Typeface.create("sans-serif", Typeface.NORMAL));
    }

    // ------------------------------------------------------------- Weather.Viewer
    @Override public void weatherChanged() { invalidate.run(); }
    @Override public boolean wantsClouds() { return wx.showClouds; }
    @Override public void radarReplaced() { dropMotion(); requestView(); }
    @Override public void cloudsReplaced() { dropMotion(Motion.CLOUDS); requestView(); }

    // ------------------------------------------------------------- geometry
    // Web Mercator: degrees <-> world coordinates (0..256)
    public static double worldX(double lon) { return (lon + 180.0) / 360.0 * 256.0; }
    public static double worldY(double lat) {
        lat = Math.max(-85.05, Math.min(85.05, lat));
        double s = Math.sin(Math.toRadians(lat));
        return (0.5 - Math.log((1 + s) / (1 - s)) / (4 * Math.PI)) * 256.0;
    }
    public static double lonOf(double wx) { return wx / 256.0 * 360.0 - 180.0; }
    public static double latOf(double wy) { double n = Math.PI - 2 * Math.PI * wy / 256.0; return Math.toDegrees(Math.atan(Math.sinh(n))); }

    public double scale() { return Math.pow(2.0, zoom) * ui; }      // screen pixels per world unit
    private float centerX() { return width / 2f; }
    private float centerY() { return height / 2f; }

    public float screenX(double wx) { return (float) (centerX() + (wx - camX) * scale()); }
    public float screenY(double wy) { return (float) (centerY() + (wy - camY) * scale()); }
    public double worldXAt(float sx) { return camX + (sx - centerX()) / scale(); }
    public double worldYAt(float sy) { return camY + (sy - centerY()) / scale(); }
    public double latAt(float sy) { return latOf(worldYAt(sy)); }
    public double lonAt(float sx) { double x = worldXAt(sx) % 256; return lonOf(x < 0 ? x + 256 : x); }

    public void centerOn(double lat, double lon, double z) {
        camX = worldX(lon);
        camY = worldY(lat);
        zoom = Math.max(2, Math.min(Weather.MAP_MAX_ZOOM, z));
        requestView();
        invalidate.run();
    }

    public void panBy(float dx, float dy) {
        camX -= dx / scale();
        camY -= dy / scale();
        camY = Math.max(0.0, Math.min(256.0, camY));
        camX = ((camX % 256.0) + 256.0) % 256.0;
        invalidate.run();
    }

    /** Zoom by dz keeping the point (sx, sy) where it is on the screen. */
    public void zoomAt(float sx, float sy, double dz) {
        double wx = worldXAt(sx), wy = worldYAt(sy);
        zoom = Math.max(2.0, Math.min(Weather.MAP_MAX_ZOOM, zoom + dz));
        camX = wx - (sx - centerX()) / scale();
        camY = wy - (sy - centerY()) / scale();
        camY = Math.max(0.0, Math.min(256.0, camY));
        invalidate.run();
    }

    // ------------------------------------------------------------- tiles in view
    int levelFor(int maxZoom) {
        // the whole zoom level whose tiles come out nearest the size they have on Windows
        int z = (int) Math.floor(zoom + TILE_DETAIL + 0.5);
        return Math.max(0, Math.min(maxZoom, z));
    }

    /** The tiles of a layer in view at whole zoom level z: {x0, y0, x1, y1}. */
    private int[] tileRange(int z) {
        double wx0 = worldXAt(0), wy0 = worldYAt(0), wx1 = worldXAt(width), wy1 = worldYAt(height);
        double n = Math.pow(2.0, z), size = 256.0 / n;
        return new int[] { (int) Math.floor(wx0 / size), Math.max(0, (int) Math.floor(wy0 / size)),
                           (int) Math.floor(wx1 / size), Math.min((int) n - 1, (int) Math.floor(wy1 / size)) };
    }

    private int mapLayer() { return darkMap ? Weather.MAP_DARK : Weather.MAP; }

    private void requestLayer(int layer, int maxZoom) {
        final int z = levelFor(maxZoom), n = 1 << z;
        int[] r = tileRange(z);
        for (int y = r[1]; y <= r[3]; ++y)
            for (int x = r[0]; x <= r[2]; ++x)
                wx.requestTile(layer, z, ((x % n) + n) % n, y, this);
    }

    /**
     * Ask for every tile in view: the radar frames other than the one shown first (they are
     * taken last: the newest request goes first), then the map, then the frame shown.
     */
    public void requestView() {
        if (width <= 0 || height <= 0) return;
        lastRequestAt = SystemClock.uptimeMillis();
        Net.dropWaitingImages(this);
        for (int f = 0; f < Weather.FRAME_COUNT; ++f)
            if (f != frame) {
                if (wx.showClouds) requestLayer(Weather.satLayer(f), Weather.SAT_MAX_ZOOM);
                if (wx.showRadar) requestLayer(Weather.radarLayer(f), Weather.RADAR_MAX_ZOOM);
            }
        requestLayer(mapLayer(), Weather.MAP_MAX_ZOOM);
        if (wx.showClouds) requestLayer(Weather.satLayer(frame), Weather.SAT_MAX_ZOOM);
        if (wx.showRadar) requestLayer(Weather.radarLayer(frame), Weather.RADAR_MAX_ZOOM);
        wx.trimTiles();
    }

    /** Is every tile of a layer in view here (or given up on)? */
    private boolean layerLoaded(int layer, int maxZoom) {
        final int z = levelFor(maxZoom), n = 1 << z;
        int[] r = tileRange(z);
        for (int y = r[1]; y <= r[3]; ++y)
            for (int x = r[0]; x <= r[2]; ++x) {
                Weather.Tile t = wx.tile(layer, z, ((x % n) + n) % n, y);
                if (t == null || (t.bitmap == null && t.failedAt == 0)) return false;
            }
        return true;
    }

    private boolean frameLoaded(int f) {
        return (!wx.showRadar || layerLoaded(Weather.radarLayer(f), Weather.RADAR_MAX_ZOOM)) &&
               (!wx.showClouds || wx.satFrame[f].isEmpty() || layerLoaded(Weather.satLayer(f), Weather.SAT_MAX_ZOOM));
    }

    private interface PieceSink { void piece(Bitmap b, Rect src, RectF dst); }

    /**
     * Each tile of a layer in view, or while it loads, the part of the nearest ancestor that
     * covers it.
     */
    private void forEachPiece(int layer, int maxZoom, PieceSink sink) {
        final int z = levelFor(maxZoom), n = 1 << z;
        int[] r = tileRange(z);
        final double size = 256.0 / n;
        final long now = SystemClock.uptimeMillis();
        for (int y = r[1]; y <= r[3]; ++y)
            for (int x = r[0]; x <= r[2]; ++x) {
                final int wrapped = ((x % n) + n) % n;
                dstRect.set(Math.round(screenX(x * size)), Math.round(screenY(y * size)),
                            Math.round(screenX((x + 1) * size)), Math.round(screenY((y + 1) * size)));   // (edges meet exactly)
                for (int up = 0; up <= 6 && z - up >= 0; ++up) {
                    final int pz = z - up, px = wrapped >> up, py = y >> up;
                    Weather.Tile t = wx.tile(layer, pz, px, py);
                    if (t == null || t.bitmap == null) continue;
                    t.used = now;
                    // the piece of the ancestor tile that covers (x, y)
                    final int part = 256 >> up;
                    final int sx = (wrapped - (px << up)) * part, sy = (y - (py << up)) * part;
                    srcRect.set(sx, sy, sx + Math.max(1, part), sy + Math.max(1, part));
                    sink.piece(t.bitmap, srcRect, dstRect);
                    break;
                }
            }
    }

    private void drawLayer(Canvas c, int layer, int maxZoom, float opacity, BlendMode blend) {
        bitmapPaint.setAlpha(Math.round(opacity * 255));
        bitmapPaint.setBlendMode(blend);
        forEachPiece(layer, maxZoom, (b, src, dst) -> c.drawBitmap(b, src, dst, bitmapPaint));
        bitmapPaint.setBlendMode(null);
        bitmapPaint.setAlpha(255);
    }

    // ------------------------------------------------------------- weather layers
    private final RenderNode[] weatherNodes = { new RenderNode("radar"), new RenderNode("clouds") };

    /** Screen pixels a tile pixel. */
    private float cellPx(int maxZoom) {
        return (float) Math.pow(2.0, zoom + Math.log(ui) / Math.log(2) - levelFor(maxZoom));
    }

    /** A blur of standard deviation sigma (Android's blur takes a "radius": sigma = 0.57735 r + 0.5). */
    private static RenderEffect blurEffect(float sigma) {
        float radius = Math.max(0.01f, (sigma - 0.5f) / 0.57735f);
        return RenderEffect.createBlurEffect(radius, radius, Shader.TileMode.DECAL);
    }

    private void setUpNode(RenderNode node, int maxZoom, float opacity) {
        node.setPosition(0, 0, width, height);
        node.setAlpha(opacity);
        node.setUseCompositingLayer(true, null);
        // the pictures are coarse - the radar's cells are about half a kilometer, a pixel of
        // its tiles - so zoomed in, each cell would show as a square; the layer goes onto the
        // map through a Gaussian blur about a third of a cell wide.  Zoomed out, no blur.
        float cell = cellPx(maxZoom);
        if (Build.VERSION.SDK_INT >= 31) node.setRenderEffect(cell >= 1.6f ? blurEffect(0.32f * cell) : null);
    }

    /**
     * A weather layer (radar, clouds): picture layerA, fading into layerB by t (0..1).  It is
     * put together off screen: A at (1 - t), then B added at t, so where both have rain it
     * stays as strong all through the fade.
     */
    private void drawWeather(Canvas c, int which, int layerA, int layerB, float t, int maxZoom, float opacity) {
        if (!c.isHardwareAccelerated()) {
            drawLayer(c, t < 0.5f ? layerA : layerB, maxZoom, opacity, null);
            return;
        }
        RenderNode node = weatherNodes[which];
        setUpNode(node, maxZoom, opacity);
        RecordingCanvas rc = node.beginRecording();
        drawLayer(rc, layerA, maxZoom, 1.0f - t, null);
        if (t > 0) drawLayer(rc, layerB, maxZoom, t, BlendMode.PLUS);
        node.endRecording();
        c.drawRenderNode(node);
    }

    // ------------------------------------------------------------- weather motion
    /** The view a motion field is for: kept only while the view stays the same. */
    private static final class View {
        final double camX, camY, zoom;
        final int w, h, cx, cy;
        View(MapRenderer m) { camX = m.camX; camY = m.camY; zoom = m.zoom; w = m.width; h = m.height; cx = (int) m.centerX(); cy = (int) m.centerY(); }
        @Override public boolean equals(Object o) {
            if (!(o instanceof View)) return false;
            View v = (View) o;
            return camX == v.camX && camY == v.camY && zoom == v.zoom && w == v.w && h == v.h && cx == v.cx && cy == v.cy;
        }
        @Override public int hashCode() { return Objects.hash(camX, camY, zoom, w, h); }
    }

    /** One weather layer's motion, and what draws it moving. */
    private static final class Mover {
        Motion.Field field;                  // in use (null: none)
        Bitmap map;                          // the field as a picture (red = x, green = y)
        BitmapShader mapShader;
        int job;                             // the newest computation (older answers are ignored)
        boolean running;
    }

    private final Mover[] movers = { new Mover(), new Mover() };
    private int motionJobs;
    private View lastView;
    private long viewSteadySince;
    private static final ExecutorService motionThread = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "WxRadar-motion");
        t.setDaemon(true);
        t.setPriority(Thread.MIN_PRIORITY);
        return t;
    });
    private final Handler main = new Handler(Looper.getMainLooper());

    private boolean motionReady(int which) {
        Mover m = movers[which];
        return m.field != null && m.map != null && m.field.view.equals(new View(this));
    }

    public boolean smoothMotion() {
        return (wx.showRadar && motionReady(Motion.RADAR)) || (wx.showClouds && motionReady(Motion.CLOUDS));
    }

    private void dropMotion(int which) {
        Mover m = movers[which];
        m.field = null;
        m.map = null;
        m.mapShader = null;
        m.job = ++motionJobs;
        m.running = false;
    }

    private void dropMotion() {
        dropMotion(Motion.RADAR);
        dropMotion(Motion.CLOUDS);
    }

    /** A frame of a layer as a grid of strength (0..255: rain, or cloud), cells cellPx square. */
    private byte[] strengthGrid(int layer, int maxZoom, float cellPx, int gw, int gh) {
        byte[] grid = new byte[gw * gh];
        final int z = levelFor(maxZoom), n = 1 << z;
        final double tileWorld = 256.0 / n;
        for (int j = 0; j < gh; ++j)
            for (int i = 0; i < gw; ++i) {
                double wx0 = worldXAt((i + 0.5f) * cellPx), wy0 = worldYAt((j + 0.5f) * cellPx);
                int tx = (int) Math.floor(wx0 / tileWorld), ty = (int) Math.floor(wy0 / tileWorld);
                if (ty < 0 || ty >= n) continue;
                Weather.Tile t = wx.tile(layer, z, ((tx % n) + n) % n, ty);
                if (t == null || t.strength == null) continue;
                int side = t.strength.length == 128 * 128 ? 128 : 64;
                int px = Math.min(side - 1, Math.max(0, (int) ((wx0 / tileWorld - tx) * side)));
                int py = Math.min(side - 1, Math.max(0, (int) ((wy0 / tileWorld - ty) * side)));
                grid[j * gw + i] = t.strength[py * side + px];
            }
        return grid;
    }

    private boolean radarLoaded() {
        for (int f = 0; f < Weather.FRAME_COUNT; ++f)
            if (!layerLoaded(Weather.radarLayer(f), Weather.RADAR_MAX_ZOOM)) return false;
        return true;
    }

    private boolean cloudsLoaded() {
        for (int f = 0; f < Weather.FRAME_COUNT; ++f)
            if (!wx.satFrame[f].isEmpty() && !layerLoaded(Weather.satLayer(f), Weather.SAT_MAX_ZOOM)) return false;
        return true;
    }

    private void startMotion(int which) {
        // radar: cells of about 2 km (6 to 40 screen pixels), moves up to 10 km in 5 minutes (75 mph);
        // clouds: cells of about 4 km (8 to 40 pixels), moves up to 25 km in 5 minutes (the jet stream, 185 mph)
        final boolean clouds = which == Motion.CLOUDS;
        final double pxPerKm = scale() / (40075.0 * Math.cos(Math.toRadians(latOf(camY))) / 256.0);
        final float k = ui / 1.5f;           // (the Windows app's pixels were at 150%)
        Motion.Input in = new Motion.Input();
        in.which = which;
        in.cellPx = (float) Math.max((clouds ? 8.0 : 6.0) * k, Math.min(40.0 * k, (clouds ? 4.0 : 2.0) * pxPerKm));
        in.reach = Math.max(2, Math.min(8, (int) Math.ceil((clouds ? 25.0 : 10.0) / (in.cellPx / pxPerKm))));
        in.view = new View(this);
        in.gw = (int) Math.ceil(width / in.cellPx);
        in.gh = (int) Math.ceil(height / in.cellPx);
        if (in.gw < 8 || in.gh < 8) return;                         // (zoomed in too far to tell)
        in.frames = new byte[Weather.FRAME_COUNT][];
        in.pairs = new boolean[Weather.FRAME_COUNT];
        boolean anyPair = false;
        for (int f = 0; f < Weather.FRAME_COUNT; ++f) {
            in.frames[f] = clouds && wx.satFrame[f].isEmpty() ? null
                    : strengthGrid(clouds ? Weather.satLayer(f) : Weather.radarLayer(f), clouds ? Weather.SAT_MAX_ZOOM : Weather.RADAR_MAX_ZOOM, in.cellPx, in.gw, in.gh);
            boolean pair = f + 1 < Weather.FRAME_COUNT && (!clouds || wx.cloudPair(f));
            in.pairs[f] = pair;
            anyPair |= pair;
        }
        if (!anyPair) return;
        Mover mv = movers[which];
        in.job = mv.job = ++motionJobs;
        mv.running = true;
        motionThread.execute(() -> {
            Motion.Field m = Motion.workOut(in);
            main.post(() -> motionArrived(m));
        });
    }

    /** The answer from the thread: kept if it is the newest and the view hasn't moved since. */
    private void motionArrived(Motion.Field m) {
        Mover mv = movers[m.which];
        if (m.job != mv.job) return;
        mv.running = false;
        if (!m.view.equals(new View(this))) return;
        int[] px = new int[m.bw * m.bh];
        for (int k = 0; k < px.length; ++k) {
            int r = (int) Math.max(0, Math.min(255, (0.5f + m.vx[k] / (2 * m.vmax)) * 255 + 0.5f));   // red: x
            int g = (int) Math.max(0, Math.min(255, (0.5f + m.vy[k] / (2 * m.vmax)) * 255 + 0.5f));   // green: y
            px[k] = 0xFF000000 | (r << 16) | (g << 8);
        }
        mv.map = Bitmap.createBitmap(px, m.bw, m.bh, Bitmap.Config.ARGB_8888);
        mv.mapShader = new BitmapShader(mv.map, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
        if (Build.VERSION.SDK_INT >= 33) mv.mapShader.setFilterMode(BitmapShader.FILTER_MODE_LINEAR);
        mv.field = m;
        invalidate.run();
    }

    // One piece of a moving picture: a tile drawn over its square widened by the farthest
    // move, showing at each point p what the field says was there - the picture at
    // p + amount (field - 0.5) - when that falls in this tile, nothing otherwise.  Every point
    // takes its color from exactly one tile, so the pieces add up to the whole picture moved.
    private static final String MOVE_SHADER =
        "uniform shader tile;\n" +
        "uniform shader field;\n" +
        "uniform float2 origin;\n" +
        "uniform float2 size;\n" +
        "uniform float4 src;\n" +
        "uniform float blockPx;\n" +
        "uniform float amount;\n" +
        "uniform float weight;\n" +
        "half4 main(float2 p) {\n" +
        "  half4 m = field.eval(p / blockPx);\n" +
        "  float2 s = p + amount * (float2(m.r, m.g) - 0.5);\n" +
        "  float2 q = (s - origin) / size;\n" +
        "  if (q.x < 0.0 || q.y < 0.0 || q.x >= 1.0 || q.y >= 1.0) return half4(0.0);\n" +
        "  return tile.eval(src.xy + q * src.zw) * weight;\n" +
        "}\n";
    private Object moveShader;               // (a RuntimeShader: Android 13 and later)
    private final Paint movePaint = new Paint();

    /**
     * A weather layer between pictures layerA and layerB, t of the way (0..1), moved along its
     * motion field.  False if it can't be (then the caller cross-fades or steps).
     */
    private boolean drawMoving(Canvas c, int which, int layerA, int layerB, int maxZoom, float t, float opacity) {
        if (Build.VERSION.SDK_INT < 33 || !c.isHardwareAccelerated() || !motionReady(which)) return false;
        final Mover mv = movers[which];
        if (moveShader == null) {
            try { moveShader = new RuntimeShader(MOVE_SHADER); } catch (RuntimeException e) { return false; }
        }
        final RuntimeShader shader = (RuntimeShader) moveShader;
        shader.setInputShader("field", mv.mapShader);
        shader.setFloatUniform("blockPx", mv.field.blockPx);
        movePaint.setShader(shader);
        movePaint.setBlendMode(BlendMode.PLUS);

        RenderNode node = weatherNodes[which];
        setUpNode(node, maxZoom, opacity);
        RecordingCanvas rc = node.beginRecording();
        // A moved forward t of the way (shown at p: A at p - t v), B moved back (B at p + (1 - t) v)
        final float span = 2 * mv.field.vmax;
        drawMovingLayer(rc, shader, layerA, maxZoom, -t * span, 1 - t);
        drawMovingLayer(rc, shader, layerB, maxZoom, (1 - t) * span, t);
        node.endRecording();
        c.drawRenderNode(node);
        return true;
    }

    private void drawMovingLayer(RecordingCanvas rc, RuntimeShader shader, int layer, int maxZoom, float amount, float weight) {
        if (weight <= 0) return;
        final float margin = Math.abs(amount) / 2 + 2;
        shader.setFloatUniform("amount", amount);
        shader.setFloatUniform("weight", weight);
        forEachPiece(layer, maxZoom, (b, src, dst) -> {
            BitmapShader tile = new BitmapShader(b, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
            if (Build.VERSION.SDK_INT >= 33) tile.setFilterMode(BitmapShader.FILTER_MODE_LINEAR);
            shader.setInputShader("tile", tile);
            shader.setFloatUniform("origin", dst.left, dst.top);
            shader.setFloatUniform("size", dst.width(), dst.height());
            shader.setFloatUniform("src", src.left, src.top, src.width(), src.height());
            movePaint.setShader(shader);
            rc.drawRect(dst.left - margin, dst.top - margin, dst.right + margin, dst.bottom + margin, movePaint);
        });
    }

    // ------------------------------------------------------------- time passing
    /** Animation and housekeeping (about 30 times a second while shown).  True: draw again. */
    public boolean tick() {
        final long now = SystemClock.uptimeMillis();
        // the radar's and the clouds' motion: worked out once the view has been still a moment and every frame is here
        View v = new View(this);
        if (!v.equals(lastView)) {
            if (lastView != null && (lastView.zoom != v.zoom || lastView.w != v.w || lastView.h != v.h || Math.abs(lastView.camX - v.camX) > 1e-9 || Math.abs(lastView.camY - v.camY) > 1e-9))
                requestViewSoon = true;
            lastView = v;
            viewSteadySince = now;
            dropMotion();
        }
        if (requestViewSoon && (now - viewSteadySince > 150 || now - lastRequestAt > 400)) {
            requestViewSoon = false;
            requestView();
        }
        if (now - viewSteadySince > 400 && wx.radarValid != 0) {
            Mover r = movers[Motion.RADAR], c = movers[Motion.CLOUDS];
            if (wx.showRadar && r.field == null && !r.running && radarLoaded()) startMotion(Motion.RADAR);
            if (wx.showClouds && c.field == null && !c.running && cloudsLoaded()) startMotion(Motion.CLOUDS);
        }
        if (playing && now - frameShownAt >= (frame == Weather.FRAME_COUNT - 1 ? LAST_FRAME_MS : FRAME_MS)) {
            int next = (frame + 1) % Weather.FRAME_COUNT;
            if (!frameLoaded(next)) {
                // (anything of it missing is asked for again: nothing is left out for good)
                if (wx.showClouds && !wx.satFrame[next].isEmpty()) requestLayer(Weather.satLayer(next), Weather.SAT_MAX_ZOOM);
                if (wx.showRadar) requestLayer(Weather.radarLayer(next), Weather.RADAR_MAX_ZOOM);
            }
            if (frameLoaded(next) || now - frameShownAt > 3000) {    // (wait for a frame, a while)
                frame = next;
                frameShownAt = now;
            }
        }
        return playing;
    }

    private boolean requestViewSoon;
    private long lastRequestAt;

    public void setPlaying(boolean on) {
        playing = on;
        if (!on) frame = Weather.FRAME_COUNT - 1;
        frameShownAt = SystemClock.uptimeMillis();
        invalidate.run();
    }

    public void step(int by) {
        playing = false;
        frame = ((frame + by) % Weather.FRAME_COUNT + Weather.FRAME_COUNT) % Weather.FRAME_COUNT;
        invalidate.run();
    }

    /** A touch on the loop's row of frames: that frame, paused. */
    public void scrubTo(float x) {
        if (framesBar.width() <= 0) return;
        int f = (int) ((x - framesBar.left) / framesBar.width() * Weather.FRAME_COUNT);
        playing = false;
        frame = Math.max(0, Math.min(Weather.FRAME_COUNT - 1, f));
        invalidate.run();
    }

    public void layersChanged() {
        dropMotion();
        requestView();
        invalidate.run();
    }

    // ------------------------------------------------------------- drawing
    public void draw(Canvas c) {
        if (width <= 0 || height <= 0) return;
        c.drawColor(darkMap ? 0xFF1E2A33 : 0xFFAAD3DF);          // (the map's sea, while tiles come)
        drawLayer(c, mapLayer(), Weather.MAP_MAX_ZOOM, 1.0f, null);
        // (darken the map a little, so the radar's colors stand out; a dark map already does)
        if (!darkMap) c.drawColor(0x1F000000);

        // the loop.  With the storms' motion known, the radar moves on all through a
        // picture's time (true in-between pictures); without it, it fades into the next in
        // the last part.  The clouds move too once their motion is known; until then they
        // step.  From the newest back to the oldest: a cut.
        final int last = Weather.FRAME_COUNT - 1;
        final boolean moving = wx.showRadar && motionReady(Motion.RADAR);
        final boolean advancing = playing && frame < last && frameLoaded(frame + 1);
        final long held = SystemClock.uptimeMillis() - frameShownAt;
        final float along = advancing ? Math.min(1.0f, (float) held / FRAME_MS) : 0;
        int next = frame;
        float t = 0;
        if (advancing) {
            if (moving) {
                next = frame + 1;
                t = along;
            } else if (held > FRAME_MS - FADE_MS) {
                next = frame + 1;
                t = Math.min(1.0f, (float) (held - (FRAME_MS - FADE_MS)) / FADE_MS);
                t = t * t * (3 - 2 * t);                        // (eased at both ends)
            }
        }
        if (wx.showClouds && !(advancing && wx.cloudPair(frame) &&
                drawMoving(c, Motion.CLOUDS, Weather.satLayer(frame), Weather.satLayer(frame + 1), Weather.SAT_MAX_ZOOM, along, 0.92f)))
            drawWeather(c, Motion.CLOUDS, Weather.satLayer(frame), Weather.satLayer(frame), 0, Weather.SAT_MAX_ZOOM, 0.92f);
        if (wx.showRadar && !(moving && next != frame &&
                drawMoving(c, Motion.RADAR, Weather.radarLayer(frame), Weather.radarLayer(next), Weather.RADAR_MAX_ZOOM, t, wx.radarOpacity)))
            drawWeather(c, Motion.RADAR, Weather.radarLayer(frame), Weather.radarLayer(next), t, Weather.RADAR_MAX_ZOOM, wx.radarOpacity);
        drawAlerts(c);
        if (pinHome != null && pinHome.set) drawPin(c, pinHome.lat, pinHome.lon, 0x2E86DE);
        if (pinPicked != null && pinPicked.set && !(pinHome != null && pinHome.set && pinPicked.lat == pinHome.lat && pinPicked.lon == pinHome.lon))
            drawPin(c, pinPicked.lat, pinPicked.lon, pinPickedColor);
        drawOverlay(c);
    }

    private static int argb(int rgb, float a) {
        return (Math.round(a * 255) << 24) | (rgb & 0xFFFFFF);
    }

    private final Path path = new Path();

    private void drawAlerts(Canvas c) {
        if (!wx.showAlerts) return;
        // (the world wraps around: draw each ring in the copy of the world nearest the view)
        for (Nws.Alert a : wx.alerts)
            for (Nws.Ring ring : a.rings) {
                path.rewind();
                double shift = 0;
                double x0 = worldX(ring.lon[0]);
                double d = x0 - camX;
                if (d > 128) shift = -256; else if (d < -128) shift = 256;
                for (int i = 0; i < ring.lon.length; ++i) {
                    float x = screenX(worldX(ring.lon[i]) + shift), y = screenY(worldY(ring.lat[i]));
                    if (i == 0) path.moveTo(x, y); else path.lineTo(x, y);
                }
                path.close();
                paint.setStyle(Paint.Style.FILL);
                paint.setColor(argb(a.color, 0.18f));
                c.drawPath(path, paint);
                paint.setStyle(Paint.Style.STROKE);
                paint.setStrokeJoin(Paint.Join.ROUND);
                paint.setColor(argb(0x000000, 0.6f));
                paint.setStrokeWidth(4 * ui);
                c.drawPath(path, paint);
                paint.setColor(argb(a.color, 1));
                paint.setStrokeWidth(2 * ui);
                c.drawPath(path, paint);
                paint.setStyle(Paint.Style.FILL);
            }
    }

    private void drawPin(Canvas c, double lat, double lon, int color) {
        double wxp = worldX(lon), d = wxp - camX;
        if (d > 128) wxp -= 256; else if (d < -128) wxp += 256;
        float x = screenX(wxp), y = screenY(worldY(lat));
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(argb(0x000000, 0.5f));
        c.drawCircle(x, y, 8 * ui, paint);
        paint.setColor(Color.WHITE);
        c.drawCircle(x, y, 6.5f * ui, paint);
        paint.setColor(argb(color, 1));
        c.drawCircle(x, y, 4.5f * ui, paint);
    }

    // the radar's colors, dBZ 5..75 (as NOAA and the IEM draw them)
    private static final int[] LEGEND = { 0x04E9E7, 0x019FF4, 0x0300F4, 0x02FD02, 0x01C501, 0x008E00, 0xFDF802,
                                          0xE5BC00, 0xFD9500, 0xFD0000, 0xD40000, 0xBC0000, 0xF800FD, 0x9854C6, 0xFDFDFD };

    private void drawLegend(Canvas c, float x, float y, float w, float textSize) {
        float cell = w / LEGEND.length, h = 8 * ui;
        for (int i = 0; i < LEGEND.length; ++i) {
            paint.setColor(argb(LEGEND[i], 1));
            c.drawRect(x + i * cell, y, x + (i + 1) * cell + 0.5f, y + h, paint);
        }
        font(textSize, false);
        text(c, "Light", x, y + h + 1, 0xFFFFFF, Paint.Align.LEFT);
        text(c, "Heavy", x + w, y + h + 1, 0xFFFFFF, Paint.Align.RIGHT);
    }

    private void font(float dp, boolean bold) {
        textPaint.setTextSize(dp * ui);
        textPaint.setFakeBoldText(false);
        textPaint.setTypeface(bold ? Typeface.create("sans-serif-medium", Typeface.NORMAL) : Typeface.create("sans-serif", Typeface.NORMAL));
    }

    /** Text with its top at y. */
    private void text(Canvas c, String s, float x, float y, int color, Paint.Align align) {
        textPaint.setColor(argb(color, 1));
        textPaint.setTextAlign(align);
        c.drawText(s, x, y - textPaint.ascent(), textPaint);
    }

    private String clock(long epochMs) {
        return DateFormat.getTimeInstance(DateFormat.SHORT).format(new Date(epochMs));
    }

    private void box(Canvas c, float l, float t, float r, float b) {
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(argb(0x101820, 0.78f));
        c.drawRoundRect(l, t, r, b, 6 * ui, 6 * ui, paint);
    }

    /** The radar's time, the loop's state and the legend, in a box at (bx, by). */
    private float drawLoopBox(Canvas c, float bx, float by, float bw) {
        final float bh = 88 * ui;
        box(c, bx, by, bx + bw, by + bh);
        String when = wx.radarValid == 0 ? "Radar: loading..." : "Radar " + clock(wx.frameTime(frame));
        font(13.5f, true);
        text(c, when, bx + 10 * ui, by + 7 * ui, 0xFFFFFF, Paint.Align.LEFT);
        float y = by + 28 * ui;
        String state = playing ? "▶  playing  (tap to pause)" : "❚❚  paused  (tap to play)";
        if (smoothMotion()) state = (playing ? "▶  playing" : "❚❚  paused") + "   • smooth motion";
        font(11.5f, false);
        text(c, state, bx + 10 * ui, y, 0xC8D6E5, Paint.Align.LEFT);
        y += 20 * ui;
        // the loop's frames, as a row of ticks
        float tx = bx + 10 * ui, tw = (bw - 20 * ui) / Weather.FRAME_COUNT;
        for (int f = 0; f < Weather.FRAME_COUNT; ++f) {
            paint.setColor(argb(f == frame ? 0xFFD166 : 0x5C7080, 1));
            c.drawRect(tx + f * tw + 1, y + 2 * ui, tx + (f + 1) * tw - 1, y + 6 * ui, paint);
        }
        framesBar.set(tx, y - 8 * ui, tx + tw * Weather.FRAME_COUNT, y + 14 * ui);
        drawLegend(c, bx + 10 * ui, y + 10 * ui, bw - 20 * ui, 11.5f);
        return by + bh;
    }

    private void drawOverlay(Canvas c) {
        final float W = width, H = height;
        hits.clear();
        final boolean wide = W >= 480 * ui;
        // the Layers box (or its button), top right
        final float lw = 190 * ui, row = 30 * ui, lx = W - lw - 10 * ui, ly = 10 * ui;
        final float buttonW = 44 * ui;
        // the radar's time and the loop's state, top left
        float bw = Math.min(230 * ui, W - 20 * ui - (wide ? lw + 10 * ui : buttonW + 10 * ui));
        float loopBottom = drawLoopBox(c, 10 * ui, 10 * ui, bw);
        RectF loopBox = new RectF(10 * ui, 10 * ui, 10 * ui + bw, loopBottom);
        hits.add(new Hit(new RectF(framesBar), HIT_FRAMES));
        hits.add(new Hit(loopBox, HIT_PLAY));

        if (!wide && !layersOpen) {
            RectF b = new RectF(W - buttonW - 10 * ui, ly, W - 10 * ui, ly + buttonW);
            box(c, b.left, b.top, b.right, b.bottom);
            drawLayersIcon(c, b.centerX(), b.centerY());
            hits.add(new Hit(b, HIT_LAYERS));
        } else {
            String[] names = { "Radar", "Clouds (satellite)", "Warnings" };
            boolean[] on = { wx.showRadar, wx.showClouds, wx.showAlerts };
            int[] what = { HIT_RADAR, HIT_CLOUDS, HIT_WARNINGS };
            float top = !wide ? ly : ly;
            final float lh = 32 * ui + row * names.length;
            box(c, lx, top, lx + lw, top + lh);
            font(13.5f, true);
            text(c, "Layers", lx + 10 * ui, top + 7 * ui, 0xFFFFFF, Paint.Align.LEFT);
            if (!wide) {
                font(13.5f, false);
                text(c, "✕", lx + lw - 12 * ui, top + 7 * ui, 0x9FB0C2, Paint.Align.RIGHT);
                hits.add(new Hit(new RectF(lx + lw - 44 * ui, top, lx + lw, top + 32 * ui), HIT_LAYERS));
            }
            for (int i = 0; i < names.length; ++i) {
                float ry = top + 32 * ui + i * row;
                RectF bx = new RectF(lx + 10 * ui, ry + 6 * ui, lx + 28 * ui, ry + 24 * ui);
                paint.setStyle(Paint.Style.FILL);
                paint.setColor(argb(on[i] ? 0x2E86DE : 0x3A4B5C, 1));
                c.drawRoundRect(bx, 3 * ui, 3 * ui, paint);
                if (on[i]) {
                    paint.setColor(Color.WHITE);
                    paint.setStrokeWidth(2 * ui);
                    paint.setStrokeCap(Paint.Cap.ROUND);
                    c.drawLine(bx.left + 4 * ui, bx.top + 9.5f * ui, bx.left + 7.5f * ui, bx.top + 13 * ui, paint);
                    c.drawLine(bx.left + 7.5f * ui, bx.top + 13 * ui, bx.left + 14 * ui, bx.top + 5 * ui, paint);
                }
                font(13.5f, false);
                text(c, names[i], lx + 38 * ui, ry + 6 * ui, on[i] ? 0xFFFFFF : 0x9FB0C2, Paint.Align.LEFT);
                hits.add(new Hit(new RectF(lx, ry, lx + lw, ry + row), what[i]));
            }
            if (!wide) hits.add(new Hit(new RectF(lx, top, lx + lw, top + lh), -1));     // (a tap in the box is not on the map)
        }

        // the zoom buttons, bottom right
        final float bs = 44 * ui, zx = W - bs - 10 * ui, zy = H - 24 * ui - 2 * bs - 14 * ui;
        String[] signs = { "+", "−" };
        for (int i = 0; i < 2; ++i) {
            RectF b = new RectF(zx, zy + i * (bs + 6 * ui), zx + bs, zy + i * (bs + 6 * ui) + bs);
            box(c, b.left, b.top, b.right, b.bottom);
            font(22, false);
            textPaint.setColor(Color.WHITE);
            textPaint.setTextAlign(Paint.Align.CENTER);
            c.drawText(signs[i], b.centerX(), b.centerY() - (textPaint.ascent() + textPaint.descent()) / 2, textPaint);
            hits.add(new Hit(b, i == 0 ? HIT_ZOOM_IN : HIT_ZOOM_OUT));
        }

        // the credits and the status, along the bottom
        drawCredits(c, W, H, true);
        int busy = Net.busy();
        String line = !status.isEmpty() ? status : busy > 0 ? "Loading (" + busy + ")..." : "";
        if (!line.isEmpty()) {
            font(11.5f, false);
            float tw = Math.min(textPaint.measureText(line), W - 40 * ui);
            float sy = Math.max(loopBottom, wide ? 0 : ly + buttonW) + 8 * ui;
            float sx = wide ? W - tw - 22 * ui : 10 * ui;
            if (wide) sy = 10 * ui + 32 * ui + row * 3 + 8 * ui;
            box(c, sx, sy, sx + tw + 12 * ui, sy + 22 * ui);
            c.save();
            c.clipRect(sx, sy, sx + tw + 12 * ui, sy + 22 * ui);
            text(c, line, sx + 6 * ui, sy + 4 * ui, 0xFFFFFF, Paint.Align.LEFT);
            c.restore();
        }
    }

    private void drawCredits(Canvas c, float W, float H, boolean tappable) {
        final String full = Weather.MAP_CREDIT + "  •  Radar: NOAA NEXRAD via Iowa Environmental Mesonet  •  Clouds: NOAA GOES via nowCOAST  •  Forecasts & alerts: National Weather Service";
        final String brief = Weather.MAP_CREDIT + "  •  NOAA  •  NWS" + (tappable ? "  (tap for more)" : "");
        font(11.5f, false);
        String s = textPaint.measureText(full) <= W - 16 * ui ? full : brief;
        float h = 20 * ui;
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(argb(0x101820, 0.7f));
        c.drawRect(0, H - h, W, H, paint);
        text(c, s, 8 * ui, H - h + 3 * ui, 0xDDE3EA, Paint.Align.LEFT);
        if (tappable) hits.add(new Hit(new RectF(0, H - h - 6 * ui, W - 60 * ui, H), HIT_CREDITS));
    }

    private void drawLayersIcon(Canvas c, float x, float y) {
        // three stacked diamonds
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(1.8f * ui);
        paint.setStrokeJoin(Paint.Join.ROUND);
        paint.setColor(Color.WHITE);
        for (int i = 0; i < 3; ++i) {
            float cy = y - 6 * ui + i * 6 * ui;
            path.rewind();
            path.moveTo(x - 11 * ui, cy);
            path.lineTo(x, cy - 5 * ui);
            path.lineTo(x + 11 * ui, cy);
            path.lineTo(x, cy + 5 * ui);
            path.close();
            c.drawPath(path, paint);
        }
        paint.setStyle(Paint.Style.FILL);
    }

    /** The hit (a control on the map) at a point, or 0. */
    public int hitAt(float x, float y) {
        for (Hit h : hits)
            if (h.r.contains(x, y)) return h.what;
        return 0;
    }

    public void release() {
        dropMotion();
        Net.dropWaitingImages(this);
    }
}
