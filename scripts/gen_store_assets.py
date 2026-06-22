from PIL import Image, ImageDraw, ImageFont
import os

BASE = "C:/BlitzBank_android_app/play-release/do-in-place"
for d in ["graphics", "screenshots/phone", "screenshots/tablet7", "screenshots/tablet10"]:
    os.makedirs(f"{BASE}/{d}", exist_ok=True)

PRIMARY      = (37,  99, 235)
PRIMARY_DARK = (29,  78, 216)
SURFACE      = (248, 250, 252)
CARD         = (255, 255, 255)
TEXT_PRI     = (15,  23,  42)
TEXT_SEC     = (100, 116, 139)
ACCENT       = (16, 185, 129)
ORANGE       = (249, 115,  22)
WHITE        = (255, 255, 255)

def fnt(size):
    try:
        return ImageFont.truetype("C:/Windows/Fonts/segoeui.ttf", size)
    except:
        return ImageFont.load_default()

def rnd_rect(draw, x0, y0, x1, y1, r, fill):
    draw.rectangle([x0+r, y0, x1-r, y1], fill=fill)
    draw.rectangle([x0, y0+r, x1, y1-r], fill=fill)
    draw.ellipse([x0, y0, x0+2*r, y0+2*r], fill=fill)
    draw.ellipse([x1-2*r, y0, x1, y0+2*r], fill=fill)
    draw.ellipse([x0, y1-2*r, x0+2*r, y1], fill=fill)
    draw.ellipse([x1-2*r, y1-2*r, x1, y1], fill=fill)

def status_bar(d, W):
    d.rectangle([0, 0, W, 44], fill=PRIMARY_DARK)
    d.text((W-110, 12), "9:41  |||", font=fnt(16), fill=WHITE)

def top_bar(d, W, title):
    d.rectangle([0, 44, W, 104], fill=PRIMARY)
    d.text((24, 56), title, font=fnt(28), fill=WHITE)

def task_row(d, x, y, w, title, sub, dot_col, checked=False):
    rnd_rect(d, x, y, x+w, y+68, 10, CARD)
    cc = ACCENT if checked else dot_col
    d.ellipse([x+14, y+18, x+36, y+40], fill=cc)
    if checked:
        d.line([(x+19, y+29), (x+25, y+35)], fill=WHITE, width=3)
        d.line([(x+25, y+35), (x+33, y+23)], fill=WHITE, width=3)
    tc = TEXT_SEC if checked else TEXT_PRI
    d.text((x+48, y+10), title, font=fnt(18), fill=tc)
    d.text((x+48, y+34), sub,   font=fnt(13), fill=TEXT_SEC)

# ── ICON 512x512 ───────────────────────────────────────────────────────────────
W = H = 512
img = Image.new("RGBA", (W, H), (0, 0, 0, 0))
d = ImageDraw.Draw(img)
rnd_rect(d, 0, 0, W, H, 96, PRIMARY)

