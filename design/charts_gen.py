import math, random, datetime
random.seed(11)
N=365
today=datetime.date(2026,6,22)
start=today-datetime.timedelta(days=N-1)
def dnum_to_date(t): return start+datetime.timedelta(days=int(round(t)))
LB_PER_KG=2.2046226; KCAL_PER_LB=3500.0; W=14; alpha=2.0/(W+1)
true_w=[]
for i in range(N):
    base=189-0.034*i
    if 150<i<210: base+=0.6*math.sin((i-150)/60*math.pi)
    true_w.append(base)
raw_w=[round(true_w[i]+random.uniform(-1.7,1.7),1) for i in range(N)]
ema=[]; e=raw_w[0]
for i in range(N):
    e=raw_w[0] if i==0 else alpha*raw_w[i]+(1-alpha)*e
    ema.append(round(e,2))
intake=[]
for i in range(N):
    b=2050+random.uniform(-170,170)
    if (start+datetime.timedelta(days=i)).weekday() in (5,6): b+=random.uniform(120,420)
    intake.append(int(round(b)))
def mifflin(kg,cm=180.3,age=33): return (10*kg+6.25*cm-5*age+5)*1.55
formula=[round(mifflin(ema[i]/LB_PER_KG)) for i in range(N)]
tdee=[]
for i in range(N):
    if i>=W:
        avg=sum(intake[i-W+1:i+1])/W; stored=(ema[i]-ema[i-W])*KCAL_PER_LB/W; tdee.append(round(avg-stored))
    elif i==0: tdee.append(formula[0])
    else:
        avg=sum(intake[:i+1])/(i+1); stored=(ema[i]-ema[0])*KCAL_PER_LB/max(i,1)
        tdee.append(round((1-i/W)*formula[i]+ (i/W)*(avg-stored)))
PT,FT,CT=165,60,235
logged=[];mp=[];mf=[];mc=[]
for i in range(N):
    if random.random()<0.85:
        logged.append(True); mp.append(int(PT*random.uniform(0.72,1.04)))
        mf.append(int(FT*random.uniform(0.70,1.08))); mc.append(int(CT*random.uniform(0.68,1.05)))
    else: logged.append(False); mp.append(None); mf.append(None); mc.append(None)
today_p,today_f,today_c=96,31,118
GOAL=175.0; cur_ema=ema[-1]
recent_rate=(ema[-1]-ema[-1-W])/W; goal_rate=-0.5/7.0
def proj_days(r): return None if r>=-1e-9 else (GOAL-cur_ema)/r
d_goal=proj_days(goal_rate); d_cur=proj_days(recent_rate)

C_BG="#faf8fd";C_GRID="#e7e2ee";C_AX="#6b6478";C_RAW="#b9b2c9";C_EMA="#6750a4";C_GOAL="#9a8fb8"
C_BAR="#7cc5c0";C_TDEE="#e08a3c";C_DEF="#cfe8d6";C_PGOAL="#3a9e6a";C_PCUR="#4a7fe0"
C_PROT="#6750a4";C_FAT="#e0a93c";C_CARB="#3c9ea0"

def frame(tmin,tmax,vmin,vmax,ml=58,mr=18,mt=22,mb=40,w=880,h=340):
    pw=w-ml-mr;ph=h-mt-mb
    px=lambda t: ml+pw*(t-tmin)/(tmax-tmin); py=lambda v: mt+ph*(1-(v-vmin)/(vmax-vmin))
    s=[f'<svg viewBox="0 0 {w} {h}" xmlns="http://www.w3.org/2000/svg" style="width:100%;height:auto">',
       f'<rect width="{w}" height="{h}" fill="{C_BG}" rx="10"/>']
    for k in range(6):
        v=vmin+(vmax-vmin)*k/5;y=py(v)
        s.append(f'<line x1="{ml}" y1="{y:.1f}" x2="{w-mr}" y2="{y:.1f}" stroke="{C_GRID}"/>')
        s.append(f'<text x="{ml-8}" y="{y+4:.1f}" text-anchor="end" font-size="12" fill="{C_AX}">{round(v)}</text>')
    for k in range(6):
        t=tmin+(tmax-tmin)*k/5;x=px(t)
        s.append(f'<text x="{x:.1f}" y="{h-14}" text-anchor="middle" font-size="11" fill="{C_AX}">{dnum_to_date(t).strftime("%b %d")}</text>')
    return s,px,py,(ml,mr,mt,mb,w,h,pw,ph)

