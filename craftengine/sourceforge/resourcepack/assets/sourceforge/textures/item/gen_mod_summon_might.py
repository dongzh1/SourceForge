# MOD卡·役使(summon_might) 图标生成脚本。
# tags:stat（纯属性词条，不含elemental/skill/keystone）-> 画框用默认暗金 border_rgb=None。
# 构图：一个暗色召唤法阵(虚线圆+内接三角)，中心一只破阵而出的兽爪(claw)向上探出，
# 象征"役使"召唤物增伤——法阵代表召唤契约，兽爪代表被役使的召唤物之力。
import sys, os
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from mod_card_base import new_card
import math

img, d, (x0, y0, x1, y1) = new_card(border_rgb=None)

cx = (x0 + x1) / 2
cy = (y0 + y1) / 2 + 2
R = 19

CIRCLE_HI = (200, 150, 230, 255)
CIRCLE_LO = (110, 70, 150, 255)
CLAW = (235, 225, 245, 255)
CLAW_SHADE = (150, 120, 170, 255)
GLOW = (170, 120, 220, 90)

# soft glow disc behind everything
d.ellipse([cx - R - 3, cy - R - 3, cx + R + 3, cy + R + 3], fill=GLOW)

# dashed outer summoning circle
n_dashes = 28
for i in range(n_dashes):
    if i % 2 == 0:
        continue
    a0 = (i / n_dashes) * 2 * math.pi
    a1 = ((i + 0.7) / n_dashes) * 2 * math.pi
    p0 = (cx + R * math.cos(a0), cy + R * math.sin(a0))
    p1 = (cx + R * math.cos(a1), cy + R * math.sin(a1))
    d.line([p0, p1], fill=CIRCLE_HI, width=2)

# inner solid circle
r2 = R - 6
d.ellipse([cx - r2, cy - r2, cx + r2, cy + r2], outline=CIRCLE_LO, width=1)

# inscribed triangle (arcane rune)
tri = []
for k in range(3):
    a = -math.pi / 2 + k * (2 * math.pi / 3)
    tri.append((cx + r2 * math.cos(a), cy + r2 * math.sin(a)))
d.line(tri + [tri[0]], fill=CIRCLE_LO, width=1)

# small rune dots at circle nodes
for k in range(6):
    a = k * (math.pi / 3)
    px, py = cx + R * math.cos(a), cy + R * math.sin(a)
    d.ellipse([px - 1, py - 1, px + 1, py + 1], fill=CIRCLE_HI)

# claw: three curved talons rising from center, breaking through the circle
def talon(base, tip_angle_deg, length, width):
    ang = math.radians(tip_angle_deg)
    bx, by = base
    tx = bx + length * math.sin(ang) * 0.35
    ty = by - length
    mx = bx + length * math.sin(ang) * 0.6
    my = by - length * 0.55
    # polygon claw shape: base wide, tip pointed, slight curve via midpoint offset
    left = (bx - width, by)
    right = (bx + width, by)
    poly = [left, (mx - width * 0.4, my), (tx, ty), (mx + width * 0.4, my), right]
    d.polygon(poly, fill=CLAW, outline=CLAW_SHADE)

base_y = cy + 3
talon((cx - 7, base_y), -18, 20, 2.6)
talon((cx, base_y + 1), 0, 24, 3.2)
talon((cx + 7, base_y), 18, 20, 2.6)

# knuckle base blob
d.ellipse([cx - 8, base_y - 3, cx + 8, base_y + 6], fill=CLAW_SHADE)
d.ellipse([cx - 6, base_y - 2, cx + 6, base_y + 4], fill=CLAW)

out_path = os.path.join(os.path.dirname(os.path.abspath(__file__)), "mod_summon_might.png")
img.save(out_path)
print("saved", out_path)
