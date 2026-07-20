"""MOD卡·黑暗收割(dark_harvest) 图标生成脚本。
基石天赋(keystone)，type-label=基石天赋，色系判定为"主宰红"(190,60,60)，
与电刑同组。构图：一把黑色巨镰刀斜劈过画面，刃口拖出暗红色收割能量弧线，
刀尖下方漂浮几缕暗红灵魂碎屑，呼应"收割生命"的主题。
64x64，用共享画框 mod_card_base.new_card() 画底，图标本体在内圈手绘。
"""
import sys, os
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from mod_card_base import new_card

BORDER_RGB = (190, 60, 60)

img, d, (x0, y0, x1, y1) = new_card(border_rgb=BORDER_RGB)

BLADE_DARK = (18, 14, 18, 255)
BLADE_EDGE = (60, 20, 24, 255)
ENERGY_HI = (230, 70, 70, 255)
ENERGY_MID = (170, 40, 45, 255)
ENERGY_LO = (90, 20, 26, 255)
SOUL = (200, 90, 90, 200)

# 镰刀柄：一条从左下到中心的深色木柄
d.line([(x0 + 4, y1 - 4), (x0 + 22, y0 + 26)], fill=(50, 34, 24, 255), width=3)

# 镰刀刀刃：一个大弧形多边形，从中心甩向右上方
blade_pts = [
    (x0 + 20, y0 + 24),
    (x0 + 30, y0 + 10),
    (x1 - 4, y0 + 4),
    (x1 - 2, y0 + 10),
    (x0 + 36, y0 + 20),
    (x0 + 26, y0 + 32),
]
d.polygon(blade_pts, fill=BLADE_DARK, outline=BLADE_EDGE)

# 刃口高光细线
d.line([(x0 + 30, y0 + 10), (x1 - 4, y0 + 4), (x1 - 2, y0 + 10)], fill=(80, 30, 34, 255), width=1)

# 收割能量弧线：沿刀背延伸出的几道暗红弧
for i, off in enumerate([0, 4, 8]):
    col = [ENERGY_HI, ENERGY_MID, ENERGY_LO][i]
    d.arc(
        [x0 + 18 - off, y0 + 6 - off, x1 - 2 - off, y0 + 30 - off],
        start=200, end=300, fill=col, width=2,
    )

# 刀尖下方漂浮的暗红灵魂碎屑（几个小三角/菱形）
soul_pts = [(x0 + 12, y1 - 14), (x0 + 18, y1 - 20), (x0 + 26, y1 - 10), (x0 + 34, y1 - 16)]
for (sx, sy) in soul_pts:
    d.polygon([(sx, sy - 3), (sx + 2, sy), (sx, sy + 3), (sx - 2, sy)], fill=SOUL)

# 中心一点暗红光晕，表现"生命被吸走"
d.ellipse([x0 + 18, y0 + 20, x0 + 24, y0 + 26], fill=ENERGY_MID)

out = os.path.join(os.path.dirname(os.path.abspath(__file__)), "mod_dark_harvest.png")
img.save(out)
print("saved", out)
