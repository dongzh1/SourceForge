"""生成 mod_frostbite.png（64x64，共享暗金画框内嵌冰蓝主题图标）。
寒霜(frostbite)：cold_damage + status_chance 元素词条卡。图标构图：中央一枚六芒雪花(冰蓝主色+
白色高光尖端)，右下角叠一颗小霜冻状态点(浅蓝小圆+短裂纹线)呼应 status_chance 的"附加异常"含义。
画框用 mod_card_base.new_card(border_rgb=(90,170,230)) 的冰蓝色系，与元素="冷"配色规则一致。
"""
import sys, os, math
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from mod_card_base import new_card

BORDER_RGB = (90, 170, 230)
ICE_HI = (225, 245, 255, 255)
ICE_MID = (140, 205, 245, 255)
ICE_LO = (60, 130, 190, 255)
FROST_DOT = (200, 235, 255, 255)

img, d, (x0, y0, x1, y1) = new_card(border_rgb=BORDER_RGB)

cx = (x0 + x1) / 2
cy = (y0 + y1) / 2 - 2
R = min(x1 - x0, y1 - y0) / 2 - 4

# 六芒雪花主干
for i in range(6):
    ang = math.radians(i * 60 - 90)
    ex = cx + R * math.cos(ang)
    ey = cy + R * math.sin(ang)
    d.line([cx, cy, ex, ey], fill=ICE_MID, width=3)
    # 分支小枝
    bx = cx + R * 0.6 * math.cos(ang)
    by = cy + R * 0.6 * math.sin(ang)
    for side in (-1, 1):
        bang = ang + side * math.radians(35)
        bx2 = bx + R * 0.28 * math.cos(bang)
        by2 = by + R * 0.28 * math.sin(bang)
        d.line([bx, by, bx2, by2], fill=ICE_LO, width=2)
    # 尖端高光
    d.ellipse([ex - 2, ey - 2, ex + 2, ey + 2], fill=ICE_HI)

# 中心核心
d.ellipse([cx - 4, cy - 4, cx + 4, cy + 4], fill=ICE_HI)
d.ellipse([cx - 2, cy - 2, cx + 2, cy + 2], fill=ICE_MID)

# 右下角霜冻状态点(呼应 status_chance)
sx, sy = x1 - 8, y1 - 8
d.ellipse([sx - 4, sy - 4, sx + 4, sy + 4], fill=FROST_DOT)
d.ellipse([sx - 4, sy - 4, sx + 4, sy + 4], outline=ICE_LO, width=1)
d.line([sx - 3, sy, sx + 3, sy], fill=ICE_LO, width=1)
d.line([sx, sy - 3, sx, sy + 3], fill=ICE_LO, width=1)

img.save(os.path.join(os.path.dirname(os.path.abspath(__file__)), "mod_frostbite.png"))
print("saved mod_frostbite.png")