def trend_chart(i0,i1,predict=False):
    tmin=i0
    tmax=(max([i1]+[i1+abs(d) for d in (d_goal,d_cur) if d])+6) if predict else i1
    vals=[raw_w[i] for i in range(i0,i1+1)]+[ema[i] for i in range(i0,i1+1)]+[GOAL]
    vmin=min(vals)-2;vmax=max(vals)+2
    s,px,py,m=frame(tmin,tmax,vmin,vmax)
    gy=py(GOAL);ml=m[0];mr=m[1];w=m[4]
    s.append(f'<line x1="{ml}" y1="{gy:.1f}" x2="{w-mr}" y2="{gy:.1f}" stroke="{C_GOAL}" stroke-dasharray="5 4"/>')
    s.append(f'<text x="{w-mr}" y="{gy-6:.1f}" text-anchor="end" font-size="11" fill="{C_GOAL}">goal {GOAL:.0f} lb</text>')
    for i in range(i0,i1+1):
        s.append(f'<circle cx="{px(i):.1f}" cy="{py(raw_w[i]):.1f}" r="2.4" fill="{C_RAW}"/>')
    pts=" ".join(f"{px(i):.1f},{py(ema[i]):.1f}" for i in range(i0,i1+1))
    s.append(f'<polyline points="{pts}" fill="none" stroke="{C_EMA}" stroke-width="3"/>')
    if predict:
        s.append(f'<line x1="{px(i1):.1f}" y1="{m[2]}" x2="{px(i1):.1f}" y2="{m[2]+m[7]}" stroke="#cfc8db" stroke-dasharray="3 3"/>')
        s.append(f'<text x="{px(i1):.1f}" y="{m[2]+12}" text-anchor="middle" font-size="10" fill="#8a7fb0">now</text>')
        for col,lbl,d in ((C_PGOAL,"goal pace",d_goal),(C_PCUR,"current pace",d_cur)):
            if not d: continue
            tg=i1+abs(d)
            s.append(f'<line x1="{px(i1):.1f}" y1="{py(cur_ema):.1f}" x2="{px(tg):.1f}" y2="{py(GOAL):.1f}" stroke="{col}" stroke-width="2.4" stroke-dasharray="7 5"/>')
            s.append(f'<circle cx="{px(tg):.1f}" cy="{py(GOAL):.1f}" r="4" fill="{col}"/>')
            yoff=-10 if col==C_PGOAL else 16
            s.append(f'<text x="{px(tg):.1f}" y="{py(GOAL)+yoff:.1f}" text-anchor="middle" font-size="11" font-weight="600" fill="{col}">{lbl}: {dnum_to_date(tg).strftime("%b %d, %Y")}</text>')
    s.append('</svg>');return "".join(s)

def expend_chart(i0,i1):
    n=i1-i0+1
    vmin=min(min(intake[i0:i1+1]),min(tdee[i0:i1+1]))-150
    vmax=max(max(intake[i0:i1+1]),max(tdee[i0:i1+1]))+150
    s,px,py,m=frame(i0,i1,vmin,vmax); bw=max(1.5,(m[6]/max(n,1))*0.7); base=m[2]+m[7]
    for i in range(i0,i1+1):
        x=px(i);y=py(intake[i])
        s.append(f'<rect x="{x-bw/2:.1f}" y="{y:.1f}" width="{bw:.1f}" height="{base-y:.1f}" fill="{C_BAR}" opacity="0.85"/>')
        if intake[i]<tdee[i]:
            yt=py(tdee[i]); s.append(f'<rect x="{x-bw/2:.1f}" y="{yt:.1f}" width="{bw:.1f}" height="{y-yt:.1f}" fill="{C_DEF}" opacity="0.7"/>')
    pts=" ".join(f"{px(i):.1f},{py(tdee[i]):.1f}" for i in range(i0,i1+1))
    s.append(f'<polyline points="{pts}" fill="none" stroke="{C_TDEE}" stroke-width="3"/>')
    s.append('</svg>');return "".join(s)

