package com.ferrellsl.wxradar;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

import java.io.File;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * WxRadar: the shared state behind the map and the panel - the radar's and the
 * satellite's times, the national alerts, the map tiles, home and the settings - and the
 * housekeeping that keeps them fresh while anything is shown.
 *
 * Data (all free, no keys):
 *   map tiles    OpenStreetMap (tile.openstreetmap.org), cached on disk for a week as its
 *                tile usage policy asks
 *   radar        NOAA NEXRAD composite reflectivity (N0Q), as map tiles from the Iowa
 *                Environmental Mesonet: the latest picture and the 23 before it, 5 minutes apart
 *   clouds       NOAA GOES East/West longwave infrared, from NOAA's nowCOAST map service (WMS,
 *                5-minute pictures), made into see-through white clouds and matched to the
 *                radar's times
 *   forecasts,   the National Weather Service API, api.weather.gov (Nws)
 *   alerts
 *   place names  OpenStreetMap's Nominatim, for the search box
 */
public final class Weather {
    public static final int FRAME_COUNT = 24;            // radar pictures in the loop: two hours, 5 minutes apart
    public static final int RADAR_MAX_ZOOM = 8;          // radar tiles: no finer than the data (about one 0.5 km cell a pixel)
    public static final int SAT_MAX_ZOOM = 6;            // satellite (2 km pictures): no finer than this
    public static final int MAP_MAX_ZOOM = 18;
    static final int MAX_TILES = 1100;                   // bitmaps kept (two hours of radar and clouds, and the map)

    // tile layers: the map; the radar frames; the satellite frames; the map turned dark
    public static final int MAP = 0;
    public static int radarLayer(int f) { return 1 + f; }
    public static int satLayer(int f) { return 1 + FRAME_COUNT + f; }
    public static final int MAP_DARK = 1 + 2 * FRAME_COUNT;
    public static boolean isSatLayer(int layer) { return layer > FRAME_COUNT && layer < MAP_DARK; }
    public static boolean isMapLayer(int layer) { return layer == MAP || layer == MAP_DARK; }

    static final String OSM_TILES = "https://tile.openstreetmap.org/{z}/{x}/{y}.png";
    public static final String MAP_CREDIT = "Map © OpenStreetMap contributors";

    public static final class Place {
        public double lat, lon;
        public String name = "";
        public boolean set;
    }

    public static final class Tile {
        public Bitmap bitmap;
        boolean pending;
        long failedAt;
        long used;
        int request;                         // the newest request for it (older answers are ignored)
        public byte[] strength;              // for working out the motion: radar 64 x 64, clouds 128 x 128
    }

    private static Weather instance;

    public static Weather get(Context context) {
        if (instance == null) instance = new Weather(context.getApplicationContext());
        return instance;
    }

    private final Context app;
    private final SharedPreferences prefs;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final File cacheFolder;

    // places and settings
    public final Place home = new Place();
    public float radarOpacity = 0.75f;
    public boolean showRadar = true, showClouds = false, showAlerts = true, darkMode = false;

    // radar
    public long radarValid;                  // epoch ms of the newest picture (0: not known yet)
    private long radarCheckedAt;

    // clouds: the satellite pictures' times (nowCOAST), and the one nearest each radar frame
    private List<String> satTimes = new ArrayList<>();
    public final String[] satFrame = new String[FRAME_COUNT];   // "" = none near enough
    private long satCheckedAt;

    // alerts
    public List<Nws.Alert> alerts = new ArrayList<>();
    public boolean alertsOk;
    private long alertsAt;

    // tiles
    public final Map<Long, Tile> tiles = new HashMap<>();
    private int tileRequests;

    /** What is shown (the map): told when something new can be drawn. */
    public interface Viewer {
        void weatherChanged();
        /** The satellite clouds are wanted (so their times are kept fresh). */
        boolean wantsClouds();
        /** The radar's frames now mean newer pictures: motion and frames to be redone. */
        void radarReplaced();
        void cloudsReplaced();
    }

    private final List<Viewer> viewers = new ArrayList<>();

