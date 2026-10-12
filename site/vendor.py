#!/usr/bin/env python3
"""Vendor the themes' third-party CSS/JS into www/vendor and point the built HTML at it.

srht.site serves with a Content-Security-Policy of style-src/script-src 'self', so every
external stylesheet and script is refused. Assets are mirrored as vendor/<host>/<path>, so
relative url() refs inside CSS keep working; root-absolute and cross-host refs are
rewritten to relative paths. CSS refs are followed recursively (fonts, icons, @imports).

usage: vendor.py <www dir> <urls file>
"""
import os, re, sys, urllib.parse, urllib.request

www, urls_file = sys.argv[1], sys.argv[2]
vendor = os.path.join(www, "vendor")
top = [u.strip() for u in open(urls_file) if u.strip()]
# ponytail: Google Fonts CSS is UA-specific and pulls from fonts.gstatic.com, also blocked by the
# CSP. Dropped; those themes fall back to system fonts. Vendor the woff2s by hand if it matters.
SKIP = ("fonts.googleapis.com",)
REF = re.compile(r"""url\(\s*['"]?([^'")]+)['"]?\s*\)|@import\s+(?:url\()?['"]?([^'")\s;]+)""")

def local(u):
    p = urllib.parse.urlsplit(u)
    return os.path.join(vendor, p.netloc, p.path.lstrip("/"))

seen = {}
def fetch(u, required):
    u = urllib.parse.urldefrag(u)[0].split("?")[0]
    if u in seen or urllib.parse.urlsplit(u).netloc in SKIP:
        return
    dst = local(u); seen[u] = dst
    try:
        data = urllib.request.urlopen(urllib.request.Request(u, headers={"User-Agent": "org-plan-site"}), timeout=30).read()
    except Exception as e:
        if required: sys.exit(f"vendor: failed {u}: {e}")
        print(f"vendor: warn {u}: {e}"); return
    os.makedirs(os.path.dirname(dst), exist_ok=True)
    if u.endswith(".css"):
        text = data.decode("utf-8", "replace")
        def fix(m):
            ref = m.group(1) or m.group(2)
            if ref.startswith("data:"): return m.group(0)
            absu = urllib.parse.urljoin(u, ref)
            if urllib.parse.urlsplit(absu).netloc in SKIP: return "/* vendor: dropped " + ref + " */"
            fetch(absu, False)
            same_host = urllib.parse.urlsplit(absu).netloc == urllib.parse.urlsplit(u).netloc
            if same_host and not ref.startswith("/"): return m.group(0)   # relative, mirror layout keeps it valid
            rel = os.path.relpath(local(absu), os.path.dirname(dst))
            return m.group(0).replace(ref, rel)
        text = REF.sub(fix, text)
        data = text.encode()
    open(dst, "wb").write(data)

for u in top: fetch(u, True)

n = 0
for root, _, files in os.walk(www):
    if root.startswith(vendor): continue
    for f in files:
        if not f.endswith(".html"): continue
        path = os.path.join(root, f); html = open(path, encoding="utf-8").read(); orig = html
        for u in top:
            html = html.replace(u, os.path.relpath(local(u), root))
        if html != orig: open(path, "w", encoding="utf-8").write(html); n += 1
print(f"vendor: {len(seen)} assets, {n} html files rewritten")
