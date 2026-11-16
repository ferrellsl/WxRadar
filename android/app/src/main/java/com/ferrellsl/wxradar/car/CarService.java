package com.ferrellsl.wxradar.car;

import android.content.pm.ApplicationInfo;

import androidx.annotation.NonNull;
import androidx.car.app.CarAppService;
import androidx.car.app.Session;
import androidx.car.app.validation.HostValidator;

/** WxRadar on the car's screen (Android Auto): a weather app that draws its own radar map. */
public final class CarService extends CarAppService {
    @NonNull @Override public HostValidator createHostValidator() {
        // (a test build talks to any host, e.g. the Desktop Head Unit; a release only to Android Auto and the like)
        if ((getApplicationInfo().flags & ApplicationInfo.FLAG_DEBUGGABLE) != 0) return HostValidator.ALLOW_ALL_HOSTS_VALIDATOR;
        return new HostValidator.Builder(getApplicationContext())
                .addAllowedHosts(androidx.car.app.R.array.hosts_allowlist_sample)
                .build();
    }

    @NonNull @Override public Session onCreateSession() {
        return new CarSession();
    }
}
