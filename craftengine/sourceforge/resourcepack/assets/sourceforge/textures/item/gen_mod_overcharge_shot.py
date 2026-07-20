# MOD卡·超载重弹(overcharge_shot) 图标生成脚本。武器主动技能(skill:burst)，紫色画框。
# 构图：一支蓄力的箭矢正贯穿一圈能量爆环，箭尖前方三道放射冲击线表现"超载"后的爆发力，
# 箭杆后段叠一圈亮色蓄能光晕，整体传达"蓄力—爆发"的重弹意象。
import sys, os
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from mod_card_base import new_card

PURPLE = (150, 90, 220)
GLOW = (210, 170, 255, 255)
CORE = (255, 240, 200, 255)
ARROW = (235, 225, 245, 255)
ARROW_DK = (150, 130, 190, 255)
RING = (190, 140, 255, 255)

img, d, (x0, y0, x1, y1) = new_card(border_rgb=PURPLE)
cx, cy = (x0 + x1) // 2, (y0 + y1) // 2

# 蓄能爆环（三层同心圆，越外越淡）
for r, w, col in [(19, 2, (150, 90, 220, 90)), (14, 2, (190, 140, 255, 150)), (9, 2, RING)]:
    d.ellipse([cx - r, cy - r, cx + r, cy + r], outline=col, width=w)

# 箭杆：从左下到右上贯穿
tail = (cx - 16, cy + 14)
tip = (cx + 17, cy - 15)
d.line([tail, tip], fill=ARROW_DK, width=5)
d.line([tail, tip], fill=ARROW, width=2)

# 箭羽（尾部两片）
import math
dx, dy = tip[0] - tail[0], tip[1] - tail[1]
length = math.hypot(dx, dy)
ux, uy = dx / length, dy / length
px, py = -uy, ux
fbase = (tail[0] + ux * 6, tail[1] + uy * 6)
for sgn in (1, -1):
    d.polygon([
        tail,
        (fbase[0] + px * 6 * sgn, fbase[1] + py * 6 * sgn),
        (fbase[0] + ux * 5, fbase[1] + uy * 5),
    ], fill=ARROW_DK)

# 箭头三角
head_len, head_w = 9, 5
back = (tip[0] - ux * head_len, tip[1] - uy * head_len)
d.polygon([
    tip,
    (back[0] + px * head_w, back[1] + py * head_w),
    (back[0] - px * head_w, back[1] - py * head_w),
], fill=CORE, outline=ARROW_DK)

# 蓄能光核（箭杆中段）
mid = (cx - 2, cy + 1)
d.ellipse([mid[0] - 4, mid[1] - 4, mid[0] + 4, mid[1] + 4], fill=GLOW)
d.ellipse([mid[0] - 2, mid[1] - 2, mid[0] + 2, mid[1] + 2], fill=CORE)

# 箭尖前方三道冲击线（超载爆发感）
for off in (-6, 0, 6):
    sx = tip[0] + ux * 4 - px * off * 0.6
    sy = tip[1] + uy * 4 - py * off * 0.6
    ex = tip[0] + ux * 11 - px * off * 1.0
    ey = tip[1] + uy * 11 - py * off * 1.0
    d.line([(sx, sy), (ex, ey)], fill=(230, 210, 255, 200), width=2)

img.save(os.path.join(os.path.dirname(os.path.abspath(__file__)), "mod_overcharge_shot.png"))
print("saved mod_overcharge_shot.png")
