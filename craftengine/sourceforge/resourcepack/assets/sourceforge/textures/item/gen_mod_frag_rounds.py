"""MOD卡·破片弹(frag_rounds) 图标生成脚本。tags=stat，画框用默认暗金(border_rgb=None)。
构图：中心一枚深灰弹壳(炮弹/榴弹造型)炸开，向外飞散若干橙红三角形弹片，
外圈几道爆炸冲击线，体现"溅射/破片"主题(关联词条 splash_power)。
"""
import sys, os, math
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from mod_card_base import new_card

img, d, (x0, y0, x1, y1) = new_card(border_rgb=None)

cx = (x0 + x1) / 2
cy = (y0 + y1) / 2 + 1

SHELL = (70, 68, 66, 255)
SHELL_HI = (110, 108, 104, 255)
SHELL_LO = (40, 38, 36, 255)
FRAG_HI = (255, 150, 70, 255)
FRAG_MID = (220, 100, 40, 255)
FRAG_LO = (140, 55, 20, 255)
SPARK = (255, 210, 120, 255)

# 中心弹壳：一个竖直椭圆+尖头，代表炮弹/榴弹本体
shell_w = 7
shell_top = cy - 10
shell_bot = cy + 6
d.ellipse([cx - shell_w, shell_top, cx + shell_w, shell_bot], fill=SHELL, outline=SHELL_LO)
d.polygon([(cx - shell_w + 1, shell_top + 3), (cx, shell_top - 6), (cx + shell_w - 1, shell_top + 3)],
          fill=SHELL_HI, outline=SHELL_LO)
d.line([(cx - 3, shell_top + 6), (cx + 3, shell_top + 6)], fill=SHELL_HI, width=1)
d.line([(cx - 3, cy - 1), (cx + 3, cy - 1)], fill=SHELL_LO, width=1)

# 裂开的破片：围绕弹壳下半部炸开的三角碎片，呈放射状
frag_defs = [
    (-1.0, 26), (-0.55, 22), (-0.15, 20), (0.25, 21), (0.65, 24), (1.05, 27),
]
for i, (ang_off, dist) in enumerate(frag_defs):
    ang = math.radians(70) + ang_off * 0.9
    bx = cx + math.cos(ang) * (dist - 10)
    by = cy + math.sin(ang) * (dist - 10) * 0.7 + 4
    tx = cx + math.cos(ang) * dist
    ty = cy + math.sin(ang) * dist * 0.75 + 6
    perp = ang + math.pi / 2
    w = 3.2
    p1 = (bx + math.cos(perp) * w, by + math.sin(perp) * w)
    p2 = (bx - math.cos(perp) * w, by - math.sin(perp) * w)
    fill = FRAG_HI if i % 2 == 0 else FRAG_MID
    d.polygon([p1, p2, (tx, ty)], fill=fill, outline=FRAG_LO)

# 冲击/爆炸线：几条从中心向外的短线，强化"炸裂"感
for ang_deg in (200, 230, 340, 10, 160):
    ang = math.radians(ang_deg)
    sx = cx + math.cos(ang) * 9
    sy = cy + math.sin(ang) * 7
    ex = cx + math.cos(ang) * 18
    ey = cy + math.sin(ang) * 15
    d.line([(sx, sy), (ex, ey)], fill=SPARK, width=1)

# 中心闪光点
d.ellipse([cx - 2, cy + 1, cx + 2, cy + 5], fill=SPARK)

out = os.path.join(os.path.dirname(os.path.abspath(__file__)), "mod_frag_rounds.png")
img.save(out)
print("saved", out)
