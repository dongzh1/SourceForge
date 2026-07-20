"""MOD卡·流体能量(flow) 图标生成脚本。tags=[stat]，纯属性词条(energy_max，能量上限)，
边框用共享模块默认暗金(border_rgb=None)。图标构图：一滴水滴状的能量液滴轮廓，内部一道闪电/能量
芯线贯穿，滴身周围点缀几个悬浮小光点表示"流体能量"意象。仿 gen_mod_hearth_whisper.py 手法，
纯几何 polygon/ellipse/line 堆砌，64x64 一次画完背景+图标。
"""
import sys, os
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from mod_card_base import new_card

img, d, (x0, y0, x1, y1) = new_card(border_rgb=None)
cx = (x0 + x1) / 2

# 水滴外形颜色
DROP_HI = (140, 210, 255, 255)
DROP_MID = (60, 150, 230, 255)
DROP_LO = (25, 90, 160, 255)
CORE = (220, 245, 255, 255)
SPARK = (170, 225, 255, 255)

top_y = y0 + 4
bottom_y = y1 - 4
width = (x1 - x0) * 0.34

# 水滴主体：尖顶+圆底，用多边形近似
drop_pts = [
    (cx, top_y),
    (cx + width, top_y + (bottom_y - top_y) * 0.55),
    (cx + width * 0.9, bottom_y - 2),
    (cx + width * 0.45, bottom_y + 2),
    (cx - width * 0.45, bottom_y + 2),
    (cx - width * 0.9, bottom_y - 2),
    (cx - width, top_y + (bottom_y - top_y) * 0.55),
]
d.polygon(drop_pts, fill=DROP_MID, outline=DROP_LO)

# 左侧高光条
hi_pts = [
    (cx - width * 0.55, top_y + (bottom_y - top_y) * 0.35),
    (cx - width * 0.15, top_y + (bottom_y - top_y) * 0.3),
    (cx - width * 0.25, bottom_y - 4),
    (cx - width * 0.6, bottom_y - 6),
]
d.polygon(hi_pts, fill=DROP_HI)

# 内部能量芯线（闪电状折线，贯穿水滴）
mid_y0 = top_y + (bottom_y - top_y) * 0.18
mid_y1 = bottom_y - 3
bolt = [
    (cx + width * 0.15, mid_y0),
    (cx - width * 0.2, cy_mid := (mid_y0 + mid_y1) / 2 - 3),
    (cx + width * 0.05, (mid_y0 + mid_y1) / 2 + 1),
    (cx - width * 0.15, mid_y1),
]
d.line(bolt, fill=CORE, width=3, joint="curve")
d.line(bolt, fill=CORE, width=3, joint="curve")

# 顶部与底部各一个小圆点收尾能量芯
d.ellipse([cx + width * 0.15 - 2, mid_y0 - 2, cx + width * 0.15 + 2, mid_y0 + 2], fill=CORE)
d.ellipse([cx - width * 0.15 - 2, mid_y1 - 2, cx - width * 0.15 + 2, mid_y1 + 2], fill=CORE)

# 外框描边加深轮廓层次
d.line(drop_pts + [drop_pts[0]], fill=DROP_LO, width=1)

# 周围悬浮小光点（能量粒子飘散感）
for (sx, sy, r) in [
    (x0 + 6, y0 + 8, 1.6),
    (x1 - 6, y0 + 10, 1.3),
    (x0 + 5, y1 - 8, 1.4),
    (x1 - 5, y1 - 10, 1.6),
    (x0 + 9, (y0 + y1) / 2 + 2, 1.1),
]:
    d.ellipse([sx - r, sy - r, sx + r, sy + r], fill=SPARK)

out_path = os.path.join(os.path.dirname(os.path.abspath(__file__)), "mod_flow.png")
img.save(out_path)
print("saved", out_path)
