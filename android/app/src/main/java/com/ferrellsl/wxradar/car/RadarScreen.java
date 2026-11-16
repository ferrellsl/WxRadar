package com.ferrellsl.wxradar.car;

import android.app.Presentation;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.graphics.Rect;
import android.location.Location;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

import androidx.annotation.NonNull;
import androidx.car.app.AppManager;
import androidx.car.app.CarContext;
import androidx.car.app.Screen;
import androidx.car.app.SurfaceCallback;
import androidx.car.app.SurfaceContainer;
import androidx.car.app.hardware.CarHardwareManager;
import androidx.car.app.hardware.common.CarValue;
import androidx.car.app.hardware.common.OnCarDataAvailableListener;
import androidx.car.app.hardware.info.Speed;
import androidx.car.app.model.Action;
import androidx.car.app.model.ActionStrip;
import androidx.car.app.model.CarColor;
import androidx.car.app.model.CarIcon;
import androidx.car.app.model.Header;
import androidx.car.app.model.ItemList;
import androidx.car.app.model.ListTemplate;
import androidx.car.app.model.Row;
import androidx.car.app.model.Template;
import androidx.car.app.navigation.model.MapController;
import androidx.car.app.navigation.model.MapWithContentTemplate;
import androidx.core.graphics.drawable.IconCompat;
import androidx.lifecycle.DefaultLifecycleObserver;
import androidx.lifecycle.LifecycleOwner;

import com.ferrellsl.wxradar.MapRenderer;
import com.ferrellsl.wxradar.Nws;
import com.ferrellsl.wxradar.R;
import com.ferrellsl.wxradar.Weather;
import com.ferrellsl.wxradar.Where;
import com.ferrellsl.wxradar.Words;

import java.util.Locale;

/**
 * The car's screen: the radar map, drawn by the same renderer as the phone's, with a list
 * beside it - the temperature now, the warnings in effect, the next hours, tonight or
 * tomorrow - for where the car is (or home, if Android won't say).
 *
 * The car rules for weather apps: no animation while driving, so the loop plays only while
 * the car is parked (its speed is known and near zero); while moving, the newest picture.
 */
public final class RadarScreen extends Screen implements SurfaceCallback, DefaultLifecycleObserver {
    private final Weather wx;
    private final MapRenderer map;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private VirtualDisplay display;
    private Presentation presentation;
    private CarMapView view;
    private boolean created;

    // where the forecast is for, and whether the map follows the car
    private final Weather.Place here = new Weather.Place();
    private boolean follow = true, located;
    private Nws.Forecast forecast;
    private long forecastAt;

    // the car's speed: the loop only while parked
    private boolean parked, wantsLoop = true;
    private OnCarDataAvailableListener<Speed> speedListener;

    public RadarScreen(@NonNull CarContext context) {
        super(context);
        wx = Weather.get(context);
        map = new MapRenderer(wx, MapRenderer.Style.CAR, this::requestDraw);
        map.pinHome = wx.home;
        map.pinPicked = here;
        map.pinPickedColor = 0x27AE60;
        map.animationAllowed = false;
        map.darkMap = context.isDarkMode();
        if (wx.home.set) map.centerOn(wx.home.lat, wx.home.lon, 8);
        else map.centerOn(38.5, -96.5, 4);
        getLifecycle().addObserver(this);
    }

    // ------------------------------------------------------------- lifecycle
    @Override public void onCreate(@NonNull LifecycleOwner owner) {
        created = true;
        getCarContext().getCarService(AppManager.class).setSurfaceCallback(this);
        wx.attach(map);
        listenToSpeed();
        locate.run();
    }

    @Override public void onDestroy(@NonNull LifecycleOwner owner) {
        created = false;
        handler.removeCallbacksAndMessages(null);
        releaseDisplay();
        wx.detach(map);
        map.release();
        try {
            if (speedListener != null)
                getCarContext().getCarService(CarHardwareManager.class).getCarInfo().removeSpeedListener(speedListener);
        } catch (RuntimeException ignored) { }
    }

