"""MOD卡·灵光召唤(summon_aery) 图标生成脚本。64x64背景+图标一次烤死，共享画框来自 mod_card_base.new_card。
基石天赋(tags含keystone) -> 按系别取色：灵光召唤属巫术蓝系 (90,150,220)。
构图：中央一颗发光的召唤精灵光球(核心亮白+蓝光晕)，外圈一个虚线魔法法阵圆环，
三颗小卫星光点沿环轨道环绕(呼应"召唤"随行精灵的意象)，光球顶部两道细小翼状光尾增添灵动感。
"""
import sys, os, math
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from mod_card_base import new_card

img, d, (x0, y0, x1, y1) = new_card(border_rgb=(90, 150, 220))

cx = (x0 + x1) // 2
cy = (y0 + y1) // 2

RING_DIM = (70, 110, 190, 255)
RING_HI = (140, 190, 255, 255)
ORB_OUTER = (60, 110, 210, 255)
ORB_MID = (130, 180, 255, 255)
ORB_CORE = (235, 245, 255, 255)
SAT = (170, 210, 255, 255)
SAT_HI = (255, 255, 255, 255)

# 外圈魔法法阵：虚线圆环
ring_r = 19
n_dash = 24
for i in range(n_dash):
    if i % 2 == 0:
        a0 = 2 * math.pi * i / n_dash
        a1 = 2 * math.pi * (i + 0.6) / n_dash
        px0 = cx + ring_r * math.cos(a0)
        py0 = cy + ring_r * math.sin(a0)
        px1 = cx + ring_r * math.cos(a1)
        py1 = cy + ring_r * math.sin(a1)
        d.line([px0, py0, px1, py1], fill=RING_DIM, width=2)

# 内圈细亮环
d.ellipse([cx - 11, cy - 11, cx + 11, cy + 11], outline=RING_HI, width=1)

# 中央光球
d.ellipse([cx - 9, cy - 9, cx + 9, cy + 9], fill=ORB_OUTER)
d.ellipse([cx - 6, cy - 6, cx + 6, cy + 6], fill=ORB_MID)
d.ellipse([cx - 3, cy - 3, cx + 3, cy + 3], fill=ORB_CORE)

# 顶部翼状光尾
d.polygon([(cx - 2, cy - 8), (cx - 14, cy - 16), (cx - 6, cy - 6)], fill=RING_HI)
d.polygon([(cx + 2, cy - 8), (cx + 14, cy - 16), (cx + 6, cy - 6)], fill=RING_HI)

# 三颗环绕卫星光点
for ang in (60, 190, 300):
    a = math.radians(ang)
    sx = cx + ring_r * math.cos(a)
    sy = cy + ring_r * math.sin(a)
    d.ellipse([sx - 3, sy - 3, sx + 3, sy + 3], fill=SAT)
    d.ellipse([sx - 1, sy - 1, sx + 1, sy + 1], fill=SAT_HI)

out_path = os.path.join(os.path.dirname(os.path.abspath(__file__)), "mod_summon_aery.png")
img.save(out_path)
print("saved:", out_path)
