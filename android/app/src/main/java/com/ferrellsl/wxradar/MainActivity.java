package com.ferrellsl.wxradar;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.text.InputType;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.Menu;
import android.view.MenuItem;
import android.view.SubMenu;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.PopupMenu;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.Locale;

/**
 * WxRadar for Android: the map with the radar loop, clouds and warnings, and beside it (or
 * below it, on a phone held upright) the panel - where, now, the alerts there, the next
 * hours and days.
 */
public final class MainActivity extends Activity implements MapView.Listener {
    // the panel's colors (as the Windows app's)
    static final int PANEL = 0xFF18222D, BAR = 0xFF101820, TEXT = 0xFFC8D6E5, DIM = 0xFF8FA3B8, WHITE = 0xFFFFFFFF,
            RULE = 0xFF33475B, RAIN = 0xFF7FC8FF, ERROR = 0xFFFFB4A2, NIGHT = 0xFFB8C6D6;
    static final int PANEL_DP = 360;              // the panel's width at first (wide screens)
    static final int PANEL_MIN_DP = 240, MAP_MIN_DP = 280;

    private Weather wx;
    private MapView mapView;
    private FrameLayout mapFrame;
    private View anchor;
    private EditText search;
    private LinearLayout root, content, panel, header, body;
    private ScrollView bodyScroll;
    private TextView chevron;
    private boolean wide, panelOpen, panelHidden;
    private int panelWidthDp;
    private View divider;
    private android.widget.ImageView panelButton;

    // the point the forecast is for
    private final Weather.Place picked = new Weather.Place();
    private Nws.Forecast forecast = new Nws.Forecast();
    private long forecastAt;
    private final Handler handler = new Handler(Looper.getMainLooper());

    private float dp(float v) { return v * getResources().getDisplayMetrics().density; }
    private int px(float v) { return Math.round(dp(v)); }

    @Override protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        wx = Weather.get(this);
        SharedPreferences p = wx.prefs();
        panelOpen = p.getBoolean("panelOpen", true);
        panelHidden = p.getBoolean("panelHidden", false);
        panelWidthDp = p.getInt("panelWidth", PANEL_DP);

