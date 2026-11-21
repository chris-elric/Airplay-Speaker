import json, re, urllib.request, urllib.parse
UA="Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36"
def get(url, headers=None, timeout=20):
    h={"User-Agent":UA}; h.update(headers or {})
    try:
        with urllib.request.urlopen(urllib.request.Request(url,headers=h),timeout=timeout) as r:
            return r.status, r.read().decode("utf-8","replace")
    except urllib.error.HTTPError as e:
        return e.code, e.read().decode("utf-8","replace")
    except Exception as e:
        return -1, repr(e)
print("== iTunes search")
ids=[]
for term in ["Ed Maverick Así Fue","Ed Maverick Asi Fue Apple Music Sessions","Así Fue (Apple Music Sessions) Ed Maverick"]:
    for c in ["US","MX","ES","TH"]:
        s,b=get("https://itunes.apple.com/search?"+urllib.parse.urlencode({"term":term,"media":"music","entity":"song","country":c,"limit":10}))
        try:
            rs=json.loads(b)["results"]
        except Exception:
            print(c,term,s,b[:120]); continue
        for r in rs:
            if "maverick" in r.get("artistName","").lower():
                print(c,"|",term[:25],"|",r["trackId"],r["trackName"],"|",r["collectionName"],"|",r.get("trackTimeMillis"))
                ids.append(r["trackId"])
print("== dev token")
s,h=get("https://music.apple.com/")
print("html",s,len(h))
jwt=re.compile(r"eyJhbGciOiJFUzI1NiI[A-Za-z0-9_\-]*\.eyJ[A-Za-z0-9_\-]+\.[A-Za-z0-9_\-]+")
m=jwt.search(urllib.parse.unquote(h))
print("token in html:",bool(m))
srcs=re.findall(r'src="([^"]*?/assets/[^"]*?\.js)"',h)
print("scripts",srcs[:6])
tok=m.group(0) if m else None
if not tok:
    for p in srcs[:4]:
        s,js=get(urllib.parse.urljoin("https://music.apple.com/",p),timeout=40)
        m=jwt.search(js); print(p,s,len(js),bool(m))
        if m: tok=m.group(0); break
print("token ok:",bool(tok))
if tok and ids:
    for sf in ["us","mx"]:
        for i in sorted(set(ids))[:6]:
            s,b=get(f"https://amp-api.music.apple.com/v1/catalog/{sf}/songs/{i}/syllable-lyrics",{"Authorization":"Bearer "+tok,"Origin":"https://music.apple.com","Referer":"https://music.apple.com/","media-user-token":"x"})
            print("amp",sf,i,s,b[:160].replace("\n"," "))
            s,b=get(f"https://amp-api.music.apple.com/v1/catalog/{sf}/songs/{i}",{"Authorization":"Bearer "+tok,"Origin":"https://music.apple.com"})
            print(" song meta",sf,i,s,b[:100].replace("\n"," "))
print("== lyricsplus")
for base in ["https://lyricsplus.binimum.org","https://lyricsplus.atomix.one","https://lyricsplus-seven.vercel.app","https://lyricsplus.prjktla.workers.dev","https://lyrics-plus-backend.vercel.app"]:
    q=urllib.parse.urlencode({"title":"Así Fue (Apple Music Sessions)","artist":"Ed Maverick","duration":"","source":"apple"})
    s,b=get(base+"/v2/lyrics/get?"+q)
    print(base,s,b[:200].replace("\n"," "))
    q=urllib.parse.urlencode({"title":"Así Fue","artist":"Ed Maverick","source":"apple,lyricsplus,musixmatch-word,musixmatch"})
    s,b=get(base+"/v2/lyrics/get?"+q)
    print("  plain title",s,b[:200].replace("\n"," "))
print("== paxsenix")
for i in sorted(set(ids))[:6]:
    s,b=get(f"https://lyrics.paxsenix.org/apple-music/lyrics?id={i}&v=2")
    print(i,s,b[:150].replace("\n"," "))
