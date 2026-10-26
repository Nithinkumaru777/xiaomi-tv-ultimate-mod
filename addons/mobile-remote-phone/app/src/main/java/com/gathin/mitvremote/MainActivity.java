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
    private final ExecutorService netPool = Executors.newFixedThreadPool(6);
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

        statePoller.scheduleWithFixedDelay(this::pollTvState, 800, 450, TimeUnit.MILLISECONDS);
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
                handleServerJson(targetIp, body);
            }
        } catch (Throwable ignored) {
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    private void handleServerJson(String ip, String body) {
        updateStatusUi(ip, true);
        if (body == null) return;
        int idx = body.indexOf("\"kbSeq\":");
        if (idx >= 0) {
            int start = idx + 8;
            int end = start;
            while (end < body.length() && Character.isDigit(body.charAt(end))) end++;
            try {
                int seq = Integer.parseInt(body.substring(start, end));
                if (lastSeenKbSeq == -1) {
                    lastSeenKbSeq = seq;
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
                    handleServerJson(targetIp, body);
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
            + ":root{--bg:#05070E;--glass:rgba(255,255,255,0.06);--glass-hi:rgba(255,255,255,0.13);--border:rgba(255,255,255,0.11);--accent:#38BDF8;--emerald:#34D399;--text:#F8FAFC;--muted:#94A3B8;}"
            + "*{box-sizing:border-box;-webkit-user-select:none;user-select:none;touch-action:manipulation;-webkit-tap-highlight-color:transparent;}"
            + "body{margin:0;background:var(--bg);color:var(--text);font-family:-apple-system,BlinkMacSystemFont,'SF Pro Display','Inter',Roboto,sans-serif;display:flex;flex-direction:column;height:100vh;overflow:hidden;position:relative;}"
            + ".aurora{position:fixed;inset:0;pointer-events:none;z-index:0;background:"
            + "radial-gradient(ellipse 85% 42% at 18% -8%,rgba(16,185,129,0.22),transparent 70%),"
            + "radial-gradient(ellipse 80% 45% at 85% 4%,rgba(56,189,248,0.20),transparent 72%),"
            + "radial-gradient(ellipse 75% 40% at 50% 108%,rgba(99,102,241,0.16),transparent 70%);}"
            + "header{position:relative;z-index:2;padding:14px 18px 10px;display:flex;justify-content:space-between;align-items:center;}"
            + ".brand{display:flex;flex-direction:column;}"
            + ".brand-sub{font-size:10px;letter-spacing:2.2px;text-transform:uppercase;color:var(--muted);font-weight:600;}"
            + ".brand-title{font-size:19px;font-weight:700;letter-spacing:-0.3px;color:#FFF;display:flex;align-items:center;gap:7px;margin-top:1px;}"
            + ".ip-pill{padding:7px 13px;border-radius:999px;background:var(--glass);border:1px solid var(--border);backdrop-filter:blur(16px);font-size:12px;font-weight:600;color:#E2E8F0;display:flex;align-items:center;gap:7px;box-shadow:0 4px 16px rgba(0,0,0,0.3);}"
            + ".dot{width:7px;height:7px;border-radius:50%;background:#F59E0B;transition:all 0.25s;}"
            + ".dot.ok{background:var(--emerald);box-shadow:0 0 10px var(--emerald);}"
            + ".seg-wrap{position:relative;z-index:2;padding:4px 18px 8px;}"
            + ".seg{display:flex;background:rgba(15,23,42,0.75);border:1px solid var(--border);border-radius:16px;padding:4px;gap:4px;backdrop-filter:blur(20px);box-shadow:inset 0 1px 0 rgba(255,255,255,0.06);}"
            + ".seg-btn{flex:1;padding:11px 8px;border-radius:12px;text-align:center;font-size:13.5px;font-weight:600;color:var(--muted);transition:all 0.2s;display:flex;align-items:center;justify-content:center;gap:7px;}"
            + ".seg-btn.active{background:rgba(255,255,255,0.94);color:#05070E;box-shadow:0 4px 18px rgba(255,255,255,0.18);}"
            + ".view{display:none;position:relative;z-index:2;flex:1;flex-direction:column;padding:6px 18px 16px;gap:12px;overflow:hidden;}"
            + ".view.active{display:flex;}"
            /* View 1: Precision Trackpad */
            + "#trackpad{flex:1;position:relative;border-radius:26px;background:linear-gradient(165deg,rgba(30,41,59,0.52),rgba(9,13,24,0.82));border:1px solid rgba(255,255,255,0.14);box-shadow:inset 0 1px 1px rgba(255,255,255,0.15),0 18px 40px rgba(0,0,0,0.55);display:flex;flex-direction:column;align-items:center;justify-content:center;overflow:hidden;touch-action:none;}"
            + ".reticle{width:54px;height:54px;border-radius:50%;border:1px solid rgba(255,255,255,0.08);display:flex;align-items:center;justify-content:center;margin-bottom:10px;pointer-events:none;}"
            + ".reticle::after{content:'';width:6px;height:6px;border-radius:50%;background:rgba(56,189,248,0.55);box-shadow:0 0 12px var(--accent);}"
            + ".pad-title{font-size:14px;font-weight:600;color:rgba(248,250,252,0.72);letter-spacing:0.2px;pointer-events:none;}"
            + ".pad-hints{position:absolute;bottom:14px;left:16px;right:16px;display:flex;justify-content:space-between;font-size:11px;color:rgba(148,163,184,0.68);pointer-events:none;padding:8px 12px;border-radius:12px;background:rgba(5,7,14,0.45);border:1px solid rgba(255,255,255,0.05);}"
            + "#touchGlow{position:absolute;width:90px;height:90px;border-radius:50%;background:radial-gradient(circle,rgba(56,189,248,0.34) 0%,transparent 70%);transform:translate(-50%,-50%);pointer-events:none;opacity:0;transition:opacity 0.15s;}"
            + ".dock{display:flex;gap:10px;}"
            + ".pill-btn{flex:1;padding:15px 10px;border-radius:18px;background:var(--glass);border:1px solid var(--border);box-shadow:inset 0 1px 0 rgba(255,255,255,0.1);color:var(--text);font-size:13.5px;font-weight:600;text-align:center;display:flex;align-items:center;justify-content:center;gap:7px;transition:transform 0.1s,background 0.15s;}"
            + ".pill-btn:active{transform:scale(0.96);background:rgba(255,255,255,0.92);color:#05070E;}"
            + ".pill-btn.kb{border-color:rgba(56,189,248,0.35);background:rgba(56,189,248,0.11);}"
            /* View 2: Better TV Remote (Apple TV Siri Remote Style) */
            + ".remote-body{flex:1;display:flex;flex-direction:column;justify-content:space-between;align-items:center;padding:4px 0;}"
            + ".ring-wrap{position:relative;width:256px;height:256px;border-radius:50%;background:radial-gradient(circle at 30% 25%,#1E293B,#0B101E 75%);border:1.5px solid rgba(255,255,255,0.16);box-shadow:inset 0 2px 4px rgba(255,255,255,0.12),0 20px 45px rgba(0,0,0,0.65);display:flex;align-items:center;justify-content:center;margin:auto;}"
            + ".d-dir{position:absolute;width:78px;height:78px;display:flex;align-items:center;justify-content:center;font-size:18px;color:#E2E8F0;border-radius:50%;transition:all 0.12s;}"
            + ".d-dir:active{background:rgba(255,255,255,0.18);color:#FFF;transform:scale(0.93);}"
            + ".d-up{top:6px;left:50%;transform:translateX(-50%);}"
            + ".d-up:active{transform:translateX(-50%) scale(0.93);}"
            + ".d-down{bottom:6px;left:50%;transform:translateX(-50%);}"
            + ".d-down:active{transform:translateX(-50%) scale(0.93);}"
            + ".d-left{left:6px;top:50%;transform:translateY(-50%);}"
            + ".d-left:active{transform:translateY(-50%) scale(0.93);}"
            + ".d-right{right:6px;top:50%;transform:translateY(-50%);}"
            + ".d-right:active{transform:translateY(-50%) scale(0.93);}"
            + ".ok-center{width:98px;height:98px;border-radius:50%;background:radial-gradient(circle at 35% 30%,#334155,#0F172A);border:1.5px solid rgba(255,255,255,0.22);box-shadow:inset 0 1px 2px rgba(255,255,255,0.2),0 6px 18px rgba(0,0,0,0.5);display:flex;align-items:center;justify-content:center;font-size:16px;font-weight:700;letter-spacing:0.8px;color:#FFF;transition:all 0.12s;}"
            + ".ok-center:active{background:#FFF;color:#05070E;transform:scale(0.94);box-shadow:0 0 24px rgba(255,255,255,0.5);}"
            + ".remote-grid{width:100%;display:flex;flex-direction:column;gap:10px;}"
            /* Floating Live Keyboard Drawer */
            + "#kbSheet{display:none;position:fixed;left:12px;right:12px;top:74px;z-index:60;background:rgba(15,23,42,0.96);border:1px solid rgba(56,189,248,0.4);border-radius:22px;padding:14px;box-shadow:0 20px 50px rgba(0,0,0,0.8);backdrop-filter:blur(24px);flex-direction:column;gap:10px;}"
            + "#kbSheet.open{display:flex;}"
            + ".kb-top{display:flex;justify-content:space-between;align-items:center;font-size:12px;font-weight:600;color:var(--accent);}"
            + ".kb-row{display:flex;gap:8px;}"
            + "#kbInput{flex:1;padding:13px 14px;border-radius:14px;border:1px solid rgba(255,255,255,0.18);background:#05070E;color:#FFF;font-size:15px;-webkit-user-select:text;user-select:text;outline:none;}"
            + "#ipModal{display:none;position:fixed;inset:0;background:rgba(5,7,14,0.85);backdrop-filter:blur(12px);z-index:99;align-items:center;justify-content:center;padding:20px;}"
            + ".modal-card{background:#0F172A;border:1px solid var(--border);border-radius:22px;padding:20px;width:100%;max-width:340px;display:flex;flex-direction:column;gap:12px;}"
            + "</style></head><body>"
            + "<div class='aurora'></div>"
            + "<header>"
            + "<div class='brand'><span class='brand-sub'>Xiaomi TV Studio</span><span class='brand-title'>MiTV Control</span></div>"
            + "<div class='ip-pill' onclick='openIpModal()'><span id='dot' class='dot'></span><span id='ipLabel'>" + initialIp + "</span></div>"
            + "</header>"
            + "<div class='seg-wrap'><div class='seg'>"
            + "<div class='seg-btn active' id='tab0' onclick='switchMode(0)'>✨ Trackpad</div>"
            + "<div class='seg-btn' id='tab1' onclick='switchMode(1)'>🎛️ TV Remote</div>"
            + "</div></div>"
            /* VIEW 0: TRACKPAD */
            + "<div class='view active' id='v0'>"
            + "<div id='trackpad'>"
            + "<div id='touchGlow'></div>"
            + "<div class='reticle'></div>"
            + "<div class='pad-title'>Multi-Touch Precision Trackpad</div>"
            + "<div class='pad-hints'><span>1-Finger: Move & Tap</span><span>2-Finger: Scroll & Back</span></div>"
            + "</div>"
            + "<div class='dock'>"
            + "<div class='pill-btn' onclick=\"api('key&val=BACK',true)\">⬅ Back</div>"
            + "<div class='pill-btn kb' onclick='openKeyboardSheet()'>⌨️ Keyboard</div>"
            + "<div class='pill-btn' onclick=\"api('key&val=HOME',true)\">🏠 Home</div>"
            + "</div></div>"
            /* VIEW 1: BETTER TV REMOTE */
            + "<div class='view' id='v1'>"
            + "<div class='remote-body'>"
            + "<div class='ring-wrap'>"
            + "<div class='d-dir d-up' onclick=\"api('key&val=UP',true)\">▲</div>"
            + "<div class='d-dir d-down' onclick=\"api('key&val=DOWN',true)\">▼</div>"
            + "<div class='d-dir d-left' onclick=\"api('key&val=LEFT',true)\">◄</div>"
            + "<div class='d-dir d-right' onclick=\"api('key&val=RIGHT',true)\">►</div>"
            + "<div class='ok-center' onclick=\"api('key&val=OK',true)\">OK</div>"
            + "</div>"
            + "<div class='remote-grid'>"
            + "<div class='dock'>"
            + "<div class='pill-btn' onclick=\"api('key&val=BACK',true)\">⬅ Back</div>"
            + "<div class='pill-btn' onclick=\"api('key&val=HOME',true)\">🏠 Home</div>"
            + "<div class='pill-btn kb' onclick='openKeyboardSheet()'>⌨️ Search</div>"
            + "</div>"
            + "<div class='dock'>"
            + "<div class='pill-btn' onclick=\"api('key&val=VOL_DOWN',true)\">🔉 Vol −</div>"
            + "<div class='pill-btn' onclick=\"api('key&val=MUTE',true)\">🔇 Mute</div>"
            + "<div class='pill-btn' onclick=\"api('key&val=VOL_UP',true)\">🔊 Vol +</div>"
            + "</div>"
            + "<div class='dock'>"
            + "<div class='pill-btn' onclick=\"api('key&val=PLAY_PAUSE',true)\">⏯ Play / Pause</div>"
            + "<div class='pill-btn' onclick=\"api('key&val=RECENTS',true)\">🗂 Recent Apps</div>"
            + "</div>"
            + "</div></div></div>"
            /* LIVE KEYBOARD OVERLAY SHEET */
            + "<div id='kbSheet'>"
            + "<div class='kb-top'><span>⌨️ Type to TV Search / Input</span><span onclick='closeKeyboardSheet()' style='color:#94A3B8;padding:4px 8px'>✕ Close</span></div>"
            + "<div class='kb-row'>"
            + "<input id='kbInput' type='text' placeholder='Type on your phone keyboard...' autocomplete='off'>"
            + "<div class='pill-btn' style='flex:0 0 88px;padding:12px;background:#38BDF8;color:#05070E;border:none' onclick='submitKb()'>Search ↵</div>"
            + "</div></div>"
            /* IP MODAL */
            + "<div id='ipModal'><div class='modal-card'>"
            + "<div style='font-size:16px;font-weight:700;color:#FFF'>Xiaomi TV Connection</div>"
            + "<div style='font-size:12.5px;color:#94A3B8'>TV IP Address (Port 8888):</div>"
            + "<input id='ipInput' type='text' style='padding:13px;border-radius:12px;border:1px solid rgba(255,255,255,0.18);background:#05070E;color:#FFF;font-size:15px' value='" + initialIp + "'>"
            + "<div class='dock'>"
            + "<div class='pill-btn' onclick='rescanTv()'>🔍 Auto-Find</div>"
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
            + "  if(e.key==='Enter'){e.preventDefault();submitKb();}"
            + "});"
            + "function submitKb(){"
            + "  const v=kbInput.value;"
            + "  api('submit&val='+encodeURIComponent(v),true);"
            + "  closeKeyboardSheet();"
            + "}"
            + "function openIpModal(){document.getElementById('ipModal').style.display='flex';}"
            + "function closeIpModal(){document.getElementById('ipModal').style.display='none';}"
            + "function saveIpModal(){const v=document.getElementById('ipInput').value;if(v&&window.AndroidBridge)AndroidBridge.setTvIp(v);closeIpModal();}"
            + "function rescanTv(){if(window.AndroidBridge)AndroidBridge.rescan();closeIpModal();}"
            /* Multi-Touch Laptop Precision Trackpad Engine */
            + "const pad=document.getElementById('trackpad');"
            + "const glow=document.getElementById('touchGlow');"
            + "let touches=0,maxTouches=0,startX=0,startY=0,lastX=0,lastY=0,moved=false,tStart=0,lastSend=0,longTimer=null;"
            + "pad.addEventListener('touchstart',e=>{"
            + "  e.preventDefault();"
            + "  touches=e.touches.length;"
            + "  if(touches>maxTouches)maxTouches=touches;"
            + "  const t=e.touches[0];"
            + "  const r=pad.getBoundingClientRect();"
            + "  glow.style.left=(t.clientX-r.left)+'px';"
            + "  glow.style.top=(t.clientY-r.top)+'px';"
            + "  glow.style.opacity='1';"
            + "  if(touches===1){"
            + "    startX=lastX=t.clientX;startY=lastY=t.clientY;moved=false;tStart=Date.now();"
            + "    clearTimeout(longTimer);"
            + "    longTimer=setTimeout(()=>{if(!moved&&maxTouches===1){moved=true;api('longclick',true);}},520);"
            + "  }else if(touches===2){"
            + "    clearTimeout(longTimer);"
            + "    lastX=(e.touches[0].clientX+e.touches[1].clientX)/2;"
            + "    lastY=(e.touches[0].clientY+e.touches[1].clientY)/2;"
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
            + "    const dist=Math.hypot(rawDx,rawDy);"
            + "    if(dist>2.2){"
            + "      moved=true;clearTimeout(longTimer);"
            + "      if(now-lastSend>=22){"
            + "        const accel=Math.min(3.2,1.45+dist*0.045);"
            + "        const dx=rawDx*accel,dy=rawDy*accel;"
            + "        lastX=t.clientX;lastY=t.clientY;lastSend=now;"
            + "        api('move&dx='+dx.toFixed(1)+'&dy='+dy.toFixed(1),false);"
            + "      }"
            + "    }"
            + "  }else if(e.touches.length===2){"
            + "    clearTimeout(longTimer);"
            + "    const cx=(e.touches[0].clientX+e.touches[1].clientX)/2;"
            + "    const cy=(e.touches[0].clientY+e.touches[1].clientY)/2;"
            + "    const dx=(cx-lastX)*2.6,dy=(cy-lastY)*2.6;"
            + "    if(Math.hypot(dx,dy)>18){"
            + "      moved=true;"
            + "      if(now-lastSend>=95){"
            + "        lastX=cx;lastY=cy;lastSend=now;"
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
            + "    const dur=Date.now()-tStart;"
            + "    if(!moved&&dur<300){"
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
