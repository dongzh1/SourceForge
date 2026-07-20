"""MOD卡·致命节奏(lethal_tempo) 图标生成脚本。64x64，共享 mod_card_base 画框(基石天赋-精密金)，
图标构图：一柄斜置的金色短剑 + 剑后三道由疏到密的弧形加速线(象征攻速节奏递增到致命一击)，
剑尖处一点亮白高光代表"致命"爆发。仿 gen_mod_hearth_whisper.py 手法，纯 PIL 几何绘制。
"""
import sys, os
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from mod_card_base import new_card

BORDER_RGB = (200, 170, 90)  # 基石天赋 · 精密金系(强攻/致命节奏/征服者)

img, d, (x0, y0, x1, y1) = new_card(border_rgb=BORDER_RGB)

GOLD_HI = (255, 224, 140, 255)
GOLD_MID = (222, 178, 80, 255)
GOLD_LO = (140, 100, 40, 255)
WHITE_SPARK = (255, 255, 255, 255)
ARC_COL = (230, 190, 110, 210)
ARC_COL2 = (230, 190, 110, 140)
ARC_COL3 = (230, 190, 110, 80)

cx, cy = (x0 + x1) / 2, (y0 + y1) / 2

# 三道加速弧线（从外到内，越靠近剑尖越密集短促，模拟节奏加快）
import math
def arc_points(center, radius, start_deg, end_deg, n=14):
    pts = []
    for i in range(n + 1):
        a = math.radians(start_deg + (end_deg - start_deg) * i / n)
        pts.append((center[0] + radius * math.cos(a), center[1] + radius * math.sin(a)))
    return pts

base = (cx - 6, cy - 4)
for radius, col, width in [(22, ARC_COL3, 2), (16, ARC_COL2, 2), (10, ARC_COL, 3)]:
    pts = arc_points(base, radius, 200, 320)
    d.line(pts, fill=col, width=width, joint="curve")

# 短剑主体：斜置菱形刀身，从左下到右上
blade_tail = (x0 + 6, y1 - 6)
blade_tip = (x1 - 6, y0 + 7)
dx, dy = blade_tip[0] - blade_tail[0], blade_tip[1] - blade_tail[1]
length = math.hypot(dx, dy)
ux, uy = dx / length, dy / length
px, py = -uy, ux  # 垂直方向

blade_w = 3.2
poly = [
    (blade_tail[0] + px * blade_w, blade_tail[1] + py * blade_w),
    (blade_tip[0] - ux * 3 + px * 1.3, blade_tip[1] - uy * 3 + py * 1.3),
    (blade_tip[0], blade_tip[1]),
    (blade_tip[0] - ux * 3 - px * 1.3, blade_tip[1] - uy * 3 - py * 1.3),
    (blade_tail[0] - px * blade_w, blade_tail[1] - py * blade_w),
]
d.polygon(poly, fill=GOLD_MID, outline=GOLD_HI)

# 刀身中线高光
mid1 = (blade_tail[0] + ux * 3, blade_tail[1] + uy * 3)
mid2 = (blade_tip[0] - ux * 4, blade_tip[1] - uy * 4)
d.line([mid1, mid2], fill=GOLD_HI, width=1)

# 护手 + 短柄
hilt_len = 5
hilt_end = (blade_tail[0] - ux * hilt_len, blade_tail[1] - uy * hilt_len)
d.line([blade_tail, hilt_end], fill=GOLD_LO, width=3)
guard_len = 5
d.line([
    (blade_tail[0] + px * guard_len, blade_tail[1] + py * guard_len),
    (blade_tail[0] - px * guard_len, blade_tail[1] - py * guard_len),
], fill=GOLD_HI, width=2)
d.ellipse([hilt_end[0] - 1.5, hilt_end[1] - 1.5, hilt_end[0] + 1.5, hilt_end[1] + 1.5], fill=GOLD_LO)

# 剑尖爆发高光（致命一击的闪光星）
sx, sy = blade_tip[0] + ux * 2, blade_tip[1] + uy * 2
d.line([(sx - 4, sy), (sx + 4, sy)], fill=WHITE_SPARK, width=1)
d.line([(sx, sy - 4), (sx, sy + 4)], fill=WHITE_SPARK, width=1)
d.ellipse([sx - 1.5, sy - 1.5, sx + 1.5, sy + 1.5], fill=WHITE_SPARK)

out_path = os.path.join(os.path.dirname(os.path.abspath(__file__)), "mod_lethal_tempo.png")
img.save(out_path)
print("saved", out_path)
