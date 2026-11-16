package com.ferrellsl.wxradar.car;

import android.content.Intent;
import android.content.res.Configuration;

import androidx.annotation.NonNull;
import androidx.car.app.Screen;
import androidx.car.app.Session;

public final class CarSession extends Session {
    private RadarScreen radar;

    @NonNull @Override public Screen onCreateScreen(@NonNull Intent intent) {
        radar = new RadarScreen(getCarContext());
        return radar;
    }

    /** Day or night on the car's screen: the map light or dark to match. */
    @Override public void onCarConfigurationChanged(@NonNull Configuration configuration) {
        if (radar != null) radar.darkModeChanged();
    }
}
