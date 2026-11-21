import json, re, urllib.request, urllib.parse, base64, time
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
def b64(x):
    x+="="*(-len(x)%4); return base64.urlsafe_b64decode(x).decode("utf-8","replace")
jwt=re.compile(r"eyJ[A-Za-z0-9_\-]{8,}\.eyJ[A-Za-z0-9_\-]{8,}\.[A-Za-z0-9_\-]{8,}")
s,js=get("https://music.apple.com/assets/index~B8RKPfmaCI.js")
toks=[]
for m in jwt.finditer(js):
    t=m.group(0); h,p,_=t.split(".")
    pre=js[max(0,m.start()-50):m.start()].replace("\n"," ")
    print("JWT hdr",b64(h),"| payload",b64(p)[:200],"| len",len(t),"| before:",pre)
    toks.append(t)
for t in toks:
    for sf in ["us","mx"]:
        s,b=get(f"https://amp-api.music.apple.com/v1/catalog/{sf}/songs/6833728368",{"Authorization":"Bearer "+t,"Origin":"https://music.apple.com"})
        print("meta",sf,s,b[:140].replace("\n"," "))
    s,b=get("https://amp-api.music.apple.com/v1/catalog/mx/songs/6833728368/syllable-lyrics",{"Authorization":"Bearer "+t,"Origin":"https://music.apple.com","Referer":"https://music.apple.com/","media-user-token":"x"})
    print("syl",s,b[:200].replace("\n"," "))
    s,b=get("https://amp-api.music.apple.com/v1/catalog/mx/songs/6833728368/lyrics",{"Authorization":"Bearer "+t,"Origin":"https://music.apple.com","media-user-token":"x"})
    print("lyr",s,b[:200].replace("\n"," "))
base="https://lyricsplus.binimum.org"
for params in [{"title":"Así Fue (Apple Music Sessions)","artist":"Ed Maverick","source":"apple,lyricsplus,musixmatch-word,musixmatch,spotify"},{"title":"Hello","artist":"Adele"},{"title":"Hello","artist":"Adele","source":"apple"}]:
    time.sleep(11)
    s,b=get(base+"/v2/lyrics/get?"+urllib.parse.urlencode(params))
    print(params,s,b[:300].replace("\n"," "))
