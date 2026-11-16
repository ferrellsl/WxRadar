package com.ferrellsl.wxradar.car;

import androidx.annotation.NonNull;
import androidx.car.app.CarContext;
import androidx.car.app.Screen;
import androidx.car.app.model.Action;
import androidx.car.app.model.CarColor;
import androidx.car.app.model.CarIcon;
import androidx.car.app.model.Header;
import androidx.car.app.model.MessageTemplate;
import androidx.car.app.model.Template;
import androidx.core.graphics.drawable.IconCompat;

import com.ferrellsl.wxradar.Nws;
import com.ferrellsl.wxradar.R;

/** A warning's headline and what to do, on the car's screen. */
final class AlertScreen extends Screen {
    private final Nws.Alert alert;

    AlertScreen(CarContext context, Nws.Alert alert) {
        super(context);
        this.alert = alert;
    }

    @NonNull @Override public Template onGetTemplate() {
        StringBuilder text = new StringBuilder(alert.headline.isEmpty() ? alert.event : alert.headline);
        if (!alert.instruction.isEmpty()) text.append("\n\n").append(alert.instruction.replace('\n', ' '));
        String message = text.length() > 600 ? text.substring(0, 597) + "..." : text.toString();
        int c = 0xFF000000 | alert.color;
        return new MessageTemplate.Builder(message)
                .setHeader(new Header.Builder().setTitle(alert.event).setStartHeaderAction(Action.BACK).build())
                .setIcon(new CarIcon.Builder(IconCompat.createWithResource(getCarContext(), R.drawable.ic_warning))
                        .setTint(CarColor.createCustom(c, c)).build())
                .build();
    }
}
