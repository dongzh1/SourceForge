"""MOD卡·不朽之握(grasp_of_the_undying) 64x64 图标生成脚本。
基石天赋(keystone)，type-label=基石天赋，坚决系(绿) -> border_rgb=(80,150,90)。
构图：一只由藤蔓缠绕的拳头(抓握姿态)，掌心一颗跳动的翠绿生命核心(不朽之意)，
拳头四周伸出几根卷曲藤须，呼应 item: minecraft:vine 与"握"字的抓取动作。
"""
import sys, os
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from mod_card_base import new_card

BORDER_RGB = (80, 150, 90)

VINE_DARK = (40, 90, 45, 255)
VINE_MID = (70, 140, 70, 255)
VINE_LIGHT = (120, 200, 110, 255)
FIST_SHADOW = (55, 40, 30, 255)
FIST_MID = (95, 70, 50, 255)
FIST_HI = (140, 105, 75, 255)
CORE_OUTER = (60, 200, 90, 255)
CORE_INNER = (200, 255, 190, 255)

img, d, (x0, y0, x1, y1) = new_card(border_rgb=BORDER_RGB)
cx, cy = (x0 + x1) // 2, (y0 + y1) // 2

# ---- 拳头主体（圆润多边形，微微向下压表示抓握） ----
fist = [
    (cx - 13, cy - 6),
    (cx - 15, cy + 4),
    (cx - 10, cy + 13),
    (cx - 2, cy + 17),
    (cx + 8, cy + 15),
    (cx + 14, cy + 7),
    (cx + 14, cy - 4),
    (cx + 8, cy - 11),
    (cx - 2, cy - 14),
    (cx - 10, cy - 12),
]
d.polygon(fist, fill=FIST_MID, outline=FIST_SHADOW)
# 高光块
d.polygon([(cx - 9, cy - 10), (cx + 2, cy - 12), (cx - 2, cy - 3), (cx - 12, cy - 2)], fill=FIST_HI)

# 指关节纹路
for k in range(3):
    kx = cx - 6 + k * 7
    d.line([(kx, cy - 9), (kx - 2, cy + 9)], fill=FIST_SHADOW, width=1)

# ---- 藤蔓缠绕拳头（几段弧线 + 小叶片） ----
def vine_arc(points, color, width=2):
    d.line(points, fill=color, width=width, joint="curve")

vine_arc([(cx - 16, cy - 2), (cx - 6, cy - 15), (cx + 8, cy - 16), (cx + 17, cy - 4)], VINE_MID, 2)
vine_arc([(cx - 17, cy + 6), (cx - 4, cy + 19), (cx + 10, cy + 18), (cx + 18, cy + 5)], VINE_DARK, 2)

leaf_pts = [
    (cx - 12, cy - 13), (cx + 3, cy - 18), (cx + 14, cy - 8),
    (cx - 14, cy + 10), (cx + 2, cy + 20), (cx + 15, cy + 9),
]
for (lx, ly) in leaf_pts:
    d.polygon([(lx, ly - 3), (lx + 4, ly), (lx, ly + 3), (lx - 4, ly)], fill=VINE_LIGHT, outline=VINE_DARK)

# 伸出的卷曲藤须（左右各一，末端小螺旋暗示"不朽"生生不息）
def curl(x, y, side):
    s = 1 if side == "r" else -1
    d.line([(x, y), (x + s * 6, y - 4), (x + s * 9, y + 2), (x + s * 7, y + 7)], fill=VINE_MID, width=2)
    ex0, ex1 = sorted([x + s * 6, x + s * 10])
    d.ellipse([ex0, y + 4, ex1, y + 8], outline=VINE_DARK, width=1)

curl(cx - 15, cy + 12, "l")
curl(cx + 15, cy - 12, "r")

# ---- 掌心生命核心（发光的绿色小球，代表不朽/复生之力） ----
r_outer = 5
d.ellipse([cx - r_outer, cy - r_outer + 1, cx + r_outer, cy + r_outer + 1], fill=CORE_OUTER, outline=VINE_DARK)
d.ellipse([cx - 2, cy - 2, cx + 2, cy + 2], fill=CORE_INNER)

img.save(os.path.join(os.path.dirname(os.path.abspath(__file__)), "mod_grasp_of_the_undying.png"))
print("saved mod_grasp_of_the_undying.png border=", BORDER_RGB)
