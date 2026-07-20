# MOD卡·精简（streamline，ability_efficiency）图标生成脚本。64x64，纯属性(tags:stat)用默认暗金画框。
# 构图：简化齿轮(仅4齿，象征"精简"去掉冗余)+穿心的双重快进箭头(>>，象征技能效率/更快循环)，银白配色。
import sys, os
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from mod_card_base import new_card

SILVER_HI = (235, 238, 242, 255)
SILVER_MID = (190, 196, 205, 255)
SILVER_LO = (120, 128, 140, 255)

img, d, (x0, y0, x1, y1) = new_card(border_rgb=None)

cx = (x0 + x1) // 2
cy = (y0 + y1) // 2

# 简化齿轮：外圈4个矩形齿 + 圆环 + 中空
r_out = 15
r_in = 10
r_hole = 5

# 4颗齿（十字方向）
tooth = 4
for ang in (0, 90, 180, 270):
    import math
    rad = math.radians(ang)
    tx = cx + (r_out) * math.cos(rad)
    ty = cy + (r_out) * math.sin(rad)
    d.rectangle([tx - tooth, ty - tooth, tx + tooth, ty + tooth], fill=SILVER_MID, outline=SILVER_LO)

# 圆环主体
d.ellipse([cx - r_in, cy - r_in, cx + r_in, cy + r_in], fill=SILVER_HI, outline=SILVER_LO, width=2)
d.ellipse([cx - r_hole, cy - r_hole, cx + r_hole, cy + r_hole], fill=(24, 20, 24, 255))

# 穿心双重快进箭头 >>（象征效率提升/更快），叠在齿轮中心偏右下留白处，用细白箭头对角穿过
import math
def arrow(offset, color):
    ax0 = cx - 3 + offset
    ay0 = cy - 3 + offset
    d.polygon([(ax0, ay0 - 3), (ax0 + 4, ay0), (ax0, ay0 + 3)], fill=color)

# 小的一对 chevrons 指向右下角，表示"提速"
chev_color = (255, 220, 140, 255)
for i, off in enumerate((-3, 3)):
    bx = cx + off
    by = cy + off
    d.line([(bx - 2, by - 4), (bx + 2, by), (bx - 2, by + 4)], fill=chev_color, width=2, joint="curve")

img.save(os.path.join(os.path.dirname(os.path.abspath(__file__)), "mod_streamline.png"))
print("saved mod_streamline.png")
