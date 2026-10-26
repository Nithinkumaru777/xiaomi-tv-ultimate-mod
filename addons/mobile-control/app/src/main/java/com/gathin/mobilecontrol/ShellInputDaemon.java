package com.gathin.mobilecontrol;

import android.hardware.input.InputManager;
import android.os.SystemClock;
import android.view.InputDevice;
import android.view.InputEvent;
import android.view.KeyCharacterMap;
import android.view.KeyEvent;
import android.view.MotionEvent;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.lang.reflect.Method;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;

/**
 * Persistent low-latency input injection daemon running under uid 2000 (shell) on 127.0.0.1:8889.
 * Injects hardware-grade KeyEvents, MotionEvents (Touch + Mouse Wheel) in < 1ms via InputManager.
 */
public final class ShellInputDaemon {

    public static final int DAEMON_PORT = 8889;
    private static Object inputManager;
    private static Method injectMethod;

    public static void main(String[] args) {
        try {
            Class<?> imClass = InputManager.class;
            Method getInstance = imClass.getDeclaredMethod("getInstance");
            getInstance.setAccessible(true);
            inputManager = getInstance.invoke(null);
            injectMethod = imClass.getMethod("injectInputEvent", InputEvent.class, int.class);
            injectMethod.setAccessible(true);
        } catch (Throwable t) {
            t.printStackTrace();
            System.exit(1);
        }

        try {
            ServerSocket server = new ServerSocket(DAEMON_PORT, 32, InetAddress.getByName("127.0.0.1"));
            while (true) {
                final Socket client = server.accept();
                new Thread(() -> handleClient(client)).start();
            }
        } catch (Throwable t) {
            t.printStackTrace();
        }
    }

    private static void handleClient(Socket client) {
        try {
            client.setSoTimeout(2500);
            BufferedReader reader = new BufferedReader(new InputStreamReader(client.getInputStream(), StandardCharsets.UTF_8));
            OutputStream out = client.getOutputStream();
            String line;
            while ((line = reader.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty()) continue;
                String[] parts = line.split(" ");
                String cmd = parts[0];
                if ("KEY".equals(cmd) && parts.length >= 2) {
                    int code = Integer.parseInt(parts[1]);
                    injectKey(code);
                } else if ("TAP".equals(cmd) && parts.length >= 3) {
                    float x = Float.parseFloat(parts[1]);
                    float y = Float.parseFloat(parts[2]);
                    injectTap(x, y, 35);
                } else if ("LONGTAP".equals(cmd) && parts.length >= 3) {
                    float x = Float.parseFloat(parts[1]);
                    float y = Float.parseFloat(parts[2]);
                    injectTap(x, y, 480);
                } else if ("SCROLL".equals(cmd) && parts.length >= 5) {
                    float x = Float.parseFloat(parts[1]);
                    float y = Float.parseFloat(parts[2]);
                    float dx = Float.parseFloat(parts[3]);
                    float dy = Float.parseFloat(parts[4]);
                    injectScroll(x, y, dx, dy);
                } else if ("TEXT".equals(cmd) && parts.length >= 2) {
                    String text = URLDecoder.decode(parts[1], "UTF-8");
                    injectText(text);
                }
                out.write("OK\n".getBytes(StandardCharsets.UTF_8));
                out.flush();
            }
            client.close();
        } catch (Throwable ignored) {
            try { client.close(); } catch (Throwable e) {}
        }
    }

    private static void injectEvent(InputEvent event) {
        try {
            // mode 0 = INJECT_INPUT_EVENT_MODE_ASYNC
            injectMethod.invoke(inputManager, event, 0);
        } catch (Throwable ignored) {}
    }

    private static void injectKey(int keyCode) {
        long now = SystemClock.uptimeMillis();
        int source = InputDevice.SOURCE_KEYBOARD | InputDevice.SOURCE_DPAD;
        KeyEvent down = new KeyEvent(now, now, KeyEvent.ACTION_DOWN, keyCode, 0, 0,
                KeyCharacterMap.VIRTUAL_KEYBOARD, 0, 0, source);
        KeyEvent up = new KeyEvent(now, now + 15, KeyEvent.ACTION_UP, keyCode, 0, 0,
                KeyCharacterMap.VIRTUAL_KEYBOARD, 0, 0, source);
        injectEvent(down);
        injectEvent(up);
    }