# pin body
cx, cy = W//2, H//2 + 50
pr = 100
d.ellipse([cx-pr, cy-2*pr, cx+pr, cy], fill=WHITE)
d.polygon([cx-pr, cy-pr, cx+pr, cy-pr, cx, cy+60], fill=WHITE)
d.ellipse([cx-pr//3, cy-pr-pr//3, cx+pr//3, cy-pr+pr//3], fill=PRIMARY)

img.save(f"{BASE}/graphics/icon-512.png")
print("icon done")

# ── FEATURE GRAPHIC 1024x500 ──────────────────────────────────────────────────
W, H = 1024, 500
img = Image.new("RGB", (W, H), PRIMARY_DARK)
d = ImageDraw.Draw(img)
for x in range(W):
    t = x / W
    r = int(PRIMARY_DARK[0]*(1-t) + PRIMARY[0]*t)
    g = int(PRIMARY_DARK[1]*(1-t) + PRIMARY[1]*t)
    b = int(PRIMARY_DARK[2]*(1-t) + PRIMARY[2]*t)
    d.line([(x, 0), (x, H)], fill=(r, g, b))

d.text((60, 90),  "Do In Place",                        font=fnt(62), fill=WHITE)
d.text((62, 170), "Reminders that find you",            font=fnt(28), fill=(*WHITE, 200))
d.text((62, 206), "at the right place, right time.",    font=fnt(28), fill=(*WHITE, 200))

pills = ["Place reminders", "Shared lists", "Date tasks", "Real-time sync"]
icons = ["📍", "🛒", "📅", "🔗"]
for i, (icon, txt) in enumerate(zip(icons, pills)):
    px = 62 + (i % 2) * 280
    py = 280 + (i // 2) * 52
    rnd_rect(d, px, py, px+258, py+38, 19, (*WHITE, 35))
    d.text((px+12, py+7), f"{icon}  {txt}", font=fnt(20), fill=WHITE)

# mini phone card
rnd_rect(d, 720, 50, 900, 430, 14, (20, 55, 180))
rnd_rect(d, 728, 70, 892, 420, 10, SURFACE)
for i, col in enumerate([ORANGE, PRIMARY, ACCENT, (139, 92, 246)]):
    ry = 82 + i*82
    rnd_rect(d, 738, ry, 882, ry+66, 6, CARD)
    d.ellipse([750, ry+16, 768, ry+34], fill=col)
    d.rectangle([778, ry+18, 860, ry+24], fill=(*TEXT_SEC, 160))
    d.rectangle([778, ry+32, 840, ry+36], fill=(*TEXT_SEC, 80))

img.save(f"{BASE}/graphics/feature-graphic-1024x500.png")
print("feature graphic done")

# ── PHONE SCREENSHOTS 1080x1920 ───────────────────────────────────────────────
def phone_home(W, H):
    img = Image.new("RGB", (W, H), SURFACE)
    d = ImageDraw.Draw(img)
    status_bar(d, W)
    top_bar(d, W, "Do In Place")
    pad = 16
    cw = W - 2*pad
    tasks = [
        ("Pharmacy pickup",    "Expires today • 0.3 km",   ORANGE,             False),
        ("Supermarket",        "Shared with Anna • 1.2 km", PRIMARY,            False),
        ("Post office",        "Send parcel • tomorrow",    ACCENT,             False),
        ("Home tasks",         "Clean kitchen",             (139, 92, 246),     False),
        ("Pick up glasses",    "Optics shop",               PRIMARY_DARK,       False),
    ]
    y = 120
    for title, sub, col, checked in tasks:
        task_row(d, pad, y, cw, title, sub, col, checked)
        y += 80
    d.ellipse([W-72, H-116, W-20, H-64], fill=PRIMARY)
    d.text((W-56, H-104), "+", font=fnt(32), fill=WHITE)
    d.rectangle([0, H-64, W, H], fill=CARD)
    for i, (icon, lbl) in enumerate([("Home", "Home"), ("Tasks", "Tasks"), ("Settings", "Settings")]):
        nx = i * (W // 3) + W//6
        col = PRIMARY if i == 0 else TEXT_SEC
        d.text((nx - 20, H-56), lbl, font=fnt(16), fill=col)
    return img

def phone_shopping(W, H):
    img = Image.new("RGB", (W, H), SURFACE)
    d = ImageDraw.Draw(img)
    status_bar(d, W)
    top_bar(d, W, "Supermarket")
    pad = 16
    cw = W - 2*pad
    rnd_rect(d, pad, 112, pad+cw, 154, 8, (209, 250, 229))
    d.text((pad+14, 124), "Shared with Anna — updates in real time", font=fnt(16), fill=(6, 95, 70))
    items = [
        ("Milk",          "Checked by Anna", True),
        ("Bread",         "",                True),
        ("Eggs",          "",                False),
        ("Butter",        "",                False),
        ("Yogurt",        "",                False),
        ("Orange juice",  "",                False),
        ("Cheese",        "",                False),
    ]
    y = 166
    for txt, sub, checked in items:
        rnd_rect(d, pad, y, pad+cw, y+62, 8, CARD)
        cc = ACCENT if checked else (203, 213, 225)
        d.ellipse([pad+12, y+14, pad+36, y+38], fill=cc)
        if checked:
            d.line([(pad+18, y+26), (pad+24, y+32)], fill=WHITE, width=3)
            d.line([(pad+24, y+32), (pad+32, y+21)], fill=WHITE, width=3)
        tc = TEXT_SEC if checked else TEXT_PRI
        d.text((pad+48, y+8),  txt, font=fnt(18), fill=tc)
        if sub:
            d.text((pad+48, y+32), sub, font=fnt(13), fill=ACCENT)
        y += 70
    return img

def phone_create(W, H):
    img = Image.new("RGB", (W, H), SURFACE)
    d = ImageDraw.Draw(img)
    status_bar(d, W)
    top_bar(d, W, "New Task")
    pad = 16
    cw = W - 2*pad
    fields = [
        ("Task title",      "Pick up medication",    PRIMARY),
        ("Place",           "Green Pharmacy",         ACCENT),
        ("Reminder radius", "300 metres",             PRIMARY),
        ("Task type",       "Shopping list",          (139, 92, 246)),
        ("Repeat",          "None",                   TEXT_SEC),
    ]
    y = 120
    for label, val, col in fields:
        d.text((pad, y), label, font=fnt(15), fill=TEXT_SEC)
        y += 24
        rnd_rect(d, pad, y, pad+cw, y+52, 8, CARD)
        d.text((pad+14, y+14), val, font=fnt(18), fill=TEXT_PRI)
        d.rectangle([pad+2, y+50, pad+cw-2, y+52], fill=col)
        y += 64
    rnd_rect(d, pad, y+12, pad+cw, y+60, 12, PRIMARY)
    d.text((W//2-36, y+24), "Save Task", font=fnt(22), fill=WHITE)
    return img

def phone_connections(W, H):
    img = Image.new("RGB", (W, H), SURFACE)
    d = ImageDraw.Draw(img)
    status_bar(d, W)
    top_bar(d, W, "Connections")
    pad = 16
    cw = W - 2*pad
    y = 116
    d.text((pad, y), "Accepted connections", font=fnt(15), fill=TEXT_SEC)
    y += 28
    contacts = [
        ("Anna M.",  "anna@example.com",  ACCENT),
        ("David K.", "david@example.com", PRIMARY),
        ("Sara L.",  "sara@example.com",  ORANGE),
    ]
    for name, email, col in contacts:
        rnd_rect(d, pad, y, pad+cw, y+68, 8, CARD)
        d.ellipse([pad+12, y+12, pad+44, y+44], fill=col)
        d.text((pad+22, y+18), name[0], font=fnt(18), fill=WHITE)
        d.text((pad+56, y+10), name,  font=fnt(18), fill=TEXT_PRI)
        d.text((pad+56, y+34), email, font=fnt(13), fill=TEXT_SEC)
        rnd_rect(d, pad+cw-88, y+20, pad+cw-8, y+46, 10, (219, 234, 254))
        d.text((pad+cw-80, y+25), "Share", font=fnt(14), fill=PRIMARY)
        y += 76
    return img

for i, fn in enumerate([phone_home, phone_shopping, phone_create, phone_connections], 1):
    fn(1080, 1920).save(f"{BASE}/screenshots/phone/phone-{i:02d}.png")
    print(f"phone {i} done")

# ── 7-INCH TABLET 1200x1920 ───────────────────────────────────────────────────
for i, fn in enumerate([phone_home, phone_shopping, phone_create, phone_connections], 1):
    fn(1200, 1920).save(f"{BASE}/screenshots/tablet7/tablet7-{i:02d}.png")
    print(f"tablet7 {i} done")

# ── 10-INCH TABLET LANDSCAPE 1920x1200 ────────────────────────────────────────
def tablet10_home():
    W, H = 1920, 1200
    img = Image.new("RGB", (W, H), SURFACE)
    d = ImageDraw.Draw(img)
    d.rectangle([0, 0, 260, H], fill=PRIMARY_DARK)
    d.text((20, 30), "Do In Place", font=fnt(24), fill=WHITE)
    nav = [("Home", True), ("Tasks", False), ("Shared", False), ("Settings", False)]
    for i, (lbl, active) in enumerate(nav):
        ny = 100 + i*72
        if active:
            d.rectangle([0, ny, 260, ny+56], fill=PRIMARY)
        d.text((24, ny+16), lbl, font=fnt(18), fill=WHITE)

    status_bar(d, W)
    d.rectangle([260, 44, W, 104], fill=PRIMARY)
    d.text((284, 56), "All Tasks", font=fnt(32), fill=WHITE)

    pad = 16
    col_w = (W - 260 - 3*pad) // 2
    left = [
        ("Pharmacy pickup",    "0.3 km Today",           ORANGE),
        ("Post office",        "Send parcel",             PRIMARY),
        ("Supermarket",        "Shared with Anna",        ACCENT),
    ]
    right = [
        ("Home tasks",         "Clean kitchen",           (139, 92, 246)),
        ("Pick up glasses",    "Optics",                  PRIMARY_DARK),
        ("Doctor appointment", "Thursday",                ORANGE),
    ]
    for j, (title, sub, col) in enumerate(left):
        task_row(d, 260+pad, 120+j*84, col_w, title, sub, col)
    for j, (title, sub, col) in enumerate(right):
        task_row(d, 260+2*pad+col_w, 120+j*84, col_w, title, sub, col)
    return img

def tablet10_shopping():
    W, H = 1920, 1200
    img = Image.new("RGB", (W, H), SURFACE)
    d = ImageDraw.Draw(img)
    d.rectangle([0, 0, 260, H], fill=PRIMARY_DARK)
    d.text((20, 30), "Do In Place", font=fnt(24), fill=WHITE)
    nav = [("Home", False), ("Tasks", False), ("Shared", True), ("Settings", False)]
    for i, (lbl, active) in enumerate(nav):
        ny = 100 + i*72
        if active:
            d.rectangle([0, ny, 260, ny+56], fill=PRIMARY)
        d.text((24, ny+16), lbl, font=fnt(18), fill=WHITE)
    status_bar(d, W)
    d.rectangle([260, 44, W, 104], fill=PRIMARY)
    d.text((284, 56), "Supermarket", font=fnt(32), fill=WHITE)
    rnd_rect(d, 276, 112, W-16, 152, 8, (209, 250, 229))
    d.text((292, 124), "Shared with Anna — items update in real time", font=fnt(18), fill=(6, 95, 70))

    pad = 16
    col_w = (W - 260 - 3*pad) // 2
    items = [
        ("Milk",          "Checked by Anna", True),
        ("Bread",         "",                True),
        ("Eggs",          "",                False),
        ("Butter",        "",                False),
        ("Yogurt",        "",                False),
        ("Orange juice",  "",                False),
    ]
    for j, (txt, sub, checked) in enumerate(items):
        xi = 260+pad if j < 3 else 260+2*pad+col_w
        yi = 166 + (j % 3)*74
        rnd_rect(d, xi, yi, xi+col_w, yi+62, 8, CARD)
        cc = ACCENT if checked else (203, 213, 225)
        d.ellipse([xi+12, yi+14, xi+36, yi+38], fill=cc)
        if checked:
            d.line([(xi+18, yi+26), (xi+24, yi+32)], fill=WHITE, width=3)
            d.line([(xi+24, yi+32), (xi+32, yi+21)], fill=WHITE, width=3)
        tc = TEXT_SEC if checked else TEXT_PRI
        d.text((xi+48, yi+8),  txt, font=fnt(20), fill=tc)
        if sub:
            d.text((xi+48, yi+32), sub, font=fnt(15), fill=ACCENT)
    return img

tablet10_home().save(f"{BASE}/screenshots/tablet10/tablet10-01.png")
print("tablet10-01 done")
tablet10_shopping().save(f"{BASE}/screenshots/tablet10/tablet10-02.png")
print("tablet10-02 done")

print("\nAll Play Store assets generated.")
