# 📺 Xiaomi Android TV Ultimate Mod: Debloat, 100% Speed, Apple TV Launcher, Brave/PC Apps & Mobile Mouse/Keyboard/Controller

Turn a laggy, ad-filled 1 GB RAM Xiaomi Android TV (`MiTV-AXSO0` / `dangal` / Mi TV 4A & 4C Pro on Android TV 9, or any Android TV) into a **100% speed, zero-ad, Apple-TV-style powerhouse** with:
- **Safe Debloat:** Disables 24+ Xiaomi PatchWall, telemetry, analytics, and Google ad/recommendation services (`user 0` only — reversible anytime, keeps HDMI 1–3, AV, Live TV tuner, Wi-Fi, Bluetooth, and Play Store intact).
- **100% Speed Optimization:** Zero window/transition animation delay, `reduced_motion` GPU mode, background Wi-Fi scan suppression, and Ahead-Of-Time (`speed`) native ARM compilation.
- **Custom Apple-TV-Style Home Screen:** Frosted-glass dock (`YouTube`, `Netflix`, `Prime Video`, `JioHotstar`), `SmartTube` directly below YouTube, built-in **App Library** for normal Android phone/tablet apps, live RAM/Storage HUD, and **Aurora** theme.
- **Android & PC Apps on TV:** Pre-configured for **Brave Browser** (with built-in adblock) and **Moonlight PC Game & Desktop Streaming**.
- **📱 Mobile Control App (`MiTV-Mobile-Remote-Phone.apk`):** Use your Android phone as a **Touchpad Mouse (with glowing TV cursor & scroll bar), Keyboard, URL Launcher, TV Remote, and Gamepad Controller**!

---

## 📦 Direct APK Downloads (Pre-Built in [`releases/`](./releases))

| File | Install On | Purpose |
|---|---|---|
| **[`MiTV-Mobile-Remote-Phone.apk`](./releases/MiTV-Mobile-Remote-Phone.apk)** | **📱 Your Android Phone** | Standalone Phone App: Touchpad Mouse, Scroll Bar, Keyboard, Brave URL Launcher, Remote & Gamepad (with haptic feedback & auto TV IP discovery) |
| **[`MiTV-TV-MobileControl-Server.apk`](./releases/MiTV-TV-MobileControl-Server.apk)** | **📺 Your Android TV** | TV-side Accessibility Service (`com.gathin.mobilecontrol`) that draws the on-screen cursor and receives mouse/keyboard/controller input on port `8888` |
| **[`MiTV-AppleTV-Launcher.apk`](./releases/MiTV-AppleTV-Launcher.apk)** | **📺 Your Android TV** | Custom Apple-TV-style Home Launcher (`com.gathin.tvhome`) with `reduced_motion`, `app_library`, and custom dock/grid layout |
| **[`Addon-BluetoothConnect.apk`](./releases/Addon-BluetoothConnect.apk)** | **📺 Your Android TV** | 1-Click Bluetooth Headphones/Speaker reconnect module card |
| **[`Addon-Brightness.apk`](./releases/Addon-Brightness.apk)** | **📺 Your Android TV** | Screen dimmer overlay module card (`0%–90%`) for night viewing |
| **[`Addon-SleepTimer.apk`](./releases/Addon-SleepTimer.apk)** | **📺 Your Android TV** | Countdown sleep timer module card (`15–120 min`) |
| **[`Addon-Weather.apk`](./releases/Addon-Weather.apk)** | **📺 Your Android TV** | Live temperature & weather module card |
| **[`Addon-DigitalRain.apk`](./releases/Addon-DigitalRain.apk)** | **📺 Your Android TV** | Matrix digital rain screensaver module card |

---

## 🚀 Step-by-Step Installation Guide (TV + Phone)

### Step 1: Enable USB Debugging on Your TV
1. On your TV, open **Settings → Device Preferences → About**.
2. Scroll down to **Build** and press **OK** on your remote **7 times** until it says *"You are now a developer!"*.
3. Press Back once, open **Developer options**, and turn **USB debugging** **ON**.
4. Find your TV's IP address in **Settings → Network & Internet → [Your Wi-Fi] → IP address** (e.g., `192.168.0.233`).

---

### Step 2: Connect from Your PC & Debloat Safely
Clone this repository and connect over Network ADB:
```powershell
git clone https://github.com/Nithinkumaru777/xiaomi-tv-ultimate-mod.git
cd xiaomi-tv-ultimate-mod

# Set your TV's IP address
$env:TV_HOST = "192.168.0.233"

# Preview and disable the 24 Xiaomi/Google bloatware packages (saves a full recovery file automatically)
.\tools\disable.ps1 .\examples\xiaomi-mitv-axso0\debloat.txt
```
*(When the TV shows **"Allow USB debugging?"**, check **"Always allow from this computer"** and click **OK**).*

---

