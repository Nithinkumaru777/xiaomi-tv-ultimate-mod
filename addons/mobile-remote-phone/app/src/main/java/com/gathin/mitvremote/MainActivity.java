package com.gathin.mitvremote;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.view.KeyEvent;
import android.view.Window;
import android.view.WindowManager;
import android.view.inputmethod.InputMethodManager;
import android.webkit.JavascriptInterface;
import android.webkit.WebSettings;
import android.webkit.WebView;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

public final class MainActivity extends Activity {

    private static final String PREFS = "mitv_remote_prefs";
    private static final String KEY_TV_IP = "tv_ip";
    private static final String DEFAULT_IP = "192.168.0.233";
    private static final int PORT = 8888;

    private WebView webView;
    private volatile String tvIp = DEFAULT_IP;
    private final ExecutorService netPool = Executors.newFixedThreadPool(8);
    private final ScheduledExecutorService statePoller = Executors.newSingleThreadScheduledExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private Vibrator vibrator;
    private volatile int lastSeenKbSeq = -1;

    @SuppressLint({"SetJavaScriptEnabled", "AddJavascriptInterface"})
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        Window w = getWindow();
        if (w != null) {
            w.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            w.setStatusBarColor(Color.parseColor("#05070E"));
            w.setNavigationBarColor(Color.parseColor("#05070E"));
        }

        SharedPreferences prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        tvIp = prefs.getString(KEY_TV_IP, DEFAULT_IP);
        vibrator = (Vibrator) getSystemService(Context.VIBRATOR_SERVICE);

        webView = new WebView(this);
        webView.setBackgroundColor(Color.parseColor("#05070E"));
        webView.setFocusable(true);
        webView.setFocusableInTouchMode(true);
        WebSettings ws = webView.getSettings();
        ws.setJavaScriptEnabled(true);
        ws.setDomStorageEnabled(true);
        ws.setBuiltInZoomControls(false);
        ws.setDisplayZoomControls(false);

        webView.addJavascriptInterface(new Bridge(), "AndroidBridge");
        setContentView(webView);

        webView.loadDataWithBaseURL("https://mitv.local/", buildAppHtml(tvIp), "text/html", "UTF-8", null);
        autoConnectOrScan();

