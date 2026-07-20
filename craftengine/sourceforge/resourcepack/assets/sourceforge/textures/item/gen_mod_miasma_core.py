"""MOD卡·瘴毒核心(miasma_core) 图标生成脚本。64x64，共享暗色画框(mod_card_base)+本卡专属图标。
tags: stat,elemental；effects: cold_damage,toxin_damage,status_chance —— 冰+毒双元素混合卡，
画框用"混合多元素"紫色 (150,90,200)。图标构图：左半冰霜六芒雪花(冷色)，右半一滴剧毒(绿色)，
两者在中央交融叠加，象征"同一张卡同时点燃冰霜与剧毒两种元素反应(viral)"。
"""
import sys, os
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from mod_card_base import new_card
import math

img, d, (x0, y0, x1, y1) = new_card(border_rgb=(150, 90, 200))

cx, cy = (x0 + x1) / 2, (y0 + y1) / 2
r_out = (x1 - x0) / 2 * 0.85

FROST_HI = (190, 230, 255, 255)
FROST_MID = (110, 190, 235, 255)
FROST_LO = (60, 120, 170, 255)
TOXIN_HI = (170, 230, 110, 255)
TOXIN_MID = (110, 180, 70, 255)
TOXIN_LO = (60, 110, 40, 255)
MIX_GLOW = (170, 130, 210, 200)

# 中央交融光晕(先画在最底层，被两侧图形部分遮盖)
d.ellipse([cx - 8, cy - 8, cx + 8, cy + 8], fill=MIX_GLOW)

# 左半：冰霜六芒雪花，偏左上方
sx, sy = cx - 6, cy - 3
spike = r_out * 0.62
for i in range(6):
    ang = math.radians(60 * i - 90)
    ex = sx + math.cos(ang) * spike
    ey = sy + math.sin(ang) * spike
    d.line([sx, sy, ex, ey], fill=FROST_MID, width=3)
    # 小分支
    bang1 = ang + math.radians(35)
    bang2 = ang - math.radians(35)
    bx, by = sx + math.cos(ang) * spike * 0.55, sy + math.sin(ang) * spike * 0.55
    d.line([bx, by, bx + math.cos(bang1) * 5, by + math.sin(bang1) * 5], fill=FROST_LO, width=2)
    d.line([bx, by, bx + math.cos(bang2) * 5, by + math.sin(bang2) * 5], fill=FROST_LO, width=2)
d.ellipse([sx - 3, sy - 3, sx + 3, sy + 3], fill=FROST_HI)

# 右半：毒滴，偏右下方
tx, ty = cx + 7, cy + 6
drop_w = r_out * 0.5
drop_h = r_out * 0.75
d.polygon([
    (tx, ty - drop_h * 0.9),
    (tx + drop_w * 0.55, ty + drop_h * 0.15),
    (tx + drop_w * 0.32, ty + drop_h * 0.55),
    (tx, ty + drop_h * 0.68),
    (tx - drop_w * 0.32, ty + drop_h * 0.55),
    (tx - drop_w * 0.55, ty + drop_h * 0.15),
], fill=TOXIN_MID, outline=TOXIN_LO)
d.ellipse([tx - drop_w * 0.16, ty - drop_h * 0.35, tx + drop_w * 0.02, ty - drop_h * 0.1], fill=TOXIN_HI)

img.save(os.path.join(os.path.dirname(os.path.abspath(__file__)), "mod_miasma_core.png"))
print("saved mod_miasma_core.png")