### Step 3: Apply 100% Speed & Performance Optimization
Run these ADB commands to remove all animation lag, stop background Wi-Fi scanning, and enable AdGuard Private DNS:
```powershell
adb connect $env:TV_HOST:5555

# 1. Zero system animation delay
adb shell "settings put global window_animation_scale 0.0; settings put global transition_animation_scale 0.0; settings put global animator_duration_scale 0.0"

# 2. Stop background Wi-Fi scanning & trim caches
adb shell "settings put global wifi_scan_always_enabled 0; pm trim-caches 999999999G"

# 3. System-wide AdGuard DNS
adb shell "settings put global private_dns_mode hostname; settings put global private_dns_specifier dns.adguard-dns.com"
```

---

### Step 4: Install the Apple-TV Launcher, Aurora Theme & Module Cards
```powershell
# Install the pre-built Launcher and Module Card Add-Ons
adb install -r .\releases\MiTV-AppleTV-Launcher.apk
adb install -r .\releases\MiTV-TV-MobileControl-Server.apk
adb install -r .\releases\Addon-BluetoothConnect.apk
adb install -r .\releases\Addon-Brightness.apk
adb install -r .\releases\Addon-SleepTimer.apk
adb install -r .\releases\Addon-Weather.apk
adb install -r .\releases\Addon-DigitalRain.apk

# Grant permissions & enable Accessibility Services (Remote Keys + Sleep Timer + Mobile Control Cursor/Mouse)
adb shell "pm grant com.gathin.tvhome android.permission.WRITE_SECURE_SETTINGS; pm grant com.gathin.tvhome android.permission.ACCESS_FINE_LOCATION"
adb shell "settings put secure enabled_accessibility_services com.gathin.tvhome/.InputKeyService:com.gathin.tvhome/.HomeKeyService:com.gathin.sleeptimer/.SleepService:com.gathin.mobilecontrol/.MobileControlService; settings put secure accessibility_enabled 1"

# Disable stock launcher so pressing Home opens the new Apple-TV-style Home screen
adb shell "pm disable-user --user 0 com.google.android.tvlauncher"

# Push the Aurora Theme
adb disconnect
.\tools\theme.ps1 .\themes\aurora
```

---

### Step 5: Install SmartTube, Brave Browser & Moonlight PC Streaming
Download and install the Android 9 ARM32 (`armeabi-v7a`) builds on your TV:
- **SmartTube (Ad-Free 4K YouTube):** [`SmartTube_stable_armeabi-v7a.apk`](https://github.com/yuliskov/SmartTube/releases/latest)
- **Brave Browser (Final Chromium build supporting Android 9 ARM32):** [`BraveMonoarm.apk (v1.60.125)`](https://github.com/brave/brave-browser/releases/download/v1.60.125/BraveMonoarm.apk)
- **Moonlight PC Streaming (Stream Windows Desktop & Games at 60fps):** [`Moonlight v12.2`](https://github.com/moonlight-stream/moonlight-android/releases/download/v12.2/app-nonRoot-release.apk)

```powershell
adb connect $env:TV_HOST:5555
adb install -r SmartTube_stable_armeabi-v7a.apk
adb install -r BraveMonoarm.apk
adb install -r app-nonRoot-release.apk

# Native AOT speed compilation for instant app startup
adb shell "cmd package compile -m speed -f com.gathin.tvhome; cmd package compile -m speed -f com.google.android.youtube.tv; cmd package compile -m speed -f org.smarttube.stable; cmd package compile -m speed -f com.gathin.mobilecontrol"
```

---

### Step 6: Lock Down Network ADB When Finished
```powershell
adb shell "settings put global adb_enabled 0; settings put global enableXiaomiAdb 0"
```

---

## 📱 Step 7: Install `MiTV-Mobile-Remote-Phone.apk` on Your Phone
1. Download **[`MiTV-Mobile-Remote-Phone.apk`](./releases/MiTV-Mobile-Remote-Phone.apk)** onto your Android phone and tap **Install**.
2. Open **MiTV Control** on your phone while connected to the same Wi-Fi network as your TV:
   - The app **automatically scans and connects** to your TV on port `8888` (or tap the IP pill in the top-right corner to enter your TV's IP manually).
   - **🖱️ Mouse Tab:** Slide your finger on the touchpad to move the glowing green cursor on the TV; tap to Left-Click; slide the right-side **`⇅ SCROLL`** strip to scroll pages in Brave Browser.
   - **⌨️ Brave & Keys Tab:** Enter any URL or search query and tap **Brave Go** to open it immediately in Brave on your TV; type text into any focused TV search box; or 1-tap launch Brave, SmartTube, YouTube, Moonlight PC, Netflix, Prime Video, or JioHotstar.
   - **📺 Remote Tab:** Full D-Pad, Back, Home, Recents, Volume, and Mute controls (plus your phone's physical Volume buttons control the TV volume!).
   - **🎮 Gamepad Tab:** D-Pad + **A / B / X / Y** buttons + 1-tap Moonlight PC Streamer launcher.
   - *(No Android phone? You can also open `http://<your-tv-ip>:8888` in Safari/Chrome on any iPhone, iPad, or laptop!).*

---

## 🔄 How to Revert to Stock Anytime
To restore the stock launcher and re-enable every disabled package:
```powershell
$env:TV_HOST = "192.168.0.233"
.\tools\restore.ps1 .\examples\xiaomi-mitv-axso0\debloat.txt
adb connect $env:TV_HOST:5555
adb shell "pm enable com.google.android.tvlauncher"
```
