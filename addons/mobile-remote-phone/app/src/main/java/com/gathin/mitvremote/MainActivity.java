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
import android.webkit.JavascriptInterface;
import android.webkit.WebSettings;
import android.webkit.WebView;

import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URL;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

public final class MainActivity extends Activity {

    private static final String PREFS = "mitv_remote_prefs";
    private static final String KEY_TV_IP = "tv_ip";
    private static final String DEFAULT_IP = "192.168.0.233";
    private static final int PORT = 8888;

    private WebView webView;
    private volatile String tvIp = DEFAULT_IP;
    private final ExecutorService netPool = Executors.newFixedThreadPool(6);
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private Vibrator vibrator;

    @SuppressLint({"SetJavaScriptEnabled", "AddJavascriptInterface"})
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        Window w = getWindow();
        if (w != null) {
            w.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            w.setStatusBarColor(Color.parseColor("#0F172A"));
            w.setNavigationBarColor(Color.parseColor("#070A14"));
        }

        SharedPreferences prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        tvIp = prefs.getString(KEY_TV_IP, DEFAULT_IP);
        vibrator = (Vibrator) getSystemService(Context.VIBRATOR_SERVICE);

        webView = new WebView(this);
        webView.setBackgroundColor(Color.parseColor("#070A14"));
        WebSettings ws = webView.getSettings();
        ws.setJavaScriptEnabled(true);
        ws.setDomStorageEnabled(true);
        ws.setBuiltInZoomControls(false);
        ws.setDisplayZoomControls(false);

        webView.addJavascriptInterface(new Bridge(), "AndroidBridge");
        setContentView(webView);

        webView.loadDataWithBaseURL("https://mitv.local/", buildAppHtml(tvIp), "text/html", "UTF-8", null);
        autoConnectOrScan();
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

