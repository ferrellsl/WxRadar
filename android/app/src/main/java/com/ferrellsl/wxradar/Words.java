package com.ferrellsl.wxradar;

import java.text.DateFormat;
import java.util.Date;
import java.util.Locale;

/** WxRadar: how the NWS's numbers and words are put on the screen (phone and car alike). */
public final class Words {
    private Words() { }

    /** The local time written in an NWS time (its own offset): "3 PM". */
    public static String hourOf(String iso) {
        if (iso == null || iso.length() < 13) return "";
        int h;
        try { h = Integer.parseInt(iso.substring(11, 13)); } catch (NumberFormatException e) { return ""; }
        return (h % 12 != 0 ? h % 12 : 12) + (h < 12 ? " AM" : " PM");
    }

    /** The NWS's short forecasts, shortened to fit a row: "Chance Showers & T-storms". */
    public static String brief(String text) {
        return text.replace("Thunderstorms", "T-storms").replace(" And ", " & ");
    }

    public static String compass(double degrees) {
        final String[] names = { "N", "NNE", "NE", "ENE", "E", "ESE", "SE", "SSE", "S", "SSW", "SW", "WSW", "W", "WNW", "NW", "NNW" };
        return names[(int) Math.floor(((degrees + 371.25) % 360.0) / 22.5) % 16];
    }

    public static String clock(String iso) {
        long t = Weather.parseIso(iso);
        return t == 0 ? "" : DateFormat.getTimeInstance(DateFormat.SHORT).format(new Date(t));
    }

    public static String fahrenheit(double c) {
        return String.format(Locale.US, "%.0f°", c * 9 / 5 + 32);
    }

    /** The wind, humidity, pressure and visibility lines of an observation. */
    public static String details(Nws.Observation o) {
        StringBuilder d = new StringBuilder();
        if (!Double.isNaN(o.windKmh)) {
            d.append(o.windKmh < 1 ? "Wind calm"
                    : String.format(Locale.US, "Wind %s %.0f mph", Double.isNaN(o.windDegrees) ? "" : compass(o.windDegrees), o.windKmh * 0.621371));
            if (!Double.isNaN(o.gustKmh)) d.append(String.format(Locale.US, ", gusts %.0f", o.gustKmh * 0.621371));
            d.append('\n');
        }
        if (!Double.isNaN(o.humidity)) d.append(String.format(Locale.US, "Humidity %.0f%%", o.humidity));
        if (!Double.isNaN(o.dewpointC)) d.append("   Dew point ").append(fahrenheit(o.dewpointC));
        if (!Double.isNaN(o.pressurePa)) d.append(String.format(Locale.US, "\nPressure %.2f in", o.pressurePa * 0.00029530));
        if (!Double.isNaN(o.visibilityM)) d.append(String.format(Locale.US, "   Visibility %.0f mi", o.visibilityM / 1609.34));
        return d.toString().trim();
    }

    /** An alert's full text, as the Windows app's message box shows it. */
    public static String alertText(Nws.Alert a) {
        StringBuilder b = new StringBuilder(a.headline);
        if (!a.area.isEmpty()) b.append("\n\nAreas: ").append(a.area);
        if (!a.description.isEmpty()) b.append("\n\n").append(a.description);
        if (!a.instruction.isEmpty()) b.append("\n\n").append(a.instruction);
        b.append("\n\n— ").append(a.sender);
        return b.toString();
    }

    /** Text for a URL's query. */
    public static String escape(String q) {
        StringBuilder out = new StringBuilder();
        for (byte b : q.getBytes(java.nio.charset.StandardCharsets.UTF_8)) {
            int c = b & 255;
            if ((c >= '0' && c <= '9') || (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || c == '-' || c == '.' || c == '_') out.append((char) c);
            else out.append(String.format(Locale.US, "%%%02X", c));
        }
        return out.toString();
    }
}
