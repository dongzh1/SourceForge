"""MOD卡·暴戾无常(blind_rage) 图标生成脚本。64x64，一次性烤死背景+图标。
tags=stat（纯属性，非elemental/skill）-> border_rgb=None，用默认暗金画框。
effects: ability_strength, ability_efficiency -> 主题：狂暴之力，画一只紧握的拳头，
拳峰迸出锯齿状红色能量裂纹，呼应"暴戾无常"(狂躁易变的爆发力)。
"""
import sys, os
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from mod_card_base import new_card

RED_HI = (240, 90, 60, 255)
RED_MID = (200, 50, 30, 255)
RED_LO = (120, 25, 15, 255)
SKIN_HI = (200, 150, 110, 255)
SKIN_MID = (160, 110, 80, 255)
SKIN_LO = (100, 65, 45, 255)

img, d, (x0, y0, x1, y1) = new_card(border_rgb=None)
cx, cy = (x0 + x1) // 2, (y0 + y1) // 2 + 2

# 拳头主体：几个矩形/多边形叠出握拳轮廓
fist_w, fist_h = 22, 18
fx0, fy0 = cx - fist_w // 2, cy - fist_h // 2
d.rectangle([fx0, fy0, fx0 + fist_w, fy0 + fist_h], fill=SKIN_MID)
# 指节分段高光
for i in range(4):
    kx = fx0 + 2 + i * 5
    d.rectangle([kx, fy0, kx + 3, fy0 + 6], fill=SKIN_HI)
    d.rectangle([kx, fy0 + 6, kx + 3, fy0 + 7], fill=SKIN_LO)
# 拇指
d.polygon([(fx0 - 5, fy0 + 8), (fx0 + 2, fy0 + 4), (fx0 + 2, fy0 + 14), (fx0 - 5, fy0 + 16)], fill=SKIN_MID)
d.line([(fx0 - 5, fy0 + 8), (fx0 + 2, fy0 + 4)], fill=SKIN_HI, width=1)
# 手腕
d.rectangle([fx0 + 3, fy0 + fist_h, fx0 + fist_w - 3, fy0 + fist_h + 6], fill=SKIN_LO)
# 暗部描边
d.rectangle([fx0, fy0, fx0 + fist_w, fy0 + fist_h], outline=SKIN_LO, width=1)

# 锯齿状能量裂纹从拳头四周炸开（模拟"暴戾"爆发）
import math
bolts = [
    [(cx - 2, fy0), (cx - 6, fy0 - 8), (cx - 2, fy0 - 10), (cx - 9, fy0 - 20)],
    [(cx + 6, fy0), (cx + 11, fy0 - 9), (cx + 6, fy0 - 10), (cx + 14, fy0 - 18)],
    [(fx0, cy - 2), (fx0 - 9, cy - 6), (fx0 - 6, cy - 1), (fx0 - 16, cy + 3)],
    [(fx0 + fist_w, cy - 2), (fx0 + fist_w + 9, cy - 7), (fx0 + fist_w + 5, cy - 1), (fx0 + fist_w + 15, cy + 4)],
]
for pts in bolts:
    d.line(pts, fill=RED_HI, width=2, joint="curve")
    d.line(pts, fill=RED_MID, width=1)

# 拳峰上一道小裂纹强调"力量迸发"
d.line([(cx - 3, fy0 + 3), (cx, fy0 + 8), (cx - 2, fy0 + 12)], fill=RED_LO, width=1)

# 底部两个小火花点缀效率/持续感
d.ellipse([cx - fist_w // 2 - 3, cy + 10, cx - fist_w // 2 + 1, cy + 14], fill=RED_MID)
d.ellipse([cx + fist_w // 2 - 1, cy + 12, cx + fist_w // 2 + 3, cy + 16], fill=RED_HI)

out = os.path.join(os.path.dirname(os.path.abspath(__file__)), "mod_blind_rage.png")
img.save(out)
print("saved", out)
