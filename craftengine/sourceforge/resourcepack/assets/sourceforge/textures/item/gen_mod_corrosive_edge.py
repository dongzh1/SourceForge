"""生成 MOD卡·腐蚀刃(corrosive_edge) 图标。tags=stat(status_chance)，纯属性卡 -> 默认暗金画框。
构图：一柄短剑刃身被酸液腐蚀出斑驳孔洞，刃面滴落绿色酸液滴，营造"提高异常状态触发几率"的
腐蚀主题。64x64，几十个 polygon/ellipse 调用手绘，不用照片级细节，仿 mod_hearth_whisper 手法。
"""
import sys, os
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from mod_card_base import new_card

img, d, (x0, y0, x1, y1) = new_card(border_rgb=None)

ACID_HI = (150, 220, 90, 255)
ACID_MID = (100, 180, 60, 255)
ACID_LO = (55, 110, 30, 255)
STEEL_HI = (210, 215, 220, 255)
STEEL_MID = (160, 165, 172, 255)
STEEL_LO = (95, 98, 105, 255)
HOLE = (30, 45, 20, 255)

cx = (x0 + x1) // 2

# 剑刃主体（竖直，从上到下），略微倾斜表现动态
blade = [
    (cx, y0 + 3),
    (cx + 6, y0 + 12),
    (cx + 5, y0 + 30),
    (cx + 3, y1 - 10),
    (cx, y1 - 4),
    (cx - 3, y1 - 10),
    (cx - 5, y0 + 30),
    (cx - 6, y0 + 12),
]
d.polygon(blade, fill=STEEL_MID, outline=STEEL_LO)
# 中线高光
d.line([(cx, y0 + 4), (cx, y1 - 6)], fill=STEEL_HI, width=1)

# 护手
d.rectangle([cx - 9, y1 - 12, cx + 9, y1 - 9], fill=STEEL_LO, outline=(40, 40, 45, 255))
# 剑柄
d.rectangle([cx - 2, y1 - 9, cx + 2, y1 - 2], fill=(90, 60, 40, 255), outline=(50, 32, 20, 255))

# 腐蚀孔洞（在刃身上挖几个不规则酸蚀孔）
for (hx, hy, r) in [(cx - 2, y0 + 16, 3), (cx + 2, y0 + 24, 2), (cx - 1, y0 + 33, 2)]:
    d.ellipse([hx - r, hy - r, hx + r, hy + r], fill=HOLE, outline=ACID_LO)

# 酸液附着与滴落
d.ellipse([cx + 4, y0 + 10, cx + 10, y0 + 16], fill=ACID_MID, outline=ACID_LO)
d.ellipse([cx - 9, y0 + 20, cx - 4, y0 + 25], fill=ACID_MID, outline=ACID_LO)

drip_x = cx + 7
drip_pts = [
    (drip_x - 2, y0 + 16),
    (drip_x + 2, y0 + 16),
    (drip_x + 2, y0 + 30),
    (drip_x, y0 + 35),
    (drip_x - 2, y0 + 30),
]
d.polygon(drip_pts, fill=ACID_MID, outline=ACID_LO)
d.ellipse([drip_x - 2, y0 + 33, drip_x + 2, y0 + 38], fill=ACID_HI, outline=ACID_LO)

# 冒泡的小气泡点缀高光
for (bx, by) in [(cx + 8, y0 + 12), (cx - 7, y0 + 21), (drip_x, y0 + 28)]:
    d.ellipse([bx - 1, by - 1, bx + 1, by + 1], fill=ACID_HI)

out = os.path.join(os.path.dirname(os.path.abspath(__file__)), "mod_corrosive_edge.png")
img.save(out)
print("saved", out)
