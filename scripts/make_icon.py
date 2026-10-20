"""Render Summit's icon: pixel art on a 32×32 grid, in the app's ice palette. A snowy peak under a bright star, with
wind streaks blowing across (the same night sky, wind and constellation glints as the map).

Writes the macOS icon (src/summit/assets/icon.png, 1024 px, used by `summit bundle` and the Dock), and the Android
launcher layers (android/app/src/main/res/mipmap-xxxhdpi): the adaptive foreground and a monochrome silhouette for
themed icons. Every grid cell scales by a whole number of pixels, so edges stay hard.

Run with: uv run --with pillow scripts/make_icon.py
"""

from pathlib import Path

from PIL import Image, ImageDraw

ROOT = Path(__file__).resolve().parent.parent
N = 32
H = lambda s: tuple(int(s[i:i + 2], 16) for i in (1, 3, 5))
SKY_TOP, SKY = H("#0B1B30"), H("#050B14")
OUT, SNOW, LIT, ICE, DEEP, GROUND = H("#02060C"), H("#F2FBFF"), H("#BFEFFF"), H("#7FDBFF"), H("#2F8FD8"), H("#16283F")
AQUA, STAR = H("#7AF2DC"), H("#E3F6FF")


def put(g, x, y, c):
    if 0 <= x < N and 0 <= y < N:
        g[y][x] = c


def draw() -> list[list]:
    """The scene as a grid of colours (None = empty, for the silhouette)."""
    g = [[SKY_TOP if y < 13 or (y == 13 and x % 2 == 0) else SKY for x in range(N)] for y in range(N)]
    # the peak: lit face left, shadow face right, a ridge line, a snow cap with a jagged edge, a dark outline
    px, py, base, left, right, cap = 16, 11, 28, 1, 30, 6
    for y in range(py, base + 1):
        t = (y - py) / (base - py)
        xl, xr = round(px - (px - left) * t), round(px + (right - px) * t)
        for x in range(xl, xr + 1):
            c = ICE if x == px else LIT if x < px else DEEP
            if y - py < cap and abs(x - px) <= (y - py) * 0.9:
                c = SNOW if x <= px else LIT
            put(g, x, y, c)
        put(g, xl - 1, y, OUT); put(g, xr + 1, y, OUT)
    put(g, px, py - 1, OUT)
    for k, x in enumerate(range(px - 6, px + 7)):
        y = py + cap + (k % 3 == 0) - (k % 4 == 1)
        put(g, x, y, SNOW if x <= px else LIT)
    for y in range(base + 1, N):
        for x in range(N):
            put(g, x, y, GROUND)
    # the star over the summit: a pixel glint
    sx, sy = 16, 5
    put(g, sx, sy, SNOW)
    for d in (1, -1):
        put(g, sx + d, sy, STAR); put(g, sx, sy + d, STAR)
        put(g, sx + 2 * d, sy, AQUA); put(g, sx, sy + 2 * d, AQUA)
    # wind: streaks with a bright head
    for y, (x0, x1) in ((14, (2, 9)), (17, (22, 30)), (21, (0, 6))):
        for x in range(x0, x1 + 1):
            put(g, x, y, STAR)
        put(g, x0, y, ICE)
    put(g, 6, 3, STAR); put(g, 26, 6, STAR); put(g, 29, 2, AQUA)
    return g


def image(g, cell: int, mask: bool) -> Image.Image:
    im = Image.new("RGBA", (N * cell, N * cell))
    d = ImageDraw.Draw(im)
    for y in range(N):
        for x in range(N):
            d.rectangle([x * cell, y * cell, (x + 1) * cell - 1, (y + 1) * cell - 1], fill=g[y][x])
    if mask:  # a rounded square, stepped on the same pixel grid
        m = Image.new("L", (N * 8, N * 8), 0)
        ImageDraw.Draw(m).rounded_rectangle([0, 0, N * 8 - 1, N * 8 - 1], radius=int(N * 8 * .22), fill=255)
        im.putalpha(m.resize((N, N), Image.NEAREST).resize(im.size, Image.NEAREST))
    return im


def silhouette(g, cell: int) -> Image.Image:
    """Themed icons: Android tints this alpha. The peak, the star and the wind; no sky."""
    keep = {OUT, SNOW, LIT, ICE, DEEP, STAR, AQUA}
    im = Image.new("RGBA", (N * cell, N * cell), (0, 0, 0, 0))
    d = ImageDraw.Draw(im)
    for y in range(N - 3):
        for x in range(N):
            if g[y][x] in keep and g[y][x] != OUT:
                d.rectangle([x * cell, y * cell, (x + 1) * cell - 1, (y + 1) * cell - 1], fill=(255, 255, 255, 255))
    return im


g = draw()
mac = image(g, 32, mask=True)                       # 32 px per cell → 1024
out = ROOT / "src/summit/assets/icon.png"
mac.save(out)
print(out)

# Android adaptive icon, 432 px (108 dp at xxxhdpi): the launcher shows the middle 72 dp and applies its own shape.
# The scene fills that middle at 9 px per cell (288 px); the rest is sky, so any launcher crop still looks right.
res = ROOT / "android/app/src/main/res/mipmap-xxxhdpi"
res.mkdir(parents=True, exist_ok=True)
# The 32-cell scene sits in a 48-cell canvas (9 px per cell); sky and ground carry on to the edges, so there's no seam.
wide = [[SKY_TOP if y < 8 + 13 or (y == 8 + 13 and x % 2 == 0) else GROUND if y > 8 + 28 else SKY for x in range(48)] for y in range(48)]
for y in range(N):
    for x in range(N):
        wide[y + 8][x + 8] = g[y][x]
for y in range(8 + 14, 8 + 29):  # the wind and the sky either side of the peak continue outwards
    for x in list(range(8)) + list(range(40, 48)):
        wide[y][x] = SKY
for y, (x0, x1) in ((8 + 17, (39, 47)), (8 + 21, (0, 7))):
    for x in range(x0, x1 + 1):
        wide[y][x] = STAR
fg = Image.new("RGBA", (432, 432))
fd = ImageDraw.Draw(fg)
for y in range(48):
    for x in range(48):
        fd.rectangle([x * 9, y * 9, x * 9 + 8, y * 9 + 8], fill=wide[y][x])
fg.save(res / "ic_launcher_foreground.png")
mono = Image.new("RGBA", (432, 432), (0, 0, 0, 0))
mono.paste(silhouette(g, 9), (72, 72))
mono.save(res / "ic_launcher_monochrome.png")
print(res)
