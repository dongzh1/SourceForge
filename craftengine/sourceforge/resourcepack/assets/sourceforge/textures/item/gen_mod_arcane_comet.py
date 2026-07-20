"""MOD卡·秘法交汇(arcane_comet) 图标生成脚本。64x64，共享底板 mod_card_base.new_card()。
基石天赋·巫术蓝画框 border_rgb=(90,150,220)（按项目约定：灵光召唤/秘法交汇/相位骤袭 同属巫术蓝系）。
构图：两道彗星尾迹从左上/右下呈交叉之势汇聚到画面中心的一枚发光法核(同心圆+十字星芒)，
体现"交汇"二字——两条轨迹相交于一点，法核外环一圈细小的秘纹刻点。
"""
import sys, os
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from mod_card_base import new_card

BORDER_RGB = (90, 150, 220)

img, d, (x0, y0, x1, y1) = new_card(border_rgb=BORDER_RGB)

cx, cy = (x0 + x1) / 2, (y0 + y1) / 2

TAIL_DEEP = (40, 70, 150, 120)
TAIL_MID = (110, 160, 230, 190)
TAIL_HI = (200, 225, 255, 230)
CORE_OUT = (140, 190, 255, 255)
CORE_MID = (200, 225, 255, 255)
CORE_HI = (255, 255, 255, 255)
RUNE = (170, 210, 255, 200)


def tail(dx, dy, length, width0):
    # 画一条从远端向中心收窄变亮的彗星尾迹，用多段短线段模拟锥形渐变
    steps = 14
    for i in range(steps):
        t0 = i / steps
        t1 = (i + 1) / steps
        px0 = cx - dx * length * (1 - t0)
        py0 = cy - dy * length * (1 - t0)
        px1 = cx - dx * length * (1 - t1)
        py1 = cy - dy * length * (1 - t1)
        w = max(1, int(width0 * (1 - t0 * 0.8)))
        if t0 < 0.4:
            col = TAIL_DEEP
        elif t0 < 0.75:
            col = TAIL_MID
        else:
            col = TAIL_HI
        d.line([px0, py0, px1, py1], fill=col, width=w)


# 两条交叉尾迹（左上->中心, 右下->中心 为一条彗星；右上->中心, 左下->中心 为另一条）
half = (x1 - x0) * 0.46
tail(0.75, 0.65, half, 4)
tail(-0.75, -0.65, half * 0.55, 3)
tail(-0.75, 0.65, half, 4)
tail(0.75, -0.65, half * 0.55, 3)

# 彗星头部小圆点(两个尾迹外端各一个亮核，暗示交汇前的两颗流星)
for dx, dy in [(0.75, 0.65), (-0.75, 0.65)]:
    hx = cx - dx * half
    hy = cy - dy * half
    d.ellipse([hx - 2, hy - 2, hx + 2, hy + 2], fill=TAIL_HI)

# 中心法核：同心圆 + 十字星芒
for r, col in [(7, CORE_OUT), (5, CORE_MID), (2.5, CORE_HI)]:
    d.ellipse([cx - r, cy - r, cx + r, cy + r], fill=col)

star_len = 11
d.line([cx - star_len, cy, cx + star_len, cy], fill=CORE_HI, width=1)
d.line([cx, cy - star_len, cx, cy + star_len], fill=CORE_HI, width=1)
diag = star_len * 0.6
d.line([cx - diag, cy - diag, cx + diag, cy + diag], fill=RUNE, width=1)
d.line([cx - diag, cy + diag, cx + diag, cy - diag], fill=RUNE, width=1)

# 外环细小秘纹刻点
import math
ring_r = 15
for i in range(10):
    ang = math.tau * i / 10
    px = cx + ring_r * math.cos(ang)
    py = cy + ring_r * math.sin(ang)
    if x0 + 1 <= px <= x1 - 1 and y0 + 1 <= py <= y1 - 1:
        d.point((px, py), fill=RUNE)

out_path = os.path.join(os.path.dirname(os.path.abspath(__file__)), "mod_arcane_comet.png")
img.save(out_path)
print("saved:", out_path)
