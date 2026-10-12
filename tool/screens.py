# Per-screen frame timing for one app, measured from the computer the way Frames measures on the phone: the
# compositor's own record of when each frame of the app's window reached the screen. For each tab: tap it, scroll
# up and down for a few seconds, and count. Only vertical swipes in the middle of the screen and taps on the tab bar.
import re,sys,subprocess,time,threading,json
dev,pkg=sys.argv[1],sys.argv[2]; tabs=json.loads(sys.argv[3]); sx=int(sys.argv[4]) if len(sys.argv)>4 else 650
def sh(*a,timeout=20): return subprocess.run(['adb','-s',dev,'shell',*a],capture_output=True,text=True,timeout=timeout).stdout
def layers():
    out=[]
    for raw in sh('dumpsys','SurfaceFlinger','--list').splitlines():
        name=raw.strip().removeprefix('RequestedLayerState{').split(' parentId')[0].removesuffix('}')
        bare=name.split(' ',1)[1] if re.match(r'^[0-9a-f]{5,8} ',name) else name
        if '#' in bare and (bare.startswith(pkg+'/') or bare.startswith('VRI-'+pkg+'/') or bare.startswith('SurfaceView['+pkg+'/')): out.append(name)
    return out
def read(layer):
    # One string, the name in single quotes: it has spaces, brackets and a dollar sign, and the phone's shell reads them.
    lines=[l.split() for l in sh("dumpsys SurfaceFlinger --latency '"+layer+"'").strip().splitlines() if l.strip()]
    if not lines: return 16.67,[]
    period=int(lines[0][0])/1e6
    rows=[(int(r[0]),int(r[1])) for r in lines[1:] if len(r)>=3 and r[1] not in('0','9223372036854775807') and r[0]!='9223372036854775807']
    return period,rows
def best():
    got=[(read(l),l) for l in layers()]
    got=[(p,r,l) for (p,r),l in got if r]
    return max(got,key=lambda g:max(x[1] for x in g[1])) if got else None
def scroll(stop):
    while not stop.is_set():
        sh('input','swipe',str(sx),'1250',str(sx),'650','220'); time.sleep(0.35)
        if stop.is_set(): break
        sh('input','swipe',str(sx),'650',str(sx),'1250','220'); time.sleep(0.35)
print(f'{"screen":<12}{"fps":>6}{"1% low":>8}{"janky":>8}{"median":>8}{"99th":>7}{"worst":>7}{"frames":>8}{"Hz":>5}  window')
for name,x,y in tabs:
    sh('input','tap',str(x),str(y)); time.sleep(2.5)
    b=best()
    if not b: print(f'{name:<12} no frames'); continue
    period,rows,layer=b
    since=max(r[1] for r in rows)
    seen={}
    stop=threading.Event(); t=threading.Thread(target=scroll,args=(stop,)); t.start()
    end=time.time()+7
    while time.time()<end:
        p,rows=read(layer)
        if p>0: period=p
        for d,a in rows:
            if a>since: seen[a]=d
        time.sleep(0.25)
    stop.set(); t.join()
    fr=sorted(seen.items())
    times=[]
    for (a0,d0),(a1,d1) in zip(fr,fr[1:]):
        ns=(a1-a0) if d1-a0<period*2e6 else max(a1-d1,0)+period*1e6
        if ns>0: times.append(ns/1e6)
    if len(times)<20: print(f'{name:<12} too few frames ({len(times)})'); continue
    secs=len({a//1_000_000_000 for a,_ in fr}); span=(fr[-1][0]-fr[0][0])/1e9
    s=sorted(times); pct=lambda q:s[min(len(s)-1,max(0,int(-(-q*len(s)//1))-1))]
    jank=sum(1 for v in s if v>period*1.5)*100/len(s)
    kind='surface' if 'SurfaceView' in layer else 'window'
    print(f'{name:<12}{len(times)/max(secs,1):>6.0f}{min(1000/pct(.99),1000/period):>8.0f}{jank:>7.1f}%{pct(.5):>8.1f}{pct(.99):>7.1f}{s[-1]:>7.0f}{len(times):>8}{1000/period:>5.0f}  {kind}  ({secs} s with frames over {span:.1f} s)')
