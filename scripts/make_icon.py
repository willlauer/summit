"""Render Summit's icon in the Ice Wire look: a neon-tube mountain peak (electric blue glow, ice-white core) under a
four-point glint, inside a cut-corner frame like the app's buttons, with light rain over a near-black blue night.

Writes the macOS icon (src/summit/assets/icon.png, 1024 px, used by `summit bundle` and the Dock; Big Sur proportions:
an 824 px rounded square with room around it), and the Android launcher layers (android/app/src/main/res/
mipmap-xxxhdpi): the adaptive foreground and a monochrome silhouette for themed icons. Drawn 4x large and scaled down,
so the tubes stay smooth.

Run with: uv run --with pillow scripts/make_icon.py
"""

from pathlib import Path

from PIL import Image, ImageChops, ImageDraw, ImageFilter

ROOT = Path(__file__).resolve().parent.parent
SS = 4  # supersampling
BG_TOP, BG_BOTTOM = (10, 22, 52), (3, 6, 15)
SIGN, TUBE, WHITE, AQUA = (47, 123, 255), (216, 246, 255), (255, 255, 255), (111, 255, 224)


def gradient(size: int) -> Image.Image:
    im = Image.new("RGB", (size, size))
    d = ImageDraw.Draw(im)
    for y in range(size):
        t = y / (size - 1)
        d.line([(0, y), (size, y)], fill=tuple(round(a + (b - a) * t) for a, b in zip(BG_TOP, BG_BOTTOM)))
    # street-light haze rising from the bottom
    haze = Image.new("RGB", (size, size))
    ImageDraw.Draw(haze).ellipse([-size * .3, size * .62, size * 1.3, size * 1.6], fill=(18, 52, 130))
    return ImageChops.add(im, haze.filter(ImageFilter.GaussianBlur(size * .12)))


def octagon(box, cut):
    x0, y0, x1, y1 = box
    return [(x0 + cut, y0), (x1 - cut, y0), (x1, y0 + cut), (x1, y1 - cut), (x1 - cut, y1), (x0 + cut, y1), (x0, y1 - cut), (x0, y0 + cut)]


def shapes(s: float):
    """The drawing in a unit square scaled to s: peak polyline, inner ridge, frame octagon, glint centre and size."""
    k = lambda pts: [(x * s, y * s) for x, y in pts]
    peak = k([(.17, .74), (.40, .40), (.50, .50), (.60, .30), (.83, .74)])
    ridge = k([(.53, .41), (.60, .30), (.67, .41), (.62, .38), (.60, .42), (.57, .38), (.53, .41)])
    frame = octagon((.11 * s, .11 * s, .89 * s, .89 * s), .09 * s)
    return peak, ridge, frame, (.60 * s, .17 * s), .07 * s


def tubes(size: int, rain: bool = True, frame_on: bool = True) -> Image.Image:
    """The neon on transparent: a wide blue glow, a tighter glow, then the white-hot core."""
    peak, ridge, frame, (gx, gy), gr = shapes(size)
    out = Image.new("RGBA", (size, size), (0, 0, 0, 0))

    def layer(width, colour, blur, alpha=255):
        im = Image.new("RGBA", (size, size), (0, 0, 0, 0))
        d = ImageDraw.Draw(im)
        d.line(peak, fill=colour + (alpha,), width=round(width), joint="curve")
        if frame_on:
            d.line(frame + frame[:1], fill=colour + (round(alpha * .6),), width=round(width * .5), joint="curve")
        d.polygon(ridge, fill=colour + (round(alpha * .8),))
        # the glint: a four-point star
        w = gr * .22
        d.polygon([(gx, gy - gr), (gx + w, gy - w), (gx + gr, gy), (gx + w, gy + w), (gx, gy + gr), (gx - w, gy + w), (gx - gr, gy), (gx - w, gy - w)],
                  fill=colour + (alpha,))
        return im.filter(ImageFilter.GaussianBlur(blur)) if blur else im

    for width, colour, blur, alpha in ((size * .09, SIGN, size * .06, 255), (size * .06, SIGN, size * .03, 255),
                                       (size * .034, SIGN, size * .01, 255), (size * .016, TUBE, size * .003, 255),
                                       (size * .007, WHITE, 0, 255)):
        out = Image.alpha_composite(out, layer(width, colour, blur, alpha))
    # chromatic split on the peak, as on the app's titles
    split = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    ImageDraw.Draw(split).line([(x - size * .006, y) for x, y in peak], fill=AQUA + (70,), width=round(size * .006))
    out = Image.alpha_composite(split, out)
    if rain:
        r = Image.new("RGBA", (size, size), (0, 0, 0, 0))
        d = ImageDraw.Draw(r)
        for i, (x, y, ln) in enumerate([(.20, .12, .10), (.33, .52, .08), (.72, .55, .09), (.86, .20, .07), (.45, .78, .06), (.27, .30, .05), (.78, .80, .07)]):
            d.line([(x * size, y * size), ((x - ln * .18) * size, (y + ln) * size)], fill=(200, 215, 255, 60 if i % 2 else 90), width=max(1, round(size * .003)))
        out = Image.alpha_composite(r, out)
    return out


