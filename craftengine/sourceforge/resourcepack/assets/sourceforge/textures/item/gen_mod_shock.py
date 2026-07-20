# MOD卡·电击（shock）图标生成脚本。64x64 背景+图标一次烤死，用共享 mod_card_base 画框。
# 构图：一道黄色闪电从内圈左上劈到右下，两侧配几点电火花小三角，呼应 electric_damage/status_chance。
# 画框色 = 元素-电 (220,200,60)。
import sys, os
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from mod_card_base import new_card

BOLT_HI = (255, 240, 140, 255)
BOLT_MID = (230, 200, 40, 255)
BOLT_LO = (150, 120, 10, 255)
SPARK = (255, 250, 200, 255)

img, d, (x0, y0, x1, y1) = new_card(border_rgb=(220, 200, 60))

# 闪电主体：经典 Z 字形折线多边形，从左上到右下
bolt = [
    (x0 + 26, y0 + 2),
    (x0 + 12, y0 + 22),
    (x0 + 20, y0 + 22),
    (x0 + 8, y0 + 46),
    (x0 + 26, y0 + 20),
    (x0 + 18, y0 + 20),
    (x0 + 30, y0 + 2),
]
d.polygon(bolt, fill=BOLT_MID, outline=BOLT_LO)

# 高光细条叠加在闪电中轴，增加层次感
hi_line = [
    (x0 + 25, y0 + 5),
    (x0 + 16, y0 + 21),
    (x0 + 21, y0 + 21),
    (x0 + 12, y0 + 41),
    (x0 + 22, y0 + 22),
    (x0 + 18, y0 + 22),
    (x0 + 27, y0 + 6),
]
d.polygon(hi_line, fill=BOLT_HI)

# 电火花小三角，围绕闪电点缀，表现 status_chance(附加状态几率)的"噼啪"感
def spark(cx, cy, s):
    d.polygon([(cx, cy - s), (cx + s, cy), (cx, cy + s), (cx - s, cy)], fill=SPARK, outline=BOLT_LO)

spark(x0 + 5, y0 + 8, 3)
spark(x0 + 35, y0 + 14, 3)
spark(x0 + 30, y0 + 38, 3)
spark(x0 + 4, y0 + 35, 2)

out = os.path.join(os.path.dirname(os.path.abspath(__file__)), "mod_shock.png")
img.save(out)
print("saved", out)
