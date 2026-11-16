package com.ferrellsl.wxradar.car;

import android.content.Context;
import android.graphics.Canvas;
import android.view.View;

import com.ferrellsl.wxradar.MapRenderer;

/**
 * The car's map: the renderer in a plain View, shown on a virtual display made from the
 * car's surface (so Android looks after that surface coming and going, which drawing on it
 * straight away does not survive).
 */
final class CarMapView extends View {
    private final MapRenderer map;

    CarMapView(Context context, MapRenderer map) {
        super(context);
        this.map = map;
    }

    // (while playing, a new picture every display frame; otherwise a look ten times a second)
    private final Runnable ticker = new Runnable() {
        @Override public void run() {
            if (!isAttachedToWindow()) return;
            if (map.tick()) invalidate();
            else postDelayed(this, 100);
        }
    };

    @Override protected void onDraw(Canvas canvas) {
        boolean again = map.tick();
        map.draw(canvas);
        removeCallbacks(ticker);
        if (again && map.playing && map.animationAllowed) postInvalidateOnAnimation();
        else postDelayed(ticker, 100);
    }

    @Override protected void onDetachedFromWindow() {
        removeCallbacks(ticker);
        super.onDetachedFromWindow();
    }
}