    void darkModeChanged() {
        map.darkMap = getCarContext().isDarkMode();
        map.requestView();
        requestDraw();
    }

    private void listenToSpeed() {
        try {
            CarHardwareManager hw = getCarContext().getCarService(CarHardwareManager.class);
            speedListener = speed -> {
                // (Android Auto gives the speed the car shows; the raw sensor value often isn't passed on)
                CarValue<Float> v = speed.getRawSpeedMetersPerSecond();
                if (v.getStatus() != CarValue.STATUS_SUCCESS || v.getValue() == null) v = speed.getDisplaySpeedMetersPerSecond();
                boolean nowParked = v.getStatus() == CarValue.STATUS_SUCCESS && v.getValue() != null && Math.abs(v.getValue()) < 0.5f;
                if (nowParked != parked) {
                    parked = nowParked;
                    loopRules();
                    invalidate();
                }
            };
            hw.getCarInfo().addSpeedListener(getCarContext().getMainExecutor(), speedListener);
        } catch (RuntimeException e) {          // (no speed from this car: the newest picture only)
            speedListener = null;
        }
    }

    private void loopRules() {
        boolean allowed = parked && wantsLoop;
        if (allowed != map.animationAllowed) {
            map.animationAllowed = allowed;
            map.setPlaying(allowed);
            map.requestView();
        }
        map.status = parked ? "" : "Loop plays when parked";
        requestDraw();
    }

    // ------------------------------------------------------------- where
    private final Runnable locate = new Runnable() {
        @Override public void run() {
            if (!created) return;
            Where.find(getCarContext(), where -> {
                if (!created) return;
                if (where != null) moveTo(where);
                else if (!located && wx.home.set) useHome();
            });
            handler.postDelayed(this, 60_000);
            if (forecast != null && here.set && SystemClock.uptimeMillis() - forecastAt > 15 * 60_000) loadForecast();
        }
    };

    private void useHome() {
        here.lat = wx.home.lat;
        here.lon = wx.home.lon;
        here.name = wx.home.name;
        here.set = true;
        located = true;
        if (follow) map.centerOn(here.lat, here.lon, Math.max(map.zoom, 8));
        loadForecast();
    }

    private void moveTo(Location where) {
        float[] km = new float[1];
        boolean far = !here.set;
        if (here.set) {
            Location.distanceBetween(here.lat, here.lon, where.getLatitude(), where.getLongitude(), km);
            far = km[0] > 5000;
        }
        here.lat = where.getLatitude();
        here.lon = where.getLongitude();
        here.name = "";
        here.set = true;
        if (follow) map.centerOn(here.lat, here.lon, !located ? 8 : map.zoom);
        located = true;
        if (far || forecast == null) loadForecast();
        requestDraw();
    }

    private void loadForecast() {
        forecastAt = SystemClock.uptimeMillis();
        Nws.loadForecast("car", here.lat, here.lon, f -> {
            forecast = f;
            if (f.waiting <= 1) invalidate();        // (when most of it is here: the car limits how often a screen changes)
        });
    }

    // ------------------------------------------------------------- the template
    private CarIcon icon(int id) {
        return new CarIcon.Builder(IconCompat.createWithResource(getCarContext(), id)).build();
    }

