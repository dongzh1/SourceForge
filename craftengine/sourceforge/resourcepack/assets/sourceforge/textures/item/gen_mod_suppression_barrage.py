# MOD卡·弹幕压制（suppression_barrage）图标生成脚本。技能类(skill:true)，画框用紫色系(150,90,220)。
# 构图：一束从右下角向左上方扇形射出的三支箭矢/弹幕，代表"压制性弹幕"的持续覆盖火力，
# 箭头尖端带小十字准星强调"压制"射击意象，背景留一道淡紫冲击弧线增加动感。
import sys, os
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from mod_card_base import new_card

BORDER_RGB = (150, 90, 220)

img, d, (x0, y0, x1, y1) = new_card(border_rgb=BORDER_RGB)

cx, cy = (x0 + x1) // 2, (y0 + y1) // 2

# 淡紫冲击弧线（背景动感层）
d.arc([x0 - 6, y0 + 6, x1 + 10, y1 + 20], start=200, end=260, fill=(120, 70, 180, 140), width=2)
d.arc([x0 - 2, y0 + 10, x1 + 6, y1 + 16], start=205, end=255, fill=(160, 110, 230, 120), width=2)

origin = (x1 - 3, y1 - 2)

def draw_bolt(dx, dy, length, color_hi, color_lo, w):
    tip = (origin[0] + dx * length, origin[1] + dy * length)
    tail = origin
    d.line([tail, tip], fill=color_lo, width=w + 2)
    d.line([tail, tip], fill=color_hi, width=w)
    # 箭头
    perp = (-dy, dx)
    head_len = 6
    back = (tip[0] - dx * head_len, tip[1] - dy * head_len)
    left = (back[0] + perp[0] * 4, back[1] + perp[1] * 4)
    right = (back[0] - perp[0] * 4, back[1] - perp[1] * 4)
    d.polygon([tip, left, right], fill=color_hi)
    # 尖端小十字准星
    d.line([tip[0] - 3, tip[1], tip[0] + 3, tip[1]], fill=(230, 210, 255, 255), width=1)
    d.line([tip[0], tip[1] - 3, tip[0], tip[1] + 3], fill=(230, 210, 255, 255), width=1)

import math

HI = (210, 170, 255, 255)
LO = (110, 60, 170, 255)

for ang_deg, length, w in [(200, 30, 3), (215, 34, 4), (230, 30, 3)]:
    rad = math.radians(ang_deg)
    dx, dy = math.cos(rad), math.sin(rad)
    draw_bolt(dx, dy, length, HI, LO, w)

img.save(os.path.join(os.path.dirname(os.path.abspath(__file__)), "mod_suppression_barrage.png"))
print("saved mod_suppression_barrage.png")