    private void sendCmd(final String query) {
        final String targetIp = tvIp;
        netPool.execute(() -> {
            HttpURLConnection conn = null;
            try {
                URL u = new URL("http://" + targetIp + ":" + PORT + "/api?cmd=" + query);
                conn = (HttpURLConnection) u.openConnection();
                conn.setConnectTimeout(900);
                conn.setReadTimeout(900);
                conn.setRequestMethod("GET");
                int code = conn.getResponseCode();
                if (code == 200) {
                    updateStatusUi(targetIp, true);
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
            if (probeHost(tvIp, 650)) {
                updateStatusUi(tvIp, true);
                return;
            }
            if (probeHost("192.168.0.233", 650)) {
                saveIp("192.168.0.233");
                return;
            }
            if (probeHost("192.168.1.9", 650)) {
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
        public void setTvIp(String ip) {
            if (ip != null && !ip.trim().isEmpty()) {
                haptic(20);
                final String clean = ip.trim();
                tvIp = clean;
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
            + "*{box-sizing:border-box;-webkit-user-select:none;user-select:none;touch-action:manipulation;-webkit-tap-highlight-color:transparent;}"
            + "body{margin:0;background:#070A14;color:#F8FAFC;font-family:-apple-system,BlinkMacSystemFont,'Segoe UI',Roboto,sans-serif;display:flex;flex-direction:column;height:100vh;overflow:hidden;}"
            + "header{padding:12px 14px;background:#0F172A;border-bottom:1px solid #1E293B;display:flex;justify-content:space-between;align-items:center;gap:8px;}"
            + "header h1{margin:0;font-size:16px;font-weight:700;color:#10B981;letter-spacing:0.2px;}"
            + ".ip-pill{padding:6px 11px;border-radius:999px;background:#1E293B;border:1px solid #334155;font-size:12px;font-weight:600;color:#E2E8F0;display:flex;align-items:center;gap:6px;}"
            + ".dot{width:8px;height:8px;border-radius:50%;background:#F59E0B;display:inline-block;}"
            + ".dot.ok{background:#10B981;box-shadow:0 0 8px #10B981;}"
            + ".tabs{display:flex;background:#0F172A;padding:6px;gap:6px;border-bottom:1px solid #1E293B;}"
            + ".tab{flex:1;padding:10px 4px;text-align:center;border-radius:10px;font-size:12.5px;font-weight:600;color:#94A3B8;background:#1E293B;}"
            + ".tab.active{background:#10B981;color:#052E16;}"
            + ".panel{display:none;flex:1;flex-direction:column;padding:12px;gap:10px;overflow-y:auto;}"
            + ".panel.active{display:flex;}"
            + ".pad-wrap{flex:1;display:flex;gap:10px;min-height:230px;}"
            + "#touchpad{flex:1;background:radial-gradient(circle at center,#1E293B,#0F172A);border:2px solid #334155;border-radius:18px;display:flex;flex-direction:column;align-items:center;justify-content:center;color:#64748B;font-size:14px;text-align:center;touch-action:none;}"
            + "#scrollbar{width:54px;background:#1E293B;border:2px solid #334155;border-radius:18px;display:flex;align-items:center;justify-content:center;color:#94A3B8;font-size:12px;writing-mode:vertical-rl;touch-action:none;}"
            + ".row{display:flex;gap:8px;}"
            + ".btn{flex:1;padding:14px 10px;background:#1E293B;border:1px solid #334155;border-radius:14px;color:#F8FAFC;font-size:14px;font-weight:600;text-align:center;}"
            + ".btn:active{background:#10B981;color:#052E16;transform:scale(0.97);}"
            + ".btn.accent{background:#10B981;color:#052E16;border-color:#10B981;}"
            + "input[type=text]{flex:1;padding:13px 14px;border-radius:12px;border:1px solid #334155;background:#0F172A;color:#FFF;font-size:15px;-webkit-user-select:text;user-select:text;}"
            + ".dpad{display:grid;grid-template-columns:1fr 1fr 1fr;gap:10px;max-width:270px;margin:auto;width:100%;}"
            + ".dpad .btn{padding:19px 0;font-size:18px;}"
            + "#ipModal{display:none;position:fixed;inset:0;background:rgba(7,10,20,0.85);z-index:99;align-items:center;justify-content:center;padding:20px;}"
            + ".modal-card{background:#0F172A;border:1px solid #334155;border-radius:18px;padding:18px;width:100%;max-width:340px;display:flex;flex-direction:column;gap:12px;}"
            + "</style></head><body>"
            + "<header><h1>📺 MiTV Control</h1>"
            + "<div class='ip-pill' onclick='openIpModal()'><span id='dot' class='dot'></span><span id='ipLabel'>" + initialIp + "</span></div>"
            + "</header>"
            + "<div class='tabs'>"
            + "<div class='tab active' onclick='showTab(0)'>🖱️ Mouse</div>"
            + "<div class='tab' onclick='showTab(1)'>⌨️ Brave & Keys</div>"
            + "<div class='tab' onclick='showTab(2)'>📺 Remote</div>"
            + "<div class='tab' onclick='showTab(3)'>🎮 Gamepad</div>"
            + "</div>"
            + "<div class='panel active' id='p0'>"
            + "<div class='pad-wrap'>"
            + "<div id='touchpad'><div style='font-size:28px;margin-bottom:6px'>🖱️</div>Slide to move TV cursor<br><span style='font-size:12px;color:#475569'>Tap anywhere to Left-Click</span></div>"
            + "<div id='scrollbar'>⇅ SCROLL</div>"
            + "</div>"
            + "<div class='row'>"
            + "<div class='btn' onclick=\"api('key&val=BACK',true)\">⬅ Back</div>"
            + "<div class='btn accent' onclick=\"api('click',true)\">🖱️ Left Click</div>"
            + "<div class='btn' onclick=\"api('key&val=HOME',true)\">🏠 Home</div>"
            + "</div></div>"
            + "<div class='panel' id='p1'>"
            + "<div class='row'><input id='urlIn' type='text' placeholder='Enter website or Brave search...'><div class='btn accent' style='flex:0 0 105px' onclick='sendUrl()'>Brave Go</div></div>"
            + "<div class='row'><input id='txtIn' type='text' placeholder='Type text into focused TV box...'><div class='btn' style='flex:0 0 105px' onclick='sendTxt()'>Send Text</div></div>"
            + "<div style='font-size:12.5px;color:#94A3B8;margin-top:4px'>1-Tap Launch TV & Android Apps:</div>"
            + "<div class='row'><div class='btn' onclick=\"api('app&pkg=com.brave.browser',true)\">🦁 Brave Browser</div><div class='btn' onclick=\"api('app&pkg=org.smarttube.stable',true)\">▶️ SmartTube</div></div>"
            + "<div class='row'><div class='btn' onclick=\"api('app&pkg=com.google.android.youtube.tv',true)\">📺 YouTube</div><div class='btn' onclick=\"api('app&pkg=com.limelight',true)\">💻 Moonlight PC</div></div>"
            + "<div class='row'><div class='btn' onclick=\"api('app&pkg=com.netflix.ninja',true)\">🎬 Netflix</div><div class='btn' onclick=\"api('app&pkg=com.amazon.amazonvideo.livingroom',true)\">📦 Prime Video</div></div>"
            + "<div class='row'><div class='btn' onclick=\"api('app&pkg=in.startv.hotstar',true)\">⭐ JioHotstar</div><div class='btn' onclick=\"api('key&val=HOME',true)\">🏠 TV Home</div></div>"
            + "</div>"
            + "<div class='panel' id='p2'>"
            + "<div class='dpad'>"
            + "<div></div><div class='btn' onclick=\"api('key&val=UP',true)\">▲</div><div></div>"
            + "<div class='btn' onclick=\"api('key&val=LEFT',true)\">◄</div><div class='btn accent' onclick=\"api('key&val=OK',true)\">OK</div><div class='btn' onclick=\"api('key&val=RIGHT',true)\">►</div>"
            + "<div></div><div class='btn' onclick=\"api('key&val=DOWN',true)\">▼</div><div></div>"
            + "</div>"
            + "<div class='row'><div class='btn' onclick=\"api('key&val=BACK',true)\">⬅ Back</div><div class='btn' onclick=\"api('key&val=HOME',true)\">🏠 Home</div><div class='btn' onclick=\"api('key&val=RECENTS',true)\">🗂 Apps</div></div>"
            + "<div class='row'><div class='btn' onclick=\"api('key&val=VOL_DOWN',true)\">🔉 Vol -</div><div class='btn' onclick=\"api('key&val=MUTE',true)\">🔇 Mute</div><div class='btn' onclick=\"api('key&val=VOL_UP',true)\">🔊 Vol +</div></div>"
            + "</div>"
            + "<div class='panel' id='p3'>"
            + "<div style='display:flex;flex:1;align-items:center;justify-content:space-between;gap:12px'>"
            + "<div class='dpad' style='max-width:168px'>"
            + "<div></div><div class='btn' onclick=\"api('key&val=UP',true)\">▲</div><div></div>"
            + "<div class='btn' onclick=\"api('key&val=LEFT',true)\">◄</div><div></div><div class='btn' onclick=\"api('key&val=RIGHT',true)\">►</div>"
            + "<div></div><div class='btn' onclick=\"api('key&val=DOWN',true)\">▼</div><div></div>"
            + "</div>"
            + "<div style='display:grid;grid-template-columns:1fr 1fr;gap:12px;width:150px'>"
            + "<div class='btn accent' onclick=\"api('key&val=OK',true)\">A (OK)</div>"
            + "<div class='btn' onclick=\"api('key&val=BACK',true)\">B (Back)</div>"
            + "<div class='btn' onclick=\"api('click',true)\">X (Tap)</div>"
            + "<div class='btn' onclick=\"api('key&val=HOME',true)\">Y (Home)</div>"
            + "</div></div>"
            + "<div class='row'><div class='btn accent' onclick=\"api('app&pkg=com.limelight',true)\">💻 Launch Moonlight PC Streamer</div></div>"
            + "</div>"
            + "<div id='ipModal'><div class='modal-card'>"
            + "<div style='font-size:16px;font-weight:700;color:#10B981'>Connect to Xiaomi TV</div>"
            + "<div style='font-size:12.5px;color:#94A3B8'>Enter your TV IP address or tap Auto-Find:</div>"
            + "<input id='ipInput' type='text' value='" + initialIp + "'>"
            + "<div class='row'>"
            + "<div class='btn' onclick='rescanTv()'>🔍 Auto-Find</div>"
            + "<div class='btn accent' onclick='saveIpModal()'>Save IP</div>"
            + "</div>"
            + "<div class='btn' onclick='closeIpModal()'>Cancel</div>"
            + "</div></div>"
            + "<script>"
            + "function api(q,vib){if(window.AndroidBridge)AndroidBridge.api(q,!!vib);}"
            + "window.onTvStatus=function(ip,ok){document.getElementById('ipLabel').textContent=ip;document.getElementById('ipInput').value=ip;document.getElementById('dot').className=ok?'dot ok':'dot';};"
            + "function openIpModal(){document.getElementById('ipModal').style.display='flex';}"
            + "function closeIpModal(){document.getElementById('ipModal').style.display='none';}"
            + "function saveIpModal(){const v=document.getElementById('ipInput').value;if(v&&window.AndroidBridge)AndroidBridge.setTvIp(v);closeIpModal();}"
            + "function rescanTv(){if(window.AndroidBridge)AndroidBridge.rescan();closeIpModal();}"
            + "function showTab(i){document.querySelectorAll('.tab').forEach((t,idx)=>t.classList.toggle('active',idx===i));document.querySelectorAll('.panel').forEach((p,idx)=>p.classList.toggle('active',idx===i));}"
            + "function sendUrl(){const v=document.getElementById('urlIn').value;if(v)api('url&val='+encodeURIComponent(v),true);}"
            + "function sendTxt(){const v=document.getElementById('txtIn').value;if(v)api('text&val='+encodeURIComponent(v),true);}"
            + "const pad=document.getElementById('touchpad');let lx=0,ly=0,moved=false,t0=0,lastSend=0;"
            + "pad.addEventListener('touchstart',e=>{const t=e.touches[0];lx=t.clientX;ly=t.clientY;moved=false;t0=Date.now();},{passive:false});"
            + "pad.addEventListener('touchmove',e=>{e.preventDefault();const t=e.touches[0];const now=Date.now();const dx=(t.clientX-lx)*2.1,dy=(t.clientY-ly)*2.1;if(Math.abs(dx)>1.8||Math.abs(dy)>1.8){moved=true;if(now-lastSend>=28){lx=t.clientX;ly=t.clientY;lastSend=now;api('move&dx='+dx.toFixed(1)+'&dy='+dy.toFixed(1),false);}}},{passive:false});"
            + "pad.addEventListener('touchend',e=>{if(!moved&&(Date.now()-t0)<280)api('click',true);});"
            + "const sb=document.getElementById('scrollbar');let sy=0,lastScroll=0;"
            + "sb.addEventListener('touchstart',e=>{sy=e.touches[0].clientY;},{passive:false});"
            + "sb.addEventListener('touchmove',e=>{e.preventDefault();const now=Date.now();const dy=(e.touches[0].clientY-sy)*3.0;if(Math.abs(dy)>24&&(now-lastScroll>=120)){sy=e.touches[0].clientY;lastScroll=now;api('scroll&dy='+dy.toFixed(0),false);}},{passive:false});"
            + "</script></body></html>";
    }
}