    @NonNull @Override public Template onGetTemplate() {
        // the map's own buttons: pan, zoom, back to the car
        ActionStrip mapActions = new ActionStrip.Builder()
                .addAction(Action.PAN)
                .addAction(new Action.Builder().setIcon(icon(R.drawable.ic_zoom_in)).setOnClickListener(() -> zoom(1)).build())
                .addAction(new Action.Builder().setIcon(icon(R.drawable.ic_zoom_out)).setOnClickListener(() -> zoom(-1)).build())
                .addAction(new Action.Builder().setIcon(icon(R.drawable.ic_my_location)).setOnClickListener(this::recenter).build())
                .build();
        ActionStrip.Builder actions = new ActionStrip.Builder();
        if (parked) {
            actions.addAction(new Action.Builder()
                    .setIcon(icon(wantsLoop ? R.drawable.ic_pause : R.drawable.ic_play))
                    .setOnClickListener(() -> { wantsLoop = !wantsLoop; loopRules(); invalidate(); })
                    .build());
        }
        actions.addAction(new Action.Builder()
                .setTitle(wx.showClouds ? "Clouds on" : "Clouds off")
                .setOnClickListener(() -> {
                    wx.showClouds = !wx.showClouds;
                    wx.save();
                    if (wx.showClouds) wx.checkSatellite();
                    map.layersChanged();
                    invalidate();
                })
                .build());

        MapWithContentTemplate.Builder t = new MapWithContentTemplate.Builder()
                .setMapController(new MapController.Builder().setMapActionStrip(mapActions).build())
                .setActionStrip(actions.build());
        t.setContentTemplate(list());
        return t.build();
    }

    private ListTemplate list() {
        Nws.Forecast f = forecast;
        String title = f != null && !f.place.isEmpty() ? f.place : here.set ? "Weather here" : "WxRadar";
        ListTemplate.Builder lt = new ListTemplate.Builder().setHeader(new Header.Builder().setTitle(title).build());
        if (f == null || (f.waiting > 1 && f.error.isEmpty() && Double.isNaN(f.now.tempC) && f.hourly.isEmpty())) {
            if (!here.set && !wx.home.set) {
                ItemList.Builder items = new ItemList.Builder();
                items.addItem(new Row.Builder().setTitle("Where are you?")
                        .addText("Open WxRadar on the phone and allow location, or set a home there.").build());
                return lt.setSingleList(items.build()).build();
            }
            return lt.setLoading(true).build();
        }
        ItemList.Builder items = new ItemList.Builder();
        if (!f.error.isEmpty())
            items.addItem(new Row.Builder().setTitle("No forecast here").addText(f.error).build());

        // now
        Nws.Observation o = f.now;
        if (!Double.isNaN(o.tempC)) {
            Row.Builder now = new Row.Builder().setTitle(Words.fahrenheit(o.tempC) + "  " + (o.text.isEmpty() ? "Now" : o.text));
            String wind = Double.isNaN(o.windKmh) ? "" : o.windKmh < 1 ? "Wind calm"
                    : String.format(Locale.US, "Wind %s %.0f mph", Double.isNaN(o.windDegrees) ? "" : Words.compass(o.windDegrees), o.windKmh * 0.621371);
            if (!Double.isNaN(o.gustKmh)) wind += String.format(Locale.US, ", gusts %.0f", o.gustKmh * 0.621371);
            if (!wind.isEmpty()) now.addText(wind);
            items.addItem(now.build());
        }

        // the warnings in effect here (the most serious ones first, as the NWS lists them)
        int shown = 0;
        for (Nws.Alert a : f.alerts) {
            if (shown++ >= 2) break;
            int c = 0xFF000000 | a.color;
            items.addItem(new Row.Builder()
                    .setTitle(a.event)
                    .addText(a.area.isEmpty() ? "Tap to read" : a.area)
                    .setImage(new CarIcon.Builder(IconCompat.createWithResource(getCarContext(), R.drawable.ic_warning))
                            .setTint(CarColor.createCustom(c, c)).build())
                    .setOnClickListener(() -> getScreenManager().push(new AlertScreen(getCarContext(), a)))
                    .build());
        }

        // the next hours: three of them on one row
        if (!f.hourly.isEmpty()) {
            StringBuilder hours = new StringBuilder();
            for (int i = 1; i < f.hourly.size() && i <= 3; ++i) {
                Nws.Period p = f.hourly.get(i);
                if (hours.length() > 0) hours.append("   ");
                hours.append(Words.hourOf(p.start)).append(' ').append(p.temperature).append('°');
                if (p.precipitation >= 20) hours.append(' ').append(p.precipitation).append('%');
            }
            Nws.Period first = f.hourly.get(0);
            Row.Builder r = new Row.Builder().setTitle("Next hours").addText(hours.toString());
            r.addText(Words.brief(first.shortForecast));
            items.addItem(r.build());
        }

        // the next period or two of the forecast
        for (int i = 0; i < f.daily.size() && i < 2; ++i) {
            Nws.Period p = f.daily.get(i);
            String head = p.name + "   " + (p.daytime ? "High " : "Low ") + p.temperature + "°" + (p.precipitation > 0 ? "   " + p.precipitation + "%" : "");
            items.addItem(new Row.Builder().setTitle(head).addText(Words.brief(p.shortForecast)).build());
        }
        return lt.setSingleList(items.build()).build();
    }

