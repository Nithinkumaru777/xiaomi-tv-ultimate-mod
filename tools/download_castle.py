import urllib.request
import os

headers = {'User-Agent': 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36'}
dest_dir = r"c:\anitgravity fix\fix anything\backups\mitv-axs00-2026-10-26"

def download_file(url, out_name):
    out_path = os.path.join(dest_dir, out_name)
    print(f"Downloading {url} to {out_path}...")
    req = urllib.request.Request(url, headers=headers)
    with urllib.request.urlopen(req, timeout=30) as resp, open(out_path, 'wb') as f:
        total = 0
        while True:
            chunk = resp.read(65536)
            if not chunk:
                break
            f.write(chunk)
            total += len(chunk)
            if total % (1024 * 1024 * 5) < 65536:
                print(f"  {total / (1024*1024):.1f} MB downloaded...")
    print(f"Done! {out_name}: {os.path.getsize(out_path)} bytes")
    return out_path

try:
    download_file('https://castletvapk.in/apk/CastleTV_v1.1.5_official.apk', 'CastleTV_v1.1.5.apk')
except Exception as e:
    print(f"Error CastleTV: {e}")

try:
    download_file('https://castletvapk.in/apk/Castle_v2.1.3_official.apk', 'Castle_v2.1.3.apk')
except Exception as e:
    print(f"Error Castle: {e}")
