package com.ferrellsl.wxradar;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * WxRadar: the National Weather Service API (api.weather.gov) - forecasts, the latest
 * observation and alerts (a port of the Windows app's Nws.cpp).  Free, no key; the NWS
 * only asks for a User-Agent that says who is asking (Net sends one).
 */
public final class Nws {
    private Nws() { }

    static final String API = "https://api.weather.gov";

    /** One forecast period: "Tonight", or an hour. */
    public static final class Period {
        public String name = "";          // "Tonight" ("" for hours)
        public String start = "";         // ISO time it begins, e.g. 2026-10-21T15:00:00-07:00
        public boolean daytime = true;
        public int temperature;
        public String unit = "";          // "F"
        public int precipitation = -1;    // chance in percent, -1 if not given
        public String wind = "";          // "5 to 10 mph SW"
        public String shortForecast = ""; // "Mostly Sunny"
        public String detailed = "";      // the forecaster's sentences (daily only)
    }

    /** The latest at the nearest station; NaN = missing. */
    public static final class Observation {
        public String station = "", stationName = "", time = "", text = "";
        public double tempC = Double.NaN, dewpointC = Double.NaN, humidity = Double.NaN, windKmh = Double.NaN,
                windDegrees = Double.NaN, gustKmh = Double.NaN, pressurePa = Double.NaN, visibilityM = Double.NaN;
    }

    public static final class Ring {
        public final double[] lon, lat;
        Ring(double[] lon, double[] lat) { this.lon = lon; this.lat = lat; }
    }

    public static final class Alert {
        public String id = "", event = "", headline = "", severity = "", area = "";
        public String description = "", instruction = "", sender = "";
        public String onset = "", expires = "", ends = "";
        public final List<Ring> rings = new ArrayList<>();   // its polygon(s), outer rings; none for zone-based alerts
        public int color = 0xFFFFFF;                          // the NWS's color for this kind of alert (RGB)
    }

    public static final class Forecast {
        public double lat, lon;
        public String place = "";         // "Mesa, AZ"
        public String office = "";        // "PSR"
        public List<Period> daily = new ArrayList<>(), hourly = new ArrayList<>();
        public Observation now = new Observation();
        public List<Alert> alerts = new ArrayList<>();       // those in effect at this point
        public String error = "";         // why there is none ("" if fine)
        public int waiting;               // parts still coming
    }

    public interface ForecastUpdate { void update(Forecast f); }
    public interface AlertsDone { void done(boolean ok, List<Alert> alerts); }

    static String coords(double lat, double lon) {
        return String.format(Locale.US, "%.4f,%.4f", lat, lon);
    }

    static double value(Json quantity) {          // {"unitCode": ..., "value": 21.3} -> 21.3, or NaN
        Json v = quantity.get("value");
        return v.isNumber() ? v.num() : Double.NaN;
    }

    static List<Period> periods(Json doc, int most) {
        List<Period> out = new ArrayList<>();
        for (Json p : doc.get("properties").get("periods").array()) {
            if (out.size() >= most) break;
            Period q = new Period();
            q.name = p.get("name").str();
            q.start = p.get("startTime").str();
            q.daytime = p.get("isDaytime").bool();
            q.temperature = (int) Math.round(p.get("temperature").num());
            q.unit = p.get("temperatureUnit").str();
            Json pop = p.get("probabilityOfPrecipitation").get("value");
            q.precipitation = pop.isNumber() ? (int) Math.round(pop.num()) : -1;
            q.wind = p.get("windSpeed").str();
            if (!p.get("windDirection").str().isEmpty()) q.wind += " " + p.get("windDirection").str();
            q.shortForecast = p.get("shortForecast").str();
            q.detailed = p.get("detailedForecast").str();
            out.add(q);
        }
        return out;
    }

    private static void addRing(Json coordinates, Alert a) {   // [[lon, lat], ...]
        int n = coordinates.size();
        if (n < 3) return;
        double[] lon = new double[n], lat = new double[n];
        for (int i = 0; i < n; ++i) {
            lon[i] = coordinates.at(i).at(0).num();
            lat[i] = coordinates.at(i).at(1).num();
        }
        a.rings.add(new Ring(lon, lat));
    }

    static List<Alert> alerts(Json doc) {
        List<Alert> out = new ArrayList<>();
        for (Json f : doc.get("features").array()) {
            Json p = f.get("properties");
            Alert a = new Alert();
            a.id = p.get("id").str();
            a.event = p.get("event").str();
            a.headline = p.get("headline").str();
            a.severity = p.get("severity").str();
            a.area = p.get("areaDesc").str();
            a.description = p.get("description").str();
            a.instruction = p.get("instruction").str();
            a.sender = p.get("senderName").str();
            a.onset = p.get("onset").str();
            a.expires = p.get("expires").str();
            a.ends = p.get("ends").str();
            a.color = alertColor(a.event);
            Json g = f.get("geometry");
            String type = g.get("type").str();
            if (type.equals("Polygon"))
                addRing(g.get("coordinates").at(0), a);
            else if (type.equals("MultiPolygon"))
                for (Json polygon : g.get("coordinates").array())
                    addRing(polygon.at(0), a);
            out.add(a);
        }
        return out;
    }

    private static final java.util.Map<String, Integer> generations = new java.util.HashMap<>();   // the newest loadForecast per slot

    /**
     * Starts loading everything for a point; update is called each time a part arrives (on
     * the main thread), with the forecast so far.  A newer call makes the older one's late
     * answers be ignored (per slot: each screen that shows a forecast has its own).
     */
    public static void loadForecast(String slot, double lat, double lon, ForecastUpdate update) {
        Integer last = generations.get(slot);
        final int mine = last == null ? 1 : last + 1;
        generations.put(slot, mine);
        final Forecast f = new Forecast();
        f.lat = lat;
        f.lon = lon;
        f.waiting = 1;
        final java.util.function.BooleanSupplier stale = () -> generations.get(slot) != mine;

        // the point -> its forecast office and grid square, and the URLs of the rest
        Net.getText(API + "/points/" + coords(lat, lon), r -> {
            if (stale.getAsBoolean()) return;
            f.waiting--;
            Json p = Json.parse(r.body).get("properties");
            if (!r.ok() || !p.isObject()) {
                f.error = r.status == 404 ? "The National Weather Service forecasts only the United States and its territories."
                        : r.status == 0 ? "No answer from the National Weather Service (are you online?)."
                        : "The National Weather Service had no forecast for this point (HTTP " + r.status + ").";
                update.update(f);
                return;
            }
            Json where = p.get("relativeLocation").get("properties");
            f.place = where.get("city").str() + (where.get("state").str().isEmpty() ? "" : ", " + where.get("state").str());
            f.office = p.get("gridId").str();
            f.waiting = 4;
            update.update(f);

            Net.getText(p.get("forecast").str(), r2 -> {
                if (stale.getAsBoolean()) return;
                f.waiting--;
                if (r2.ok()) f.daily = periods(Json.parse(r2.body), 14);
                else if (f.error.isEmpty()) f.error = "The forecast didn't come (HTTP " + r2.status + "); it will be tried again.";
                update.update(f);
            });
            Net.getText(p.get("forecastHourly").str(), r2 -> {
                if (stale.getAsBoolean()) return;
                f.waiting--;
                if (r2.ok()) f.hourly = periods(Json.parse(r2.body), 48);
                update.update(f);
            });
            Net.getText(API + "/alerts/active?point=" + coords(lat, lon), r2 -> {
                if (stale.getAsBoolean()) return;
                f.waiting--;
                if (r2.ok()) f.alerts = alerts(Json.parse(r2.body));
                update.update(f);
            });
            // the observation: the nearest station first, then its latest report
            Net.getText(p.get("observationStations").str(), r2 -> {
                if (stale.getAsBoolean()) return;
                Json first = Json.parse(r2.body).get("features").at(0).get("properties");
                String id = first.get("stationIdentifier").str();
                if (!r2.ok() || id.isEmpty()) { f.waiting--; update.update(f); return; }
                f.now.station = id;
                f.now.stationName = first.get("name").str();
                Net.getText(API + "/stations/" + id + "/observations/latest", r3 -> {
                    if (stale.getAsBoolean()) return;
                    f.waiting--;
                    Json o = Json.parse(r3.body).get("properties");
                    if (r3.ok() && o.isObject()) {
                        f.now.time = o.get("timestamp").str();
                        f.now.text = o.get("textDescription").str();
                        f.now.tempC = value(o.get("temperature"));
                        f.now.dewpointC = value(o.get("dewpoint"));
                        f.now.humidity = value(o.get("relativeHumidity"));
                        f.now.windKmh = value(o.get("windSpeed"));
                        f.now.windDegrees = value(o.get("windDirection"));
                        f.now.gustKmh = value(o.get("windGust"));
                        f.now.pressurePa = value(o.get("barometricPressure"));
                        f.now.visibilityM = value(o.get("visibility"));
                    }
                    update.update(f);
                });
            });
        });
    }

    /** All the alerts in effect in the US now. */
    public static void loadAlerts(AlertsDone done) {
        Net.getText(API + "/alerts/active?status=actual", r -> {
            List<Alert> list = new ArrayList<>();
            boolean ok = false;
            if (r.ok()) {
                Json doc = Json.parse(r.body);
                ok = doc.isObject();
                list = alerts(doc);
            }
            done.done(ok, list);
        });
    }

    private static final Object[][] COLORS = {
        { "Tornado Warning", 0xFF0000 },               { "Tornado Watch", 0xFFFF00 },
        { "Severe Thunderstorm Warning", 0xFFA500 },   { "Severe Thunderstorm Watch", 0xDB7093 },
        { "Flash Flood Warning", 0x8B0000 },           { "Flash Flood Watch", 0x2E8B57 },
        { "Flood Warning", 0x00FF00 },                 { "Flood Watch", 0x2E8B57 },
        { "Flood Advisory", 0x00FF7F },                { "Special Marine Warning", 0xFFA500 },
        { "Hurricane Warning", 0xDC143C },             { "Hurricane Watch", 0xFF00FF },
        { "Tropical Storm Warning", 0xB22222 },        { "Tropical Storm Watch", 0xF08080 },
        { "Storm Surge Warning", 0xB524F7 },           { "Extreme Wind Warning", 0xFF8C00 },
        { "Blizzard Warning", 0xFF4500 },              { "Winter Storm Warning", 0xFF69B4 },
        { "Winter Storm Watch", 0x4682B4 },            { "Winter Weather Advisory", 0x7B68EE },
        { "Ice Storm Warning", 0x8B008B },             { "High Wind Warning", 0xDAA520 },
        { "Wind Advisory", 0xD2B48C },                 { "Excessive Heat Warning", 0xC71585 },
        { "Heat Advisory", 0xFF7F50 },                 { "Dense Fog Advisory", 0x708090 },
        { "Red Flag Warning", 0xFF1493 },              { "Fire Weather Watch", 0xFFDEAD },
        { "Dust Storm Warning", 0xFFE4C4 },            { "Special Weather Statement", 0xFFE4B5 },
        { "Gale Warning", 0xDDA0DD },                  { "Storm Warning", 0x9400D3 },
        { "Small Craft Advisory", 0xD8BFD8 },          { "Coastal Flood Warning", 0x228B22 },
        { "Coastal Flood Advisory", 0x7CFC00 },        { "High Surf Advisory", 0xBA55D3 },
        { "Rip Current Statement", 0x40E0D0 },         { "Freeze Warning", 0x483D8B },
        { "Frost Advisory", 0x6495ED },
    };

    /** The NWS's own colors for its alerts (weather.gov's map legend); anything else a neutral tan. */
    public static int alertColor(String e) {
        for (Object[] t : COLORS)
            if (t[0].equals(e)) return (Integer) t[1];
        return 0xD2B48C;
    }
}
