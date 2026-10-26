package com.gathin.mobilecontrol;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.animation.ValueAnimator;
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
import java.net.NetworkInterface;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

public class MobileControlService extends AccessibilityService {

    public static final int PORT = 8888;
    public static volatile MobileControlService sInstance;

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private WindowManager windowManager;
    private CursorView cursorView;
    private WindowManager.LayoutParams cursorParams;
    private int screenW = 1280;
    private int screenH = 720;
    private float cursorX = 640f;
    private float cursorY = 360f;
    private boolean cursorAdded = false;
    private volatile boolean remoteMode = false;

    private final AtomicInteger kbSeq = new AtomicInteger(0);
    private volatile boolean editableNow = false;
    private long lastKbTriggerMs = 0L;
    private final Rect lastRemoteFocusRect = new Rect();

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
        int type = event.getEventType();
        if (type == AccessibilityEvent.TYPE_VIEW_FOCUSED
                || type == AccessibilityEvent.TYPE_VIEW_CLICKED
                || type == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            AccessibilityNodeInfo src = event.getSource();
            if (src != null) {
                boolean isEdit = isNodeEditableOrSearch(src, type == AccessibilityEvent.TYPE_VIEW_CLICKED);
                src.recycle();
                if (isEdit) {
                    triggerKeyboardPopup();
                    return;
                }
            }
            checkActiveWindowForEditable(false);
        }
    }

    @Override
    public void onInterrupt() {
    }

    private void triggerKeyboardPopup() {
        long now = System.currentTimeMillis();
        editableNow = true;
        if (now - lastKbTriggerMs > 650) {
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
        remoteMode = false;
        mainHandler.post(() -> {
            if (cursorView != null) {
                showCursorAndScheduleHide();
                cursorView.playClickPulse();
            }
            final int tapX = (int) cursorX;
            final int tapY = (int) cursorY;

            // Pre-check if the user clicked directly on a Search button or EditText
            try {
                AccessibilityNodeInfo root = getRootInActiveWindow();
                if (root != null) {
                    AccessibilityNodeInfo hit = findDeepestNodeAt(root, tapX, tapY);
                    root.recycle();
                    if (hit != null) {
                        boolean isSearchOrEdit = isNodeEditableOrSearch(hit, true);
                        AccessibilityNodeInfo parent = hit.getParent();
                        if (!isSearchOrEdit && parent != null) {
                            isSearchOrEdit = isNodeEditableOrSearch(parent, true);
                        }
                        if (parent != null) parent.recycle();
                        hit.recycle();
                        if (isSearchOrEdit) {
                            triggerKeyboardPopup();
                        }
                    }
                }
            } catch (Exception ignored) {
            }

            try {
                Path path = new Path();
                path.moveTo(cursorX, cursorY);
                GestureDescription.Builder builder = new GestureDescription.Builder();
                builder.addStroke(new GestureDescription.StrokeDescription(path, 0, longPress ? 520 : 42));
                dispatchGesture(builder.build(), null, null);
            } catch (Exception ignored) {
            }

            mainHandler.postDelayed(() -> checkActiveWindowForEditable(true), 220);
            mainHandler.postDelayed(() -> checkActiveWindowForEditable(false), 520);
        });
    }

    private void scrollAtCursor(float dx, float dy) {
        mainHandler.post(() -> {
            try {
                float cx = remoteMode ? (screenW / 2f) : cursorX;
                float cy = remoteMode ? (screenH / 2f) : cursorY;
                float startX = Math.max(80f, Math.min(screenW - 80f, cx));
                float startY = Math.max(80f, Math.min(screenH - 80f, cy));
                float endX = Math.max(30f, Math.min(screenW - 30f, startX + dx));
                float endY = Math.max(30f, Math.min(screenH - 30f, startY + dy));
                if (Math.abs(endX - startX) < 4f && Math.abs(endY - startY) < 4f) return;
                Path path = new Path();
                path.moveTo(startX, startY);
                path.lineTo(endX, endY);
                GestureDescription.Builder builder = new GestureDescription.Builder();
                builder.addStroke(new GestureDescription.StrokeDescription(path, 0, 150));
                dispatchGesture(builder.build(), null, null);
            } catch (Exception ignored) {
            }
        });
    }

    private void injectText(String text, boolean submit) {
        if (text == null) return;
        mainHandler.post(() -> {
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
                    if (!target.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, b) && !text.isEmpty()) {
                        target.performAction(AccessibilityNodeInfo.ACTION_PASTE);
                    }
                    if (submit) {
                        if (Build.VERSION.SDK_INT >= 30) {
                            target.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.getId());
                        }
                        target.performAction(AccessibilityNodeInfo.ACTION_CLICK);
                    }
                    target.recycle();
                } else if (submit && !text.trim().isEmpty()) {
                    openUrlInBrave(text.trim());
                }
            } catch (Exception ignored) {
            }
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

    private void handleKeyAction(String key) {
        if (key == null) return;
        remoteMode = true;
        hideCursorNow();
        mainHandler.post(() -> {
            try {
                AudioManager am = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
                switch (key) {
                    case "BACK":
                        performGlobalAction(GLOBAL_ACTION_BACK);
                        break;
                    case "HOME":
                        performGlobalAction(GLOBAL_ACTION_HOME);
                        break;
                    case "RECENTS":
                        performGlobalAction(GLOBAL_ACTION_RECENTS);
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
                        if (am != null) {
                            am.dispatchMediaKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE));
                            am.dispatchMediaKeyEvent(new KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE));
                        }
                        break;
                    case "OK":
                        performRemoteOk();
                        break;
                    case "UP":
                        performRemoteDirection(View.FOCUS_UP, "UP", 0, 220);
                        break;
                    case "DOWN":
                        performRemoteDirection(View.FOCUS_DOWN, "DOWN", 0, -220);
                        break;
                    case "LEFT":
                        performRemoteDirection(View.FOCUS_LEFT, "LEFT", 220, 0);
                        break;
                    case "RIGHT":
                        performRemoteDirection(View.FOCUS_RIGHT, "RIGHT", -220, 0);
                        break;
                }
            } catch (Exception ignored) {
            }
        });
    }

    private void performRemoteDirection(int focusDir, String keyName, float swipeDx, float swipeDy) {
        // 1. Send direct broadcast to our Launcher (works even in Touch Mode!)
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

        // 2. For all other apps: try focusSearch or spatial 2D focus navigation
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
        if (swipeDy != 0 || swipeDx != 0) {
            scrollAtCursor(swipeDx, swipeDy);
        }
    }

    private void performRemoteOk() {
        // 1. Send direct broadcast to our Launcher
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

        // 2. For all other apps: click focused node (NEVER click the mouse cursor!)
        try {
            AccessibilityNodeInfo f = findFocus(AccessibilityNodeInfo.FOCUS_INPUT);
            if (f == null) f = findFocus(AccessibilityNodeInfo.FOCUS_ACCESSIBILITY);
            if (f != null) {
                boolean isSearch = isNodeEditableOrSearch(f, true);
                Rect r = new Rect();
                f.getBoundsInScreen(r);
                boolean clicked = f.performAction(AccessibilityNodeInfo.ACTION_CLICK);
                f.recycle();
                if (isSearch) triggerKeyboardPopup();
                if (!clicked && !r.isEmpty()) {
                    silentTapAt(r.centerX(), r.centerY());
                }
                if (root != null) root.recycle();
                mainHandler.postDelayed(() -> checkActiveWindowForEditable(true), 220);
                return;
            }
            if (!lastRemoteFocusRect.isEmpty()) {
                silentTapAt(lastRemoteFocusRect.centerX(), lastRemoteFocusRect.centerY());
                mainHandler.postDelayed(() -> checkActiveWindowForEditable(true), 220);
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
                    injectText(q.get("val"), false);
                } else if ("submit".equals(cmd)) {
                    injectText(q.get("val"), true);
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

    /**
     * Premium macOS Sonoma / Apple VisionOS inspired fluid pointer with diffused drop shadow,
     * obsidian glass core, pearl-white rim, cyan tip glow, and radial click pulse animation.
     */
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

            // Sleek macOS / VisionOS rounded arrow geometry
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
        "<title>MiTV Studio Remote</title>" +
        "<style>" +
        "*{box-sizing:border-box;-webkit-user-select:none;user-select:none;touch-action:manipulation;}" +
        "body{margin:0;background:#05070E;color:#F8FAFC;font-family:-apple-system,BlinkMacSystemFont,'Inter',sans-serif;display:flex;flex-direction:column;height:100vh;overflow:hidden;}" +
        "</style></head><body><h2 style='padding:20px;color:#38BDF8'>MiTV Control Server v2.0 Active</h2></body></html>";
}
