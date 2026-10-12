# Prints only what is needed to get about an app: the labels and middles of things that can be tapped along the
# bottom edge (its tab bar) and, with --all, the short labels of other tappable things. No values are printed.
import re,sys,subprocess
dev=sys.argv[1]; every='--all' in sys.argv
subprocess.run(['adb','-s',dev,'shell','uiautomator','dump','/sdcard/u.xml'],capture_output=True)
x=subprocess.run(['adb','-s',dev,'shell','cat','/sdcard/u.xml'],capture_output=True,text=True).stdout
nodes=re.findall(r'<node [^>]*>',x)
def attr(n,k):
    m=re.search(k+r'="([^"]*)"',n); return m.group(1) if m else ''
size=[0,0]
for n in nodes:
    b=re.findall(r'\d+',attr(n,'bounds'))
    if len(b)==4: size=[max(size[0],int(b[2])),max(size[1],int(b[3]))]
print('screen',size,'nodes',len(nodes),'pkg',attr(nodes[0],'package') if nodes else '')
seen=set()
for n in nodes:
    b=[int(v) for v in re.findall(r'\d+',attr(n,'bounds'))]
    if len(b)!=4: continue
    label=(attr(n,'text') or attr(n,'content-desc')).replace('&#10;',' ').strip()
    if not label or len(label)>28 or re.search(r'\d',label) or '$' in label: continue
    cx,cy=(b[0]+b[2])//2,(b[1]+b[3])//2
    bottom=cy>size[1]*0.86
    if not (bottom or every): continue
    if attr(n,'clickable')!='true' and not bottom: continue
    key=(label,cx//20,cy//20)
    if key in seen: continue
    seen.add(key); print(('TAB ' if bottom else '    ')+f'{cx},{cy}  {label}')
