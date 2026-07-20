"""MOD卡·锯齿(serration) 图标生成脚本。64x64，纯属性(tags:stat) -> 默认暗金画框(border_rgb=None)。
构图：一条斜置的锯齿状刀刃(zigzag 三角齿)，象征物理增伤(base_damage)词条，刃身留高光条，刃后拖一道
浅红色划痕表示"割伤/流血"意象，呼应 display-name "&c锯齿" 的红色主题。
输出：mod_serration.png
"""
import sys, os
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from mod_card_base import new_card

img, d, (x0, y0, x1, y1) = new_card(border_rgb=None)

# 斜向刀身：一条粗对角带，从左下到右上
BLADE_LO = (150, 150, 158, 255)
BLADE_MID = (205, 205, 212, 255)
BLADE_HI = (245, 245, 250, 255)
EDGE_RED = (200, 40, 40, 255)
EDGE_RED_DK = (120, 20, 20, 255)

cx0, cy0 = x0 + 6, y1 - 8
cx1, cy1 = x1 - 6, y0 + 10

# 刀身主体(平行四边形)
import math
dx, dy = cx1 - cx0, cy1 - cy0
length = math.hypot(dx, dy)
ux, uy = dx / length, dy / length
nx, ny = -uy, ux
half_w = 4.5

def offset(px, py, n):
    return (px + nx * n, py + ny * n)

p1 = offset(cx0, cy0, -half_w)
p2 = offset(cx1, cy1, -half_w)
p3 = offset(cx1, cy1, half_w)
p4 = offset(cx0, cy0, half_w)
d.polygon([p1, p2, p3, p4], fill=BLADE_MID, outline=BLADE_LO)

# 高光细线沿刃身中线偏上
hp1 = offset(cx0, cy0, -half_w + 1.5)
hp2 = offset(cx1, cy1, -half_w + 1.5)
d.line([hp1, hp2], fill=BLADE_HI, width=1)

# 锯齿：沿刃身下缘每隔一段画一个小三角尖齿，朝外(下方)突出
n_teeth = 6
for i in range(n_teeth):
    t0 = i / n_teeth
    t1 = (i + 0.6) / n_teeth
    bx0 = cx0 + dx * t0
    by0 = cy0 + dy * t0
    bx1 = cx0 + dx * t1
    by1 = cy0 + dy * t1
    base_a = offset(bx0, by0, half_w)
    base_b = offset(bx1, by1, half_w)
    mx = (bx0 + bx1) / 2
    my = (by0 + by1) / 2
    tip = offset(mx, my, half_w + 3.2)
    d.polygon([base_a, base_b, tip], fill=BLADE_LO, outline=EDGE_RED_DK)

# 刀尖三角(右上端)
tip_a = offset(cx1, cy1, -half_w)
tip_b = offset(cx1, cy1, half_w)
tip_c = (cx1 + ux * 6, cy1 + uy * 6)
d.polygon([tip_a, tip_b, tip_c], fill=BLADE_HI, outline=BLADE_LO)

# 刀柄(左下端)简单矩形收尾
grip_a = offset(cx0 - ux * 5, cy0 - uy * 5, -half_w * 0.7)
grip_b = offset(cx0, cy0, -half_w * 0.7)
grip_c = offset(cx0, cy0, half_w * 0.7)
grip_d = offset(cx0 - ux * 5, cy0 - uy * 5, half_w * 0.7)
d.polygon([grip_a, grip_b, grip_c, grip_d], fill=(90, 60, 40, 255), outline=(50, 32, 20, 255))

# 一道划痕血迹：从刃身中部斜下方甩出的小红点/短线，表示撕裂伤害
mid_t = 0.55
mxp = cx0 + dx * mid_t + nx * (half_w + 5)
myp = cy0 + dy * mid_t + ny * (half_w + 5)
d.line([(mxp, myp), (mxp + nx * 4 - 2, myp + ny * 4 + 3)], fill=EDGE_RED, width=1)
d.ellipse([mxp + 3, myp + 5, mxp + 5, myp + 7], fill=EDGE_RED)

out_path = os.path.join(os.path.dirname(os.path.abspath(__file__)), "mod_serration.png")
img.save(out_path)
print("saved", out_path)
