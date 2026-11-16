package com.ferrellsl.wxradar;

import android.content.Context;
import android.graphics.Canvas;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;

/** WxRadar: the phone's map - the renderer in a View, with touch: drag, pinch, taps, long press. */
public final class MapView extends View {
    public interface Listener {
        void mapTapped(float x, float y);                   // not on a control
        void mapLongPressed(float x, float y);
        void controlTapped(int what);
        void viewMoved();
    }

    final Weather wx;
    final MapRenderer map;
    private Listener listener;
    private final GestureDetector gestures;
    private final ScaleGestureDetector pinch;
    private boolean scrubbing, attached;

    public MapView(Context context) {
        super(context);
        wx = Weather.get(context);
        map = new MapRenderer(wx, this::invalidate);
        map.ui = getResources().getDisplayMetrics().density;
        setFocusable(true);
        setContentDescription("Weather radar map");

        gestures = new GestureDetector(context, new GestureDetector.SimpleOnGestureListener() {
            @Override public boolean onDown(MotionEvent e) { return true; }

            @Override public boolean onSingleTapConfirmed(MotionEvent e) {
                int what = map.hitAt(e.getX(), e.getY());
                if (what == MapRenderer.HIT_FRAMES) return true;      // (handled as a scrub)
                if (what != 0) { if (what > 0 && listener != null) listener.controlTapped(what); return true; }
                if (map.layersOpen) { map.layersOpen = false; invalidate(); return true; }
                if (listener != null) listener.mapTapped(e.getX(), e.getY());
                return true;
            }

            @Override public boolean onDoubleTap(MotionEvent e) {
                int what = map.hitAt(e.getX(), e.getY());
                if (what != 0) {                       // (a quick second tap on a control is just a tap)
                    if (what > 0 && what != MapRenderer.HIT_FRAMES && listener != null) listener.controlTapped(what);
                    return true;
                }
                map.zoomAt(e.getX(), e.getY(), 1);
                moved();
                return true;
            }

            @Override public void onLongPress(MotionEvent e) {
                if (map.hitAt(e.getX(), e.getY()) == 0 && listener != null) listener.mapLongPressed(e.getX(), e.getY());
            }

            @Override public boolean onScroll(MotionEvent e1, MotionEvent e2, float dx, float dy) {
                if (scrubbing) return true;
                map.panBy(-dx, -dy);
                moved();
                return true;
            }
        });
        gestures.setIsLongpressEnabled(true);
        pinch = new ScaleGestureDetector(context, new ScaleGestureDetector.SimpleOnScaleGestureListener() {
            @Override public boolean onScale(ScaleGestureDetector d) {
                map.zoomAt(d.getFocusX(), d.getFocusY(), Math.log(d.getScaleFactor()) / Math.log(2));
                moved();
                return true;
            }
        });
        pinch.setQuickScaleEnabled(false);
    }

    public void setListener(Listener l) { listener = l; }

    private void moved() {
        if (listener != null) listener.viewMoved();
    }

    @Override public boolean onTouchEvent(MotionEvent e) {
        if (e.getActionMasked() == MotionEvent.ACTION_DOWN)
            scrubbing = map.hitAt(e.getX(), e.getY()) == MapRenderer.HIT_FRAMES;
        if (scrubbing && e.getPointerCount() == 1) {
            map.scrubTo(e.getX());
            if (e.getActionMasked() == MotionEvent.ACTION_UP || e.getActionMasked() == MotionEvent.ACTION_CANCEL) scrubbing = false;
            return true;
        }
        pinch.onTouchEvent(e);
        if (!pinch.isInProgress()) gestures.onTouchEvent(e);
        return true;
    }

    @Override protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        map.width = w;
        map.height = h;
        map.requestView();
    }

    @Override protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        attached = true;
        wx.attach(map);
        map.requestView();
        invalidate();
    }

    @Override protected void onDetachedFromWindow() {
        attached = false;
        removeCallbacks(ticker);
        wx.detach(map);
        map.release();
        super.onDetachedFromWindow();
    }

    // (while playing, a new picture every display frame; otherwise a look ten times a second)
    private final Runnable ticker = new Runnable() {
        @Override public void run() {
            if (!attached) return;
            if (map.tick()) invalidate();
            else postDelayed(this, 100);
        }
    };

    @Override protected void onDraw(Canvas canvas) {
        boolean again = map.tick();
        map.draw(canvas);
        removeCallbacks(ticker);
        if (again && map.playing) postInvalidateOnAnimation();
        else postDelayed(ticker, 100);
    }
}
