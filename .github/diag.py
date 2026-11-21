import json, re, urllib.request, urllib.parse
UA="Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36"
def get(url, headers=None, timeout=40):
    h={"User-Agent":UA}; h.update(headers or {})
    try:
        with urllib.request.urlopen(urllib.request.Request(url,headers=h),timeout=timeout) as r:
            return r.status, r.read().decode("utf-8","replace")
    except urllib.error.HTTPError as e:
        return e.code, e.read().decode("utf-8","replace")
    except Exception as e:
        return -1, repr(e)
jwt=re.compile(r"eyJ[A-Za-z0-9_\-]{8,}\.eyJ[A-Za-z0-9_\-]{8,}\.[A-Za-z0-9_\-]{8,}")
s,h=get("https://music.apple.com/")
print("html",s,len(h))
for kw in ["developerToken","MEDIA_API","\"token\"","desktop-music-app","mediaToken","Bearer"]:
    for m in list(re.finditer(re.escape(kw),h))[:2]:
        print("HTML kw",kw,"::",h[max(0,m.start()-80):m.end()+160].replace("\n"," "))
print("jwt in html:",[x[:40] for x in jwt.findall(urllib.parse.unquote(h))][:3])
print("metas:",re.findall(r'<meta name="([^"]+)"',h)[:20])
s,js=get("https://music.apple.com/assets/index~B8RKPfmaCI.js")
print("index",s,len(js))
print("jwt in index:",[x[:60] for x in jwt.findall(js)][:3])
for kw in ["developerToken","MEDIA_API","Bearer","amp-api","ES256","eyJhbGci","authorization"]:
    for m in list(re.finditer(re.escape(kw),js))[:2]:
        print("JS kw",kw,"::",js[max(0,m.start()-120):m.end()+200].replace("\n"," "))
chunks=sorted(set(re.findall(r'["\'`]([^"\'`\s]*?[A-Za-z0-9_~\-]+\.js)["\'`]',js)))
print("chunk refs",len(chunks),chunks[:10])
found=None
for c in chunks[:80]:
    url=urllib.parse.urljoin("https://music.apple.com/assets/",c.split("/")[-1]) if "/" not in c.lstrip("./") else urllib.parse.urljoin("https://music.apple.com/assets/",c)
    s2,b=get(url,timeout=30)
    if s2!=200: continue
    m=jwt.search(b)
    if m:
        print("FOUND in",url,m.group(0)[:50],len(m.group(0))); found=m.group(0); break
print("found",bool(found))
if found:
    for sf in ["us","mx"]:
        s,b=get(f"https://amp-api.music.apple.com/v1/catalog/{sf}/songs/6833728368",{"Authorization":"Bearer "+found,"Origin":"https://music.apple.com"})
        print("meta",sf,s,b[:200].replace("\n"," "))
        s,b=get(f"https://amp-api.music.apple.com/v1/catalog/{sf}/songs/6833728368/syllable-lyrics",{"Authorization":"Bearer "+found,"Origin":"https://music.apple.com","media-user-token":"x"})
        print("syl",sf,s,b[:200].replace("\n"," "))
print("== lyricsplus variants")
base="https://lyricsplus.binimum.org"
for params in [{"title":"Así Fue","artist":"Ed Maverick"},{"title":"Así Fue (Apple Music Sessions)","artist":"Ed Maverick","album":"Sessions","duration":"345"},{"title":"Asi Fue","artist":"Ed Maverick","source":"apple"},{"title":"Así Fue","artist":"Ed Maverick","source":"apple,lyricsplus,musixmatch-word,musixmatch,spotify"},{"title":"Cuando Me Enamoro","artist":"Enrique Iglesias"}]:
    s,b=get(base+"/v2/lyrics/get?"+urllib.parse.urlencode(params))
    print(params,s,b[:260].replace("\n"," "))
for path in ["/v2/lyrics/sources","/v1/lyrics/get"]:
    s,b=get(base+path+"?title=Hello&artist=Adele"); print(path,s,b[:200].replace("\n"," "))
