package com.ferrellsl.wxradar;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Handler;
import android.os.Looper;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * WxRadar: downloads in the background (a port of the Windows app's Net.cpp).
 *
 * A few worker threads fetch URLs.  Map tiles go on a stack (the newest request first:
 * after a quick pan, the tiles now in view come before the ones scrolled past) and are
 * decoded on the worker thread; API requests (forecasts, alerts) go on a queue of their
 * own, in order.  When a download is done, its callback runs on the main thread.
 */
public final class Net {
    /** Who we are, as the NWS and OpenStreetMap ask every program to say. */
    static final String USER_AGENT = "WxRadar/1.0 (Android weather radar viewer; +https://github.com/ferrellsl/WxRadar)";

    public static final class Result {
        public int status;              // HTTP status, 0 if it never got that far
        public String body = "";        // the response (text requests)
        public Bitmap bitmap;           // a decoded image, ready to draw
        public byte[] strength;         // for working out the motion (see Filter / Strength)
        public boolean ok() { return status == 200; }
    }

    public interface Done { void done(Result r); }

    /** What to do to a tile's pixels once decoded. */
    public enum Filter {
        AS_IS,
        CLOUDS,      // satellite infrared (grey: bright = cold cloud tops) -> white clouds, clear ground transparent
        DARK,        // a map tile for dark mode
    }

    /** The rain or cloud strength copy kept for working out the motion. */
    public enum Strength { NONE, RADAR, CLOUDS }

    private static final class Job {
        String url;
        boolean image;
        Filter filter = Filter.AS_IS;
        Strength strength = Strength.NONE;
        File diskCache;
        long maxAgeMs;
        Object owner;
        Done done;
        final Result result = new Result();
    }

    private static final Object lock = new Object();
    private static final ArrayDeque<Job> texts = new ArrayDeque<>();     // in order
    private static final ArrayDeque<Job> images = new ArrayDeque<>();    // newest at the back, taken from the back
    private static final List<Thread> workers = new ArrayList<>();
    private static int active;
    private static final Handler main = new Handler(Looper.getMainLooper());

    private Net() { }

    /** Starts the workers (once). */
    public static void start(int count) {
        synchronized (lock) {
            if (!workers.isEmpty()) return;
            for (int i = 0; i < count; ++i) {
                Thread t = new Thread(Net::work, "WxRadar-net-" + i);
                t.setDaemon(true);
                t.setPriority(Thread.NORM_PRIORITY - 1);
                workers.add(t);
                t.start();
            }
        }
    }

    /** A JSON API request (Accept: application/geo+json), in order. */
    public static void getText(String url, Done done) {
        Job job = new Job();
        job.url = url;
        job.done = done;
        synchronized (lock) {
            texts.addLast(job);
            lock.notify();
        }
    }

    /**
     * A map tile, decoded.  diskCache: a file to read it from if it is younger than
     * maxAgeMs, and to save it to after a download (null for none).  Newest request first.
     */
    public static void getImage(String url, File diskCache, long maxAgeMs, Filter filter, Strength strength, Object owner, Done done) {
        Job job = new Job();
        job.url = url;
        job.image = true;
        job.filter = filter;
        job.strength = strength;
        job.diskCache = diskCache;
        job.maxAgeMs = maxAgeMs;
        job.owner = owner;
        job.done = done;
        synchronized (lock) {
            images.addLast(job);
            lock.notify();
        }
    }

    /** Forget an owner's tile requests not yet started (their callbacks get status 0). */
    public static void dropWaitingImages(Object owner) {
        List<Job> dropped = new ArrayList<>();
        synchronized (lock) {
            for (Iterator<Job> it = images.iterator(); it.hasNext(); ) {
                Job j = it.next();
                if (j.owner == owner) { dropped.add(j); it.remove(); }
            }
        }
        for (Job j : dropped) {
            j.result.status = 0;
            if (j.done != null) j.done.done(j.result);
        }
    }

    /** How many requests are waiting or under way (for the status line). */
    public static int busy() {
        synchronized (lock) {
            return texts.size() + images.size() + active;
        }
    }

    // ------------------------------------------------------------- the workers
    private static void work() {
        while (true) {
            Job job;
            synchronized (lock) {
                while (texts.isEmpty() && images.isEmpty()) {
                    try { lock.wait(); } catch (InterruptedException e) { return; }
                }
                job = !texts.isEmpty() ? texts.pollFirst() : images.pollLast();
                ++active;
            }
            try {
                run(job);
            } catch (Throwable e) {          // (out of memory, a bad picture: that tile fails)
                job.result.status = -1;
                job.result.bitmap = null;
            }
            synchronized (lock) { --active; }
            final Job j = job;
            main.post(() -> { if (j.done != null) j.done.done(j.result); });
        }
    }

    private static void run(Job job) {
        byte[] data = null;
        if (job.image && job.diskCache != null) {
            data = readFile(job.diskCache, job.maxAgeMs);
            if (data != null) job.result.status = 200;
        }
        if (data == null) {
            data = download(job);
            if (job.image && job.result.ok() && job.diskCache != null && data != null)
                writeFile(job.diskCache, data);
        }
        if (!job.image) {
            job.result.body = data == null ? "" : new String(data, StandardCharsets.UTF_8);
            return;
        }
        if (!job.result.ok() || data == null) return;
        Bitmap b = BitmapFactory.decodeByteArray(data, 0, data.length);
        if (b == null) { job.result.status = -1; return; }        // (not a picture after all)
        int w = b.getWidth(), h = b.getHeight();
        if (job.filter != Filter.AS_IS || job.strength != Strength.NONE) {
            int[] px = new int[w * h];
            b.getPixels(px, 0, w, 0, 0, w, h);
            if (job.filter == Filter.CLOUDS) Pixels.cloudify(px);
            else if (job.filter == Filter.DARK) Pixels.darken(px);
            if (job.strength != Strength.NONE && w == 256 && h == 256)
                job.result.strength = Pixels.strength(px, job.strength == Strength.CLOUDS);
            if (job.filter != Filter.AS_IS) {
                Bitmap m = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
                m.setPixels(px, 0, w, 0, 0, w, h);
                b.recycle();
                b = m;
            }
        }
        // (kept on the graphics card: two hours of radar and clouds is a lot of pictures)
        Bitmap hw = b.copy(Bitmap.Config.HARDWARE, false);
        if (hw != null) { b.recycle(); b = hw; }
        job.result.bitmap = b;
    }

    private static byte[] download(Job job) {
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(job.url).openConnection();
            c.setConnectTimeout(15000);
            c.setReadTimeout(30000);
            c.setRequestProperty("User-Agent", USER_AGENT);
            if (!job.image) c.setRequestProperty("Accept", "application/geo+json");
            int status = c.getResponseCode();
            InputStream in = status >= 400 ? c.getErrorStream() : c.getInputStream();
            byte[] data = in == null ? new byte[0] : readAll(in, 64 << 20);
            job.result.status = status;
            return data;
        } catch (IOException | RuntimeException e) {
            job.result.status = 0;
            return null;
        } finally {
            if (c != null) c.disconnect();
        }
    }

    private static byte[] readAll(InputStream in, int most) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[65536];
        int n;
        try {
            while ((n = in.read(buffer)) > 0 && out.size() < most) out.write(buffer, 0, n);
        } finally {
            in.close();
        }
        return out.toByteArray();
    }

    private static byte[] readFile(File f, long maxAgeMs) {
        if (!f.isFile() || System.currentTimeMillis() - f.lastModified() > maxAgeMs) return null;
        try (FileInputStream in = new FileInputStream(f)) {
            byte[] data = readAll(in, 16 << 20);
            return data.length > 0 ? data : null;
        } catch (IOException e) {
            return null;
        }
    }

    private static void writeFile(File f, byte[] data) {
        File dir = f.getParentFile();
        if (dir != null) dir.mkdirs();
        File part = new File(f.getPath() + ".part");     // (never a half-written tile)
        try (FileOutputStream out = new FileOutputStream(part)) {
            out.write(data);
        } catch (IOException e) {
            part.delete();
            return;
        }
        if (!part.renameTo(f)) part.delete();
    }
}
