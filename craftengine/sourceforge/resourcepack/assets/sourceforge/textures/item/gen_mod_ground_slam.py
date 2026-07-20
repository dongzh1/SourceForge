"""MOD卡·震地(ground_slam) 图标生成脚本。64x64，紫色系画框(技能类，武器主动技能)。
构图：一只举拳砸下的拳头(简化为菱形拳峰+竖臂)砸在地面上，地面炸出放射状裂纹+左右扬起碎块，
外圈叠加冲击波圆弧，体现"举锤/拳砸地面震荡四周"的主题。仿 gen_mod_hearth_whisper.py 手法，
纯几何 polygon/line/ellipse 拼接，不做照片级细节。
"""
import sys, os
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from mod_card_base import new_card

img, d, (x0, y0, x1, y1) = new_card(border_rgb=(150, 90, 220))

cx = (x0 + x1) // 2
ground_y = y0 + 34

# 冲击波圆弧（浅紫，半透明感用较暗色模拟）
for r, col in [(20, (150, 110, 210, 255)), (14, (180, 140, 230, 255))]:
    d.arc([cx - r, ground_y - 4, cx + r, ground_y + 4], start=200, end=340, fill=col, width=2)

# 地面线
d.line([x0 + 2, ground_y, x1 - 2, ground_y], fill=(90, 60, 130, 255), width=2)

# 放射裂纹
crack_pts = [
    (cx - 14, ground_y + 1), (cx - 7, ground_y + 5), (cx + 7, ground_y + 5), (cx + 14, ground_y + 1),
    (cx - 10, ground_y + 8), (cx + 10, ground_y + 8),
]
for (tx, ty) in crack_pts:
    d.line([cx, ground_y, tx, ty], fill=(220, 200, 250, 255), width=1)

# 左右碎块飞起
for dx, dy in [(-16, -6), (16, -6), (-20, 2), (20, 2)]:
    bx, by = cx + dx, ground_y + dy
    d.polygon([(bx - 2, by - 2), (bx + 2, by - 3), (bx + 3, by + 1), (bx - 1, by + 2)],
              fill=(120, 90, 160, 255), outline=(200, 170, 240, 255))

# 拳头竖臂（举起姿态，从上方砸下）
arm_top = y0 + 2
fist_cy = ground_y - 14
d.polygon([(cx - 3, arm_top), (cx + 3, arm_top), (cx + 4, fist_cy - 4), (cx - 4, fist_cy - 4)],
          fill=(90, 60, 140, 255), outline=(180, 150, 230, 255))

# 拳峰(菱形，代表拳头)
fist_r = 8
d.polygon([
    (cx, fist_cy - fist_r), (cx + fist_r, fist_cy), (cx, fist_cy + fist_r), (cx - fist_r, fist_cy)
], fill=(160, 110, 220, 255), outline=(230, 200, 255, 255))
# 拳头高光
d.polygon([(cx - 2, fist_cy - 4), (cx + 2, fist_cy - 4), (cx, fist_cy)], fill=(220, 190, 255, 255))

out = os.path.join(os.path.dirname(os.path.abspath(__file__)), "mod_ground_slam.png")
img.save(out)
print("saved", out)
