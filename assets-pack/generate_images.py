"""
Do In Place — store asset generator v2
Renders at 2x / 4x internally then downsamples for crisp edges.
Outputs:
  icon.png          512 × 512   (Google Play icon)
  icon@2x.png      1024 × 1024  (larger export)
  feature-graphic.png  1024 × 500
"""
from PIL import Image, ImageDraw, ImageFont, ImageFilter
import math, os

OUT = os.path.dirname(os.path.abspath(__file__))

# ── Palette ──────────────────────────────────────────────────
BLUE_1   = (29,  78, 216)    # #1D4ED8  deep blue (gradient top)
BLUE_2   = (17,  24, 96)     # #111860  near-navy (gradient bottom)
BLUE_MID = (37,  99, 235)    # #2563EB  accent
WHITE    = (255, 255, 255)
TEAL     = (16,  185, 129)   # #10B981
SLATE    = (148, 163, 184)   # #94A3B8
NAVY     = (10,  22,  40)    # #0A1628
NAVY2    = (15,  37,  87)    # #0F2557
GRID     = (30,  58,  95)    # #1E3A5F


def vert_grad(img, x0, y0, x1, y1, top, bot):
    d = ImageDraw.Draw(img)
    h = y1 - y0 or 1
    for y in range(y0, y1):
        t = (y - y0) / h
        r = int(top[0] + (bot[0]-top[0])*t)
        g = int(top[1] + (bot[1]-top[1])*t)
        b = int(top[2] + (bot[2]-top[2])*t)
        d.line([(x0, y), (x1-1, y)], fill=(r, g, b))


