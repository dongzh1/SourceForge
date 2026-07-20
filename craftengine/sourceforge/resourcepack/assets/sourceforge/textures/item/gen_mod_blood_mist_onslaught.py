"""MOD卡·血雾冲阵(blood_mist_onslaught) 图标生成脚本。
tags: skill, mobility；武器主动技能(skill:true)，无关联词条 -> 紫色系画框 (150,90,220)。
构图：一道血红色的冲阵斩击弧线 + 环绕的雾气小点粒子，表现"冲入敌阵、身后拖出血雾"的动感主题。
仿 gen_mod_hearth_whisper.py 手法，用简单几何(弧线/多边形/圆点)在 64x64 内圈拼出图标。
"""
import sys, os
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from mod_card_base import new_card
from PIL import ImageDraw
import math

img, d, (x0, y0, x1, y1) = new_card(border_rgb=(150, 90, 220))

cx, cy = (x0 + x1) / 2, (y0 + y1) / 2

# 冲阵斩击弧线：三道由粗到细的血红弧线，表现挥砍轨迹
BLOOD_HI = (235, 70, 70, 255)
BLOOD_MID = (185, 30, 40, 255)
BLOOD_LO = (110, 15, 25, 255)

def arc_slash(offset, width, color):
    bbox = [x0 - 4 + offset, y0 - 2 + offset, x1 + 4 - offset, y1 + 6 - offset]
    d.arc(bbox, start=200, end=330, fill=color, width=width)

arc_slash(0, 5, BLOOD_LO)
arc_slash(4, 4, BLOOD_MID)
arc_slash(8, 3, BLOOD_HI)

# 斩击末端的尖锐箭头(冲阵方向)
tip_x, tip_y = x1 - 6, y0 + 10
d.polygon([(tip_x, tip_y), (tip_x - 9, tip_y - 3), (tip_x - 4, tip_y + 7)], fill=BLOOD_HI)
d.polygon([(tip_x, tip_y), (tip_x - 9, tip_y - 3), (tip_x - 4, tip_y + 7)], outline=BLOOD_LO)

# 血雾粒子：环绕弧线飘散的小圆点，靠近弧线密、远处稀疏
mist_pts = [
    (x0 + 6, y1 - 10, 3, (200, 50, 60, 220)),
    (x0 + 11, y1 - 5, 2, (170, 40, 55, 190)),
    (x0 + 4, y1 - 18, 2, (150, 40, 60, 160)),
    (cx - 8, y1 - 4, 3, (190, 45, 60, 200)),
    (cx - 2, y1 - 12, 2, (140, 35, 55, 150)),
    (x0 + 16, y1 - 16, 2, (160, 40, 55, 170)),
    (x0 + 9, y0 + 5, 2, (120, 30, 45, 120)),
]
for (px, py, r, col) in mist_pts:
    d.ellipse([px - r, py - r, px + r, py + r], fill=col)

# 冲阵脚印/速度线：左下角三道短线表示突进
for i, dx in enumerate([0, 5, 10]):
    lx0 = x0 + 2 + dx
    ly0 = y1 - 3
    lx1 = lx0 + 6
    ly1 = ly0 - 6 - i
    d.line([(lx0, ly0), (lx1, ly1)], fill=(210, 60, 70, 200 - i * 30), width=2)

out_path = os.path.join(os.path.dirname(os.path.abspath(__file__)), "mod_blood_mist_onslaught.png")
img.save(out_path)
print("saved", out_path)