def donut(i0,i1,today_view=False):
    if today_view: ap,af,ac,cap=today_p,today_f,today_c,"Today"
    else:
        idx=[i for i in range(i0,i1+1) if logged[i]]; nc=len(idx); nd=i1-i0+1
        ap=round(sum(mp[i] for i in idx)/max(nc,1)); af=round(sum(mf[i] for i in idx)/max(nc,1)); ac=round(sum(mc[i] for i in idx)/max(nc,1))
        cap=f"avg/day · {nc} of {nd} days logged"
    cal=ap*4+af*9+ac*4; size=300;cx=cy=150;r=95;sw=34;Cc=2*math.pi*r
    pk=ap*4;fk=af*9;ck=ac*4;tot=max(pk+fk+ck,1)
    s=[f'<svg viewBox="0 0 {size} {size}" xmlns="http://www.w3.org/2000/svg" style="width:280px;height:280px">',
       f'<circle cx="{cx}" cy="{cy}" r="{r}" fill="none" stroke="#eee7f3" stroke-width="{sw}"/>']
    off=0
    for val,col in ((pk,C_PROT),(fk,C_FAT),(ck,C_CARB)):
        seg=val/tot*Cc
        s.append(f'<circle cx="{cx}" cy="{cy}" r="{r}" fill="none" stroke="{col}" stroke-width="{sw}" stroke-dasharray="{seg:.1f} {Cc-seg:.1f}" stroke-dashoffset="{-off:.1f}" transform="rotate(-90 {cx} {cy})"/>')
        off+=seg
    s.append(f'<text x="{cx}" y="{cy-4}" text-anchor="middle" font-size="28" font-weight="600" fill="#2b2433">{cal}</text>')
    s.append(f'<text x="{cx}" y="{cy+17}" text-anchor="middle" font-size="12" fill="{C_AX}">{"kcal so far" if today_view else "avg kcal/day"}</text></svg>')
    def bar(l,c,t,col):
        return (f'<div class="mb"><div class="mlbl"><span>{l}</span><span>{c} / {t} g</span></div>'
                f'<div class="mtrack"><div class="mfill" style="width:{min(100,c/t*100):.0f}%;background:{col}"></div></div></div>')
    return "".join(s),bar("Protein",ap,PT,C_PROT)+bar("Fat",af,FT,C_FAT)+bar("Carbs",ac,CT,C_CARB),cap

WINDOWS=[("1mo",30),("3mo",90),("6mo",180),("1yr",365),("all",N)]
def win_idx(d): return max(0,N-d),N-1
trend_variants={};exp_variants={};donut_variants={}
for key,d in WINDOWS:
    i0,i1=win_idx(d)
    trend_variants[key]=trend_chart(i0,i1,False)
    trend_variants[key+"_pred"]=trend_chart(i0,i1,True)   # NEW: per-range prediction variant
    exp_variants[key]=expend_chart(i0,i1)
donut_variants["today"]=donut(0,0,True)
for key,d in WINDOWS:
    i0,i1=win_idx(d); donut_variants[key]=donut(i0,i1)

def pills(group,opts,active,fn="sel"):
    h=[f'<div class="pills" data-g="{group}">']
    for val,lbl in opts:
        h.append(f'<button class="{"pill active" if val==active else "pill"}" onclick="{fn}(\'{group}\',\'{val}\')" data-v="{val}">{lbl}</button>')
    return "".join(h)+"</div>"
def stack(group,variants):
    return "".join(f'<div class="variant" data-g="{group}" data-v="{k}">{v}</div>' for k,v in variants.items())

dwin=dnum_to_date(N-1+abs(d_goal)).strftime("%b %d, %Y"); dwinc=dnum_to_date(N-1+abs(d_cur)).strftime("%b %d, %Y")