def pin_polygon(cx, cy, head_r, tail_y, n=300):
    """
    Map-pin teardrop: circle head + two bezier sides converging to a point.
    Returns a flat list of (x,y) tuples.
    """
    pts = []
    # Left half of circle: angle π → 0  (top → right)
    for i in range(n//2 + 1):
        a = math.pi * (1 - i/(n//2))
        pts.append((cx + head_r*math.cos(a), cy + head_r*math.sin(a)))
    # Right side: quadratic bezier from (cx+head_r, cy) → (cx, tail_y)
    ctrl = (cx + head_r * 0.55, tail_y * 0.72 + cy * 0.28)
    for i in range(1, n//4 + 1):
        t = i / (n//4)
        p0 = (cx + head_r, cy)
        p1 = ctrl
        p2 = (cx, tail_y)
        x = (1-t)**2*p0[0] + 2*t*(1-t)*p1[0] + t**2*p2[0]
        y_ = (1-t)**2*p0[1] + 2*t*(1-t)*p1[1] + t**2*p2[1]
        pts.append((x, y_))
    # Left side: mirror bezier
    ctrl_l = (cx - head_r * 0.55, tail_y * 0.72 + cy * 0.28)
    for i in range(1, n//4 + 1):
        t = i / (n//4)
        p0 = (cx, tail_y)
        p1 = ctrl_l
        p2 = (cx - head_r, cy)
        x = (1-t)**2*p0[0] + 2*t*(1-t)*p1[0] + t**2*p2[0]
        y_ = (1-t)**2*p0[1] + 2*t*(1-t)*p1[1] + t**2*p2[1]
        pts.append((x, y_))
    return [(int(round(x)), int(round(y))) for x, y in pts]


# ══════════════════════════════════════════════════════════
# ICON  —  render at 2048×2048, downsample to 512×512
# ══════════════════════════════════════════════════════════
SCALE = 4          # 4× internal resolution → downsample to 512
SZ    = 512 * SCALE   # 2048

icon_hi = Image.new("RGB", (SZ, SZ))
vert_grad(icon_hi, 0, 0, SZ, SZ, BLUE_1, BLUE_2)
d = ImageDraw.Draw(icon_hi)

cx, cy   = SZ // 2, int(SZ * 0.41)
head_r   = int(SZ * 0.235)   # ~480 px at 2048
tail_y   = int(SZ * 0.855)

# ── Drop shadow
shadow_poly = pin_polygon(cx+18, cy+20, head_r, tail_y+20)
d.polygon(shadow_poly, fill=(10, 20, 80))

# ── White pin body
pin_poly = pin_polygon(cx, cy, head_r, tail_y)
d.polygon(pin_poly, fill=WHITE)

# ── Subtle inner sheen (top-left lighter area on the pin)
sheen_r = int(head_r * 0.62)
sheen_cx = cx - int(head_r * 0.18)
sheen_cy = cy - int(head_r * 0.18)
sheen = Image.new("RGBA", (SZ, SZ), (0, 0, 0, 0))
sd = ImageDraw.Draw(sheen)
sd.ellipse([sheen_cx-sheen_r, sheen_cy-sheen_r,
            sheen_cx+sheen_r, sheen_cy+sheen_r],
           fill=(255, 255, 255, 28))
icon_hi.paste(Image.alpha_composite(icon_hi.convert("RGBA"), sheen).convert("RGB"))
d = ImageDraw.Draw(icon_hi)

# ── Deep blue inner ring
ring_r = int(head_r * 0.52)
d.ellipse([cx-ring_r, cy-ring_r, cx+ring_r, cy+ring_r], fill=BLUE_MID)

# ── Bright inner ring accent (thin white ring)
accent_r = int(head_r * 0.42)
d.ellipse([cx-accent_r, cy-accent_r, cx+accent_r, cy+accent_r],
          outline=WHITE, width=int(SZ*0.012))

# ── Small white dot at centre (classic map pin style)
dot_r = int(head_r * 0.14)
d.ellipse([cx-dot_r, cy-dot_r, cx+dot_r, cy+dot_r], fill=WHITE)

# Downsample to 512×512
icon_512 = icon_hi.resize((512, 512), Image.LANCZOS)
icon_512.save(os.path.join(OUT, "icon.png"), dpi=(300, 300))
print("icon.png  512x512 saved")

icon_hi.resize((1024, 1024), Image.LANCZOS).save(
    os.path.join(OUT, "icon@2x.png"), dpi=(300, 300))
print("icon@2x.png  1024x1024 saved")


# ══════════════════════════════════════════════════════════
# FEATURE GRAPHIC  —  render at 2× (2048×1000), downsample
# ══════════════════════════════════════════════════════════
FW, FH = 1024, 500
FS = 2   # internal scale
fw, fh = FW*FS, FH*FS

feat = Image.new("RGB", (fw, fh))
vert_grad(feat, 0, 0, fw, fh, NAVY, NAVY2)
d = ImageDraw.Draw(feat)

# Subtle grid on left half only
gstep = 240
for gy in range(0, fh, gstep):
    d.line([(0, gy), (fw//2, gy)], fill=GRID, width=1)
for gx in range(0, fw//2, gstep):
    d.line([(gx, 0), (gx, fh)], fill=GRID, width=1)

# Radial glow behind pin
glow = Image.new("RGBA", (fw, fh), (0,0,0,0))
gd = ImageDraw.Draw(glow)
gcx, gcy = int(fw*0.235), int(fh*0.47)
for gr in range(360, 0, -3):
    a = max(0, int(60 * (1 - gr/360)))
    gd.ellipse([gcx-gr, gcy-int(gr*0.9), gcx+gr, gcy+int(gr*0.9)],
               fill=(*BLUE_MID, a))
feat_rgba = Image.alpha_composite(feat.convert("RGBA"), glow).convert("RGB")
feat = feat_rgba
d = ImageDraw.Draw(feat)

# Pin
pcx, pcy = gcx, int(fh * 0.38)
phead_r  = int(fh * 0.285)
ptail_y  = int(fh * 0.90)

shadow_p2 = pin_polygon(pcx+12, pcy+14, phead_r, ptail_y+14)
d.polygon(shadow_p2, fill=(8, 16, 60))
pin_p2 = pin_polygon(pcx, pcy, phead_r, ptail_y)
d.polygon(pin_p2, fill=WHITE)

# Inner ring
pr2 = int(phead_r * 0.52)
d.ellipse([pcx-pr2, pcy-pr2, pcx+pr2, pcy+pr2], fill=BLUE_MID)
pa2 = int(phead_r * 0.42)
d.ellipse([pcx-pa2, pcy-pa2, pcx+pa2, pcy+pa2], outline=WHITE, width=int(fh*0.013))
pd2 = int(phead_r * 0.14)
d.ellipse([pcx-pd2, pcy-pd2, pcx+pd2, pcy+pd2], fill=WHITE)

# Ripple rings below the pin tip
for rr, ro in [(28, 90), (54, 55), (80, 28)]:
    d.ellipse([pcx-rr, ptail_y-rr//4, pcx+rr, ptail_y+rr//4],
              outline=(*BLUE_MID, ro) if False else (60, 120, 220), width=2)

# Vertical divider
d.line([(fw//2 + 10, fh//8), (fw//2 + 10, fh*7//8)], fill=GRID, width=2)

# ── Text
tx = fw//2 + 60   # text start x

try:
    fnt_title  = ImageFont.truetype("C:/Windows/Fonts/arialbd.ttf", 112)
    fnt_tag    = ImageFont.truetype("C:/Windows/Fonts/arial.ttf",    40)
    fnt_body   = ImageFont.truetype("C:/Windows/Fonts/arial.ttf",    34)
except Exception:
    fnt_title = fnt_tag = fnt_body = ImageFont.load_default()

# App name
d.text((tx, int(fh*0.27)), "Do In Place", font=fnt_title, fill=WHITE)

# Tagline
d.text((tx, int(fh*0.50)), "Reminders that trigger when you arrive",
       font=fnt_tag, fill=TEAL)

# Separator
d.line([(tx, int(fh*0.585)), (fw-80, int(fh*0.585))], fill=GRID, width=2)

# Bullets
bullets = [
    "Pin tasks to exact places — not time slots",
    "Shopping lists that learn your store layout",
    "Share tasks with family and contacts",
]
br = 9
for i, text in enumerate(bullets):
    by = int(fh*0.635) + i*86
    d.ellipse([tx+2, by+10, tx+2+br*2, by+10+br*2], fill=BLUE_MID)
    d.text((tx + br*2 + 18, by), text, font=fnt_body, fill=SLATE)

# Downsample
feat_out = feat.resize((FW, FH), Image.LANCZOS)
feat_out.save(os.path.join(OUT, "feature-graphic.png"), dpi=(150, 150))
print("feature-graphic.png  1024x500 saved")
