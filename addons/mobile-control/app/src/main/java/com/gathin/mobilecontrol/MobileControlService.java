package com.gathin.mobilecontrol;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.animation.ValueAnimator;
import android.app.SearchManager;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.Shader;
import android.media.AudioManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

public class MobileControlService extends AccessibilityService {

    public static final int PORT = 8888;
    public static volatile MobileControlService sInstance;

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService bgPool = Executors.newCachedThreadPool();
    private WindowManager windowManager;
    private CursorView cursorView;
    private WindowManager.LayoutParams cursorParams;
    private int screenW = 1280;
    private int screenH = 720;
    private float cursorX = 640f;
    private float cursorY = 360f;
    private boolean cursorAdded = false;
    private volatile boolean remoteMode = false;
    private volatile String activePackage = "";

    private final AtomicInteger kbSeq = new AtomicInteger(0);
    private volatile boolean editableNow = false;
    private long lastKbTriggerMs = 0L;
    private final Rect lastRemoteFocusRect = new Rect();
    private float scrollAccumX = 0f;
    private float scrollAccumY = 0f;
    private float padMoveAccumX = 0f;
    private float padMoveAccumY = 0f;
    private long lastDpadStepMs = 0L;

    private ServerSocket serverSocket;
    private volatile boolean running = false;