html=f"""<!doctype html><html><head><meta charset="utf-8"><title>Project TDEE — Chart Designs v3</title>
<style>
 body{{font-family:-apple-system,Segoe UI,Roboto,sans-serif;max-width:940px;margin:0 auto;padding:26px 22px 90px;color:#241f2e;line-height:1.55;background:#fff}}
 h1{{font-size:26px;margin:0 0 4px}} h2{{font-size:20px;margin:34px 0 6px}} .sub{{color:#6b6478;margin:0 0 22px}}
 .card{{background:#fff;border:1px solid #ece7f3;border-radius:14px;padding:16px;margin:14px 0;box-shadow:0 1px 3px rgba(80,60,120,.05)}}
 .ctrl{{display:flex;align-items:center;gap:10px;flex-wrap:wrap;margin:2px 0 12px}}
 .pills{{display:flex;gap:7px;flex-wrap:wrap}}
 .pill{{border:1px solid #ddd5e8;background:#fff;color:#5a5368;padding:5px 13px;border-radius:20px;font-size:13px;cursor:pointer}}
 .pill.active{{background:#6750a4;border-color:#6750a4;color:#fff}}
 .sep{{width:1px;height:22px;background:#e2dcec}}
 .toggle{{border-color:#cdbef0}} .toggle.active{{background:#6750a4;border-color:#6750a4;color:#fff}}
 .variant{{display:none}} .variant.show{{display:block}}
 .legend{{display:flex;gap:16px;flex-wrap:wrap;font-size:13px;color:#4a4458;margin:10px 2px 0}}
 .key{{display:inline-flex;align-items:center;gap:6px}} .dot{{width:12px;height:12px;border-radius:3px}} .line{{width:18px;height:3px;border-radius:2px}} .dash{{width:18px;height:0;border-top:3px dashed currentColor}}
 .expl{{font-size:14.5px;color:#3b3547}} .expl b{{color:#241f2e}}
 .how{{background:#f7f4fb;border-radius:10px;padding:12px 14px;font-size:13.5px;color:#4a4458;margin-top:10px}}
 .donutwrap{{display:flex;gap:26px;align-items:center;flex-wrap:wrap}}
 .mb{{margin:9px 0}} .mlbl{{display:flex;justify-content:space-between;font-size:13px;color:#3b3547;margin-bottom:4px}}
 .mtrack{{height:9px;background:#eee7f3;border-radius:6px;overflow:hidden}} .mfill{{height:100%;border-radius:6px}}
 .cap{{font-size:12.5px;color:#6b6478;margin-top:8px}}
 .new{{display:inline-block;background:#ece5fb;color:#6750a4;font-size:11px;font-weight:600;padding:1px 7px;border-radius:10px;margin-left:6px}}
 code{{background:#f0ecf6;padding:1px 5px;border-radius:4px;font-size:12.5px}}
</style></head><body>
<h1>Project TDEE — Chart Designs <span class="new">v3</span></h1>
<p class="sub">Interactive, 1 year of example data through the real engine math. <b>Prediction is now an independent overlay</b> —
it keeps your selected range and only adds the forward projection.</p>

<h2>1 · Trend Graph</h2>
<div class="card">
 <div class="ctrl">
  {pills("trend",[("1mo","1 mo"),("3mo","3 mo"),("6mo","6 mo"),("1yr","1 yr"),("all","All")],"3mo","setTrendRange")}
  <span class="sep"></span>
  <button id="predToggle" class="pill toggle" onclick="togglePred()">🔮 Prediction</button>
 </div>
 {stack("trend",trend_variants)}
 <div class="legend">
  <span class="key"><span class="dot" style="background:{C_RAW}"></span>Raw weight</span>
  <span class="key"><span class="line" style="background:{C_EMA}"></span>14-day EMA</span>
  <span class="key"><span class="line" style="background:{C_GOAL}"></span>Goal</span>
  <span class="key" style="color:{C_PGOAL}"><span class="dash"></span>Goal-pace projection</span>
  <span class="key" style="color:{C_PCUR}"><span class="dash"></span>Current-pace projection</span>
 </div></div>
<p class="expl"><b>What it's for:</b> raw points vs the smoothed 14-day trend. The <b>Prediction</b> overlay keeps whatever
range you've selected and adds two dashed forward projections to the goal: <b>goal pace</b> (green) and your
<b>current measured pace</b> (blue), each dated.</p>
<div class="how"><b>How it works:</b> trend = <code>EMA, α=2/15</code>. Goal-pace uses your chosen rate (0.5 lb/wk → <b>{dwin}</b>);
current-pace uses your real recent slope (→ <b>{dwinc}</b>). Range pills set history shown; Prediction only changes the
forward part, not the range.</div>

<h2>2 · Expenditure Graph</h2>
<div class="card">
 <div class="ctrl">{pills("exp",[("1mo","1 mo"),("3mo","3 mo"),("6mo","6 mo"),("1yr","1 yr"),("all","All")],"3mo")}</div>
 {stack("exp",exp_variants)}
 <div class="legend">
  <span class="key"><span class="dot" style="background:{C_BAR}"></span>Calories eaten</span>
  <span class="key"><span class="line" style="background:{C_TDEE}"></span>Computed TDEE</span>
  <span class="key"><span class="dot" style="background:{C_DEF}"></span>Deficit (under TDEE)</span>
 </div></div>
<p class="expl"><b>What it's for:</b> intake bars vs the moving TDEE line; the gap is shaded green only when you're
<b>under</b> TDEE. Bars above the line are an obvious surplus.</p>
<div class="how"><b>How it works:</b> <code>TDEE = avg intake − (trend Δ × 3500/window)</code>, formula-seeded only for the first ~14 days.</div>

<h2>3 · Macro Donut <span class="new">window selector</span></h2>
<div class="card">
 <div class="ctrl">{pills("donut",[("today","Today"),("1mo","1 mo"),("3mo","3 mo"),("6mo","6 mo"),("1yr","1 yr"),("all","All")],"today")}</div>
 <div class="donutwrap">
  <div>{"".join(f'<div class="variant" data-g="donut" data-v="{k}"><div>{v[0]}</div><div class="cap">{v[2]}</div></div>' for k,v in donut_variants.items())}</div>
  <div style="flex:1;min-width:280px">{"".join(f'<div class="variant" data-g="donutbars" data-v="{k}">{v[1]}</div>' for k,v in donut_variants.items())}</div>
 </div>
 <div class="legend">
  <span class="key"><span class="dot" style="background:{C_PROT}"></span>Protein</span>
  <span class="key"><span class="dot" style="background:{C_FAT}"></span>Fat</span>
  <span class="key"><span class="dot" style="background:{C_CARB}"></span>Carbs</span>
 </div></div>
<p class="expl"><b>What it's for:</b> "Today" = live split + grams vs target; longer windows = your average day.</p>
<div class="how"><b>Missing entries handled:</b> long-window averages count <b>only complete logging days</b> (caption shows "N of M days logged"); unlogged days are excluded, never zero.</div>

<script>
let trendRange='3mo', predOn=false;
function showTrend(){{const v=trendRange+(predOn?'_pred':'');document.querySelectorAll('.variant[data-g="trend"]').forEach(e=>e.classList.toggle('show',e.dataset.v===v));}}
function setTrendRange(g,v){{trendRange=v;document.querySelectorAll('.pills[data-g="trend"] .pill').forEach(p=>p.classList.toggle('active',p.dataset.v===v));showTrend();}}
function togglePred(){{predOn=!predOn;document.getElementById('predToggle').classList.toggle('active',predOn);showTrend();}}
function sel(g,v){{document.querySelectorAll('.pills[data-g="'+g+'"] .pill').forEach(p=>p.classList.toggle('active',p.dataset.v===v));
 document.querySelectorAll('.variant[data-g="'+g+'"]').forEach(e=>e.classList.toggle('show',e.dataset.v===v));
 if(g==='donut') document.querySelectorAll('.variant[data-g="donutbars"]').forEach(e=>e.classList.toggle('show',e.dataset.v===v));}}
showTrend(); sel('exp','3mo'); sel('donut','today');
document.querySelectorAll('.pills[data-g="trend"] .pill').forEach(p=>p.classList.toggle('active',p.dataset.v==='3mo'));
</script>
</body></html>"""
open("/home/ldstrohl/tdee/design/charts.html","w").write(html)
print("v3 written",len(html),"bytes; prediction is now an independent overlay keeping the range")
