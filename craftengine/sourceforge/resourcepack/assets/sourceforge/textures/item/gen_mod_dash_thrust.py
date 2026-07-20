"""MOD卡·冲刺突进(dash_thrust) 图标生成脚本。64x64，共享暗紫技能画框(new_card border_rgb=(150,90,220))，
内圈画一支向右突刺的箭头/长矛 + 身后三道运动残影线，表达"武器主动技能：冲刺突进"的位移突袭主题。
"""
import sys, os
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from mod_card_base import new_card

BORDER_RGB = (150, 90, 220)

img, d, (x0, y0, x1, y1) = new_card(border_rgb=BORDER_RGB)
cy = (y0 + y1) // 2

# 三道渐强的运动残影线（从左侧尾部向前，越靠前越亮越粗）
trail_colors = [(120, 80, 170, 120), (150, 100, 200, 170), (190, 140, 230, 220)]
for i, col in enumerate(trail_colors):
    xo = x0 + 2 + i * 3
    yo = -4 + i * 2
    d.line([(xo, cy + yo), (xo + 7, cy + yo)], fill=col, width=2)

# 长矛/箭头主体：一条粗直线杆 + 三角形矛头，指向右方（突进方向）
shaft_x0 = x0 + 6
shaft_x1 = x1 - 14
d.line([(shaft_x0, cy), (shaft_x1, cy)], fill=(235, 220, 250, 255), width=4)
d.line([(shaft_x0, cy - 1), (shaft_x1, cy - 1)], fill=(255, 245, 255, 180), width=1)

tip_x = x1 - 3
d.polygon(
    [(shaft_x1 - 2, cy - 8), (tip_x, cy), (shaft_x1 - 2, cy + 8)],
    fill=(210, 170, 250, 255),
    outline=(255, 235, 255, 255),
)
d.polygon(
    [(shaft_x1 - 6, cy - 4), (shaft_x1 + 2, cy), (shaft_x1 - 6, cy + 4)],
    fill=(150, 90, 220, 255),
)

# 尾羽小三角，强化"箭"的意象
feather_x = shaft_x0 - 1
d.polygon([(feather_x, cy), (feather_x - 5, cy - 5), (feather_x - 2, cy)], fill=(190, 140, 230, 220))
d.polygon([(feather_x, cy), (feather_x - 5, cy + 5), (feather_x - 2, cy)], fill=(190, 140, 230, 220))

# 底部两个脚印小圆点，呼应"冲刺"的奔跑感
d.ellipse([x0 + 6, y1 - 6, x0 + 10, y1 - 2], fill=(150, 90, 220, 200))
d.ellipse([x0 + 14, y1 - 9, x0 + 18, y1 - 5], fill=(120, 70, 190, 160))

out_path = os.path.join(os.path.dirname(os.path.abspath(__file__)), "mod_dash_thrust.png")
img.save(out_path)
print("saved", out_path)
