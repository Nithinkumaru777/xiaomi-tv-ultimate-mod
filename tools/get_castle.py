import urllib.request
import re
import sys

headers = {'User-Agent': 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36'}

urls = [
    'https://castletvapk.in/download/',
    'https://castletvapk.in/',
    'https://castleapp.co.in/download/',
    'https://castle.am.in/'
]

for url in urls:
    try:
        req = urllib.request.Request(url, headers=headers)
        with urllib.request.urlopen(req, timeout=10) as resp:
            html = resp.read().decode('utf-8', errors='ignore')
            # Look for any .apk link or download redirect link
            links = re.findall(r'href=["\']([^"\']+)["\']', html)
            apk_links = [l for l in links if '.apk' in l.lower() or 'download' in l.lower()]
            print(f"Results for {url}:")
            for al in set(apk_links[:10]):
                print("  ", al)
    except Exception as e:
        print(f"Error {url}: {e}")
