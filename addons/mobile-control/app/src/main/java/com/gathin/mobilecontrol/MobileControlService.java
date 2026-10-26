package com.gathin.mobilecontrol;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.media.AudioManager;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.DisplayMetrics;
import android.view.Gravity;
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
import java.util.Enumeration;
import java.util.HashMap;
import java.util.Map;

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

    private ServerSocket serverSocket;
    private volatile boolean running = false;

    private final Runnable hideCursorRunnable = new Runnable() {
        @Override
        public void run() {
            if (cursorView != null) {
                cursorView.setVisibility(View.GONE);
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
    }

    @Override
    public void onInterrupt() {
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
                        36, 36,
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

    private void moveCursor(float dx, float dy) {
        mainHandler.post(() -> {
            if (!cursorAdded || cursorView == null) {
                setupOverlay();
                return;
            }
            cursorX = Math.max(0f, Math.min(screenW - 8f, cursorX + dx));
            cursorY = Math.max(0f, Math.min(screenH - 8f, cursorY + dy));
            cursorParams.x = (int) cursorX;
            cursorParams.y = (int) cursorY;
            cursorView.setVisibility(View.VISIBLE);
            try {
                windowManager.updateViewLayout(cursorView, cursorParams);
            } catch (Exception ignored) {
            }
            mainHandler.removeCallbacks(hideCursorRunnable);
            mainHandler.postDelayed(hideCursorRunnable, 5000);
        });
    }

    private void clickAtCursor() {
        mainHandler.post(() -> {
            if (cursorView != null) {
                cursorView.setVisibility(View.VISIBLE);
                mainHandler.removeCallbacks(hideCursorRunnable);
                mainHandler.postDelayed(hideCursorRunnable, 5000);
            }
            try {
                Path path = new Path();
                path.moveTo(cursorX, cursorY);
                GestureDescription.Builder builder = new GestureDescription.Builder();
                builder.addStroke(new GestureDescription.StrokeDescription(path, 0, 45));
                dispatchGesture(builder.build(), null, null);
            } catch (Exception ignored) {
            }
        });
    }

    private void scrollAtCursor(float dy) {
        mainHandler.post(() -> {
            try {
                float startY = Math.max(80f, Math.min(screenH - 80f, cursorY));
                float endY = Math.max(40f, Math.min(screenH - 40f, startY + dy));
                Path path = new Path();
                path.moveTo(cursorX, startY);
                path.lineTo(cursorX, endY);
                GestureDescription.Builder builder = new GestureDescription.Builder();
                builder.addStroke(new GestureDescription.StrokeDescription(path, 0, 180));
                dispatchGesture(builder.build(), null, null);
            } catch (Exception ignored) {
            }
        });
    }

    private void injectText(String text) {
        if (text == null || text.isEmpty()) return;
        mainHandler.post(() -> {
            try {
                ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
                if (cm != null) {
                    cm.setPrimaryClip(ClipData.newPlainText("tv", text));
                }
                AccessibilityNodeInfo focus = findFocus(AccessibilityNodeInfo.FOCUS_INPUT);
                if (focus != null) {
                    Bundle b = new Bundle();
                    b.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text);
                    if (!focus.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, b)) {
                        focus.performAction(AccessibilityNodeInfo.ACTION_PASTE);
                    }
                    focus.recycle();
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
                    case "OK":
                        AccessibilityNodeInfo f = findFocus(AccessibilityNodeInfo.FOCUS_INPUT);
                        if (f != null && f.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                            f.recycle();
                        } else {
                            if (f != null) f.recycle();
                            clickAtCursor();
                        }
                        break;
                    case "UP":
                        moveFocusOrSwipe(View.FOCUS_UP, 0, 220);
                        break;
                    case "DOWN":
                        moveFocusOrSwipe(View.FOCUS_DOWN, 0, -220);
                        break;
                    case "LEFT":
                        moveFocusOrSwipe(View.FOCUS_LEFT, 220, 0);
                        break;
                    case "RIGHT":
                        moveFocusOrSwipe(View.FOCUS_RIGHT, -220, 0);
                        break;
                }
            } catch (Exception ignored) {
            }
        });
    }

    private void moveFocusOrSwipe(int direction, float dx, float dy) {
        try {
            AccessibilityNodeInfo current = findFocus(AccessibilityNodeInfo.FOCUS_INPUT);
            if (current != null) {
                AccessibilityNodeInfo next = current.focusSearch(direction);
                current.recycle();
                if (next != null) {
                    boolean ok = next.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
                            || next.performAction(AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS);
                    next.recycle();
                    if (ok) return;
                }
            }
        } catch (Exception ignored) {
        }
        if (dy != 0) scrollAtCursor(dy);
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
                    clickAtCursor();
                } else if ("scroll".equals(cmd)) {
                    float dy = parseFloat(q.get("dy"));
                    scrollAtCursor(dy);
                } else if ("key".equals(cmd)) {
                    handleKeyAction(q.get("val"));
                } else if ("text".equals(cmd)) {
                    injectText(q.get("val"));
                } else if ("url".equals(cmd)) {
                    openUrlInBrave(q.get("val"));
                } else if ("app".equals(cmd)) {
                    launchPackage(q.get("pkg"));
                }
                byte[] body = "{\"ok\":true}".getBytes(StandardCharsets.UTF_8);
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
        private final Paint fillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint strokePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path arrowPath = new Path();

        CursorView(Context context) {
            super(context);
            fillPaint.setColor(Color.parseColor("#10B981"));
            fillPaint.setStyle(Paint.Style.FILL);
            strokePaint.setColor(Color.WHITE);
            strokePaint.setStyle(Paint.Style.STROKE);
            strokePaint.setStrokeWidth(2.5f);

            arrowPath.moveTo(2f, 2f);
            arrowPath.lineTo(2f, 28f);
            arrowPath.lineTo(10f, 21f);
            arrowPath.lineTo(16f, 32f);
            arrowPath.lineTo(21f, 29f);
            arrowPath.lineTo(15f, 18f);
            arrowPath.lineTo(25f, 18f);
            arrowPath.close();
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            canvas.drawPath(arrowPath, fillPaint);
            canvas.drawPath(arrowPath, strokePaint);
        }
    }

    private static final String WEB_UI_HTML =
        "<!DOCTYPE html><html><head><meta charset='utf-8'>" +
        "<meta name='viewport' content='width=device-width,initial-scale=1,maximum-scale=1,user-scalable=no'>" +
        "<title>Xiaomi TV Mobile Control</title>" +
        "<style>" +
        "*{box-sizing:border-box;-webkit-user-select:none;user-select:none;touch-action:manipulation;}" +
        "body{margin:0;background:#070A14;color:#F8FAFC;font-family:-apple-system,BlinkMacSystemFont,'Segoe UI',Roboto,sans-serif;display:flex;flex-direction:column;height:100vh;overflow:hidden;}" +
        "header{padding:12px 16px;background:#0F172A;border-bottom:1px solid #1E293B;display:flex;justify-content:space-between;align-items:center;}" +
        "header h1{margin:0;font-size:16px;font-weight:700;color:#10B981;}" +
        ".tabs{display:flex;background:#0F172A;padding:6px;gap:6px;border-bottom:1px solid #1E293B;}" +
        ".tab{flex:1;padding:10px 4px;text-align:center;border-radius:10px;font-size:13px;font-weight:600;color:#94A3B8;background:#1E293B;cursor:pointer;}" +
        ".tab.active{background:#10B981;color:#052E16;}" +
        ".panel{display:none;flex:1;flex-direction:column;padding:12px;gap:10px;overflow-y:auto;}" +
        ".panel.active{display:flex;}" +
        ".pad-wrap{flex:1;display:flex;gap:10px;min-height:240px;}" +
        "#touchpad{flex:1;background:radial-gradient(circle at center,#1E293B,#0F172A);border:2px solid #334155;border-radius:18px;display:flex;align-items:center;justify-content:center;color:#64748B;font-size:14px;text-align:center;touch-action:none;}" +
        "#scrollbar{width:54px;background:#1E293B;border:2px solid #334155;border-radius:18px;display:flex;align-items:center;justify-content:center;color:#94A3B8;font-size:12px;writing-mode:vertical-rl;touch-action:none;}" +
        ".row{display:flex;gap:8px;}" +
        ".btn{flex:1;padding:14px 10px;background:#1E293B;border:1px solid #334155;border-radius:14px;color:#F8FAFC;font-size:14px;font-weight:600;text-align:center;active:scale(0.97);}" +
        ".btn:active{background:#10B981;color:#052E16;}" +
        ".btn.accent{background:#10B981;color:#052E16;border-color:#10B981;}" +
        "input[type=text]{flex:1;padding:14px;border-radius:12px;border:1px solid #334155;background:#0F172A;color:#FFF;font-size:15px;-webkit-user-select:text;user-select:text;}" +
        ".dpad{display:grid;grid-template-columns:1fr 1fr 1fr;gap:10px;max-width:280px;margin:auto;width:100%;}" +
        ".dpad .btn{padding:20px 0;font-size:18px;}" +
        "</style></head><body>" +
        "<header><h1>📺 MiTV Mobile Control</h1><span style='font-size:12px;color:#94A3B8'>Mouse · Keys · Apps</span></header>" +
        "<div class='tabs'>" +
        "<div class='tab active' onclick='showTab(0)'>🖱️ Mouse</div>" +
        "<div class='tab' onclick='showTab(1)'>⌨️ Brave & Keys</div>" +
        "<div class='tab' onclick='showTab(2)'>📺 Remote</div>" +
        "<div class='tab' onclick='showTab(3)'>🎮 Controller</div>" +
        "</div>" +
        "<div class='panel active' id='p0'>" +
        "<div class='pad-wrap'>" +
        "<div id='touchpad'>Slide to move TV cursor<br>Tap anywhere to Click</div>" +
        "<div id='scrollbar'>⇅ SCROLL</div>" +
        "</div>" +
        "<div class='row'>" +
        "<div class='btn' onclick=\"api('key&val=BACK')\">⬅ Back</div>" +
        "<div class='btn accent' onclick=\"api('click')\">🖱️ Left Click</div>" +
        "<div class='btn' onclick=\"api('key&val=HOME')\">🏠 Home</div>" +
        "</div></div>" +
        "<div class='panel' id='p1'>" +
        "<div class='row'><input id='urlIn' type='text' placeholder='Enter URL or Brave search...'><div class='btn accent' style='flex:0 0 110px' onclick='sendUrl()'>Brave Go</div></div>" +
        "<div class='row'><input id='txtIn' type='text' placeholder='Type text into focused TV box...'><div class='btn' style='flex:0 0 110px' onclick='sendTxt()'>Send Text</div></div>" +
        "<div style='font-size:13px;color:#94A3B8;margin-top:6px'>Quick Launch TV & Android Apps:</div>" +
        "<div class='row'><div class='btn' onclick=\"api('app&pkg=com.brave.browser')\">🦁 Brave Browser</div><div class='btn' onclick=\"api('app&pkg=org.smarttube.stable')\">▶️ SmartTube</div></div>" +
        "<div class='row'><div class='btn' onclick=\"api('app&pkg=com.google.android.youtube.tv')\">📺 YouTube</div><div class='btn' onclick=\"api('app&pkg=com.limelight')\">💻 Moonlight PC</div></div>" +
        "<div class='row'><div class='btn' onclick=\"api('app&pkg=com.netflix.ninja')\">🎬 Netflix</div><div class='btn' onclick=\"api('app&pkg=in.startv.hotstar')\">⭐ JioHotstar</div></div>" +
        "</div>" +
        "<div class='panel' id='p2'>" +
        "<div class='dpad'>" +
        "<div></div><div class='btn' onclick=\"api('key&val=UP')\">▲</div><div></div>" +
        "<div class='btn' onclick=\"api('key&val=LEFT')\">◄</div><div class='btn accent' onclick=\"api('key&val=OK')\">OK</div><div class='btn' onclick=\"api('key&val=RIGHT')\">►</div>" +
        "<div></div><div class='btn' onclick=\"api('key&val=DOWN')\">▼</div><div></div>" +
        "</div>" +
        "<div class='row'>" +
        "<div class='btn' onclick=\"api('key&val=BACK')\">⬅ Back</div>" +
        "<div class='btn' onclick=\"api('key&val=HOME')\">🏠 Home</div>" +
        "<div class='btn' onclick=\"api('key&val=RECENTS')\">🗂 Apps</div>" +
        "</div>" +
        "<div class='row'>" +
        "<div class='btn' onclick=\"api('key&val=VOL_DOWN')\">🔉 Vol -</div>" +
        "<div class='btn' onclick=\"api('key&val=MUTE')\">🔇 Mute</div>" +
        "<div class='btn' onclick=\"api('key&val=VOL_UP')\">🔊 Vol +</div>" +
        "</div></div>" +
        "<div class='panel' id='p3'>" +
        "<div style='display:flex;flex:1;align-items:center;justify-content:space-between;gap:12px'>" +
        "<div class='dpad' style='max-width:170px'>" +
        "<div></div><div class='btn' onclick=\"api('key&val=UP')\">▲</div><div></div>" +
        "<div class='btn' onclick=\"api('key&val=LEFT')\">◄</div><div></div><div class='btn' onclick=\"api('key&val=RIGHT')\">►</div>" +
        "<div></div><div class='btn' onclick=\"api('key&val=DOWN')\">▼</div><div></div>" +
        "</div>" +
        "<div style='display:grid;grid-template-columns:1fr 1fr;gap:12px;width:150px'>" +
        "<div class='btn accent' onclick=\"api('key&val=OK')\">A (OK)</div>" +
        "<div class='btn' onclick=\"api('key&val=BACK')\">B (Back)</div>" +
        "<div class='btn' onclick=\"api('click')\">X (Tap)</div>" +
        "<div class='btn' onclick=\"api('key&val=HOME')\">Y (Home)</div>" +
        "</div></div>" +
        "<div class='row'><div class='btn accent' onclick=\"api('app&pkg=com.limelight')\">💻 Launch Moonlight PC Streamer</div></div>" +
        "</div>" +
        "<script>" +
        "function api(q){fetch('/api?cmd='+q).catch(()=>{});}" +
        "function showTab(i){document.querySelectorAll('.tab').forEach((t,idx)=>t.classList.toggle('active',idx===i));document.querySelectorAll('.panel').forEach((p,idx)=>p.classList.toggle('active',idx===i));}" +
        "function sendUrl(){const v=document.getElementById('urlIn').value;if(v)api('url&val='+encodeURIComponent(v));}" +
        "function sendTxt(){const v=document.getElementById('txtIn').value;if(v)api('text&val='+encodeURIComponent(v));}" +
        "const pad=document.getElementById('touchpad');let lx=0,ly=0,moved=false,t0=0,busy=false;" +
        "pad.addEventListener('touchstart',e=>{const t=e.touches[0];lx=t.clientX;ly=t.clientY;moved=false;t0=Date.now();},{passive:false});" +
        "pad.addEventListener('touchmove',e=>{e.preventDefault();const t=e.touches[0];const dx=(t.clientX-lx)*1.9,dy=(t.clientY-ly)*1.9;if(Math.abs(dx)>2||Math.abs(dy)>2){moved=true;lx=t.clientX;ly=t.clientY;if(!busy){busy=true;fetch('/api?cmd=move&dx='+dx.toFixed(1)+'&dy='+dy.toFixed(1)).finally(()=>{busy=false;});}}},{passive:false});" +
        "pad.addEventListener('touchend',e=>{if(!moved&&(Date.now()-t0)<280)api('click');});" +
        "const sb=document.getElementById('scrollbar');let sy=0,sbusy=false;" +
        "sb.addEventListener('touchstart',e=>{sy=e.touches[0].clientY;},{passive:false});" +
        "sb.addEventListener('touchmove',e=>{e.preventDefault();const dy=(e.touches[0].clientY-sy)*3.0;if(Math.abs(dy)>25&&!sbusy){sy=e.touches[0].clientY;sbusy=true;fetch('/api?cmd=scroll&dy='+dy.toFixed(0)).finally(()=>{sbusy=false;});}},{passive:false});" +
        "</script></body></html>";
}
