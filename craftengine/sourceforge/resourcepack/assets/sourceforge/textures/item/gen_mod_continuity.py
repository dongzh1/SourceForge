"""MOD卡·延展（continuity）图标生成脚本。64x64，共享暗金画框(纯属性词条 tags:stat，
border_rgb=None 走 mod_card_base 默认暗金)。effects: ability_duration —— 技能/效果持续时间延长。
构图：内圈画一个时钟表盘(表壳描边+指针)，指针周围加一圈延伸的弧形箭头，表示"把时间往外拉长"。
仿照 gen_mod_hearth_whisper.py 的写法：几十个 draw 调用堆出一个一眼能看懂主题的小图标。
"""
import sys, os
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from mod_card_base import new_card
import math

img, d, (x0, y0, x1, y1) = new_card(border_rgb=None)

cx = (x0 + x1) / 2
cy = (y0 + y1) / 2
r = min(x1 - x0, y1 - y0) / 2 - 4

FACE = (235, 220, 180, 255)
FACE_SHADOW = (200, 180, 130, 255)
RIM_HI = (250, 235, 190, 255)
RIM_LO = (120, 95, 50, 255)
HAND = (60, 45, 25, 255)
ARROW = (222, 168, 45, 255)
ARROW_LO = (168, 122, 38, 255)

# clock body: outer rim ring
d.ellipse([cx - r, cy - r, cx + r, cy + r], outline=RIM_LO, width=3, fill=FACE)
d.ellipse([cx - r + 2, cy - r + 2, cx + r - 2, cy + r - 2], outline=RIM_HI, width=1)

# tiny top button
d.rectangle([cx - 2, cy - r - 3, cx + 2, cy - r + 1], fill=RIM_LO)

# hour ticks (12 marks)
for i in range(12):
    ang = math.radians(i * 30 - 90)
    inner = r - (4 if i % 3 == 0 else 2)
    ox0 = cx + math.cos(ang) * inner
    oy0 = cy + math.sin(ang) * inner
    ox1 = cx + math.cos(ang) * (r - 1)
    oy1 = cy + math.sin(ang) * (r - 1)
    d.line([ox0, oy0, ox1, oy1], fill=FACE_SHADOW, width=2 if i % 3 == 0 else 1)

# clock hands: hour hand pointing to ~10, minute hand pointing to ~2 (both stretched outward -> "extended")
hour_ang = math.radians(-60)
min_ang = math.radians(30)
d.line([cx, cy, cx + math.cos(hour_ang) * (r * 0.55), cy + math.sin(hour_ang) * (r * 0.55)], fill=HAND, width=3)
d.line([cx, cy, cx + math.cos(min_ang) * (r * 0.8), cy + math.sin(min_ang) * (r * 0.8)], fill=HAND, width=2)
d.ellipse([cx - 2, cy - 2, cx + 2, cy + 2], fill=HAND)

# extension arc: a dashed/segmented arc around clock (outside rim) with arrowheads on both ends,
# suggesting the clock face is being "stretched" longer in time
arc_r = r + 6
bbox = [cx - arc_r, cy - arc_r, cx + arc_r, cy + arc_r]
d.arc(bbox, start=200, end=340, fill=ARROW, width=3)

# arrowhead at end (340 deg = lower right-ish, pointing clockwise/outward)
end_ang = math.radians(340)
tip_x = cx + math.cos(end_ang) * arc_r
tip_y = cy + math.sin(end_ang) * arc_r
tang = end_ang + math.radians(90)
p1 = (tip_x, tip_y)
p2 = (tip_x - math.cos(tang) * 5 - math.cos(end_ang) * 4, tip_y - math.sin(tang) * 5 - math.sin(end_ang) * 4)
p3 = (tip_x + math.cos(tang) * 5 - math.cos(end_ang) * 4, tip_y + math.sin(tang) * 5 - math.sin(end_ang) * 4)
d.polygon([p1, p2, p3], fill=ARROW)

# arrowhead at start (200 deg)
start_ang = math.radians(200)
tip_x2 = cx + math.cos(start_ang) * arc_r
tip_y2 = cy + math.sin(start_ang) * arc_r
tang2 = start_ang - math.radians(90)
q1 = (tip_x2, tip_y2)
q2 = (tip_x2 - math.cos(tang2) * 5 + math.cos(start_ang) * 4, tip_y2 - math.sin(tang2) * 5 + math.sin(start_ang) * 4)
q3 = (tip_x2 + math.cos(tang2) * 5 + math.cos(start_ang) * 4, tip_y2 + math.sin(tang2) * 5 + math.sin(start_ang) * 4)
d.polygon([q1, q2, q3], fill=ARROW)

out = os.path.join(os.path.dirname(os.path.abspath(__file__)), "mod_continuity.png")
img.save(out)
print("saved", out)
