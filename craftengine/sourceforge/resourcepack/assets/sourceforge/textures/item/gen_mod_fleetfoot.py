# MOD卡·疾风之靴(fleetfoot) 图标生成脚本。tags=stat, effects=movement_speed，
# 纯属性卡 -> border_rgb=None(默认暗金画框)。构图：一只简化的靴子轮廓 + 靴跟后方三道向左倾斜的
# 速度线，营造"穿上就能疾跑"的观感；靴身用浅棕/米色区分暗色背景，速度线用亮青白色提示"风速"。
import sys, os
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from mod_card_base import new_card

img, d, (x0, y0, x1, y1) = new_card(border_rgb=None)

BOOT_HI = (210, 175, 130, 255)
BOOT_MID = (170, 130, 90, 255)
BOOT_LO = (110, 80, 55, 255)
SOLE = (60, 45, 35, 255)
WIND = (170, 230, 240, 255)
WIND_DIM = (110, 190, 210, 200)

cx = (x0 + x1) // 2
cy = (y0 + y1) // 2

# 靴子主体：一个简化的侧面靴形(多边形)，靴筒偏右上，靴头偏左下
boot_pts = [
    (cx + 6, cy - 16),   # 靴筒顶后
    (cx + 12, cy - 14),  # 靴筒顶前
    (cx + 12, cy + 2),   # 靴筒前下到脚背
    (cx + 16, cy + 6),   # 脚背到脚尖
    (cx - 8, cy + 10),   # 脚尖
    (cx - 10, cy + 14),  # 鞋头下沿
    (cx + 14, cy + 14),  # 鞋底前
    (cx + 16, cy + 10),  # 鞋跟下
    (cx + 6, cy + 6),    # 鞋跟内侧回到靴筒
]
d.polygon(boot_pts, fill=BOOT_MID, outline=BOOT_HI)

# 鞋底加深
d.polygon([(cx - 10, cy + 14), (cx + 14, cy + 14), (cx + 16, cy + 10), (cx + 6, cy + 10), (cx - 8, cy + 12)], fill=SOLE)

# 靴筒高光竖条
d.line([(cx + 8, cy - 14), (cx + 8, cy + 2)], fill=BOOT_HI, width=2)
# 靴口边缘
d.line([(cx + 6, cy - 16), (cx + 12, cy - 14)], fill=BOOT_LO, width=1)

# 速度线：三道向左下倾斜的弧线/线段，从靴子右后方向左飘出，长短错落表现疾风
for i, (ox, oy, length) in enumerate([(-2, -6, 14), (-6, -1, 18), (-10, 5, 12)]):
    sx, sy = cx - 4 + ox, cy + oy
    ex, ey = sx - length, sy - 3
    color = WIND if i == 1 else WIND_DIM
    d.line([(sx, sy), (ex, ey)], fill=color, width=2)

# 小翅膀点缀：靴筒侧面两片小羽翼三角，强调"疾风"
wing_base = (cx + 12, cy - 8)
d.polygon([wing_base, (wing_base[0] + 6, wing_base[1] - 3), (wing_base[0] + 4, wing_base[1] + 3)], fill=WIND_DIM, outline=WIND)
d.polygon([(wing_base[0] + 1, wing_base[1] + 2), (wing_base[0] + 7, wing_base[1] + 2), (wing_base[0] + 4, wing_base[1] + 7)], fill=WIND_DIM, outline=WIND)

out_path = os.path.join(os.path.dirname(os.path.abspath(__file__)), "mod_fleetfoot.png")
img.save(out_path)
print("saved:", out_path)
