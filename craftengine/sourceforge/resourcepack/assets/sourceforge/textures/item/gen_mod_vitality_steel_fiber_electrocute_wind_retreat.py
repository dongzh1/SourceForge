"""补画4张workflow批次里失败/掉线的MOD卡：vitality(活力)/steel_fiber(钢化纤维)/electrocute(电刑)/
wind_retreat(疾风撤退)。64x64，用共享 mod_card_base.new_card() 画框+内圈面板，图标手绘在内圈。
"""
import sys, os
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from mod_card_base import new_card

HEART = (230, 60, 70, 255)
HEART_HI = (255, 140, 140, 255)
GLOW = (255, 205, 70, 120)

STEEL_HI = (225, 230, 235, 255)
STEEL_MID = (170, 178, 186, 255)
STEEL_LO = (105, 112, 120, 255)

BOLT_CORE = (255, 250, 200, 255)
BOLT_MID = (255, 220, 70, 255)
SPARK = (255, 255, 255, 255)

WIND = (200, 170, 255, 255)
WIND_HI = (235, 220, 255, 255)


def draw_vitality():
    img, d, (x0, y0, x1, y1) = new_card()
    cx, cy = (x0 + x1) // 2, (y0 + y1) // 2
    for r in (20, 16):
        d.ellipse([cx - r, cy - r, cx + r, cy + r], outline=GLOW, width=2)
    d.polygon([
        (cx, cy + 15), (cx - 16, cy - 2), (cx - 16, cy - 12), (cx - 8, cy - 18),
        (cx, cy - 12), (cx + 8, cy - 18), (cx + 16, cy - 12), (cx + 16, cy - 2),
    ], fill=HEART)
    d.polygon([(cx - 6, cy - 8), (cx - 2, cy - 8), (cx - 6, cy + 2), (cx - 1, cy + 2), (cx + 8, cy - 10), (cx + 2, cy - 10), (cx + 6, cy - 16)], fill=HEART_HI)
    img.save("mod_vitality.png")


def draw_steel_fiber():
    img, d, (x0, y0, x1, y1) = new_card()
    cx, cy = (x0 + x1) // 2, (y0 + y1) // 2
    step = 7
    for row in range(-3, 4):
        y = cy + row * step
        offset = step // 2 if row % 2 else 0
        for col in range(-4, 5):
            x = cx + col * step + offset
            if x0 + 4 < x < x1 - 4 and y0 + 4 < y < y1 - 4:
                d.ellipse([x - 3, y - 3, x + 3, y + 3], outline=STEEL_MID, width=2)
    d.ellipse([cx - 5, cy - 5, cx + 5, cy + 5], fill=STEEL_HI)
    d.line([(cx - 5, cy - 5), (cx + 5, cy + 5)], fill=STEEL_LO, width=1)
    img.save("mod_steel_fiber.png")


def draw_electrocute():
    img, d, (x0, y0, x1, y1) = new_card(border_rgb=(190, 60, 60))
    cx, cy = (x0 + x1) // 2, (y0 + y1) // 2
    bolt = [
        (cx - 2, cy - 20), (cx + 6, cy - 4), (cx - 2, cy - 2), (cx + 8, cy + 20),
        (cx - 6, cy + 2), (cx + 2, cy), (cx - 10, cy - 16),
    ]
    d.polygon(bolt, fill=BOLT_MID)
    inner = [(cx, cy - 16), (cx + 3, cy - 4), (cx - 1, cy - 2), (cx + 4, cy + 12), (cx - 2, cy), (cx - 6, cy - 12)]
    d.polygon(inner, fill=BOLT_CORE)
    for (sx, sy) in [(cx - 14, cy - 12), (cx + 14, cy - 6), (cx - 12, cy + 14), (cx + 12, cy + 16)]:
        d.line([(sx - 2, sy), (sx + 2, sy)], fill=SPARK, width=1)
        d.line([(sx, sy - 2), (sx, sy + 2)], fill=SPARK, width=1)
    img.save("mod_electrocute.png")


def draw_wind_retreat():
    img, d, (x0, y0, x1, y1) = new_card(border_rgb=(150, 90, 220))
    cx, cy = (x0 + x1) // 2, (y0 + y1) // 2
    for i, r in enumerate((18, 13, 8)):
        bbox = [cx - r, cy - r, cx + r, cy + r]
        d.arc(bbox, start=40, end=200, fill=WIND_HI if i == 2 else WIND, width=3)
    d.polygon([(cx - 18, cy), (cx - 10, cy - 5), (cx - 10, cy + 5)], fill=WIND_HI)
    for (fx, fy) in [(cx + 6, cy + 14), (cx + 14, cy + 18)]:
        d.line([(fx - 4, fy), (fx + 4, fy - 3)], fill=WIND, width=2)
    img.save("mod_wind_retreat.png")


draw_vitality()
draw_steel_fiber()
draw_electrocute()
draw_wind_retreat()
print("done: 4 icons")