def icon(size: int) -> Image.Image:
    big = size * SS
    im = gradient(big).convert("RGBA")
    im = Image.alpha_composite(im, tubes(big))
    return im.resize((size, size), Image.LANCZOS)


# macOS: an 824 px rounded square centred in 1024, with a soft shadow
art = icon(824)
mask = Image.new("L", (824 * SS, 824 * SS), 0)
ImageDraw.Draw(mask).rounded_rectangle([0, 0, 824 * SS - 1, 824 * SS - 1], radius=185 * SS, fill=255)
art.putalpha(mask.resize((824, 824), Image.LANCZOS))
mac = Image.new("RGBA", (1024, 1024), (0, 0, 0, 0))
shadow = Image.new("RGBA", (1024, 1024), (0, 0, 0, 0))
ImageDraw.Draw(shadow).rounded_rectangle([100, 112, 924, 936], radius=185, fill=(0, 0, 0, 110))
mac = Image.alpha_composite(mac, shadow.filter(ImageFilter.GaussianBlur(14)))
mac.alpha_composite(art, (100, 100))
out = ROOT / "src/summit/assets/icon.png"
mac.save(out)
print(out)

# Android adaptive icon, 432 px (108 dp at xxxhdpi): launchers show the middle ~72 dp in their own shape, so the art
# sits in the middle 300 px and the night carries on to the edges.
res = ROOT / "android/app/src/main/res/mipmap-xxxhdpi"
res.mkdir(parents=True, exist_ok=True)
big = 432 * SS
fg = gradient(big).convert("RGBA")
inner = tubes(330 * SS, frame_on=False)
fg.alpha_composite(inner, ((big - inner.width) // 2, (big - inner.height) // 2))
fg.resize((432, 432), Image.LANCZOS).save(res / "ic_launcher_foreground.png")
# Themed icons: Android tints this alpha. The peak, ridge and glint, no frame or rain.
peak, ridge, _, (gx, gy), gr = shapes(330 * SS)
m = Image.new("L", (330 * SS, 330 * SS), 0)
d = ImageDraw.Draw(m)
d.line(peak, fill=255, width=round(330 * SS * .05), joint="curve")
d.polygon(ridge, fill=255)
w = gr * .22
d.polygon([(gx, gy - gr), (gx + w, gy - w), (gx + gr, gy), (gx + w, gy + w), (gx, gy + gr), (gx - w, gy + w), (gx - gr, gy), (gx - w, gy - w)], fill=255)
mono = Image.new("RGBA", (432, 432), (0, 0, 0, 0))
white = Image.new("RGBA", (330, 330), (255, 255, 255, 255))
white.putalpha(m.resize((330, 330), Image.LANCZOS))
mono.alpha_composite(white, (51, 51))
mono.save(res / "ic_launcher_monochrome.png")
print(res)