    private static void injectTap(float x, float y, long durationMs) {
        long downTime = SystemClock.uptimeMillis();
        MotionEvent down = MotionEvent.obtain(downTime, downTime, MotionEvent.ACTION_DOWN, x, y, 1.0f, 1.0f, 0, 1.0f, 1.0f, 0, 0);
        down.setSource(InputDevice.SOURCE_TOUCHSCREEN);
        injectEvent(down);
        down.recycle();

        if (durationMs > 20) {
            try { Thread.sleep(durationMs); } catch (InterruptedException ignored) {}
        }
        long upTime = SystemClock.uptimeMillis();
        MotionEvent up = MotionEvent.obtain(downTime, upTime, MotionEvent.ACTION_UP, x, y, 0.0f, 1.0f, 0, 1.0f, 1.0f, 0, 0);
        up.setSource(InputDevice.SOURCE_TOUCHSCREEN);
        injectEvent(up);
        up.recycle();
    }

    private static void injectScroll(float x, float y, float dx, float dy) {
        // 1. Inject native Mouse Wheel Scroll (AXIS_VSCROLL / AXIS_HSCROLL) for browsers & scrollable views
        try {
            long now = SystemClock.uptimeMillis();
            MotionEvent.PointerProperties[] props = new MotionEvent.PointerProperties[1];
            props[0] = new MotionEvent.PointerProperties();
            props[0].id = 0;
            props[0].toolType = MotionEvent.TOOL_TYPE_MOUSE;

            MotionEvent.PointerCoords[] coords = new MotionEvent.PointerCoords[1];
            coords[0] = new MotionEvent.PointerCoords();
            coords[0].x = x;
            coords[0].y = y;
            coords[0].pressure = 1.0f;
            coords[0].size = 1.0f;
            // Convert pixel delta to smooth wheel ticks
            coords[0].setAxisValue(MotionEvent.AXIS_VSCROLL, Math.max(-6.0f, Math.min(6.0f, dy / 42.0f)));
            coords[0].setAxisValue(MotionEvent.AXIS_HSCROLL, Math.max(-6.0f, Math.min(6.0f, -dx / 42.0f)));

            MotionEvent wheel = MotionEvent.obtain(now, now, MotionEvent.ACTION_SCROLL, 1, props, coords,
                    0, 0, 1.0f, 1.0f, 0, 0, InputDevice.SOURCE_MOUSE, 0);
            injectEvent(wheel);
            wheel.recycle();
        } catch (Throwable ignored) {}

        // 2. Also inject multi-step Touchscreen Drag for touch-scrollable lists
        long downTime = SystemClock.uptimeMillis();
        MotionEvent down = MotionEvent.obtain(downTime, downTime, MotionEvent.ACTION_DOWN, x, y, 1.0f, 1.0f, 0, 1.0f, 1.0f, 0, 0);
        down.setSource(InputDevice.SOURCE_TOUCHSCREEN);
        injectEvent(down);
        down.recycle();

        int steps = 5;
        for (int i = 1; i <= steps; i++) {
            try { Thread.sleep(10); } catch (InterruptedException ignored) {}
            float frac = i / (float) steps;
            long t = SystemClock.uptimeMillis();
            MotionEvent move = MotionEvent.obtain(downTime, t, MotionEvent.ACTION_MOVE,
                    x + dx * frac, y + dy * frac, 1.0f, 1.0f, 0, 1.0f, 1.0f, 0, 0);
            move.setSource(InputDevice.SOURCE_TOUCHSCREEN);
            injectEvent(move);
            move.recycle();
        }

        long upTime = SystemClock.uptimeMillis();
        MotionEvent up = MotionEvent.obtain(downTime, upTime, MotionEvent.ACTION_UP,
                x + dx, y + dy, 0.0f, 1.0f, 0, 1.0f, 1.0f, 0, 0);
        up.setSource(InputDevice.SOURCE_TOUCHSCREEN);
        injectEvent(up);
        up.recycle();
    }

    private static void injectText(String text) {
        if (text == null || text.isEmpty()) return;
        KeyCharacterMap kcm = KeyCharacterMap.load(KeyCharacterMap.VIRTUAL_KEYBOARD);
        KeyEvent[] events = kcm.getEvents(text.toCharArray());
        if (events != null) {
            for (KeyEvent e : events) {
                injectEvent(e);
            }
        }
    }
}
