"""生成 MOD卡·延伸(stretch) 的 64x64 图标。
tags: stat -> 默认暗金画框 (border_rgb=None)。
effects: ability_range -> 图标语义：以中心一点(角色)向外辐射的双向箭头，表示技能作用范围被拉伸/延伸。
构图：中心一个小圆点(角色本体) + 上下左右四条向外延伸的箭头线(带箭头头部)，箭头颜色用浅青白色，
在暗金画框的暗色内圈上有清晰对比，一眼能看出"范围扩展"的主题。
"""
import sys, os
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from mod_card_base import new_card

img, d, (x0, y0, x1, y1) = new_card(border_rgb=None)

cx = (x0 + x1) // 2
cy = (y0 + y1) // 2

ARROW = (200, 230, 235, 255)
ARROW_DK = (120, 160, 170, 255)
CORE = (235, 225, 190, 255)
CORE_DK = (150, 120, 60, 255)

# 中心核心点（角色本体）
d.ellipse([cx - 4, cy - 4, cx + 4, cy + 4], fill=CORE, outline=CORE_DK, width=1)

def arrow(x_from, y_from, x_to, y_to, head=5):
    d.line([x_from, y_from, x_to, y_to], fill=ARROW, width=3)
    import math
    ang = math.atan2(y_to - y_from, x_to - x_from)
    for side in (1, -1):
        a2 = ang + side * 2.5
        hx = x_to - head * math.cos(a2)
        hy = y_to - head * math.sin(a2)
        d.line([x_to, y_to, hx, hy], fill=ARROW, width=3)

# 四个方向延伸箭头（上下左右），起点离核心稍远，终点靠近内圈边缘
gap = 9
end = 20
arrow(cx, cy - gap, cx, cy - end)
arrow(cx, cy + gap, cx, cy + end)
arrow(cx - gap, cy, cx - end, cy)
arrow(cx + gap, cy, cx + end, cy)

# 四条虚线短划，暗示"距离刻度被拉长"
for dx, dy in [(-end - 4, 0), (end + 4, 0), (0, -end - 4), (0, end + 4)]:
    tx, ty = cx + dx, cy + dy
    if x0 + 2 <= tx <= x1 - 2 and y0 + 2 <= ty <= y1 - 2:
        d.ellipse([tx - 1, ty - 1, tx + 1, ty + 1], fill=ARROW_DK)

out = os.path.join(os.path.dirname(os.path.abspath(__file__)), "mod_stretch.png")
img.save(out)
print("saved", out)
