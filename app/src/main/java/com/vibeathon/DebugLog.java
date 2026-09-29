package com.vibeathon;

import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/** In-memory ring buffer of automation steps, shown in the setup screen's Debug log. */
public final class DebugLog {

    public interface Listener {
        void onLogChanged();
    }

    private static final String TAG = "Vibeathon";
    private static final int MAX_LINES = 400;
    private static final ArrayDeque<String> LINES = new ArrayDeque<>();
    private static final List<Listener> LISTENERS = new ArrayList<>();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private DebugLog() {
    }

    public static void log(String message) {
        Log.i(TAG, message);
        String time = new SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(new Date());
        synchronized (LINES) {
            LINES.addLast(time + "  " + message);
            while (LINES.size() > MAX_LINES) {
                LINES.removeFirst();
            }
        }
        MAIN.post(DebugLog::notifyListeners);
    }

    public static void error(String message, Throwable t) {
        Log.w(TAG, message, t);
        log("ERROR " + message + (t == null ? "" : ": " + t));
    }

    public static String text() {
        StringBuilder sb = new StringBuilder();
        synchronized (LINES) {
            for (String line : LINES) {
                sb.append(line).append('\n');
            }
        }
        return sb.toString();
    }

    public static void clear() {
        synchronized (LINES) {
            LINES.clear();
        }
        MAIN.post(DebugLog::notifyListeners);
    }

    public static void addListener(Listener listener) {
        synchronized (LISTENERS) {
            LISTENERS.add(listener);
        }
    }

    public static void removeListener(Listener listener) {
        synchronized (LISTENERS) {
            LISTENERS.remove(listener);
        }
    }

    private static void notifyListeners() {
        List<Listener> copy;
        synchronized (LISTENERS) {
            copy = new ArrayList<>(LISTENERS);
        }
        for (Listener l : copy) {
            l.onLogChanged();
        }
    }
}
