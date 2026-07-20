# MOD卡·震刀（parry）图标生成脚本。64x64，共享暗紫画框(技能类 border_rgb=(150,90,220))。
# 构图：一柄竖立的剑，剑身周围画出格挡瞬间的冲击环+短促火花线，体现"架势格挡/弹反"的动感。
import sys, os
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from mod_card_base import new_card
import math

img, d, (x0, y0, x1, y1) = new_card(border_rgb=(150, 90, 220))
cx, cy = (x0 + x1) // 2, (y0 + y1) // 2

BLADE_HI = (225, 220, 255, 255)
BLADE_MID = (180, 175, 235, 255)
BLADE_LO = (120, 110, 190, 255)
GUARD = (210, 190, 90, 255)
GRIP = (90, 60, 40, 255)
RING = (170, 130, 255, 255)
RING_HI = (220, 200, 255, 255)

# 剑：竖直，剑尖朝上
tip_y = y0 + 4
hilt_y = y1 - 6
blade_w = 3

# 剑身(菱形截面简化为窄多边形)
d.polygon([
    (cx, tip_y),
    (cx + blade_w, tip_y + 6),
    (cx + blade_w, hilt_y - 4),
    (cx - blade_w, hilt_y - 4),
    (cx - blade_w, tip_y + 6),
], fill=BLADE_MID, outline=BLADE_LO)
# 中线高光
d.line([(cx, tip_y + 1), (cx, hilt_y - 5)], fill=BLADE_HI, width=1)

# 护手(十字架)
guard_y = hilt_y - 4
d.rectangle([cx - 9, guard_y, cx + 9, guard_y + 2], fill=GUARD, outline=BLADE_LO)

# 握把
d.rectangle([cx - 2, guard_y + 3, cx + 2, hilt_y], fill=GRIP, outline=(50, 34, 22, 255))
# 剑柄头
d.ellipse([cx - 3, hilt_y, cx + 3, hilt_y + 5], fill=GUARD, outline=BLADE_LO)

# 格挡冲击环：以护手为中心的破碎圆弧，体现"弹反瞬间"
ring_cx, ring_cy = cx, guard_y - 2
for radius, width, color, start, extent in [
    (14, 2, RING, -40, 100),
    (14, 2, RING, 140, 100),
    (18, 1, RING_HI, -25, 50),
    (18, 1, RING_HI, 155, 50),
]:
    bbox = [ring_cx - radius, ring_cy - radius, ring_cx + radius, ring_cy + radius]
    d.arc(bbox, start=start, end=start + extent, fill=color, width=width)

# 四道短促火花线，从冲击环向外迸发
for ang_deg in (20, 70, 110, 160, 200, 250, 290, 340):
    a = math.radians(ang_deg)
    r0, r1 = 15, 20
    x_s = ring_cx + r0 * math.cos(a)
    y_s = ring_cy + r0 * math.sin(a) * 0.85
    x_e = ring_cx + r1 * math.cos(a)
    y_e = ring_cy + r1 * math.sin(a) * 0.85
    if x0 <= x_e <= x1 and y0 <= y_e <= y1:
        d.line([(x_s, y_s), (x_e, y_e)], fill=RING_HI, width=1)

out_path = os.path.join(os.path.dirname(os.path.abspath(__file__)), "mod_parry.png")
img.save(out_path)
print("saved:", out_path)
