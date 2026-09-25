import gzip, urllib.request, os, sys, re, subprocess
BASE="http://archive.ubuntu.com/ubuntu/"
idx={}
for comp in ["main","universe"]:
  for suite in ["noble","noble-updates"]:
    fn=f"Packages-{suite}-{comp}.gz"
    if not os.path.exists(fn):
      urllib.request.urlretrieve(f"{BASE}dists/{suite}/{comp}/binary-amd64/Packages.gz",fn)
    for block in gzip.open(fn,"rt",errors="replace").read().split("\n\n"):
      d={}
      for line in block.split("\n"):
        if ":" in line and not line.startswith(" "):
          k,v=line.split(":",1); d[k]=v.strip()
      if "Package" in d:
        idx[d["Package"]]=d  # later suites override
      for p in d.get("Provides","").split(","):
        p=p.strip().split(" ")[0]
        if p and p not in idx: idx.setdefault("__prov__"+p,d)
installed=set(subprocess.run(["dpkg-query","-W","-f=${Package}\n"],capture_output=True,text=True).stdout.split())
installed={p.split(":")[0] for p in installed}
want=sys.argv[1:]; seen=set(); order=[]
skip={"libc6","libgcc-s1","libstdc++6","debconf","dpkg","perl-base","xkb-data-i18n","x11-common","libpam0g","adduser"}
def add(n):
  if n in seen or n in installed or n in skip: return
  d=idx.get(n) or idx.get("__prov__"+n)
  if not d: print("MISSING",n,file=sys.stderr); return
  seen.add(n); seen.add(d["Package"])
  for grp in (d.get("Depends","")+","+d.get("Pre-Depends","")).split(","):
    alts=[a.strip().split(" ")[0].split(":")[0] for a in grp.split("|") if a.strip()]
    if not alts: continue
    if any(a in installed or a in seen for a in alts): continue
    add(alts[0])
  order.append(d)
for w in want: add(w)
os.makedirs("debs",exist_ok=True)
for d in order:
  f="debs/"+os.path.basename(d["Filename"])
  if not os.path.exists(f): urllib.request.urlretrieve(BASE+d["Filename"],f)
  print(d["Package"])