        root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(BAR);
        root.setOnApplyWindowInsetsListener((v, insets) -> {
            android.graphics.Insets i = insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout() | WindowInsets.Type.ime());
            v.setPadding(i.left, i.top, i.right, i.bottom);
            return WindowInsets.CONSUMED;
        });

        // the top bar: search, home, the menu
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(px(10), px(6), px(4), px(6));
        search = new EditText(this);
        search.setSingleLine(true);
        search.setHint("Search a place (city, ZIP, address)");
        search.setHintTextColor(DIM);
        search.setTextColor(0xFFE8EEF4);
        search.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        search.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_POSTAL_ADDRESS);
        search.setImeOptions(EditorInfo.IME_ACTION_SEARCH);
        search.setPadding(px(12), px(8), px(12), px(8));
        GradientDrawable field = new GradientDrawable();
        field.setColor(0xFF24303C);
        field.setCornerRadius(dp(8));
        search.setBackground(field);
        search.setOnEditorActionListener((v, action, e) -> {
            if (action == EditorInfo.IME_ACTION_SEARCH || action == EditorInfo.IME_ACTION_DONE || action == EditorInfo.IME_NULL) {
                search(search.getText().toString().trim());
                hideKeyboard();
                return true;
            }
            return false;
        });
        bar.addView(search, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        panelButton = new android.widget.ImageView(this);
        panelButton.setImageResource(R.drawable.ic_panel);
        panelButton.setScaleType(android.widget.ImageView.ScaleType.CENTER);
        TypedValue ripple = new TypedValue();
        getTheme().resolveAttribute(android.R.attr.selectableItemBackgroundBorderless, ripple, true);
        panelButton.setBackgroundResource(ripple.resourceId);
        panelButton.setOnClickListener(v -> setPanelHidden(!panelHidden));
        bar.addView(panelButton, new LinearLayout.LayoutParams(px(48), px(44)));
        TextView homeButton = barButton("⌂", "Go home");
        homeButton.setOnClickListener(v -> goHome());
        bar.addView(homeButton);
        TextView menuButton = barButton("⋮", "More");
        menuButton.setOnClickListener(this::showMenu);
        bar.addView(menuButton);
        root.addView(bar, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // the map
        mapView = new MapView(this);
        mapView.setListener(this);
        mapView.map.pinHome = wx.home;
        mapView.map.pinPicked = picked;
        mapView.map.darkMap = wx.darkMode;
        mapFrame = new FrameLayout(this);
        mapFrame.addView(mapView, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        anchor = new View(this);
        mapFrame.addView(anchor, new FrameLayout.LayoutParams(1, 1));

        // the panel: a header (where, now) and the rest, scrolling
        panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setBackgroundColor(PANEL);
        header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(px(14), px(8), px(10), px(8));
        header.setOnClickListener(v -> { if (!wide) { panelOpen = !panelOpen; layoutPanel(); wx.prefs().edit().putBoolean("panelOpen", panelOpen).apply(); } });
        panel.addView(header, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        bodyScroll = new ScrollView(this);
        bodyScroll.setFillViewport(true);
        body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(px(14), 0, px(14), px(20));
        bodyScroll.addView(body, new ScrollView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        panel.addView(bodyScroll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));

        divider = new PanelDivider(this);
        content = new LinearLayout(this);
        root.addView(content, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
        content.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> {
            if (r - l != or - ol || b - t != ob - ot) handler.post(this::layoutPanel);
        });
        setContentView(root);

        // the last place and view
        if (p.contains("viewLat")) {
            mapView.map.camY = MapRenderer.worldY(p.getFloat("viewLat", 38.5f));
            mapView.map.camX = MapRenderer.worldX(p.getFloat("viewLon", -96.5f));
            mapView.map.zoom = p.getFloat("zoom", 6);
        } else if (wx.home.set) {
            mapView.map.camX = MapRenderer.worldX(wx.home.lon);
            mapView.map.camY = MapRenderer.worldY(wx.home.lat);
            mapView.map.zoom = 8;
        } else {
            mapView.map.camX = MapRenderer.worldX(-96.5);       // (the lower 48)
            mapView.map.camY = MapRenderer.worldY(38.5);
            mapView.map.zoom = 4;
        }
        mapView.map.zoom = Math.max(2, Math.min(Weather.MAP_MAX_ZOOM, mapView.map.zoom));

        if (wx.home.set) {
            loadForecast(wx.home.lat, wx.home.lon, wx.home.name);
        } else {
            mapView.map.status = "Long-press the map: \"Make this my home\" - or search for your town.";
            fillPanel();
            // first run: where is this phone? (if allowed)
            if (Where.allowed(this)) locateHome();
            else requestPermissions(new String[] { Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.ACCESS_FINE_LOCATION }, 1);
        }
        handler.postDelayed(refresh, 60_000);
    }

    private TextView barButton(String sign, String description) {
        TextView b = new TextView(this);
        b.setText(sign);
        b.setTextColor(WHITE);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 24);
        b.setGravity(Gravity.CENTER);
        b.setContentDescription(description);
        b.setMinWidth(px(48));
        b.setMinHeight(px(44));
        TypedValue tv = new TypedValue();
        getTheme().resolveAttribute(android.R.attr.selectableItemBackgroundBorderless, tv, true);
        b.setBackgroundResource(tv.resourceId);
        return b;
    }

    @Override public void onRequestPermissionsResult(int code, String[] permissions, int[] results) {
        if (code == 1 && Where.allowed(this) && !wx.home.set) locateHome();
    }

    private void locateHome() {
        Where.find(this, where -> {
            if (where == null || wx.home.set) return;
            wx.setHome(where.getLatitude(), where.getLongitude(), "");
            mapView.map.status = "";
            mapView.map.centerOn(wx.home.lat, wx.home.lon, 8);
            loadForecast(wx.home.lat, wx.home.lon, "");
        });
    }

    @Override protected void onPause() {
        super.onPause();
        wx.prefs().edit()
            .putFloat("viewLat", (float) MapRenderer.latOf(mapView.map.camY))
            .putFloat("viewLon", (float) MapRenderer.lonOf(mapView.map.camX))
            .putFloat("zoom", (float) mapView.map.zoom)
            .apply();
    }

    @Override protected void onDestroy() {
        handler.removeCallbacks(refresh);
        super.onDestroy();
    }

    private final Runnable refresh = new Runnable() {
        @Override public void run() {
            if (picked.set && SystemClock.uptimeMillis() - forecastAt > 15 * 60_000) loadForecast(picked.lat, picked.lon, picked.name);
            handler.postDelayed(this, 60_000);
        }
    };

    // ------------------------------------------------------------- layout
    /** Wide (a tablet, a phone on its side, an unfolded phone): the panel at the side; else below the map. */
    private void layoutPanel() {
        int w = content.getWidth(), h = content.getHeight();
        if (w == 0) return;
        boolean nowWide = w >= dp(720) || (w > h && w >= dp(560));
        if (nowWide != wide || content.getChildCount() == 0) {
            wide = nowWide;
            content.removeAllViews();
            content.setOrientation(wide ? LinearLayout.HORIZONTAL : LinearLayout.VERTICAL);
            if (wide) {
                content.addView(mapFrame, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1));
                content.addView(divider, new LinearLayout.LayoutParams(px(14), ViewGroup.LayoutParams.MATCH_PARENT));
                content.addView(panel, new LinearLayout.LayoutParams(px(PANEL_DP), ViewGroup.LayoutParams.MATCH_PARENT));
            } else {
                content.addView(mapFrame, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
                content.addView(panel, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            }
        }
        panel.setVisibility(panelHidden ? View.GONE : View.VISIBLE);
        divider.setVisibility(wide && !panelHidden ? View.VISIBLE : View.GONE);
        panelButton.setAlpha(panelHidden ? 0.55f : 1f);
        panelButton.setContentDescription(panelHidden ? "Show the forecast" : "Hide the forecast");
        LinearLayout.LayoutParams lp = (LinearLayout.LayoutParams) panel.getLayoutParams();
        if (!wide) {
            lp.height = panelOpen ? Math.round(h * 0.45f) : ViewGroup.LayoutParams.WRAP_CONTENT;
            bodyScroll.setVisibility(panelOpen ? View.VISIBLE : View.GONE);
        } else {
            lp.width = clampPanel(px(panelWidthDp), w);
            bodyScroll.setVisibility(View.VISIBLE);
        }
        panel.setLayoutParams(lp);
        fillHeader();
    }

    /** A panel width that leaves the map room (wide screens). */
    private int clampPanel(int widthPx, int contentPx) {
        int most = Math.max(px(PANEL_MIN_DP), contentPx - px(14) - px(MAP_MIN_DP));
        return Math.max(px(PANEL_MIN_DP), Math.min(most, widthPx));
    }

    /** The forecast panel away (the map takes the whole width) or back. */
    private void setPanelHidden(boolean hidden) {
        panelHidden = hidden;
        wx.prefs().edit().putBoolean("panelHidden", hidden).apply();
        layoutPanel();
    }

    /** The panel back, open, to show a forecast just asked for. */
    private void showPanel() {
        if (!panelHidden && (wide || panelOpen)) return;
        panelOpen = true;
        wx.prefs().edit().putBoolean("panelOpen", true).apply();
        setPanelHidden(false);
    }

    /**
     * The handle between the map and the panel (wide screens): drag it to make the panel
     * narrower or wider; dragged almost to the edge, the panel goes away (the top bar's
     * panel button brings it back).
     */
    private final class PanelDivider extends View {
        private final android.graphics.Paint grip = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
        private boolean dragging;

        PanelDivider(Context c) {
            super(c);
            setBackgroundColor(0xFF0B1118);
            setContentDescription("Drag to resize the forecast");
        }

        @Override protected void onDraw(android.graphics.Canvas c) {
            float x = getWidth() / 2f, y = getHeight() / 2f;
            grip.setColor(dragging ? 0xFF2E86DE : 0xFF5C7080);
            c.drawRoundRect(x - dp(2), y - dp(22), x + dp(2), y + dp(22), dp(2), dp(2), grip);
        }

        @Override public boolean onTouchEvent(android.view.MotionEvent e) {
            int[] at = new int[2];
            content.getLocationOnScreen(at);
            int right = at[0] + content.getWidth();
            int width = Math.round(right - e.getRawX() - getWidth() / 2f);
            switch (e.getActionMasked()) {
                case android.view.MotionEvent.ACTION_DOWN:
                    dragging = true;
                    getParent().requestDisallowInterceptTouchEvent(true);
                    invalidate();
                    return true;
                case android.view.MotionEvent.ACTION_MOVE: {
                    LinearLayout.LayoutParams lp = (LinearLayout.LayoutParams) panel.getLayoutParams();
                    lp.width = clampPanel(width, content.getWidth());
                    panel.setLayoutParams(lp);
                    return true;
                }
                case android.view.MotionEvent.ACTION_UP:
                case android.view.MotionEvent.ACTION_CANCEL:
                    dragging = false;
                    invalidate();
                    if (e.getActionMasked() == android.view.MotionEvent.ACTION_UP && width < px(PANEL_MIN_DP) - px(60)) {
                        setPanelHidden(true);      // (dragged nearly off: away; its width stays as it was)
                        return true;
                    }
                    panelWidthDp = Math.round(panel.getLayoutParams().width / getResources().getDisplayMetrics().density);
                    wx.prefs().edit().putInt("panelWidth", panelWidthDp).apply();
                    return true;
            }
            return super.onTouchEvent(e);
        }
    }

    // ------------------------------------------------------------- the panel
    private TextView label(String s, float sp, int color, boolean bold) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        t.setTypeface(Typeface.create(bold ? "sans-serif-medium" : "sans-serif", Typeface.NORMAL));
        t.setLineSpacing(0, 1.05f);
        return t;
    }

    private void add(LinearLayout to, View v, float topDp) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = px(topDp);
        to.addView(v, lp);
    }

    private void rule() {
        View r = new View(this);
        r.setBackgroundColor(RULE);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, px(1)));
        lp.topMargin = px(12);
        lp.bottomMargin = px(8);
        body.addView(r, lp);
    }

    private boolean pickedIsHome() {
        return wx.home.set && Math.abs(picked.lat - wx.home.lat) < 1e-6 && Math.abs(picked.lon - wx.home.lon) < 1e-6;
    }

    /** The header: where, and the temperature now. */
    private void fillHeader() {
        header.removeAllViews();
        LinearLayout text = new LinearLayout(this);
        text.setOrientation(LinearLayout.VERTICAL);
        String where = !picked.set ? "No place picked yet" : !forecast.place.isEmpty() ? forecast.place : !picked.name.isEmpty() ? picked.name : "This point";
        text.addView(label(where + (picked.set && pickedIsHome() ? "  ⌂" : ""), 19, WHITE, true));
        Nws.Observation o = forecast.now;
        if (picked.set && !Double.isNaN(o.tempC) && !wide && !panelOpen)   // (open, the panel shows it big)
            text.addView(label(Words.fahrenheit(o.tempC) + "  " + o.text, 14, TEXT, false));
        header.addView(text, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        if (!wide) {
            chevron = label(panelOpen ? "▾" : "▴", 20, DIM, false);
            chevron.setPadding(px(10), 0, px(4), 0);
            header.addView(chevron);
            header.setContentDescription(panelOpen ? "Hide the forecast" : "Show the forecast");
        }
    }

    /** The rest: the point, now, the alerts there, the next hours, the days. */
    private void fillPanel() {
        fillHeader();
        body.removeAllViews();
        if (!picked.set) {
            add(body, label("Long-press the map and choose \"Forecast here\", or search for a place above.", 14, TEXT, false), 4);
            return;
        }
        add(body, label(String.format(Locale.US, "%.3f°, %.3f°%s", picked.lat, picked.lon,
                forecast.office.isEmpty() ? "" : "   NWS " + forecast.office), 12, DIM, false), 0);
        if (!forecast.error.isEmpty()) add(body, label(forecast.error, 14, ERROR, false), 6);

        // now
        Nws.Observation o = forecast.now;
        if (!Double.isNaN(o.tempC)) {
            LinearLayout now = new LinearLayout(this);
            now.setOrientation(LinearLayout.HORIZONTAL);
            now.setGravity(Gravity.CENTER_VERTICAL);
            TextView temp = label(Words.fahrenheit(o.tempC), 46, WHITE, false);
            temp.setTypeface(Typeface.create("sans-serif-light", Typeface.NORMAL));
            now.addView(temp, new LinearLayout.LayoutParams(px(110), ViewGroup.LayoutParams.WRAP_CONTENT));
            LinearLayout right = new LinearLayout(this);
            right.setOrientation(LinearLayout.VERTICAL);
            right.addView(label(o.text, 14, 0xFFE8EEF4, true));
            String when = Words.clock(o.time);
            right.addView(label(o.station + (when.isEmpty() ? "" : "  at " + when), 12, DIM, false));
            now.addView(right, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
            add(body, now, 4);
            String details = Words.details(o);
            if (!details.isEmpty()) add(body, label(details, 14, TEXT, false), 2);
        } else if (forecast.waiting > 0) {
            add(body, label("Loading...", 14, DIM, false), 6);
        }

        // alerts in effect here
        for (Nws.Alert a : forecast.alerts) {
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            View stripe = new View(this);
            stripe.setBackgroundColor(0xFF000000 | a.color);
            row.addView(stripe, new LinearLayout.LayoutParams(px(5), ViewGroup.LayoutParams.MATCH_PARENT));
            LinearLayout words = new LinearLayout(this);
            words.setOrientation(LinearLayout.VERTICAL);
            words.setPadding(px(10), px(2), 0, px(2));
            words.addView(label(a.event, 14, WHITE, true));
            words.addView(label("Tap for the details", 12, DIM, false));
            row.addView(words, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
            row.setOnClickListener(v -> showAlert(a));
            TypedValue tv = new TypedValue();
            getTheme().resolveAttribute(android.R.attr.selectableItemBackground, tv, true);
            row.setForeground(getDrawable(tv.resourceId));
            add(body, row, 8);
        }

        // the next hours
        if (!forecast.hourly.isEmpty()) {
            rule();
            add(body, label("Next hours", 14, WHITE, true), 0);
            for (int i = 0; i < forecast.hourly.size() && i < 12; ++i) {
                Nws.Period p = forecast.hourly.get(i);
                LinearLayout row = new LinearLayout(this);
                row.setOrientation(LinearLayout.HORIZONTAL);
                row.addView(label(Words.hourOf(p.start), 14, TEXT, false), new LinearLayout.LayoutParams(px(62), ViewGroup.LayoutParams.WRAP_CONTENT));
                row.addView(label(p.temperature + "°", 14, WHITE, true), new LinearLayout.LayoutParams(px(43), ViewGroup.LayoutParams.WRAP_CONTENT));
                row.addView(label(p.precipitation > 0 ? p.precipitation + "%" : "", 14, RAIN, false), new LinearLayout.LayoutParams(px(43), ViewGroup.LayoutParams.WRAP_CONTENT));
                row.addView(label(Words.brief(p.shortForecast), 14, TEXT, false), new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
                add(body, row, i == 0 ? 6 : 2);
            }
        }

        // the days
        if (!forecast.daily.isEmpty()) {
            rule();
            add(body, label("Forecast", 14, WHITE, true), 0);
            boolean first = true;
            for (Nws.Period p : forecast.daily) {
                String head = p.name + "   " + (p.daytime ? "High " : "Low ") + p.temperature + "°" + (p.precipitation > 0 ? "   " + p.precipitation + "%" : "");
                add(body, label(head, 14, p.daytime ? WHITE : NIGHT, true), first ? 6 : 10);
                add(body, label(p.detailed, 14, TEXT, false), 1);
                first = false;
            }
        }
    }

    private void loadForecast(double lat, double lon, String name) {
        picked.lat = lat;
        picked.lon = lon;
        picked.name = name == null ? "" : name;
        picked.set = true;
        forecastAt = SystemClock.uptimeMillis();
        forecast = new Nws.Forecast();
        forecast.lat = lat;
        forecast.lon = lon;
        forecast.waiting = 1;
        bodyScroll.scrollTo(0, 0);
        fillPanel();
        mapView.invalidate();
        Nws.loadForecast("phone", lat, lon, f -> {
            forecast = f;
            // the home place takes its name from the NWS when it has none
            if (wx.home.set && wx.home.name.isEmpty() && Math.abs(f.lat - wx.home.lat) < 1e-6 && Math.abs(f.lon - wx.home.lon) < 1e-6 && !f.place.isEmpty()) {
                wx.home.name = f.place;
                wx.save();
            }
            fillPanel();
        });
    }

    private void showAlert(Nws.Alert a) {
        new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle(a.event)
            .setIcon(a.severity.equals("Extreme") || a.severity.equals("Severe") ? android.R.drawable.ic_dialog_alert : android.R.drawable.ic_dialog_info)
            .setMessage(Words.alertText(a))
            .setPositiveButton("OK", null)
            .show();
    }

    // ------------------------------------------------------------- actions
    private void hideKeyboard() {
        InputMethodManager im = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
        if (im != null) im.hideSoftInputFromWindow(search.getWindowToken(), 0);
        search.clearFocus();
    }

    private void goHome() {
        if (!wx.home.set) {
            mapView.map.status = "No home yet: long-press the map, \"Make this my home\".";
            mapView.invalidate();
            return;
        }
        mapView.map.centerOn(wx.home.lat, wx.home.lon, Math.max(mapView.map.zoom, 8.0));
        loadForecast(wx.home.lat, wx.home.lon, wx.home.name);
    }

    /** Search: a place name, through OpenStreetMap's Nominatim (US first). */
    private void search(String text) {
        if (text.isEmpty()) return;
        mapView.map.status = "Searching for " + text + "...";
        mapView.invalidate();
        Net.getText("https://nominatim.openstreetmap.org/search?format=jsonv2&limit=1&countrycodes=us,pr,vi,gu,as,mp&q=" + Words.escape(text), r -> {
            Json first = Json.parse(r.body).at(0);
            if (!r.ok() || !first.isObject()) {
                mapView.map.status = "Nothing found for \"" + text + "\".";
                mapView.invalidate();
                handler.postDelayed(() -> { mapView.map.status = ""; mapView.invalidate(); }, 4000);
                return;
            }
            double lat, lon;
            try {
                lat = Double.parseDouble(first.get("lat").str());
                lon = Double.parseDouble(first.get("lon").str());
            } catch (NumberFormatException e) { return; }
            mapView.map.status = "";
            mapView.map.centerOn(lat, lon, Math.max(mapView.map.zoom, 9.0));
            loadForecast(lat, lon, "");
            showPanel();
        });
    }

    private static final int CMD_GO_HOME = 1, CMD_LOCATE = 2, CMD_ALERTS = 3, CMD_DARK = 4, CMD_REFRESH = 5, CMD_ABOUT = 6,
            CMD_OPACITY_50 = 10, CMD_OPACITY_75 = 11, CMD_OPACITY_100 = 12, CMD_FORECAST_HERE = 20, CMD_HOME_HERE = 21;

    private void showMenu(View v) {
        PopupMenu m = new PopupMenu(this, v);
        Menu menu = m.getMenu();
        menu.add(0, CMD_GO_HOME, 0, "Go home").setEnabled(wx.home.set);
        menu.add(0, CMD_LOCATE, 0, "Make where I am my home");
        menu.add(0, CMD_ALERTS, 0, "Show warnings on the map").setCheckable(true).setChecked(wx.showAlerts);
        SubMenu opacity = menu.addSubMenu(0, 0, 0, "Radar opacity");
        opacity.add(1, CMD_OPACITY_50, 0, "50%").setChecked(wx.radarOpacity < 0.6f);
        opacity.add(1, CMD_OPACITY_75, 0, "75%").setChecked(wx.radarOpacity >= 0.6f && wx.radarOpacity < 0.9f);
        opacity.add(1, CMD_OPACITY_100, 0, "100%").setChecked(wx.radarOpacity >= 0.9f);
        opacity.setGroupCheckable(1, true, true);
        menu.add(0, CMD_DARK, 0, "Dark map").setCheckable(true).setChecked(wx.darkMode);
        menu.add(0, CMD_REFRESH, 0, "Refresh now");
        menu.add(0, CMD_ABOUT, 0, "About WxRadar");
        m.setOnMenuItemClickListener(this::command);
        m.show();
    }

    private double menuLat, menuLon;

    private boolean command(MenuItem item) {
        switch (item.getItemId()) {
            case CMD_FORECAST_HERE:
                loadForecast(menuLat, menuLon, "");
                showPanel();
                break;
            case CMD_HOME_HERE:
                wx.setHome(menuLat, menuLon, "");
                mapView.map.status = "";
                loadForecast(wx.home.lat, wx.home.lon, "");
                break;
            case CMD_GO_HOME: goHome(); break;
            case CMD_LOCATE:
                if (!Where.allowed(this)) {
                    requestPermissions(new String[] { Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.ACCESS_FINE_LOCATION }, 2);
                    break;
                }
                mapView.map.status = "Finding where you are...";
                mapView.invalidate();
                Where.find(this, where -> {
                    mapView.map.status = where == null ? "Android couldn't tell where you are (is Location on?)." : "";
                    if (where != null) {
                        wx.setHome(where.getLatitude(), where.getLongitude(), "");
                        mapView.map.centerOn(wx.home.lat, wx.home.lon, Math.max(mapView.map.zoom, 8.0));
                        loadForecast(wx.home.lat, wx.home.lon, "");
                    }
                    mapView.invalidate();
                });
                break;
            case CMD_ALERTS: wx.showAlerts = !wx.showAlerts; wx.save(); break;
            case CMD_DARK:
                wx.darkMode = !wx.darkMode;
                wx.save();
                mapView.map.darkMap = wx.darkMode;
                mapView.map.requestView();
                break;
            case CMD_OPACITY_50: wx.radarOpacity = 0.5f; wx.save(); break;
            case CMD_OPACITY_75: wx.radarOpacity = 0.75f; wx.save(); break;
            case CMD_OPACITY_100: wx.radarOpacity = 1.0f; wx.save(); break;
            case CMD_REFRESH:
                wx.refresh();
                if (picked.set) loadForecast(picked.lat, picked.lon, picked.name);
                break;
            case CMD_ABOUT: about(); break;
            default: return false;
        }
        mapView.invalidate();
        return true;
    }

    private void about() {
        String version = "";
        try { version = getPackageManager().getPackageInfo(getPackageName(), 0).versionName; } catch (PackageManager.NameNotFoundException ignored) { }
        new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("About WxRadar")
            .setMessage("WxRadar " + version + " - weather radar, warnings and forecasts, without ads.\n\n"
                + "Radar: NOAA NEXRAD composite reflectivity, as map tiles from the Iowa Environmental Mesonet (Iowa State University).\n"
                + "Clouds: NOAA GOES East/West longwave infrared, from NOAA nowCOAST.\n"
                + "Forecasts, observations and alerts: the National Weather Service (api.weather.gov).\n"
                + "Map: © OpenStreetMap contributors (openstreetmap.org/copyright); place search: OpenStreetMap Nominatim.\n\n"
                + "Drag to move, pinch or double-tap to zoom, tap a warning for its text, long-press for a forecast or to set home. "
                + "Tap the radar box to play or pause; slide along its row of ticks to step through the loop.\n\n"
                + "No ads, no tracking, no account: https://github.com/ferrellsl/WxRadar")
            .setPositiveButton("OK", null)
            .show();
    }

    // ------------------------------------------------------------- MapView.Listener
    @Override public void mapTapped(float x, float y) {
        hideKeyboard();
        Nws.Alert a = wx.alertAt(mapView.map.latAt(y), mapView.map.lonAt(x));
        if (a != null) showAlert(a);
    }

    @Override public void mapLongPressed(float x, float y) {
        menuLat = mapView.map.latAt(y);
        menuLon = mapView.map.lonAt(x);
        anchor.setTranslationX(x);
        anchor.setTranslationY(y);
        mapView.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS);
        PopupMenu m = new PopupMenu(this, anchor);
        m.getMenu().add(0, CMD_FORECAST_HERE, 0, "Forecast here");
        m.getMenu().add(0, CMD_HOME_HERE, 0, "Make this my home");
        m.setOnMenuItemClickListener(this::command);
        m.show();
    }

    @Override public void controlTapped(int what) {
        MapRenderer map = mapView.map;
        switch (what) {
            case MapRenderer.HIT_PLAY: map.setPlaying(!map.playing); return;
            case MapRenderer.HIT_LAYERS: map.layersOpen = !map.layersOpen; mapView.invalidate(); return;
            case MapRenderer.HIT_CREDITS: about(); return;
            case MapRenderer.HIT_RADAR: wx.showRadar = !wx.showRadar; break;
            case MapRenderer.HIT_CLOUDS: wx.showClouds = !wx.showClouds; if (wx.showClouds) wx.checkSatellite(); break;
            case MapRenderer.HIT_WARNINGS: wx.showAlerts = !wx.showAlerts; break;
            case MapRenderer.HIT_ZOOM_IN: map.zoomAt(map.width / 2f, map.height / 2f, 1); return;
            case MapRenderer.HIT_ZOOM_OUT: map.zoomAt(map.width / 2f, map.height / 2f, -1); return;
            default: return;
        }
        wx.save();
        map.layersChanged();
    }

    @Override public void viewMoved() { }

    static int color(int rgb) { return Color.rgb((rgb >> 16) & 255, (rgb >> 8) & 255, rgb & 255); }
}
