#!/usr/bin/env python3
# Genera l'icona app DoorPhone (citofono video) per tutte le densita' + adaptive icon.
import os
from PIL import Image, ImageDraw

RES = "app/src/main/res"
SS = 4  # supersampling per bordi morbidi

# Palette
TOP   = (56, 132, 255)   # blu chiaro
BOT   = (22, 70, 170)    # blu profondo
WHITE = (255, 255, 255)
LENS  = (24, 52, 96)     # navy obiettivo
GREEN = (38, 200, 110)   # LED connesso
RING  = (206, 216, 230)  # ghiera obiettivo
SHAD  = (10, 30, 70)     # ombra pannello

def lerp(a, b, t):
    return tuple(int(round(a[i] + (b[i]-a[i])*t)) for i in range(3))

def make_gradient(w, h, top, bottom):
    g = Image.new("RGB", (1, h))
    for y in range(h):
        g.putpixel((0, y), lerp(top, bottom, y/max(1, h-1)))
    return g.resize((w, h))

def draw_foreground(img, S, content_frac):
    d = ImageDraw.Draw(img)
    ph = content_frac * S
    pw = ph * 0.60
    cx = S/2.0
    cy = S/2.0
    x0, y0, x1, y1 = cx-pw/2, cy-ph/2, cx+pw/2, cy+ph/2
    rad = pw*0.26

    # ombra morbida sotto il pannello
    sh = Image.new("RGBA", img.size, (0,0,0,0))
    sd = ImageDraw.Draw(sh)
    off = ph*0.035
    sd.rounded_rectangle([x0, y0+off, x1, y1+off], radius=rad, fill=SHAD+(90,))
    try:
        from PIL import ImageFilter
        sh = sh.filter(ImageFilter.GaussianBlur(radius=S*0.012))
    except Exception:
        pass
    img.alpha_composite(sh)

    # pannello citofono
    d.rounded_rectangle([x0, y0, x1, y1], radius=rad, fill=WHITE)

    # obiettivo camera
    lcx, lcy = cx, y0 + ph*0.30
    lr = pw*0.27
    d.ellipse([lcx-lr, lcy-lr, lcx+lr, lcy+lr], fill=RING)
    lr2 = lr*0.74
    d.ellipse([lcx-lr2, lcy-lr2, lcx+lr2, lcy+lr2], fill=LENS)
    gl = lr2*0.30
    gx, gy = lcx - lr2*0.42, lcy - lr2*0.42
    d.ellipse([gx-gl, gy-gl, gx+gl, gy+gl], fill=WHITE)

    # LED verde "connesso"
    sr = pw*0.075
    sx, sy = cx, y0 + ph*0.555
    d.ellipse([sx-sr, sy-sr, sx+sr, sy+sr], fill=GREEN)

    # griglia altoparlante (3 barrette)
    gw = pw*0.46
    gh = ph*0.028
    gap = ph*0.072
    gyy = y0 + ph*0.70
    for i in range(3):
        yy = gyy + i*gap
        d.rounded_rectangle([cx-gw/2, yy-gh/2, cx+gw/2, yy+gh/2], radius=gh/2, fill=LENS)

def render_full(S, rounded=True):
    big = S*SS
    base = Image.new("RGBA", (big, big), (0,0,0,0))
    grad = make_gradient(big, big, TOP, BOT).convert("RGBA")
    mask = Image.new("L", (big, big), 0)
    if rounded:
        ImageDraw.Draw(mask).rounded_rectangle([0,0,big-1,big-1], radius=big*0.22, fill=255)
    else:
        mask = Image.new("L", (big, big), 255)
    base.paste(grad, (0,0), mask)
    draw_foreground(base, big, 0.60)
    return base.resize((S, S), Image.LANCZOS)

def render_round(S):
    big = S*SS
    base = Image.new("RGBA", (big, big), (0,0,0,0))
    grad = make_gradient(big, big, TOP, BOT).convert("RGBA")
    mask = Image.new("L", (big, big), 0)
    ImageDraw.Draw(mask).ellipse([0,0,big-1,big-1], fill=255)
    base.paste(grad, (0,0), mask)
    draw_foreground(base, big, 0.56)
    return base.resize((S, S), Image.LANCZOS)

def render_fg(S):
    big = S*SS
    base = Image.new("RGBA", (big, big), (0,0,0,0))
    draw_foreground(base, big, 0.46)
    return base.resize((S, S), Image.LANCZOS)

def render_bg(S):
    big = S*SS
    grad = make_gradient(big, big, TOP, BOT).convert("RGBA")
    return grad.resize((S, S), Image.LANCZOS)

dens   = {"mdpi":48, "hdpi":72, "xhdpi":96, "xxhdpi":144, "xxxhdpi":192}
fgdens = {"mdpi":108,"hdpi":162,"xhdpi":216,"xxhdpi":324,"xxxhdpi":432}

import sys
if "--preview" in sys.argv:
    out = sys.argv[sys.argv.index("--preview")+1]
    render_full(256).save(out)
    render_round(256).save(out.replace(".png","_round.png"))
    print("preview salvato:", out)
    sys.exit(0)

for d, s in dens.items():
    folder = os.path.join(RES, "mipmap-"+d)
    os.makedirs(folder, exist_ok=True)
    render_full(s).save(os.path.join(folder, "ic_launcher.png"))
    render_round(s).save(os.path.join(folder, "ic_launcher_round.png"))
    fs = fgdens[d]
    render_fg(fs).save(os.path.join(folder, "ic_launcher_foreground.png"))
    render_bg(fs).save(os.path.join(folder, "ic_launcher_background.png"))
    print("ok", d, s)

# adaptive icon xml (API 26+)
adir = os.path.join(RES, "mipmap-anydpi-v26")
os.makedirs(adir, exist_ok=True)
xml = ('<?xml version="1.0" encoding="utf-8"?>\n'
       '<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">\n'
       '    <background android:drawable="@mipmap/ic_launcher_background"/>\n'
       '    <foreground android:drawable="@mipmap/ic_launcher_foreground"/>\n'
       '</adaptive-icon>\n')
open(os.path.join(adir, "ic_launcher.xml"), "w").write(xml)
open(os.path.join(adir, "ic_launcher_round.xml"), "w").write(xml)
print("adaptive xml ok")
print("DONE")