        statePoller.scheduleWithFixedDelay(this::pollTvState, 800, 420, TimeUnit.MILLISECONDS);
    }

    @Override
    protected void onDestroy() {
        statePoller.shutdownNow();
        netPool.shutdownNow();
        super.onDestroy();
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_VOLUME_UP) {
            haptic(18);
            sendCmd("key&val=VOL_UP");
            return true;
        } else if (keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) {
            haptic(18);
            sendCmd("key&val=VOL_DOWN");
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    private void haptic(int ms) {
        try {
            if (vibrator == null || !vibrator.hasVibrator()) return;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator.vibrate(VibrationEffect.createOneShot(ms, VibrationEffect.DEFAULT_AMPLITUDE));
            } else {
                vibrator.vibrate(ms);
            }
        } catch (Throwable ignored) {}
    }

    private void showNativeKeyboard() {
        mainHandler.post(() -> {
            try {
                if (webView != null) {
                    webView.requestFocus();
                    webView.requestFocusFromTouch();
                    InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
                    if (imm != null) {
                        imm.showSoftInput(webView, InputMethodManager.SHOW_IMPLICIT);
                    }
                }
            } catch (Throwable ignored) {}
        });
    }

    private void hideNativeKeyboard() {
        mainHandler.post(() -> {
            try {
                if (webView != null) {
                    InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
                    if (imm != null) {
                        imm.hideSoftInputFromWindow(webView.getWindowToken(), 0);
                    }
                }
            } catch (Throwable ignored) {}
        });
    }

    private void pollTvState() {
        final String targetIp = tvIp;
        HttpURLConnection conn = null;
        try {
            URL u = new URL("http://" + targetIp + ":" + PORT + "/state");
            conn = (HttpURLConnection) u.openConnection();
            conn.setConnectTimeout(400);
            conn.setReadTimeout(400);
            conn.setRequestMethod("GET");
            if (conn.getResponseCode() == 200) {
                BufferedReader br = new BufferedReader(new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8));
                String body = br.readLine();
                br.close();
                handleServerJson(targetIp, body, false);
            }
        } catch (Throwable ignored) {
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    private synchronized void handleServerJson(String ip, String body, boolean allowKbPopup) {
        updateStatusUi(ip, true);
        if (body == null) return;
        int idx = body.indexOf("\"kbSeq\":");
        if (idx >= 0) {
            int start = idx + 8;
            int end = start;
            while (end < body.length() && Character.isDigit(body.charAt(end))) end++;
            try {
                int seq = Integer.parseInt(body.substring(start, end));
                if (lastSeenKbSeq == -1 || !allowKbPopup) {
                    if (seq > lastSeenKbSeq) lastSeenKbSeq = seq;
                } else if (seq > lastSeenKbSeq) {
                    lastSeenKbSeq = seq;
                    triggerMobileKeyboardOverlay();
                }
            } catch (NumberFormatException ignored) {}
        }
    }

    private void triggerMobileKeyboardOverlay() {
        haptic(24);
        mainHandler.post(() -> {
            if (webView != null) {
                webView.evaluateJavascript("window.openKeyboardSheet && window.openKeyboardSheet();", null);
            }
            mainHandler.postDelayed(this::showNativeKeyboard, 80);
        });
    }

    private void sendCmd(final String query) {
        final String targetIp = tvIp;
        final boolean allowKb = query != null && query.startsWith("click");
        netPool.execute(() -> {
            HttpURLConnection conn = null;
            try {
                URL u = new URL("http://" + targetIp + ":" + PORT + "/api?cmd=" + query);
                conn = (HttpURLConnection) u.openConnection();
                conn.setConnectTimeout(750);
                conn.setReadTimeout(750);
                conn.setRequestMethod("GET");
                int code = conn.getResponseCode();
                if (code == 200) {
                    BufferedReader br = new BufferedReader(new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8));
                    String body = br.readLine();
                    br.close();
                    handleServerJson(targetIp, body, allowKb);
                }
            } catch (Throwable t) {
                updateStatusUi(targetIp, false);
            } finally {
                if (conn != null) conn.disconnect();
            }
        });
    }

    private boolean probeHost(String ip, int timeoutMs) {
        try (Socket s = new Socket()) {
            s.connect(new InetSocketAddress(ip, PORT), timeoutMs);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    private void saveIp(String ip) {
        tvIp = ip;
        lastSeenKbSeq = -1;
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putString(KEY_TV_IP, ip).apply();
        updateStatusUi(ip, true);
    }

    private void updateStatusUi(final String ip, final boolean online) {
        mainHandler.post(() -> {
            if (webView != null) {
                webView.evaluateJavascript("window.onTvStatus && window.onTvStatus('" + ip + "'," + online + ");", null);
            }
        });
    }

    private void autoConnectOrScan() {
        netPool.execute(() -> {
            if (probeHost(tvIp, 600)) {
                updateStatusUi(tvIp, true);
                return;
            }
            if (probeHost("192.168.0.233", 600)) {
                saveIp("192.168.0.233");
                return;
            }
            if (probeHost("192.168.1.9", 600)) {
                saveIp("192.168.1.9");
                return;
            }
            scanSubnet();
        });
    }

    private void scanSubnet() {
        netPool.execute(() -> {
            String prefix = "192.168.0.";
            try {
                WifiManager wm = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
                if (wm != null) {
                    WifiInfo info = wm.getConnectionInfo();
                    int ipInt = info != null ? info.getIpAddress() : 0;
                    if (ipInt != 0) {
                        prefix = (ipInt & 0xff) + "." + ((ipInt >> 8) & 0xff) + "." + ((ipInt >> 16) & 0xff) + ".";
                    }
                }
            } catch (Throwable ignored) {}

            final String sub = prefix;
            final AtomicBoolean found = new AtomicBoolean(false);
            ExecutorService scanner = Executors.newFixedThreadPool(28);
            for (int i = 1; i <= 254; i++) {
                final String candidate = sub + i;
                scanner.execute(() -> {
                    if (found.get()) return;
                    if (probeHost(candidate, 320)) {
                        if (found.compareAndSet(false, true)) {
                            saveIp(candidate);
                        }
                    }
                });
            }
            scanner.shutdown();
        });
    }

    public final class Bridge {
        @JavascriptInterface
        public void api(String query, boolean vibrate) {
            if (vibrate) haptic(14);
            sendCmd(query);
        }

        @JavascriptInterface
        public void vibrateOnly(int ms) {
            haptic(ms);
        }

        @JavascriptInterface
        public void openSoftKeyboard() {
            haptic(16);
            showNativeKeyboard();
        }

        @JavascriptInterface
        public void closeSoftKeyboard() {
            hideNativeKeyboard();
        }

        @JavascriptInterface
        public void setTvIp(String ip) {
            if (ip != null && !ip.trim().isEmpty()) {
                haptic(20);
                final String clean = ip.trim();
                tvIp = clean;
                lastSeenKbSeq = -1;
                getSharedPreferences(PREFS, MODE_PRIVATE).edit().putString(KEY_TV_IP, clean).apply();
                netPool.execute(() -> {
                    boolean ok = probeHost(clean, 800);
                    updateStatusUi(clean, ok);
                });
            }
        }

        @JavascriptInterface
        public void rescan() {
            haptic(20);
            autoConnectOrScan();
        }
    }

    private static String buildAppHtml(String initialIp) {
        return "<!DOCTYPE html><html><head><meta charset='utf-8'>"
            + "<meta name='viewport' content='width=device-width,initial-scale=1,maximum-scale=1,user-scalable=no'>"
            + "<style>"
            + ":root{--bg:#05070E;--surface:rgba(20,27,45,0.62);--surface-hi:rgba(34,46,74,0.78);--border:rgba(255,255,255,0.10);--border-hi:rgba(255,255,255,0.20);--accent:#38BDF8;--emerald:#34D399;--text:#F8FAFC;--muted:#8B9BB4;}"
            + "*{box-sizing:border-box;-webkit-user-select:none;user-select:none;touch-action:manipulation;-webkit-tap-highlight-color:transparent;}"
            + "body{margin:0;background:var(--bg);color:var(--text);font-family:-apple-system,BlinkMacSystemFont,'SF Pro Display','Inter',Roboto,sans-serif;display:flex;flex-direction:column;height:100vh;overflow:hidden;position:relative;}"
            + ".aurora{position:fixed;inset:0;pointer-events:none;z-index:0;background:"
            + "radial-gradient(ellipse 90% 42% at 15% -10%,rgba(16,185,129,0.20),transparent 68%),"
            + "radial-gradient(ellipse 85% 45% at 88% 2%,rgba(56,189,248,0.18),transparent 70%),"
            + "radial-gradient(ellipse 75% 38% at 50% 108%,rgba(99,102,241,0.14),transparent 70%);}"
            + "header{position:relative;z-index:2;padding:16px 20px 10px;display:flex;justify-content:space-between;align-items:center;}"
            + ".brand{display:flex;flex-direction:column;}"
            + ".brand-sub{font-size:9.5px;letter-spacing:2.6px;text-transform:uppercase;color:var(--muted);font-weight:700;}"
            + ".brand-title{font-size:19px;font-weight:700;letter-spacing:-0.4px;color:#FFF;margin-top:2px;}"
            + ".ip-pill{padding:7px 13px;border-radius:999px;background:var(--surface);border:1px solid var(--border);backdrop-filter:blur(20px);font-size:11.5px;font-weight:600;letter-spacing:0.3px;color:#E2E8F0;display:flex;align-items:center;gap:8px;box-shadow:inset 0 1px 0 rgba(255,255,255,0.08),0 6px 18px rgba(0,0,0,0.35);}"
            + ".dot{width:7px;height:7px;border-radius:50%;background:#F59E0B;transition:all 0.25s;}"
            + ".dot.ok{background:var(--emerald);box-shadow:0 0 10px var(--emerald);}"
            + ".seg-wrap{position:relative;z-index:2;padding:4px 20px 8px;}"
            + ".seg{display:flex;background:rgba(12,17,30,0.82);border:1px solid var(--border);border-radius:16px;padding:4px;gap:4px;backdrop-filter:blur(24px);box-shadow:inset 0 1px 0 rgba(255,255,255,0.05);}"
            + ".seg-btn{flex:1;padding:11px 10px;border-radius:12px;text-align:center;font-size:13px;font-weight:600;letter-spacing:0.2px;color:var(--muted);transition:all 0.18s;display:flex;align-items:center;justify-content:center;gap:8px;}"
            + ".seg-btn svg{width:16px;height:16px;stroke:currentColor;stroke-width:2;fill:none;}"
            + ".seg-btn.active{background:linear-gradient(180deg,#FFFFFF,#E2E8F0);color:#05070E;box-shadow:0 4px 16px rgba(255,255,255,0.16);}"
            + ".view{display:none;position:relative;z-index:2;flex:1;flex-direction:column;padding:6px 20px 18px;gap:12px;overflow:hidden;}"
            + ".view.active{display:flex;}"
            /* View 1: Precision Trackpad */
            + "#trackpad{flex:1;position:relative;border-radius:28px;background:linear-gradient(165deg,rgba(24,34,56,0.56),rgba(8,12,22,0.88));border:1px solid var(--border-hi);box-shadow:inset 0 1px 1px rgba(255,255,255,0.14),0 22px 48px rgba(0,0,0,0.6);display:flex;flex-direction:column;align-items:center;justify-content:center;overflow:hidden;touch-action:none;}"
            + ".corner{position:absolute;width:14px;height:14px;border-color:rgba(255,255,255,0.14);border-style:solid;pointer-events:none;}"
            + ".c-tl{top:16px;left:16px;border-width:1.5px 0 0 1.5px;border-top-left-radius:5px;}"
            + ".c-tr{top:16px;right:16px;border-width:1.5px 1.5px 0 0;border-top-right-radius:5px;}"
            + ".c-bl{bottom:54px;left:16px;border-width:0 0 1.5px 1.5px;border-bottom-left-radius:5px;}"
            + ".c-br{bottom:54px;right:16px;border-width:0 1.5px 1.5px 0;border-bottom-right-radius:5px;}"
            + ".reticle{width:52px;height:52px;border-radius:50%;border:1px solid rgba(255,255,255,0.08);display:flex;align-items:center;justify-content:center;margin-bottom:10px;pointer-events:none;}"
            + ".reticle::after{content:'';width:5px;height:5px;border-radius:50%;background:rgba(56,189,248,0.65);box-shadow:0 0 12px var(--accent);}"
            + ".pad-title{font-size:13px;font-weight:600;color:rgba(248,250,252,0.62);letter-spacing:0.6px;text-transform:uppercase;pointer-events:none;}"
            + ".pad-hints{position:absolute;bottom:14px;left:16px;right:16px;display:flex;justify-content:space-between;font-size:10.5px;letter-spacing:0.3px;color:rgba(139,155,180,0.72);pointer-events:none;padding:8px 14px;border-radius:12px;background:rgba(5,7,14,0.52);border:1px solid rgba(255,255,255,0.05);}"
            + "#touchGlow{position:absolute;width:96px;height:96px;border-radius:50%;background:radial-gradient(circle,rgba(56,189,248,0.32) 0%,transparent 70%);transform:translate(-50%,-50%);pointer-events:none;opacity:0;transition:opacity 0.14s;}"
            + ".dock{display:flex;gap:10px;}"
            + ".pill-btn{flex:1;padding:15px 10px;border-radius:18px;background:var(--surface);border:1px solid var(--border);box-shadow:inset 0 1px 0 rgba(255,255,255,0.10),0 8px 20px rgba(0,0,0,0.32);color:var(--text);font-size:13px;font-weight:600;letter-spacing:0.2px;text-align:center;display:flex;align-items:center;justify-content:center;gap:8px;transition:transform 0.1s,background 0.14s,color 0.14s;}"
            + ".pill-btn svg{width:17px;height:17px;stroke:currentColor;stroke-width:1.9;fill:none;flex-shrink:0;}"
            + ".pill-btn:active{transform:scale(0.96);background:#FFFFFF;color:#05070E;}"
            + ".pill-btn.kb{border-color:rgba(56,189,248,0.34);background:rgba(56,189,248,0.10);}"
            /* View 2: Better TV Remote (Machined Clickpad) */
            + ".remote-body{flex:1;display:flex;flex-direction:column;justify-content:space-between;align-items:center;padding:2px 0;}"
            + ".ring-wrap{position:relative;width:258px;height:258px;border-radius:50%;background:radial-gradient(circle at 32% 24%,#22304E,#090E1A 76%);border:1.5px solid rgba(255,255,255,0.16);box-shadow:inset 0 2px 4px rgba(255,255,255,0.12),0 24px 50px rgba(0,0,0,0.7);display:flex;align-items:center;justify-content:center;margin:auto;}"
            + ".d-dir{position:absolute;width:78px;height:78px;display:flex;align-items:center;justify-content:center;color:#E2E8F0;border-radius:50%;transition:all 0.12s;}"
            + ".d-dir svg{width:22px;height:22px;stroke:currentColor;stroke-width:2.2;fill:none;}"
            + ".d-dir:active{background:rgba(255,255,255,0.18);color:#FFF;transform:scale(0.92);}"
            + ".d-up{top:6px;left:50%;transform:translateX(-50%);}"
            + ".d-up:active{transform:translateX(-50%) scale(0.92);}"
            + ".d-down{bottom:6px;left:50%;transform:translateX(-50%);}"
            + ".d-down:active{transform:translateX(-50%) scale(0.92);}"
            + ".d-left{left:6px;top:50%;transform:translateY(-50%);}"
            + ".d-left:active{transform:translateY(-50%) scale(0.92);}"
            + ".d-right{right:6px;top:50%;transform:translateY(-50%);}"
            + ".d-right:active{transform:translateY(-50%) scale(0.92);}"
            + ".ok-center{width:98px;height:98px;border-radius:50%;background:radial-gradient(circle at 35% 28%,#33476E,#0F172A);border:1.5px solid rgba(255,255,255,0.24);box-shadow:inset 0 1px 3px rgba(255,255,255,0.22),0 8px 22px rgba(0,0,0,0.55);display:flex;align-items:center;justify-content:center;font-size:14px;font-weight:700;letter-spacing:1.4px;color:#FFF;transition:all 0.12s;}"
            + ".ok-center:active{background:#FFFFFF;color:#05070E;transform:scale(0.94);box-shadow:0 0 26px rgba(255,255,255,0.5);}"
            + ".remote-grid{width:100%;display:flex;flex-direction:column;gap:10px;}"
            /* Floating Live Keyboard Drawer */
            + "#kbSheet{display:none;position:fixed;left:14px;right:14px;top:76px;z-index:60;background:rgba(12,18,32,0.96);border:1px solid rgba(56,189,248,0.42);border-radius:22px;padding:15px;box-shadow:0 24px 56px rgba(0,0,0,0.85);backdrop-filter:blur(28px);flex-direction:column;gap:10px;}"
            + "#kbSheet.open{display:flex;}"
            + ".kb-top{display:flex;justify-content:space-between;align-items:center;font-size:11.5px;font-weight:700;letter-spacing:0.6px;text-transform:uppercase;color:var(--accent);}"
            + ".kb-row{display:flex;gap:8px;}"
            + "#kbInput{flex:1;padding:13px 15px;border-radius:14px;border:1px solid rgba(255,255,255,0.18);background:#05070E;color:#FFF;font-size:15px;-webkit-user-select:text;user-select:text;outline:none;}"
            + "#ipModal{display:none;position:fixed;inset:0;background:rgba(5,7,14,0.86);backdrop-filter:blur(16px);z-index:99;align-items:center;justify-content:center;padding:20px;}"
            + ".modal-card{background:#0D1322;border:1px solid var(--border-hi);border-radius:24px;padding:22px;width:100%;max-width:340px;display:flex;flex-direction:column;gap:14px;box-shadow:0 24px 60px rgba(0,0,0,0.8);}"
            + "</style></head><body>"
            + "<div class='aurora'></div>"
            + "<header>"
            + "<div class='brand'><span class='brand-sub'>Xiaomi TV Studio</span><span class='brand-title'>MiTV Control</span></div>"
            + "<div class='ip-pill' onclick='openIpModal()'><span id='dot' class='dot'></span><span id='ipLabel'>" + initialIp + "</span></div>"
            + "</header>"
            + "<div class='seg-wrap'><div class='seg'>"
            + "<div class='seg-btn active' id='tab0' onclick='switchMode(0)'>"
            + "<svg viewBox='0 0 24 24'><rect x='3' y='4' width='18' height='16' rx='3'/><line x1='3' y1='14' x2='21' y2='14'/><line x1='12' y1='14' x2='12' y2='20'/></svg>Trackpad</div>"
            + "<div class='seg-btn' id='tab1' onclick='switchMode(1)'>"
            + "<svg viewBox='0 0 24 24'><rect x='6' y='2' width='12' height='20' rx='4'/><circle cx='12' cy='9' r='3'/><line x1='10' y1='16' x2='14' y2='16'/></svg>TV Remote</div>"
            + "</div></div>"
            /* VIEW 0: TRACKPAD */
            + "<div class='view active' id='v0'>"
            + "<div id='trackpad'>"
            + "<div class='corner c-tl'></div><div class='corner c-tr'></div><div class='corner c-bl'></div><div class='corner c-br'></div>"
            + "<div id='touchGlow'></div>"
            + "<div class='reticle'></div>"
            + "<div class='pad-title'>Multi-Touch Surface</div>"
            + "<div class='pad-hints'><span>1-Finger Move & Tap</span><span>2-Finger Fast Scroll & Back</span></div>"
            + "</div>"
            + "<div class='dock'>"
            + "<div class='pill-btn' onclick=\"api('key&val=BACK',true)\"><svg viewBox='0 0 24 24'><polyline points='15 18 9 12 15 6'/></svg>Back</div>"
            + "<div class='pill-btn kb' onclick='openKeyboardSheet()'><svg viewBox='0 0 24 24'><circle cx='11' cy='11' r='7'/><line x1='21' y1='21' x2='16.65' y2='16.65'/></svg>Search / Type</div>"
            + "<div class='pill-btn' onclick=\"api('key&val=HOME',true)\"><svg viewBox='0 0 24 24'><path d='M3 10l9-7 9 7v10a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2z'/></svg>Home</div>"
            + "</div></div>"
            /* VIEW 1: BETTER TV REMOTE */
            + "<div class='view' id='v1'>"
            + "<div class='remote-body'>"
            + "<div class='ring-wrap'>"
            + "<div class='d-dir d-up' onclick=\"api('key&val=UP',true)\"><svg viewBox='0 0 24 24'><polyline points='18 15 12 9 6 15'/></svg></div>"
            + "<div class='d-dir d-down' onclick=\"api('key&val=DOWN',true)\"><svg viewBox='0 0 24 24'><polyline points='6 9 12 15 18 9'/></svg></div>"
            + "<div class='d-dir d-left' onclick=\"api('key&val=LEFT',true)\"><svg viewBox='0 0 24 24'><polyline points='15 18 9 12 15 6'/></svg></div>"
            + "<div class='d-dir d-right' onclick=\"api('key&val=RIGHT',true)\"><svg viewBox='0 0 24 24'><polyline points='9 18 15 12 9 6'/></svg></div>"
            + "<div class='ok-center' onclick=\"api('key&val=OK',true)\">SELECT</div>"
            + "</div>"
            + "<div class='remote-grid'>"
            + "<div class='dock'>"
            + "<div class='pill-btn' onclick=\"api('key&val=BACK',true)\"><svg viewBox='0 0 24 24'><polyline points='15 18 9 12 15 6'/></svg>Back</div>"
            + "<div class='pill-btn' onclick=\"api('key&val=HOME',true)\"><svg viewBox='0 0 24 24'><path d='M3 10l9-7 9 7v10a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2z'/></svg>Home</div>"
            + "<div class='pill-btn kb' onclick='openKeyboardSheet()'><svg viewBox='0 0 24 24'><circle cx='11' cy='11' r='7'/><line x1='21' y1='21' x2='16.65' y2='16.65'/></svg>Search</div>"
            + "</div>"
            + "<div class='dock'>"
            + "<div class='pill-btn' onclick=\"api('key&val=VOL_DOWN',true)\"><svg viewBox='0 0 24 24'><polygon points='11 5 6 9 2 9 2 15 6 15 11 19 11 5'/><line x1='16' y1='12' x2='21' y2='12'/></svg>Vol −</div>"
            + "<div class='pill-btn' onclick=\"api('key&val=MUTE',true)\"><svg viewBox='0 0 24 24'><polygon points='11 5 6 9 2 9 2 15 6 15 11 19 11 5'/><line x1='22' y1='9' x2='16' y2='15'/><line x1='16' y1='9' x2='22' y2='15'/></svg>Mute</div>"
            + "<div class='pill-btn' onclick=\"api('key&val=VOL_UP',true)\"><svg viewBox='0 0 24 24'><polygon points='11 5 6 9 2 9 2 15 6 15 11 19 11 5'/><line x1='18.5' y1='9.5' x2='18.5' y2='14.5'/><line x1='16' y1='12' x2='21' y2='12'/></svg>Vol +</div>"
            + "</div>"
            + "<div class='dock'>"
            + "<div class='pill-btn' onclick=\"api('key&val=PLAY_PAUSE',true)\"><svg viewBox='0 0 24 24'><polygon points='6 4 15 12 6 20 6 4'/><line x1='19' y1='5' x2='19' y2='19'/></svg>Play / Pause</div>"
            + "<div class='pill-btn' onclick=\"api('key&val=RECENTS',true)\"><svg viewBox='0 0 24 24'><rect x='3' y='3' width='7' height='7' rx='1.5'/><rect x='14' y='3' width='7' height='7' rx='1.5'/><rect x='3' y='14' width='7' height='7' rx='1.5'/><rect x='14' y='14' width='7' height='7' rx='1.5'/></svg>Apps</div>"
            + "</div>"
            + "</div></div></div>"
            /* LIVE KEYBOARD & INSTANT SEARCH SHEET */
            + "<div id='kbSheet'>"
            + "<div class='kb-top'><span>Instant TV Search & Keyboard</span><span onclick='closeKeyboardSheet()' style='color:#8B9BB4;padding:4px 8px'>Close</span></div>"
            + "<div class='kb-row'>"
            + "<input id='kbInput' type='search' enterkeyhint='search' placeholder='Search YouTube, SmartTube, Brave or type...' autocomplete='off'>"
            + "<div class='pill-btn' style='flex:0 0 92px;padding:12px;background:#38BDF8;color:#05070E;border:none' onclick='submitKb()'>Search</div>"
            + "</div></div>"
            /* IP MODAL */
            + "<div id='ipModal'><div class='modal-card'>"
            + "<div style='font-size:16px;font-weight:700;color:#FFF'>TV Network Connection</div>"
            + "<div style='font-size:12px;color:#8B9BB4'>Xiaomi TV IP Address (Port 8888):</div>"
            + "<input id='ipInput' type='text' style='padding:13px;border-radius:12px;border:1px solid rgba(255,255,255,0.18);background:#05070E;color:#FFF;font-size:15px' value='" + initialIp + "'>"
            + "<div class='dock'>"
            + "<div class='pill-btn' onclick='rescanTv()'>Auto-Scan</div>"
            + "<div class='pill-btn' style='background:#FFF;color:#05070E' onclick='saveIpModal()'>Connect</div>"
            + "</div>"
            + "<div class='pill-btn' onclick='closeIpModal()'>Cancel</div>"
            + "</div></div>"
            + "<script>"
            + "function api(q,vib){if(window.AndroidBridge)AndroidBridge.api(q,!!vib);}"
            + "function vib(ms){if(window.AndroidBridge)AndroidBridge.vibrateOnly(ms||12);}"
            + "window.onTvStatus=function(ip,ok){document.getElementById('ipLabel').textContent=ip;document.getElementById('ipInput').value=ip;document.getElementById('dot').className=ok?'dot ok':'dot';};"
            + "function switchMode(m){"
            + "  document.getElementById('tab0').classList.toggle('active',m===0);"
            + "  document.getElementById('tab1').classList.toggle('active',m===1);"
            + "  document.getElementById('v0').classList.toggle('active',m===0);"
            + "  document.getElementById('v1').classList.toggle('active',m===1);"
            + "  if(m===1){api('mode&val=remote',true);}else{api('mode&val=trackpad',true);}"
            + "}"
            + "const kbSheet=document.getElementById('kbSheet');"
            + "const kbInput=document.getElementById('kbInput');"
            + "window.openKeyboardSheet=function(){"
            + "  kbSheet.classList.add('open');"
            + "  kbInput.value='';"
            + "  kbInput.focus();"
            + "  if(window.AndroidBridge)AndroidBridge.openSoftKeyboard();"
            + "};"
            + "window.closeKeyboardSheet=function(){"
            + "  kbSheet.classList.remove('open');"
            + "  kbInput.blur();"
            + "  if(window.AndroidBridge)AndroidBridge.closeSoftKeyboard();"
            + "};"
            + "kbInput.addEventListener('input',()=>{"
            + "  api('text&val='+encodeURIComponent(kbInput.value),false);"
            + "});"
            + "kbInput.addEventListener('keydown',e=>{"
            + "  if(e.key==='Enter'||e.keyCode===13){e.preventDefault();submitKb();}"
            + "});"
            + "function submitKb(){"
            + "  const v=kbInput.value.trim();"
            + "  if(v)api('submit&val='+encodeURIComponent(v),true);"
            + "  closeKeyboardSheet();"
            + "}"
            + "function openIpModal(){document.getElementById('ipModal').style.display='flex';}"
            + "function closeIpModal(){document.getElementById('ipModal').style.display='none';}"
            + "function saveIpModal(){const v=document.getElementById('ipInput').value;if(v&&window.AndroidBridge)AndroidBridge.setTvIp(v);closeIpModal();}"
            + "function rescanTv(){if(window.AndroidBridge)AndroidBridge.rescan();closeIpModal();}"
            /* Multi-Touch Laptop Precision Trackpad Engine (Conflict-Free Single-Axis + High-Sensi Scroll) */
            + "const pad=document.getElementById('trackpad');"
            + "const glow=document.getElementById('touchGlow');"
            + "let touches=0,maxTouches=0,startX=0,startY=0,lastX=0,lastY=0,moved=false,tStart=0,lastSend=0,lastMoveEnd=0,longTimer=null;"
            + "pad.addEventListener('touchstart',e=>{"
            + "  e.preventDefault();"
            + "  touches=e.touches.length;"
            + "  if(touches>maxTouches)maxTouches=touches;"
            + "  const t=e.touches[0];"
            + "  const r=pad.getBoundingClientRect();"
            + "  glow.style.left=(t.clientX-r.left)+'px';"
            + "  glow.style.top=(t.clientY-r.top)+'px';"
            + "  glow.style.opacity='1';"
            + "  if(touches===1&&maxTouches===1){"
            + "    startX=lastX=t.clientX;startY=lastY=t.clientY;moved=false;tStart=Date.now();"
            + "    clearTimeout(longTimer);"
            + "    longTimer=setTimeout(()=>{if(!moved&&maxTouches===1&&(Date.now()-lastMoveEnd>200)){moved=true;api('longclick',true);}},550);"
            + "  }else if(touches>=2){"
            + "    clearTimeout(longTimer);"
            + "    const cx=(e.touches[0].clientX+e.touches[1].clientX)/2;"
            + "    const cy=(e.touches[0].clientY+e.touches[1].clientY)/2;"
            + "    startX=lastX=cx;startY=lastY=cy;"
            + "  }"
            + "},{passive:false});"
            + "pad.addEventListener('touchmove',e=>{"
            + "  e.preventDefault();"
            + "  const now=Date.now();"
            + "  const t=e.touches[0];"
            + "  const r=pad.getBoundingClientRect();"
            + "  glow.style.left=(t.clientX-r.left)+'px';"
            + "  glow.style.top=(t.clientY-r.top)+'px';"
            + "  if(e.touches.length===1&&maxTouches===1){"
            + "    const rawDx=t.clientX-lastX,rawDy=t.clientY-lastY;"
            + "    const totalDist=Math.hypot(t.clientX-startX,t.clientY-startY);"
            + "    const dist=Math.hypot(rawDx,rawDy);"
            + "    if(totalDist>5.0)moved=true;"
            + "    if(dist>2.2){"
            + "      moved=true;clearTimeout(longTimer);"
            + "      if(now-lastSend>=22){"
            + "        const accel=Math.min(3.2,1.5+dist*0.05);"
            + "        const dx=rawDx*accel,dy=rawDy*accel;"
            + "        lastX=t.clientX;lastY=t.clientY;lastSend=now;lastMoveEnd=now;"
            + "        api('move&dx='+dx.toFixed(1)+'&dy='+dy.toFixed(1),false);"
            + "      }"
            + "    }"
            + "  }else if(e.touches.length>=2){"
            + "    clearTimeout(longTimer);"
            + "    const cx=(e.touches[0].clientX+e.touches[1].clientX)/2;"
            + "    const cy=(e.touches[0].clientY+e.touches[1].clientY)/2;"
            + "    const rawDx=cx-lastX,rawDy=cy-lastY;"
            + "    const totalDist=Math.hypot(cx-startX,cy-startY);"
            + "    const dist=Math.hypot(rawDx,rawDy);"
            + "    if(totalDist>4.5)moved=true;"
            + "    if(dist>2.2){"
            + "      moved=true;"
            + "      if(now-lastSend>=28){"
            + "        const boost=Math.min(8.5,4.8+dist*0.12);"
            + "        let dx=0,dy=0;"
            + "        if(Math.abs(rawDy)>=Math.abs(rawDx)){dy=rawDy*boost;}else{dx=rawDx*boost;}"
            + "        lastX=cx;lastY=cy;lastSend=now;lastMoveEnd=now;"
            + "        api('scroll&dx='+dx.toFixed(0)+'&dy='+dy.toFixed(0),false);"
            + "      }"
            + "    }"
            + "  }"
            + "},{passive:false});"
            + "pad.addEventListener('touchend',e=>{"
            + "  e.preventDefault();"
            + "  clearTimeout(longTimer);"
            + "  if(e.touches.length===0){"
            + "    glow.style.opacity='0';"
            + "    const now=Date.now();"
            + "    const dur=now-tStart;"
            + "    if(!moved&&dur<250&&(now-lastMoveEnd>150)){"
            + "      if(maxTouches===1)api('click',true);"
            + "      else if(maxTouches===2)api('key&val=BACK',true);"
            + "      else if(maxTouches>=3)api('key&val=HOME',true);"
            + "    }"
            + "    maxTouches=0;touches=0;"
            + "  }"
            + "},{passive:false});"
            + "</script></body></html>";
    }
}