    private final Runnable hideCursorRunnable = new Runnable() {
        @Override
        public void run() {
            if (cursorView != null) {
                cursorView.animate().alpha(0f).setDuration(220).withEndAction(() -> {
                    if (cursorView != null) cursorView.setVisibility(View.GONE);
                }).start();
            }
        }
    };

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        sInstance = this;
        setupOverlay();
        startHttpServer();
    }

    @Override
    public void onDestroy() {
        sInstance = null;
        stopHttpServer();
        removeOverlay();
        super.onDestroy();
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event == null) return;
        CharSequence evPkg = event.getPackageName();
        if (evPkg != null && evPkg.length() > 0) {
            String p = evPkg.toString();
            if (!"com.gathin.mobilecontrol".equals(p)
                    && !"com.android.systemui".equals(p)
                    && !"android".equals(p)) {
                activePackage = p;
                if (isDpadFirstTvApp(p)) {
                    hideCursorNow();
                }
            }
        }
        // Never auto-pop the mobile keyboard from background TV focus events;
        // only auto-pop when the user clicks an actual EditText inside a Browser/Touch app.
        if (!isDpadFirstTvApp(activePackage) && event.getEventType() == AccessibilityEvent.TYPE_VIEW_CLICKED) {
            AccessibilityNodeInfo src = event.getSource();
            if (src != null) {
                boolean isEdit = src.isEditable();
                src.recycle();
                if (isEdit) {
                    triggerKeyboardPopup();
                }
            }
        }
    }

    @Override
    public void onInterrupt() {
    }

    private String getForegroundPackage() {
        try {
            AccessibilityNodeInfo root = getRootInActiveWindow();
            if (root != null) {
                CharSequence p = root.getPackageName();
                root.recycle();
                if (p != null && p.length() > 0) {
                    String pkg = p.toString();
                    if (!"com.gathin.mobilecontrol".equals(pkg)
                            && !"com.android.systemui".equals(pkg)
                            && !"android".equals(pkg)) {
                        activePackage = pkg;
                        return activePackage;
                    }
                }
            }
        } catch (Exception ignored) {}
        return activePackage != null ? activePackage : "";
    }

    private boolean isBrowserOrTouchApp(String pkg) {
        if (pkg == null || pkg.isEmpty()) return false;
        String lower = pkg.toLowerCase(Locale.US);
        return lower.equals("com.brave.browser")
                || lower.contains("browser")
                || lower.contains("chrome")
                || lower.contains("firefox");
    }

    private boolean isDpadFirstTvApp(String pkg) {
        return !isBrowserOrTouchApp(pkg);
    }

    private boolean sendDaemonCmd(String cmdLine) {
        try (Socket s = new Socket()) {
            s.connect(new InetSocketAddress("127.0.0.1", ShellInputDaemon.DAEMON_PORT), 120);
            s.setSoTimeout(400);
            OutputStream out = s.getOutputStream();
            out.write((cmdLine + "\n").getBytes(StandardCharsets.UTF_8));
            out.flush();
            BufferedReader br = new BufferedReader(new InputStreamReader(s.getInputStream(), StandardCharsets.UTF_8));
            String resp = br.readLine();
            return resp != null && resp.startsWith("OK");
        } catch (Throwable t) {
            return false;
        }
    }

    private void triggerKeyboardPopup() {
        long now = System.currentTimeMillis();
        editableNow = true;
        if (now - lastKbTriggerMs > 600) {
            lastKbTriggerMs = now;
            kbSeq.incrementAndGet();
        }
    }

    private boolean isNodeEditableOrSearch(AccessibilityNodeInfo node, boolean includeSearchButtons) {
        if (node == null) return false;
        if (node.isEditable()) return true;
        CharSequence cls = node.getClassName();
        if (cls != null) {
            String c = cls.toString().toLowerCase(Locale.US);
            if (c.contains("edittext") || c.contains("autocompletetextview") || c.contains("searchview")) {
                return true;
            }
        }
        if (includeSearchButtons) {
            String combined = ((node.getText() != null ? node.getText().toString() : "") + " "
                    + (node.getContentDescription() != null ? node.getContentDescription().toString() : "") + " "
                    + (node.getViewIdResourceName() != null ? node.getViewIdResourceName() : "")).toLowerCase(Locale.US);
            if (combined.contains("search") || combined.contains("url_bar") || combined.contains("omnibox")
                    || combined.contains("query") || combined.contains("type ") || combined.contains("keyboard")) {
                return true;
            }
        }
        return false;
    }

    private void checkActiveWindowForEditable(boolean clickedSearchCheck) {
        try {
            AccessibilityNodeInfo focus = findFocus(AccessibilityNodeInfo.FOCUS_INPUT);
            if (focus != null) {
                boolean edit = isNodeEditableOrSearch(focus, clickedSearchCheck);
                focus.recycle();
                if (edit) {
                    triggerKeyboardPopup();
                    return;
                }
            }
            AccessibilityNodeInfo root = getRootInActiveWindow();
            if (root != null) {
                AccessibilityNodeInfo editNode = findFirstEditableNode(root);
                root.recycle();
                if (editNode != null) {
                    boolean focused = editNode.isFocused() || editNode.isAccessibilityFocused() || clickedSearchCheck;
                    editNode.recycle();
                    if (focused) {
                        triggerKeyboardPopup();
                        return;
                    }
                }
            }
            editableNow = false;
        } catch (Exception ignored) {
        }
    }

    private AccessibilityNodeInfo findFirstEditableNode(AccessibilityNodeInfo node) {
        if (node == null) return null;
        if (node.isVisibleToUser() && node.isEditable()) {
            return AccessibilityNodeInfo.obtain(node);
        }
        int count = node.getChildCount();
        for (int i = 0; i < count; i++) {
            AccessibilityNodeInfo child = node.getChild(i);
            if (child != null) {
                AccessibilityNodeInfo found = findFirstEditableNode(child);
                child.recycle();
                if (found != null) return found;
            }
        }
        return null;
    }

    private AccessibilityNodeInfo findDeepestNodeAt(AccessibilityNodeInfo node, int x, int y) {
        if (node == null || !node.isVisibleToUser()) return null;
        Rect r = new Rect();
        node.getBoundsInScreen(r);
        if (!r.contains(x, y)) return null;
        int count = node.getChildCount();
        for (int i = count - 1; i >= 0; i--) {
            AccessibilityNodeInfo child = node.getChild(i);
            if (child != null) {
                AccessibilityNodeInfo hit = findDeepestNodeAt(child, x, y);
                child.recycle();
                if (hit != null) return hit;
            }
        }
        return AccessibilityNodeInfo.obtain(node);
    }

    private void setupOverlay() {
        mainHandler.post(() -> {
            try {
                windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
                DisplayMetrics dm = getResources().getDisplayMetrics();
                screenW = dm.widthPixels > 0 ? dm.widthPixels : 1280;
                screenH = dm.heightPixels > 0 ? dm.heightPixels : 720;
                cursorX = screenW / 2f;
                cursorY = screenH / 2f;

                cursorView = new CursorView(this);
                cursorParams = new WindowManager.LayoutParams(
                        56, 56,
                        WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                                | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                                | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                                | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                        PixelFormat.TRANSLUCENT);
                cursorParams.gravity = Gravity.TOP | Gravity.LEFT;
                cursorParams.x = (int) cursorX;
                cursorParams.y = (int) cursorY;
                cursorView.setVisibility(View.GONE);
                windowManager.addView(cursorView, cursorParams);
                cursorAdded = true;
            } catch (Exception ignored) {
            }
        });
    }

    private void removeOverlay() {
        mainHandler.post(() -> {
            if (cursorAdded && windowManager != null && cursorView != null) {
                try {
                    windowManager.removeView(cursorView);
                } catch (Exception ignored) {
                }
                cursorAdded = false;
            }
        });
    }

    private void hideCursorNow() {
        mainHandler.post(() -> {
            mainHandler.removeCallbacks(hideCursorRunnable);
            if (cursorView != null) {
                cursorView.animate().cancel();
                cursorView.setAlpha(0f);
                cursorView.setVisibility(View.GONE);
            }
        });
    }

    private void showCursorAndScheduleHide() {
        if (remoteMode || cursorView == null) return;
        cursorView.animate().cancel();
        cursorView.setAlpha(1f);
        if (cursorView.getVisibility() != View.VISIBLE) {
            cursorView.setVisibility(View.VISIBLE);
        }
        mainHandler.removeCallbacks(hideCursorRunnable);
        mainHandler.postDelayed(hideCursorRunnable, 3800);
    }

    private void moveCursor(float dx, float dy) {
        final String fg = getForegroundPackage();
        if (isDpadFirstTvApp(fg)) {
            hideCursorNow();
            bgPool.execute(() -> {
                long now = System.currentTimeMillis();
                if (Math.abs(dy) >= Math.abs(dx) * 1.15f) {
                    padMoveAccumX = 0f;
                    padMoveAccumY += dy;
                    if (Math.abs(padMoveAccumY) >= 62f) {
                        if (now - lastDpadStepMs >= 85) {
                            lastDpadStepMs = now;
                            if (padMoveAccumY > 0) {
                                dispatchKeyFast("DOWN", KeyEvent.KEYCODE_DPAD_DOWN);
                            } else {
                                dispatchKeyFast("UP", KeyEvent.KEYCODE_DPAD_UP);
                            }
                        }
                        padMoveAccumY = 0f;
                    }
                } else if (Math.abs(dx) > Math.abs(dy) * 1.15f) {
                    padMoveAccumY = 0f;
                    padMoveAccumX += dx;
                    if (Math.abs(padMoveAccumX) >= 62f) {
                        if (now - lastDpadStepMs >= 85) {
                            lastDpadStepMs = now;
                            if (padMoveAccumX > 0) {
                                dispatchKeyFast("RIGHT", KeyEvent.KEYCODE_DPAD_RIGHT);
                            } else {
                                dispatchKeyFast("LEFT", KeyEvent.KEYCODE_DPAD_LEFT);
                            }
                        }
                        padMoveAccumX = 0f;
                    }
                }
            });
            return;
        }

        remoteMode = false;
        mainHandler.post(() -> {
            if (!cursorAdded || cursorView == null) {
                setupOverlay();
                return;
            }
            cursorX = Math.max(0f, Math.min(screenW - 6f, cursorX + dx));
            cursorY = Math.max(0f, Math.min(screenH - 6f, cursorY + dy));
            cursorParams.x = (int) cursorX;
            cursorParams.y = (int) cursorY;
            showCursorAndScheduleHide();
            try {
                windowManager.updateViewLayout(cursorView, cursorParams);
            } catch (Exception ignored) {
            }
        });
    }

    private void clickAtCursor(boolean longPress) {
        final String fg = getForegroundPackage();
        if (isDpadFirstTvApp(fg)) {
            hideCursorNow();
            padMoveAccumX = 0f;
            padMoveAccumY = 0f;
            scrollAccumX = 0f;
            scrollAccumY = 0f;
            bgPool.execute(() -> {
                if (longPress) {
                    if (!sendDaemonCmd("KEY " + KeyEvent.KEYCODE_MENU)) {
                        dispatchKeyFast("OK", KeyEvent.KEYCODE_DPAD_CENTER);
                    }
                } else {
                    dispatchKeyFast("OK", KeyEvent.KEYCODE_DPAD_CENTER);
                }
            });
            return;
        }

        remoteMode = false;
        mainHandler.post(() -> {
            if (cursorView != null) {
                showCursorAndScheduleHide();
                cursorView.playClickPulse();
            }
            final int tapX = (int) cursorX;
            final int tapY = (int) cursorY;

            try {
                AccessibilityNodeInfo root = getRootInActiveWindow();
                if (root != null) {
                    AccessibilityNodeInfo hit = findDeepestNodeAt(root, tapX, tapY);
                    root.recycle();
                    if (hit != null) {
                        boolean isEdit = hit.isEditable();
                        hit.recycle();
                        if (isEdit) {
                            triggerKeyboardPopup();
                        }
                    }
                }
            } catch (Exception ignored) {
            }

            bgPool.execute(() -> {
                String cmd = (longPress ? "LONGTAP " : "TAP ") + tapX + " " + tapY;
                if (!sendDaemonCmd(cmd)) {
                    mainHandler.post(() -> {
                        try {
                            Path path = new Path();
                            path.moveTo(tapX, tapY);
                            GestureDescription.Builder builder = new GestureDescription.Builder();
                            builder.addStroke(new GestureDescription.StrokeDescription(path, 0, longPress ? 520 : 42));
                            dispatchGesture(builder.build(), null, null);
                        } catch (Exception ignored) {}
                    });
                }
            });
        });
    }

    private void scrollAtCursor(float dx, float dy) {
        final String fg = getForegroundPackage();
        final float cx = remoteMode ? (screenW / 2f) : cursorX;
        final float cy = remoteMode ? (screenH / 2f) : cursorY;

        bgPool.execute(() -> {
            // For TV highlight apps (YouTube, Netflix, Prime Video, Hotstar, Home Launcher),
            // convert 2-finger Trackpad scrolling into single-axis, normal-sensitivity D-Pad steps!
            if (isDpadFirstTvApp(fg)) {
                hideCursorNow();
                long now = System.currentTimeMillis();
                if (Math.abs(dy) >= Math.abs(dx) * 1.15f) {
                    scrollAccumX = 0f;
                    scrollAccumY += dy;
                    if (Math.abs(scrollAccumY) >= 58f) {
                        if (now - lastDpadStepMs >= 78) {
                            lastDpadStepMs = now;
                            if (scrollAccumY > 0) {
                                dispatchKeyFast("UP", KeyEvent.KEYCODE_DPAD_UP);
                            } else {
                                dispatchKeyFast("DOWN", KeyEvent.KEYCODE_DPAD_DOWN);
                            }
                        }
                        scrollAccumY = 0f;
                    }
                } else if (Math.abs(dx) > Math.abs(dy) * 1.15f) {
                    scrollAccumY = 0f;
                    scrollAccumX += dx;
                    if (Math.abs(scrollAccumX) >= 58f) {
                        if (now - lastDpadStepMs >= 78) {
                            lastDpadStepMs = now;
                            if (scrollAccumX > 0) {
                                dispatchKeyFast("LEFT", KeyEvent.KEYCODE_DPAD_LEFT);
                            } else {
                                dispatchKeyFast("RIGHT", KeyEvent.KEYCODE_DPAD_RIGHT);
                            }
                        }
                        scrollAccumX = 0f;
                    }
                }
                return;
            }

            // For standard Android apps & browsers (Brave Browser):
            // Inject high-sensitivity Mouse Wheel + Touchscreen Drag via ShellInputDaemon
            float startX = Math.max(80f, Math.min(screenW - 80f, cx));
            float startY = Math.max(80f, Math.min(screenH - 80f, cy));
            float clampedDx = Math.max(-520f, Math.min(520f, dx));
            float clampedDy = Math.max(-520f, Math.min(520f, dy));
            if (sendDaemonCmd("SCROLL " + (int) startX + " " + (int) startY + " " + (int) clampedDx + " " + (int) clampedDy)) {
                return;
            }
            mainHandler.post(() -> {
                try {
                    float endX = Math.max(30f, Math.min(screenW - 30f, startX + clampedDx));
                    float endY = Math.max(30f, Math.min(screenH - 30f, startY + clampedDy));
                    if (Math.abs(endX - startX) < 4f && Math.abs(endY - startY) < 4f) return;
                    Path path = new Path();
                    path.moveTo(startX, startY);
                    path.lineTo(endX, endY);
                    GestureDescription.Builder builder = new GestureDescription.Builder();
                    builder.addStroke(new GestureDescription.StrokeDescription(path, 0, 125));
                    dispatchGesture(builder.build(), null, null);
                } catch (Exception ignored) {}
            });
        });
    }

    private void injectTextAndSubmit(String text, boolean submit) {
        if (text == null) return;
        final String fg = getForegroundPackage();
        final String clean = text.trim();

        // When the user taps "Search" on the Mobile Keyboard:
        // Execute the search immediately in YouTube, SmartTube, Brave, or any focused input!
        if (submit && !clean.isEmpty()) {
            if ("com.google.android.youtube.tv".equals(fg)) {
                launchDirectSearch("com.google.android.youtube.tv", clean);
                return;
            } else if ("org.smarttube.stable".equals(fg)) {
                launchDirectSearch("org.smarttube.stable", clean);
                return;
            } else if ("com.gathin.tvhome".equals(fg)) {
                // On the Home screen: if it looks like a URL, open in Brave; otherwise search YouTube!
                if (clean.contains(".") && !clean.contains(" ")) {
                    openUrlInBrave(clean);
                } else {
                    launchDirectSearch("com.google.android.youtube.tv", clean);
                }
                return;
            }
        }

        mainHandler.post(() -> {
            boolean setViaAccessibility = false;
            try {
                if (!text.isEmpty()) {
                    ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
                    if (cm != null) {
                        cm.setPrimaryClip(ClipData.newPlainText("tv", text));
                    }
                }
                AccessibilityNodeInfo target = findFocus(AccessibilityNodeInfo.FOCUS_INPUT);
                if (target != null && !target.isEditable()) {
                    target.recycle();
                    target = null;
                }
                if (target == null) {
                    AccessibilityNodeInfo root = getRootInActiveWindow();
                    if (root != null) {
                        target = findFirstEditableNode(root);
                        root.recycle();
                    }
                }
                if (target != null) {
                    target.performAction(AccessibilityNodeInfo.ACTION_FOCUS);
                    Bundle b = new Bundle();
                    b.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text);
                    setViaAccessibility = target.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, b);
                    if (!setViaAccessibility && !text.isEmpty()) {
                        setViaAccessibility = target.performAction(AccessibilityNodeInfo.ACTION_PASTE);
                    }
                    if (submit) {
                        if (Build.VERSION.SDK_INT >= 30) {
                            target.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.getId());
                        }
                        target.performAction(AccessibilityNodeInfo.ACTION_CLICK);
                    }
                    target.recycle();
                }
            } catch (Exception ignored) {
            }

            final boolean wasSet = setViaAccessibility;
            if (submit) {
                bgPool.execute(() -> {
                    if (!wasSet && "com.brave.browser".equals(fg) && !clean.isEmpty()) {
                        openUrlInBrave(clean);
                        return;
                    }
                    if (!wasSet && !clean.isEmpty()) {
                        try {
                            sendDaemonCmd("TEXT " + URLEncoder.encode(clean, "UTF-8"));
                        } catch (Exception ignored) {}
                    }
                    // Press hardware ENTER (66) so the app executes the search immediately without manual clicking!
                    sendDaemonCmd("KEY " + KeyEvent.KEYCODE_ENTER);
                });
            }
        });
    }

    private void launchDirectSearch(String pkg, String query) {
        mainHandler.post(() -> {
            try {
                Intent searchIntent = new Intent(Intent.ACTION_SEARCH);
                searchIntent.setPackage(pkg);
                searchIntent.putExtra(SearchManager.QUERY, query);
                searchIntent.putExtra("query", query);
                searchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
                startActivity(searchIntent);
                return;
            } catch (Exception ignored) {}
            try {
                Uri uri = Uri.parse("https://www.youtube.com/results?search_query=" + Uri.encode(query));
                Intent viewIntent = new Intent(Intent.ACTION_VIEW, uri);
                viewIntent.setPackage(pkg);
                viewIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(viewIntent);
            } catch (Exception ignored) {}
        });
    }

    private void openUrlInBrave(String raw) {
        if (raw == null || raw.trim().isEmpty()) return;
        mainHandler.post(() -> {
            String q = raw.trim();
            String url;
            if (q.startsWith("http://") || q.startsWith("https://")) {
                url = q;
            } else if (q.contains(".") && !q.contains(" ")) {
                url = "https://" + q;
            } else {
                url = "https://search.brave.com/search?q=" + Uri.encode(q);
            }
            try {
                Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
                intent.setPackage("com.brave.browser");
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(intent);
            } catch (Exception e) {
                try {
                    Intent fallback = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
                    fallback.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    startActivity(fallback);
                } catch (Exception ignored) {
                }
            }
        });
    }

    private void launchPackage(String pkg) {
        if (pkg == null || pkg.isEmpty()) return;
        mainHandler.post(() -> {
            try {
                Intent intent = getPackageManager().getLeanbackLaunchIntentForPackage(pkg);
                if (intent == null) {
                    intent = getPackageManager().getLaunchIntentForPackage(pkg);
                }
                if (intent != null) {
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    startActivity(intent);
                }
            } catch (Exception ignored) {
            }
        });
    }

    private void dispatchKeyFast(String keyName, int keyCode) {
        String fg = getForegroundPackage();
        if ("com.gathin.tvhome".equals(fg) && ("UP".equals(keyName) || "DOWN".equals(keyName)
                || "LEFT".equals(keyName) || "RIGHT".equals(keyName) || "OK".equals(keyName))) {
            try {
                Intent b = new Intent("com.gathin.tvhome.REMOTE_KEY");
                b.setPackage("com.gathin.tvhome");
                b.putExtra("key", keyName);
                sendBroadcast(b);
                return;
            } catch (Exception ignored) {}
        }
        if (sendDaemonCmd("KEY " + keyCode)) {
            return;
        }
        // Fallback if ShellInputDaemon is not running
        mainHandler.post(() -> {
            if ("OK".equals(keyName)) {
                performRemoteOk();
            } else if ("UP".equals(keyName)) {
                performRemoteDirection(View.FOCUS_UP, "UP", 0, 220);
            } else if ("DOWN".equals(keyName)) {
                performRemoteDirection(View.FOCUS_DOWN, "DOWN", 0, -220);
            } else if ("LEFT".equals(keyName)) {
                performRemoteDirection(View.FOCUS_LEFT, "LEFT", 220, 0);
            } else if ("RIGHT".equals(keyName)) {
                performRemoteDirection(View.FOCUS_RIGHT, "RIGHT", -220, 0);
            }
        });
    }

    private void handleKeyAction(String key) {
        if (key == null) return;
        remoteMode = true;
        hideCursorNow();
        bgPool.execute(() -> {
            try {
                AudioManager am = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
                switch (key) {
                    case "BACK":
                        if (!sendDaemonCmd("KEY " + KeyEvent.KEYCODE_BACK)) {
                            mainHandler.post(() -> performGlobalAction(GLOBAL_ACTION_BACK));
                        }
                        break;
                    case "HOME":
                        mainHandler.post(() -> performGlobalAction(GLOBAL_ACTION_HOME));
                        break;
                    case "RECENTS":
                        mainHandler.post(() -> performGlobalAction(GLOBAL_ACTION_RECENTS));
                        break;
                    case "VOL_UP":
                        if (am != null) am.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_RAISE, AudioManager.FLAG_SHOW_UI);
                        break;
                    case "VOL_DOWN":
                        if (am != null) am.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_LOWER, AudioManager.FLAG_SHOW_UI);
                        break;
                    case "MUTE":
                        if (am != null) am.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_TOGGLE_MUTE, AudioManager.FLAG_SHOW_UI);
                        break;
                    case "PLAY_PAUSE":
                        if (!sendDaemonCmd("KEY " + KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE) && am != null) {
                            am.dispatchMediaKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE));
                            am.dispatchMediaKeyEvent(new KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE));
                        }
                        break;
                    case "OK":
                        dispatchKeyFast("OK", KeyEvent.KEYCODE_DPAD_CENTER);
                        break;
                    case "UP":
                        dispatchKeyFast("UP", KeyEvent.KEYCODE_DPAD_UP);
                        break;
                    case "DOWN":
                        dispatchKeyFast("DOWN", KeyEvent.KEYCODE_DPAD_DOWN);
                        break;
                    case "LEFT":
                        dispatchKeyFast("LEFT", KeyEvent.KEYCODE_DPAD_LEFT);
                        break;
                    case "RIGHT":
                        dispatchKeyFast("RIGHT", KeyEvent.KEYCODE_DPAD_RIGHT);
                        break;
                }
            } catch (Exception ignored) {
            }
        });
    }

    private void performRemoteDirection(int focusDir, String keyName, float swipeDx, float swipeDy) {
        try {
            Intent b = new Intent("com.gathin.tvhome.REMOTE_KEY");
            b.setPackage("com.gathin.tvhome");
            b.putExtra("key", keyName);
            sendBroadcast(b);
        } catch (Exception ignored) {
        }

        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root != null) {
            CharSequence pkg = root.getPackageName();
            if (pkg != null && "com.gathin.tvhome".equals(pkg.toString())) {
                root.recycle();
                return;
            }
        }

        try {
            AccessibilityNodeInfo current = findFocus(AccessibilityNodeInfo.FOCUS_INPUT);
            if (current == null) {
                current = findFocus(AccessibilityNodeInfo.FOCUS_ACCESSIBILITY);
            }
            if (current != null) {
                current.getBoundsInScreen(lastRemoteFocusRect);
                AccessibilityNodeInfo next = current.focusSearch(focusDir);
                current.recycle();
                if (next != null) {
                    next.performAction(AccessibilityNodeInfo.ACTION_FOCUS);
                    next.performAction(AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS);
                    next.getBoundsInScreen(lastRemoteFocusRect);
                    next.recycle();
                    if (root != null) root.recycle();
                    return;
                }
            }
            if (root != null) {
                List<AccessibilityNodeInfo> candidates = new ArrayList<>();
                collectInteractiveNodes(root, candidates);
                if (!candidates.isEmpty()) {
                    if (lastRemoteFocusRect.isEmpty()) {
                        AccessibilityNodeInfo first = candidates.get(0);
                        first.performAction(AccessibilityNodeInfo.ACTION_FOCUS);
                        first.performAction(AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS);
                        first.getBoundsInScreen(lastRemoteFocusRect);
                    } else {
                        AccessibilityNodeInfo best = pickSpatialNeighbor(candidates, lastRemoteFocusRect, focusDir);
                        if (best != null) {
                            best.performAction(AccessibilityNodeInfo.ACTION_FOCUS);
                            best.performAction(AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS);
                            best.getBoundsInScreen(lastRemoteFocusRect);
                            for (AccessibilityNodeInfo n : candidates) n.recycle();
                            root.recycle();
                            return;
                        }
                    }
                    for (AccessibilityNodeInfo n : candidates) n.recycle();
                }
                root.recycle();
            }
        } catch (Exception ignored) {
        }
    }

    private void performRemoteOk() {
        try {
            Intent b = new Intent("com.gathin.tvhome.REMOTE_KEY");
            b.setPackage("com.gathin.tvhome");
            b.putExtra("key", "OK");
            sendBroadcast(b);
        } catch (Exception ignored) {
        }

        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root != null) {
            CharSequence pkg = root.getPackageName();
            if (pkg != null && "com.gathin.tvhome".equals(pkg.toString())) {
                root.recycle();
                return;
            }
        }

        try {
            AccessibilityNodeInfo f = findFocus(AccessibilityNodeInfo.FOCUS_INPUT);
            if (f == null) f = findFocus(AccessibilityNodeInfo.FOCUS_ACCESSIBILITY);
            if (f != null) {
                boolean isEdit = f.isEditable() && !isDpadFirstTvApp(getForegroundPackage());
                f.performAction(AccessibilityNodeInfo.ACTION_CLICK);
                f.recycle();
                if (isEdit) triggerKeyboardPopup();
                if (root != null) root.recycle();
                return;
            }
            if (root != null) root.recycle();
        } catch (Exception ignored) {
        }
    }

    private void silentTapAt(int x, int y) {
        try {
            Path path = new Path();
            path.moveTo(x, y);
            GestureDescription.Builder builder = new GestureDescription.Builder();
            builder.addStroke(new GestureDescription.StrokeDescription(path, 0, 40));
            dispatchGesture(builder.build(), null, null);
        } catch (Exception ignored) {
        }
    }

    private void collectInteractiveNodes(AccessibilityNodeInfo node, List<AccessibilityNodeInfo> out) {
        if (node == null || !node.isVisibleToUser()) return;
        if (node.isClickable() || node.isFocusable()) {
            Rect r = new Rect();
            node.getBoundsInScreen(r);
            if (r.width() > 16 && r.height() > 16) {
                out.add(AccessibilityNodeInfo.obtain(node));
            }
        }
        int count = node.getChildCount();
        for (int i = 0; i < count; i++) {
            AccessibilityNodeInfo c = node.getChild(i);
            if (c != null) {
                collectInteractiveNodes(c, out);
                c.recycle();
            }
        }
    }

    private AccessibilityNodeInfo pickSpatialNeighbor(List<AccessibilityNodeInfo> nodes, Rect from, int dir) {
        int fx = from.centerX();
        int fy = from.centerY();
        AccessibilityNodeInfo best = null;
        double bestScore = Double.MAX_VALUE;
        Rect r = new Rect();
        for (AccessibilityNodeInfo n : nodes) {
            n.getBoundsInScreen(r);
            int cx = r.centerX();
            int cy = r.centerY();
            if (cx == fx && cy == fy) continue;
            int dx = cx - fx;
            int dy = cy - fy;
            boolean valid = false;
            double primary = 0, orthogonal = 0;
            if (dir == View.FOCUS_LEFT && dx < -15) {
                valid = true; primary = -dx; orthogonal = Math.abs(dy) * 2.4;
            } else if (dir == View.FOCUS_RIGHT && dx > 15) {
                valid = true; primary = dx; orthogonal = Math.abs(dy) * 2.4;
            } else if (dir == View.FOCUS_UP && dy < -15) {
                valid = true; primary = -dy; orthogonal = Math.abs(dx) * 2.0;
            } else if (dir == View.FOCUS_DOWN && dy > 15) {
                valid = true; primary = dy; orthogonal = Math.abs(dx) * 2.0;
            }
            if (valid) {
                double score = primary + orthogonal;
                if (score < bestScore) {
                    bestScore = score;
                    best = n;
                }
            }
        }
        return best;
    }

    private void startHttpServer() {
        if (running) return;
        running = true;
        Thread serverThread = new Thread(() -> {
            try {
                serverSocket = new ServerSocket(PORT);
                while (running) {
                    Socket client = serverSocket.accept();
                    handleClient(client);
                }
            } catch (Exception ignored) {
            }
        }, "MobileControlHttp");
        serverThread.setDaemon(true);
        serverThread.start();
    }

    private void stopHttpServer() {
        running = false;
        try {
            if (serverSocket != null) serverSocket.close();
        } catch (Exception ignored) {
        }
    }

    private void handleClient(Socket client) {
        try {
            client.setSoTimeout(3000);
            BufferedReader reader = new BufferedReader(new InputStreamReader(client.getInputStream(), StandardCharsets.UTF_8));
            String line = reader.readLine();
            if (line == null) {
                client.close();
                return;
            }
            String[] parts = line.split(" ");
            String path = parts.length > 1 ? parts[1] : "/";
            OutputStream out = client.getOutputStream();

            if (path.startsWith("/api")) {
                Map<String, String> q = parseQuery(path);
                String cmd = q.get("cmd");
                if ("move".equals(cmd)) {
                    float dx = parseFloat(q.get("dx"));
                    float dy = parseFloat(q.get("dy"));
                    moveCursor(dx, dy);
                } else if ("click".equals(cmd)) {
                    clickAtCursor(false);
                } else if ("longclick".equals(cmd)) {
                    clickAtCursor(true);
                } else if ("scroll".equals(cmd)) {
                    float dx = parseFloat(q.get("dx"));
                    float dy = parseFloat(q.get("dy"));
                    scrollAtCursor(dx, dy);
                } else if ("mode".equals(cmd)) {
                    String m = q.get("val");
                    if ("remote".equals(m)) {
                        remoteMode = true;
                        hideCursorNow();
                    } else {
                        remoteMode = false;
                    }
                } else if ("cursor".equals(cmd)) {
                    if ("hide".equals(q.get("val"))) {
                        hideCursorNow();
                    }
                } else if ("key".equals(cmd)) {
                    handleKeyAction(q.get("val"));
                } else if ("text".equals(cmd)) {
                    injectTextAndSubmit(q.get("val"), false);
                } else if ("submit".equals(cmd)) {
                    injectTextAndSubmit(q.get("val"), true);
                } else if ("url".equals(cmd)) {
                    openUrlInBrave(q.get("val"));
                } else if ("app".equals(cmd)) {
                    launchPackage(q.get("pkg"));
                }
                String json = "{\"ok\":true,\"kbSeq\":" + kbSeq.get() + ",\"editable\":" + editableNow + "}";
                byte[] body = json.getBytes(StandardCharsets.UTF_8);
                String header = "HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nAccess-Control-Allow-Origin: *\r\nContent-Length: " + body.length + "\r\nConnection: close\r\n\r\n";
                out.write(header.getBytes(StandardCharsets.UTF_8));
                out.write(body);
            } else if (path.startsWith("/state")) {
                String json = "{\"ok\":true,\"kbSeq\":" + kbSeq.get() + ",\"editable\":" + editableNow + "}";
                byte[] body = json.getBytes(StandardCharsets.UTF_8);
                String header = "HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nAccess-Control-Allow-Origin: *\r\nContent-Length: " + body.length + "\r\nConnection: close\r\n\r\n";
                out.write(header.getBytes(StandardCharsets.UTF_8));
                out.write(body);
            } else {
                byte[] body = WEB_UI_HTML.getBytes(StandardCharsets.UTF_8);
                String header = "HTTP/1.1 200 OK\r\nContent-Type: text/html; charset=utf-8\r\nContent-Length: " + body.length + "\r\nConnection: close\r\n\r\n";
                out.write(header.getBytes(StandardCharsets.UTF_8));
                out.write(body);
            }
            out.flush();
            client.close();
        } catch (Exception ignored) {
            try { client.close(); } catch (Exception e) {}
        }
    }

    private static Map<String, String> parseQuery(String url) {
        Map<String, String> map = new HashMap<>();
        int idx = url.indexOf('?');
        if (idx < 0) return map;
        String[] pairs = url.substring(idx + 1).split("&");
        for (String p : pairs) {
            int eq = p.indexOf('=');
            if (eq > 0) {
                try {
                    String k = URLDecoder.decode(p.substring(0, eq), "UTF-8");
                    String v = URLDecoder.decode(p.substring(eq + 1), "UTF-8");
                    map.put(k, v);
                } catch (Exception ignored) {}
            }
        }
        return map;
    }

    private static float parseFloat(String s) {
        if (s == null) return 0f;
        try { return Float.parseFloat(s); } catch (Exception e) { return 0f; }
    }

    public static String getLocalIpAddress() {
        try {
            Enumeration<NetworkInterface> en = NetworkInterface.getNetworkInterfaces();
            while (en.hasMoreElements()) {
                NetworkInterface intf = en.nextElement();
                Enumeration<InetAddress> addrs = intf.getInetAddresses();
                while (addrs.hasMoreElements()) {
                    InetAddress addr = addrs.nextElement();
                    if (!addr.isLoopbackAddress() && addr instanceof Inet4Address) {
                        return addr.getHostAddress();
                    }
                }
            }
        } catch (Exception ignored) {}
        return "192.168.0.233";
    }

    private static final class CursorView extends View {
        private final Paint shadowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint bodyPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint rimPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint tipGlowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint pulsePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path pointerPath = new Path();
        private final Path shadowPath = new Path();
        private float pulseProgress = 0f;

        CursorView(Context context) {
            super(context);
            shadowPaint.setColor(Color.argb(135, 0, 0, 0));
            shadowPaint.setStyle(Paint.Style.FILL);

            bodyPaint.setShader(new LinearGradient(4f, 4f, 26f, 34f,
                    Color.parseColor("#1E293B"), Color.parseColor("#090D16"), Shader.TileMode.CLAMP));
            bodyPaint.setStyle(Paint.Style.FILL);

            rimPaint.setColor(Color.WHITE);
            rimPaint.setStyle(Paint.Style.STROKE);
            rimPaint.setStrokeWidth(2.4f);
            rimPaint.setStrokeJoin(Paint.Join.ROUND);
            rimPaint.setStrokeCap(Paint.Cap.ROUND);

            tipGlowPaint.setColor(Color.parseColor("#38BDF8"));
            tipGlowPaint.setStyle(Paint.Style.FILL);

            pulsePaint.setColor(Color.parseColor("#38BDF8"));
            pulsePaint.setStyle(Paint.Style.STROKE);
            pulsePaint.setStrokeWidth(2.2f);

            pointerPath.moveTo(5f, 4f);
            pointerPath.lineTo(5f, 31f);
            pointerPath.lineTo(12.2f, 24.2f);
            pointerPath.lineTo(17.8f, 35.5f);
            pointerPath.lineTo(22.5f, 33.2f);
            pointerPath.lineTo(16.8f, 21.8f);
            pointerPath.lineTo(26.5f, 21.8f);
            pointerPath.close();

            shadowPath.set(pointerPath);
            shadowPath.offset(2.2f, 3.2f);
        }

        void playClickPulse() {
            ValueAnimator va = ValueAnimator.ofFloat(0.01f, 1f);
            va.setDuration(240);
            va.addUpdateListener(anim -> {
                pulseProgress = (float) anim.getAnimatedValue();
                invalidate();
            });
            va.start();
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            if (pulseProgress > 0.01f && pulseProgress < 0.99f) {
                int alpha = (int) ((1f - pulseProgress) * 210);
                pulsePaint.setAlpha(alpha);
                float radius = 6f + pulseProgress * 18f;
                canvas.drawCircle(6f, 6f, radius, pulsePaint);
            }
            canvas.drawPath(shadowPath, shadowPaint);
            canvas.drawPath(pointerPath, bodyPaint);
            canvas.drawPath(pointerPath, rimPaint);
            canvas.drawCircle(6.2f, 6.2f, 2.0f, tipGlowPaint);
        }
    }

    private static final String WEB_UI_HTML =
        "<!DOCTYPE html><html><head><meta charset='utf-8'>" +
        "<meta name='viewport' content='width=device-width,initial-scale=1,maximum-scale=1,user-scalable=no'>" +
        "<title>MiTV Studio Remote v3.0</title>" +
        "<style>body{margin:0;background:#05070E;color:#F8FAFC;font-family:sans-serif;display:flex;align-items:center;justify-content:center;height:100vh;}</style>" +
        "</head><body><h2 style='color:#38BDF8'>MiTV Control Server v3.0 Active</h2></body></html>";
}
