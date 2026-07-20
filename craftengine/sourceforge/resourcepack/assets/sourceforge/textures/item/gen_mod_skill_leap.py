# MOD卡·技能：跃迁（skill_leap）图标生成脚本。64x64，共享暗色画框(mod_card_base.new_card)
# + 专属图标：紫色动感旋风弧线 + 一只向前跃出的脚印，表达"纵身一跃"的位移技能主题。
# border_rgb=(150,90,220) 紫色系，对应技能类(skill:true)配色规则。
import sys, os
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from mod_card_base import new_card
import math

img, d, (x0, y0, x1, y1) = new_card(border_rgb=(150, 90, 220))
cx, cy = (x0 + x1) / 2, (y0 + y1) / 2

PURPLE_HI = (210, 170, 250, 255)
PURPLE_MID = (160, 100, 230, 255)
PURPLE_LO = (100, 60, 160, 255)
WHITE_GLOW = (235, 220, 255, 255)

# 三道由左下向右上飞掠的动感弧线（速度线），暗示跃迁方向
for i, (r, w, col) in enumerate([(20, 3, PURPLE_LO), (15, 3, PURPLE_MID), (10, 3, PURPLE_HI)]):
    bbox = [cx - r, cy - r, cx + r, cy + r]
    d.arc(bbox, start=200, end=290, fill=col, width=w)

# 脚印（脚掌+脚跟两个椭圆），放在弧线末端右上方，代表跳跃落点/起点
foot_cx, foot_cy = cx + 6, cy + 4
d.ellipse([foot_cx - 5, foot_cy - 3, foot_cx + 5, foot_cy + 7], fill=PURPLE_MID, outline=PURPLE_HI)
d.ellipse([foot_cx - 3, foot_cy - 9, foot_cx + 3, foot_cy - 2], fill=PURPLE_MID, outline=PURPLE_HI)

# 另一只脚印，淡一些，画在左下方作为起跳点，营造位移轨迹
foot2_cx, foot2_cy = cx - 10, cy + 12
d.ellipse([foot2_cx - 4, foot2_cy - 2, foot2_cx + 4, foot2_cy + 5], fill=PURPLE_LO)
d.ellipse([foot2_cx - 2, foot2_cy - 7, foot2_cx + 2, foot2_cy - 1], fill=PURPLE_LO)

# 顶端小星光点缀，强调"跃迁"的瞬移感
for dx, dy, r in [(9, -8, 2), (13, -3, 1), (5, -12, 1)]:
    px, py = cx + dx, cy + dy
    d.ellipse([px - r, py - r, px + r, py + r], fill=WHITE_GLOW)

out_path = os.path.join(os.path.dirname(os.path.abspath(__file__)), "mod_skill_leap.png")
img.save(out_path)
print("saved", out_path)
