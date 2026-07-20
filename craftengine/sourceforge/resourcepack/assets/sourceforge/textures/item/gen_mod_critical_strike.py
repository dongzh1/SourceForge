# MOD卡·致命一击(critical_strike) 图标生成脚本。tags=stat -> 默认暗金画框(border_rgb=None)。
# 构图：一把倾斜的短剑刺穿一个爆裂的暴击星芒(红色冲击线+星形)，一眼看出"暴击"主题。
import sys, os
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from mod_card_base import new_card

img, d, (x0, y0, x1, y1) = new_card(border_rgb=None)

cx = (x0 + x1) // 2
cy = (y0 + y1) // 2

RED_HI = (255, 90, 70, 255)
RED_MID = (210, 40, 30, 255)
RED_LO = (120, 15, 15, 255)
STEEL_HI = (230, 230, 235, 255)
STEEL_MID = (180, 182, 190, 255)
STEEL_LO = (110, 112, 120, 255)
GOLD = (222, 168, 45, 255)

# 冲击星芒(暴击标志)，先画在剑后面
import math
star_r_out = 15
star_r_in = 6
pts = []
for i in range(8):
    ang = math.pi / 8 * i - math.pi / 2
    r = star_r_out if i % 2 == 0 else star_r_in
    pts.append((cx + r * math.cos(ang), cy + r * math.sin(ang)))
d.polygon(pts, fill=RED_LO)

star_r_out2 = 11
star_r_in2 = 4
pts2 = []
for i in range(8):
    ang = math.pi / 8 * i - math.pi / 2
    r = star_r_out2 if i % 2 == 0 else star_r_in2
    pts2.append((cx + r * math.cos(ang), cy + r * math.sin(ang)))
d.polygon(pts2, fill=RED_MID)

# 剑身：从左上到右下斜插，剑尖朝右下
blade_len = 20
angle = math.radians(40)
dx, dy = math.cos(angle), math.sin(angle)
nx, ny = -dy, dx
tip = (cx + blade_len * dx, cy + blade_len * dy)
base = (cx - blade_len * dx, cy - blade_len * dy)
w = 3.2
blade_poly = [
    (base[0] + nx * w, base[1] + ny * w),
    (tip[0] + nx * 0.6, tip[1] + ny * 0.6),
    (cx + (blade_len + 5) * dx, cy + (blade_len + 5) * dy),
    (tip[0] - nx * 0.6, tip[1] - ny * 0.6),
    (base[0] - nx * w, base[1] - ny * w),
]
d.polygon(blade_poly, fill=STEEL_MID, outline=STEEL_LO)
# 剑脊高光细线
d.line([base, (cx + (blade_len + 5) * dx, cy + (blade_len + 5) * dy)], fill=STEEL_HI, width=1)

# 护手
guard_cx, guard_cy = base
gw = 6
d.line([(guard_cx + nx * gw, guard_cy + ny * gw), (guard_cx - nx * gw, guard_cy - ny * gw)], fill=GOLD, width=2)

# 剑柄
hilt_end = (base[0] - dx * 6, base[1] - dy * 6)
d.line([base, hilt_end], fill=(90, 60, 30, 255), width=3)
d.ellipse([hilt_end[0] - 1.5, hilt_end[1] - 1.5, hilt_end[0] + 1.5, hilt_end[1] + 1.5], fill=GOLD)

# 几道暴击冲击速度线
for off in (-10, 10):
    d.line([(cx + off, y0 + 3), (cx + off * 1.6, y0 - 2 + 8)], fill=RED_HI, width=1)

img.save(os.path.join(os.path.dirname(os.path.abspath(__file__)), "mod_critical_strike.png"))
print("saved mod_critical_strike.png")