    private Weather(Context app) {
        this.app = app;
        prefs = app.getSharedPreferences("wxradar", Context.MODE_PRIVATE);
        cacheFolder = new File(app.getCacheDir(), "tiles");
        java.util.Arrays.fill(satFrame, "");
        if (prefs.contains("homeLat")) {
            home.lat = prefs.getFloat("homeLat", 0);
            home.lon = prefs.getFloat("homeLon", 0);
            home.name = prefs.getString("homeName", "");
            home.set = true;
        }
        radarOpacity = prefs.getFloat("radarOpacity", 0.75f);
        showRadar = prefs.getBoolean("showRadar", true);
        showClouds = prefs.getBoolean("showClouds", false);
        showAlerts = prefs.getBoolean("showAlerts", true);
        // (dark mode: as last chosen; the first time, as the phone is set)
        boolean night = (app.getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
        darkMode = prefs.getBoolean("darkMode", night);
        Net.start(4);
    }

    public SharedPreferences prefs() { return prefs; }

    public void save() {
        SharedPreferences.Editor e = prefs.edit();
        if (home.set) {
            e.putFloat("homeLat", (float) home.lat);
            e.putFloat("homeLon", (float) home.lon);
            e.putString("homeName", home.name);
        }
        e.putFloat("radarOpacity", radarOpacity);
        e.putBoolean("showRadar", showRadar);
        e.putBoolean("showClouds", showClouds);
        e.putBoolean("showAlerts", showAlerts);
        e.putBoolean("darkMode", darkMode);
        e.apply();
    }

    public void setHome(double lat, double lon, String name) {
        home.lat = lat;
        home.lon = lon;
        home.name = name == null ? "" : name;
        home.set = true;
        save();
    }

    // ------------------------------------------------------------- viewers and housekeeping
    public void attach(Viewer v) {
        if (viewers.contains(v)) return;
        viewers.add(v);
        if (viewers.size() == 1) {
            handler.removeCallbacks(tick);
            tick.run();
        }
    }

    public void detach(Viewer v) {
        viewers.remove(v);
        if (viewers.isEmpty()) handler.removeCallbacks(tick);
    }

    void changed() {
        for (Viewer v : new ArrayList<>(viewers)) v.weatherChanged();
    }

    private boolean cloudsWanted() {
        for (Viewer v : viewers) if (v.wantsClouds()) return true;
        return false;
    }

    private final Runnable tick = new Runnable() {
        @Override public void run() {
            long now = SystemClock.uptimeMillis();
            if (radarCheckedAt == 0 || now - radarCheckedAt > 60_000) checkRadar();
            if (alertsAt == 0 || now - alertsAt > 2 * 60_000) loadAlerts();
            if (cloudsWanted() && (satCheckedAt == 0 || now - satCheckedAt > 5 * 60_000)) checkSatellite();
            handler.postDelayed(this, 1000);
        }
    };

    /** Everything again now (F5 on Windows). */
    public void refresh() {
        dropWeatherTiles();
        radarValid = 0;
        for (Viewer v : new ArrayList<>(viewers)) v.radarReplaced();
        checkRadar();
        loadAlerts();
        if (cloudsWanted()) checkSatellite();
    }

    // ------------------------------------------------------------- data
    public void checkRadar() {
        radarCheckedAt = SystemClock.uptimeMillis();
        Net.getText("https://mesonet.agron.iastate.edu/data/gis/images/4326/USCOMP/n0q_0.json", r -> {
            if (!r.ok()) return;
            long valid = parseIso(Json.parse(r.body).get("meta").get("valid").str());
            if (valid == 0 || valid == radarValid) return;
            boolean first = radarValid == 0;
            radarValid = valid;
            if (!first) dropWeatherTiles();         // (the frames' names now mean newer pictures)
            java.util.Arrays.fill(satFrame, "");
            for (Viewer v : new ArrayList<>(viewers)) v.radarReplaced();
            matchSatFrames();
            changed();
        });
    }

    /** The time of radar frame f (epoch ms). */
    public long frameTime(int f) {
        return radarValid - (long) (FRAME_COUNT - 1 - f) * 5 * 60_000;
    }

    /**
     * Each radar frame gets the satellite picture nearest its time (within 8 minutes); when
     * that changes, the old cloud tiles go.
     */
    private void matchSatFrames() {
        if (radarValid == 0 || satTimes.isEmpty()) return;
        boolean changed = false;
        for (int f = 0; f < FRAME_COUNT; ++f) {
            long want = frameTime(f);
            String best = "";
            double bestGap = 8 * 60;
            for (String s : satTimes) {
                long t = parseIso(s);
                if (t == 0) continue;
                double gap = Math.abs(t - want) / 1000.0;
                if (gap <= bestGap) { bestGap = gap; best = s; }
            }
            if (!best.equals(satFrame[f])) { satFrame[f] = best; changed = true; }
        }
        if (changed) {
            for (Iterator<Map.Entry<Long, Tile>> it = tiles.entrySet().iterator(); it.hasNext(); )
                if (isSatLayer(layerOf(it.next().getKey()))) it.remove();
            for (Viewer v : new ArrayList<>(viewers)) v.cloudsReplaced();
            changed();
        }
    }

    /** Can the clouds move from frame f to f + 1: two satellite pictures about 5 minutes apart? */
    public boolean cloudPair(int f) {
        if (f < 0 || f + 1 >= FRAME_COUNT || satFrame[f].isEmpty() || satFrame[f + 1].isEmpty()) return false;
        long a = parseIso(satFrame[f]), b = parseIso(satFrame[f + 1]);
        if (a == 0 || b == 0) return false;
        double minutes = (b - a) / 60000.0;
        return minutes > 3 && minutes < 7;
    }

    /** The times nowCOAST has GOES infrared pictures for (its WMS capabilities). */
    public void checkSatellite() {
        satCheckedAt = SystemClock.uptimeMillis();
        Net.getText("https://nowcoast.noaa.gov/geoserver/satellite/wms?service=WMS&version=1.3.0&request=GetCapabilities", r -> {
            if (!r.ok()) return;
            List<String> times = satelliteTimes(r.body);
            if (times.isEmpty()) return;
            satTimes = times;
            matchSatFrames();
        });
    }

    /** The goes_longwave_imagery layer's times, from nowCOAST's capabilities document. */
    static List<String> satelliteTimes(String body) {
        List<String> times = new ArrayList<>();
        int at = body.indexOf("<Name>goes_longwave_imagery</Name>");
        if (at < 0) return times;
        int dim = body.indexOf("<Dimension name=\"time\"", at);
        int open = dim < 0 ? -1 : body.indexOf('>', dim);
        int close = open < 0 ? -1 : body.indexOf('<', open);
        if (close < 0) return times;
        for (String t : body.substring(open + 1, close).split(",")) {
            t = t.trim();
            if (!t.isEmpty()) times.add(t);
        }
        return times;
    }

    public void loadAlerts() {
        alertsAt = SystemClock.uptimeMillis();
        Nws.loadAlerts((ok, list) -> {
            if (!ok) return;                      // (keep the last ones)
            alerts = list;
            alertsOk = true;
            changed();
        });
    }

    /** The alert (storm-based: drawn on the map) at a point, the last drawn first; null if none. */
    public Nws.Alert alertAt(double lat, double lon) {
        if (!showAlerts) return null;
        for (int a = alerts.size() - 1; a >= 0; --a)
            for (Nws.Ring r : alerts.get(a).rings) {
                boolean inside = false;
                for (int i = 0, j = r.lon.length - 1; i < r.lon.length; j = i++)
                    if (((r.lat[i] > lat) != (r.lat[j] > lat)) &&
                        (lon < (r.lon[j] - r.lon[i]) * (lat - r.lat[i]) / (r.lat[j] - r.lat[i]) + r.lon[i]))
                        inside = !inside;
                if (inside) return alerts.get(a);
            }
        return null;
    }

    // ------------------------------------------------------------- tiles
    public static long tileKey(int layer, int z, int x, int y) {
        return ((long) layer << 56) | ((long) z << 50) | ((long) x << 25) | (long) y;
    }

    static int layerOf(long key) { return (int) (key >>> 56); }

    private void dropWeatherTiles() {
        for (Iterator<Map.Entry<Long, Tile>> it = tiles.entrySet().iterator(); it.hasNext(); )
            if (!isMapLayer(layerOf(it.next().getKey()))) it.remove();
    }

    void trimTiles() {
        if (tiles.size() <= MAX_TILES) return;
        List<long[]> byAge = new ArrayList<>();
        for (Map.Entry<Long, Tile> t : tiles.entrySet())
            if (!t.getValue().pending) byAge.add(new long[] { t.getValue().used, t.getKey() });
        Collections.sort(byAge, (a, b) -> Long.compare(a[0], b[0]));
        int remove = tiles.size() - MAX_TILES * 3 / 4;
        for (int i = 0; i < remove && i < byAge.size(); ++i)
            tiles.remove(byAge.get(i)[1]);
    }

    public Tile tile(int layer, int z, int x, int y) {
        return tiles.get(tileKey(layer, z, x, y));
    }

    private static final DateTimeFormatter RADAR_NAME = DateTimeFormatter.ofPattern("yyyyMMddHHmm", Locale.US).withZone(ZoneOffset.UTC);

    /** Ask for a tile (owner: whose request it is, for Net.dropWaitingImages). */
    public void requestTile(int layer, int z, int x, int y, Object owner) {
        final long key = tileKey(layer, z, x, y);
        Tile t = tiles.get(key);
        if (t == null) { t = new Tile(); tiles.put(key, t); }
        long now = SystemClock.uptimeMillis();
        t.used = now;
        if (t.bitmap != null || t.pending) return;
        if (t.failedAt != 0 && now - t.failedAt < 60_000) return;   // (try a failed one again a minute later)
        t.pending = true;

        String url;
        File cache = null;
        long age = 0;
        Net.Filter filter = Net.Filter.AS_IS;
        Net.Strength strength = Net.Strength.NONE;
        if (isMapLayer(layer)) {
            url = OSM_TILES.replace("{z}", Integer.toString(z)).replace("{x}", Integer.toString(x)).replace("{y}", Integer.toString(y));
            cache = new File(cacheFolder, "osm/" + z + "/" + x + "/" + y + ".png");
            age = 7L * 24 * 3600 * 1000;
            if (layer == MAP_DARK) filter = Net.Filter.DARK;
        } else if (isSatLayer(layer)) {
            // nowCOAST's WMS: the tile's square in Web Mercator meters, at one time
            String when = satFrame[layer - 1 - FRAME_COUNT];
            if (when.isEmpty()) { t.pending = false; t.failedAt = now; return; }
            final double R = 20037508.342789244, size = 2 * R / (1 << z);
            url = String.format(Locale.US, "https://nowcoast.noaa.gov/geoserver/satellite/wms?service=WMS&version=1.3.0&request=GetMap"
                    + "&layers=goes_longwave_imagery&styles=&format=image/png&transparent=true&crs=EPSG:3857"
                    + "&bbox=%.2f,%.2f,%.2f,%.2f&width=256&height=256&time=%s",
                    -R + x * size, R - (y + 1) * size, -R + (x + 1) * size, R - y * size, when);
            filter = Net.Filter.CLOUDS;
            strength = Net.Strength.CLOUDS;
        } else {
            // the frame's own time, e.g. ridge::USCOMP-N0Q-202610211545 (with "minutes before
            // the latest" names, tiles fetched either side of a new picture wouldn't match)
            if (radarValid == 0) { t.pending = false; return; }
            String when = RADAR_NAME.format(Instant.ofEpochMilli(frameTime(layer - 1)));
            url = "https://mesonet.agron.iastate.edu/cache/tile.py/1.0.0/ridge::USCOMP-N0Q-" + when + "/" + z + "/" + x + "/" + y + ".png";
            strength = Net.Strength.RADAR;
        }

        // (a tile dropped and asked for again - new radar or satellite times - is a new
        // request: the old one's answer, if it comes after, is not this tile's)
        final int request = t.request = ++tileRequests;
        Net.getImage(url, cache, age, filter, strength, owner, r -> {
            Tile tl = tiles.get(key);
            if (tl == null || tl.request != request) return;          // (dropped meanwhile)
            tl.pending = false;
            if (r.status == 0 && r.bitmap == null) return;            // (dropped from the queue: asked again if still needed)
            if (!r.ok() || r.bitmap == null) { tl.failedAt = SystemClock.uptimeMillis(); return; }
            tl.bitmap = r.bitmap;
            tl.strength = r.strength;
            changed();
        });
    }

    // ------------------------------------------------------------- times
    /** 2026-10-21T15:45:00Z (or with an offset, or fractions of a second) -> epoch ms; 0 if not a time. */
    public static long parseIso(String s) {
        if (s == null || s.isEmpty()) return 0;
        try { return OffsetDateTime.parse(s).toInstant().toEpochMilli(); } catch (RuntimeException ignored) { }
        try { return Instant.parse(s).toEpochMilli(); } catch (RuntimeException ignored) { }
        try {      // (no offset: UTC)
            return java.time.LocalDateTime.parse(s.length() > 19 ? s.substring(0, 19) : s).toInstant(ZoneOffset.UTC).toEpochMilli();
        } catch (RuntimeException ignored) { }
        return 0;
    }
}
