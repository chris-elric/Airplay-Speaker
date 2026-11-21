import re,urllib.request
UA="Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/130.0.0.0 Safari/537.36"
r=urllib.request.urlopen(urllib.request.Request("https://music.apple.com/",headers={"User-Agent":UA}),timeout=30)
print("content-length",r.headers.get("Content-Length"),"encoding",r.headers.get("Content-Encoding"))
h=r.read().decode("utf-8","replace")
m=re.search(r'src="([^"]*?/assets/[^"]*?\.js)"',h)
print("script src at offset",m.start() if m else None,"of",len(h),m.group(1) if m else None)
js=urllib.request.urlopen(urllib.request.Request("https://music.apple.com"+m.group(1),headers={"User-Agent":UA}),timeout=60).read().decode("utf-8","replace")
t=re.search(r"eyJ0eXAi[A-Za-z0-9_\-]+\.eyJ[A-Za-z0-9_\-]+\.[A-Za-z0-9_\-]+",js)
print("first typ-first jwt offset",t.start(),"of",len(js))
