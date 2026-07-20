"""生成 MOD卡·超能赋力(intensify) 的 64x64 图标。
效果：ability_strength 词条，纯属性(tags:stat)增益卡，画框走默认暗金(border_rgb=None)。
构图：中心一枚向上冲刺的能量箭头，箭身用亮黄-橙渐变三色叠加表现"赋力"发光感，
两侧加短促的放射线条模拟能量迸发，底部一道弧形托底线表示"承载/加成"的基座意象。
"""
import sys, os
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from mod_card_base import new_card

img, d, (x0, y0, x1, y1) = new_card(border_rgb=None)

cx = (x0 + x1) / 2
cy = (y0 + y1) / 2

GLOW_OUT = (255, 200, 60, 255)
GLOW_MID = (255, 230, 110, 255)
GLOW_HI = (255, 250, 200, 255)
GLOW_DK = (200, 130, 20, 255)

# 底部承托弧线
d.arc([x0 + 4, y1 - 14, x1 - 4, y1 + 10], start=200, end=340, fill=GLOW_DK, width=2)

# 主箭头：向上，箭身用三层描边制造发光层次
arrow_top = (cx, y0 + 4)
arrow_l = (cx - 9, cy + 4)
arrow_r = (cx + 9, cy + 4)
shaft_l = (cx - 3, cy + 4)
shaft_r = (cx + 3, cy + 4)
shaft_bl = (cx - 3, y1 - 6)
shaft_br = (cx + 3, y1 - 6)

# 外层发光
d.polygon([arrow_top, (cx - 12, cy + 6), (cx - 4, cy + 6), (cx - 4, y1 - 4), (cx + 4, y1 - 4), (cx + 4, cy + 6), (cx + 12, cy + 6)], fill=GLOW_OUT)
# 中层
d.polygon([arrow_top, (cx - 9, cy + 4), (cx - 3, cy + 4), (cx - 3, y1 - 6), (cx + 3, y1 - 6), (cx + 3, cy + 4), (cx + 9, cy + 4)], fill=GLOW_MID)
# 高光尖端
d.polygon([arrow_top, (cx - 4, cy - 2), (cx + 4, cy - 2)], fill=GLOW_HI)
d.rectangle([cx - 1, cy - 2, cx + 1, y1 - 6], fill=GLOW_HI)

# 两侧放射短线，表现能量迸发
import math
for ang_deg in (-150, -120, -60, -30, 150, 120, 60, 30):
    ang = math.radians(ang_deg)
    r0, r1 = 14, 20
    x_start = cx + r0 * math.cos(ang)
    y_start = cy - 2 + r0 * math.sin(ang) * 0.6
    x_end = cx + r1 * math.cos(ang)
    y_end = cy - 2 + r1 * math.sin(ang) * 0.6
    d.line([(x_start, y_start), (x_end, y_end)], fill=GLOW_MID, width=1)

img.save(os.path.join(os.path.dirname(os.path.abspath(__file__)), "mod_intensify.png"))
print("saved mod_intensify.png")
