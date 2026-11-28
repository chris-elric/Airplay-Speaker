import re, json, urllib.request, urllib.parse
UA="Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36"
def get(url, headers=None, limit=None):
    h={"User-Agent":UA}; h.update(headers or {})
    try:
        with urllib.request.urlopen(urllib.request.Request(url,headers=h),timeout=25) as r:
            return r.status, r.read(limit) if limit else r.read()
    except urllib.error.HTTPError as e:
        return e.code, e.read()[:300]
    except Exception as e:
        return -1, str(e).encode()
jwt=re.compile(r"eyJ(?:0eXAi|hbGci)[A-Za-z0-9_-]{10,}\.eyJ[A-Za-z0-9_-]{10,}\.[A-Za-z0-9_-]{20,}")
c,html=get("https://music.apple.com/")
html=html.decode("utf8","replace")
tok=None
for p in re.findall(r'src="([^"]*?/assets/[^"]*?\.js)"',html):
    if "index" in p:
        c,b=get("https://music.apple.com"+p); m=jwt.search(b.decode("utf8","replace"))
        if m: tok=m.group(0); break
print("token", bool(tok))
H={"Authorization":"Bearer "+tok,"Origin":"https://music.apple.com","Referer":"https://music.apple.com/"}
cases=[("Taylor Swift","Speak Now (Taylor’s Version)","Speak Now (Taylor’s Version)"),
       ("Bring Me The Horizon","N/A","POST HUMAN: NeX GEn"),
       ("Olivia Rodrigo","Pretty Isn’t Pretty","GUTS")]
for artist,title,album in cases:
    print("=====",artist,"|",title,"|",album)
    for cc in ("us","mx"):
        for term in (f"{artist} {title}", f"{artist} {title.replace(chr(0x2019),chr(39))}"):
            u="https://itunes.apple.com/search?"+urllib.parse.urlencode({"term":term,"media":"music","entity":"song","country":cc,"limit":"25"})
            c,b=get(u)
            try: rs=json.loads(b)["results"]
            except Exception: print("ITUNES",cc,term,c,b[:100]); continue
            print("ITUNES",cc,repr(term),len(rs))
            for r in rs[:8]:
                print("  ",r.get("trackId"),"|",r.get("trackName"),"|",r.get("collectionName"),"|",r.get("artistName"),"|",r.get("trackExplicitness"),"|",r.get("trackTimeMillis"))
    for cc in ("us","mx"):
        u=f"https://amp-api.music.apple.com/v1/catalog/{cc}/search?"+urllib.parse.urlencode({"term":f"{artist} {title}","types":"songs","limit":"10"})
        c,b=get(u,H)
        print("CATALOG",cc,c)
        try:
            for r in json.loads(b)["results"]["songs"]["data"]:
                a=r["attributes"]; print("  ",r["id"],"|",a.get("name"),"|",a.get("albumName"),"|",a.get("contentRating"),"|",a.get("audioTraits"),"|",a.get("durationInMillis"),"|",a.get("hasLyrics"),"|",a.get("hasTimeSyncedLyrics"))
        except Exception as e: print("  parse",e,b[:200])
