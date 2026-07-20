# 生成 mod_thorned_retribution.png（MOD卡·荆棘反震，64x64，纯stat词条→默认暗金画框）。
# 构图：内圈中央一枚暗红盾牌轮廓，盾面外周伸出一圈灰白荆棘尖刺(反震意象)，盾心一滴暗红汁液
# 代表"反震伤害"，整体表达"受创即反击"的荆棘护体主题。仿照 mod_hearth_whisper.py 的极简
# 多边形/线段拼图手法，不用照片级细节。
import sys, os
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from mod_card_base import new_card

img, d, (x0, y0, x1, y1) = new_card(border_rgb=None)

cx = (x0 + x1) / 2
cy = (y0 + y1) / 2

THORN = (200, 200, 200, 255)
THORN_DARK = (120, 120, 120, 255)
SHIELD_HI = (150, 40, 40, 255)
SHIELD_MID = (110, 24, 24, 255)
SHIELD_LO = (70, 12, 12, 255)
DROP = (190, 30, 30, 255)
DROP_HI = (230, 90, 90, 255)

# 盾牌轮廓（五边形：顶平，两侧收窄至底尖）
shield_w = 20
shield_top = cy - 15
shield_bot = cy + 17
shield = [
    (cx - shield_w / 2, shield_top),
    (cx + shield_w / 2, shield_top),
    (cx + shield_w / 2 - 2, shield_top + 18),
    (cx, shield_bot),
    (cx - shield_w / 2 + 2, shield_top + 18),
]
d.polygon(shield, fill=SHIELD_MID, outline=SHIELD_LO)
# 盾面高光条
d.polygon([
    (cx - shield_w / 2 + 2, shield_top + 2),
    (cx - 2, shield_top + 2),
    (cx - 3, shield_bot - 6),
    (cx - shield_w / 2 + 4, shield_top + 16),
], fill=SHIELD_HI)

# 盾牌外周一圈荆棘尖刺（8根，方向朝外）
import math
n_thorns = 8
r_in = 15
r_out = 24
for i in range(n_thorns):
    ang = math.pi * 2 * i / n_thorns + math.pi / 8
    bx = cx + r_in * math.cos(ang)
    by = cy + r_in * math.sin(ang) * 0.9
    tx = cx + r_out * math.cos(ang)
    ty = cy + r_out * math.sin(ang) * 0.9
    # 每根刺画成细三角
    perp = ang + math.pi / 2
    w = 2.0
    p1 = (bx + w * math.cos(perp), by + w * math.sin(perp))
    p2 = (bx - w * math.cos(perp), by - w * math.sin(perp))
    color = THORN if i % 2 == 0 else THORN_DARK
    d.polygon([p1, p2, (tx, ty)], fill=color)

# 盾心一滴暗红反震汁液（水滴形：圆+三角尖）
drop_r = 4
d.ellipse([cx - drop_r, cy - 2, cx + drop_r, cy - 2 + drop_r * 2], fill=DROP)
d.polygon([(cx - drop_r, cy - 2 + drop_r * 0.4), (cx + drop_r, cy - 2 + drop_r * 0.4), (cx, cy - 9)], fill=DROP)
d.ellipse([cx - 1, cy - 1, cx + 1, cy + 1], fill=DROP_HI)

out = os.path.join(os.path.dirname(os.path.abspath(__file__)), "mod_thorned_retribution.png")
img.save(out)
print("saved", out)
