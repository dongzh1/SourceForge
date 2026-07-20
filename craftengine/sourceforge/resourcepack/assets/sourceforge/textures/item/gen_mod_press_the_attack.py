"""MOD卡·强攻(press_the_attack) 图标生成脚本。64x64，基石天赋-精密系(金色画框)。
构图：一柄斜插的剑，剑尖处叠加一道向右上方甩出的攻击弧线(斩击轨迹)，
表现"连续攻击加速/强攻"的主题；剑身用金色高光呼应画框色调。
"""
import sys, os
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from mod_card_base import new_card
import math

BORDER_RGB = (200, 170, 90)  # 精密金 - 基石天赋

img, d, (x0, y0, x1, y1) = new_card(border_rgb=BORDER_RGB)

cx = (x0 + x1) / 2
cy = (y0 + y1) / 2

GOLD_HI = (255, 225, 140, 255)
GOLD_MID = (210, 175, 90, 255)
GOLD_LO = (130, 100, 40, 255)
STEEL = (225, 225, 235, 255)
STEEL_LO = (140, 140, 150, 255)
WHITE_FLASH = (255, 250, 220, 255)

# --- 剑：从左下到右上的斜向刀刃 ---
# 刀刃方向向量 (从左下 to 右上), 角度约 -50度
blade_tail = (x0 + 8, y1 - 8)   # 剑柄端(左下)
blade_tip = (x1 - 6, y0 + 6)    # 剑尖(右上)

dx = blade_tip[0] - blade_tail[0]
dy = blade_tip[1] - blade_tail[1]
length = math.hypot(dx, dy)
ux, uy = dx / length, dy / length
# 垂直方向
px, py = -uy, ux

blade_w = 2.2
# 剑身四边形(细长)
p1 = (blade_tail[0] + px * blade_w, blade_tail[1] + py * blade_w)
p2 = (blade_tip[0] + px * blade_w * 0.4, blade_tip[1] + py * blade_w * 0.4)
p3 = (blade_tip[0] - px * blade_w * 0.4, blade_tip[1] - py * blade_w * 0.4)
p4 = (blade_tail[0] - px * blade_w, blade_tail[1] - py * blade_w)
d.polygon([p1, p2, p3, p4], fill=STEEL)
# 刃线高光(中线)
d.line([blade_tail, blade_tip], fill=GOLD_HI, width=1)

# 护手(十字架)
guard_cx = blade_tail[0] + ux * 5
guard_cy = blade_tail[1] + uy * 5
gw = 5
d.line([(guard_cx + px * gw, guard_cy + py * gw), (guard_cx - px * gw, guard_cy - py * gw)],
       fill=GOLD_MID, width=2)

# 剑柄
grip_end = (blade_tail[0] - ux * 6, blade_tail[1] - uy * 6)
d.line([blade_tail, grip_end], fill=GOLD_LO, width=3)
d.ellipse([grip_end[0] - 1.5, grip_end[1] - 1.5, grip_end[0] + 1.5, grip_end[1] + 1.5], fill=GOLD_HI)

# --- 攻击弧线：从剑尖向右上甩出的三道斩击弧 ---
arc_cx = blade_tip[0] - 3
arc_cy = blade_tip[1] + 3
for i, (radius, w, col) in enumerate([
    (14, 2, GOLD_HI),
    (10, 2, WHITE_FLASH),
    (6, 1, GOLD_MID),
]):
    bbox = [arc_cx - radius, arc_cy - radius, arc_cx + radius, arc_cy + radius]
    d.arc(bbox, start=200, end=330, fill=col, width=w)

# 冲击星芒(剑尖处)
star_len = 3
for ang in [0, 90, 180, 270, 45, 225]:
    rad = math.radians(ang)
    ex = blade_tip[0] + math.cos(rad) * star_len
    ey = blade_tip[1] + math.sin(rad) * star_len
    d.line([blade_tip, (ex, ey)], fill=WHITE_FLASH, width=1)

img.save(os.path.join(os.path.dirname(os.path.abspath(__file__)), "mod_press_the_attack.png"))
print("saved mod_press_the_attack.png")