    private void zoom(int by) {
        Rect a = map.focus.isEmpty() ? new Rect(0, 0, map.width, map.height) : map.focus;
        map.zoomAt(a.exactCenterX(), a.exactCenterY(), by);
    }

    private void recenter() {
        follow = true;
        Weather.Place p = here.set ? here : wx.home;
        if (p.set) map.centerOn(p.lat, p.lon, Math.max(map.zoom, 8));
    }

    // ------------------------------------------------------------- SurfaceCallback
    @Override public void onSurfaceAvailable(@NonNull SurfaceContainer container) {
        releaseDisplay();
        if (container.getSurface() == null || container.getWidth() <= 0 || container.getHeight() <= 0) return;
        map.width = container.getWidth();
        map.height = container.getHeight();
        map.ui = container.getDpi() > 0 ? container.getDpi() / 160f : 1;
        DisplayManager dm = getCarContext().getSystemService(DisplayManager.class);
        display = dm.createVirtualDisplay("WxRadar", container.getWidth(), container.getHeight(), Math.max(1, container.getDpi()),
                container.getSurface(), DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY);
        presentation = new Presentation(getCarContext(), display.getDisplay());
        view = new CarMapView(presentation.getContext(), map);
        presentation.setContentView(view);
        presentation.show();
        map.requestView();
    }

    @Override public void onSurfaceDestroyed(@NonNull SurfaceContainer container) {
        releaseDisplay();
    }

    private void releaseDisplay() {
        if (presentation != null) { try { presentation.dismiss(); } catch (RuntimeException ignored) { } }
        if (display != null) display.release();
        presentation = null;
        display = null;
        view = null;
    }

    @Override public void onVisibleAreaChanged(@NonNull Rect visibleArea) {
        map.focus.set(visibleArea);
        requestDraw();
    }

    @Override public void onStableAreaChanged(@NonNull Rect stableArea) { }

    @Override public void onScroll(float distanceX, float distanceY) {
        follow = false;
        map.panBy(-distanceX, -distanceY);
    }

    @Override public void onFling(float velocityX, float velocityY) { }

    @Override public void onScale(float focusX, float focusY, float scaleFactor) {
        if (focusX < 0 || focusY < 0) {
            Rect a = map.focus.isEmpty() ? new Rect(0, 0, map.width, map.height) : map.focus;
            focusX = a.exactCenterX();
            focusY = a.exactCenterY();
        }
        map.zoomAt(focusX, focusY, Math.log(scaleFactor) / Math.log(2));
    }

    @Override public void onClick(float x, float y) {
        Nws.Alert a = wx.alertAt(map.latAt(y), map.lonAt(x));
        if (a != null) getScreenManager().push(new AlertScreen(getCarContext(), a));
    }

    // ------------------------------------------------------------- drawing
    private void requestDraw() {
        if (view != null) view.invalidate();
    }
}
