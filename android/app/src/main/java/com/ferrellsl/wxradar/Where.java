package com.ferrellsl.wxradar;

import android.Manifest;
import android.annotation.SuppressLint;
import android.content.Context;
import android.content.pm.PackageManager;
import android.location.Location;
import android.location.LocationManager;
import android.os.Build;
import android.os.CancellationSignal;
import android.os.Handler;
import android.os.Looper;

/** WxRadar: where the phone is, from Android's own location service (no Google Play services). */
public final class Where {
    public interface Found { void found(Location where); }   // (null: not known)

    private Where() { }

    public static boolean allowed(Context c) {
        return c.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
            || c.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED;
    }

    /** The newest position Android already has, if under maxAgeMs old. */
    @SuppressLint("MissingPermission")
    public static Location last(Context c, long maxAgeMs) {
        if (!allowed(c)) return null;
        LocationManager lm = (LocationManager) c.getSystemService(Context.LOCATION_SERVICE);
        if (lm == null) return null;
        Location best = null;
        for (String p : lm.getProviders(true)) {
            try {
                Location l = lm.getLastKnownLocation(p);
                if (l != null && (best == null || l.getTime() > best.getTime())) best = l;
            } catch (RuntimeException ignored) { }
        }
        return best != null && System.currentTimeMillis() - best.getTime() < maxAgeMs ? best : null;
    }

    /** Where the phone is now (a recent position if there is one); found(null) if it can't tell. */
    @SuppressLint("MissingPermission")
    public static void find(Context c, Found found) {
        Location recent = last(c, 10 * 60_000);
        if (recent != null) { found.found(recent); return; }
        LocationManager lm = (LocationManager) c.getSystemService(Context.LOCATION_SERVICE);
        if (!allowed(c) || lm == null) { found.found(null); return; }
        String provider = lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER) ? LocationManager.NETWORK_PROVIDER
                        : lm.isProviderEnabled(LocationManager.GPS_PROVIDER) ? LocationManager.GPS_PROVIDER : null;
        if (provider == null) { found.found(last(c, Long.MAX_VALUE)); return; }
        Handler main = new Handler(Looper.getMainLooper());
        final boolean[] done = { false };
        Found once = l -> {
            if (done[0]) return;
            done[0] = true;
            found.found(l != null ? l : last(c, Long.MAX_VALUE));
        };
        main.postDelayed(() -> once.found(null), 20_000);
        try {
            if (Build.VERSION.SDK_INT >= 30) {
                lm.getCurrentLocation(provider, new CancellationSignal(), c.getMainExecutor(), once::found);
            } else {
                lm.requestSingleUpdate(provider, once::found, Looper.getMainLooper());
            }
        } catch (RuntimeException e) {
            once.found(null);
        }
    }
}
