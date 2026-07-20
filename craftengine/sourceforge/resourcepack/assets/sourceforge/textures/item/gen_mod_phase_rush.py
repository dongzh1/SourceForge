"""生成 mod_phase_rush.png（MOD卡·相位骤袭，基石天赋/巫术系）。
64x64，共享暗金→巫术蓝画框(90,150,220，基石天赋-巫术系配色) + 专属图标：
三道由远及近、逐渐变亮变实的"相位残影"箭头（表现连击后瞬间加速冲刺、驱散减速的既视感），
残影用半透明蓝，最前一道用亮白突出"当前速度体"。
"""
import sys, os
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from mod_card_base import new_card

BORDER_RGB = (90, 150, 220)

img, d, (x0, y0, x1, y1) = new_card(border_rgb=BORDER_RGB)

cx = (x0 + x1) // 2
cy = (y0 + y1) // 2

# 三支从左下飞向右上的箭头残影，越靠右上越亮越大（速度感 + 相位感）
def arrow(offset, scale, color):
    ox, oy = offset
    ax0, ay0 = cx - 14 + ox, cy + 10 + oy
    ax1, ay1 = cx + 12 + ox, cy - 12 + oy
    # 箭身（细长四边形）
    import math
    dx, dy = ax1 - ax0, ay1 - ay0
    length = math.hypot(dx, dy)
    nx, ny = -dy / length, dx / length
    w = 2.2 * scale
    body = [
        (ax0 + nx * w, ay0 + ny * w),
        (ax1 + nx * w, ay1 + ny * w),
        (ax1 - nx * w, ay1 - ny * w),
        (ax0 - nx * w, ay0 - ny * w),
    ]
    d.polygon(body, fill=color)
    # 箭头三角
    tip = (ax1 + dx / length * 4 * scale, ay1 + dy / length * 4 * scale)
    left = (ax1 + nx * w * 3.2, ay1 + ny * w * 3.2)
    right = (ax1 - nx * w * 3.2, ay1 - ny * w * 3.2)
    d.polygon([tip, left, right], fill=color)

# 最远、最淡的残影
arrow((-9, 5), 0.7, (60, 100, 190, 90))
# 中间残影
arrow((-4, 2), 0.9, (100, 150, 230, 150))
# 最近、最亮（当前体）
arrow((0, 0), 1.1, (215, 235, 255, 255))

# 前端加一点高光速度线，强调"骤袭"的突进感
for i, (lx, ly) in enumerate([(cx + 6, cy - 16), (cx + 9, cy - 12), (cx + 3, cy - 20)]):
    d.line([(lx - 5, ly + 5), (lx + 3, ly - 3)], fill=(230, 245, 255, 200), width=1)

img.save(os.path.join(os.path.dirname(os.path.abspath(__file__)), "mod_phase_rush.png"))
print("saved mod_phase_rush.png border=", BORDER_RGB)
