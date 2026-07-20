"""MOD卡·汲取导管(siphon_rounds) 图标生成脚本。tags=stat -> 默认暗金画框(border_rgb=None)。
效果 life_steal：造成伤害按比例转化为自身回血。图标构图：一枚子弹(汲取导管的"导管"/弹药意象)
垂直竖立在画面中心，弹体内嵌一滴红色血珠，一条弧形箭头从底部血珠处向上环绕缠绕弹壳，
表示"伤害经由这发子弹被抽汲回流"的因果关系；弹尖朝上带高光，弹壳用深红/暗铜双色分层增加立体感。
"""
import sys, os
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from mod_card_base import new_card

img, d, (x0, y0, x1, y1) = new_card(border_rgb=None)

cx = (x0 + x1) // 2

# --- 子弹主体（竖直，尖朝上）---
bullet_top = y0 + 6
bullet_bottom = y1 - 8
case_top = bullet_top + 14  # 弹头与弹壳分界
half_w = 7

# 弹头（尖顶三角+小段直筒）
d.polygon([
    (cx, bullet_top),
    (cx - half_w, case_top),
    (cx + half_w, case_top),
], fill=(225, 200, 150, 255), outline=(120, 90, 50, 255))

# 弹壳（矩形，暗铜色，底部略窄）
case_half_w = half_w
d.polygon([
    (cx - case_half_w, case_top),
    (cx + case_half_w, case_top),
    (cx + case_half_w - 1, bullet_bottom),
    (cx - case_half_w + 1, bullet_bottom),
], fill=(120, 60, 40, 255), outline=(70, 30, 20, 255))

# 弹壳分层高光条
d.line([(cx - case_half_w + 2, case_top + 5), (cx + case_half_w - 2, case_top + 5)], fill=(170, 90, 60, 255), width=1)
d.line([(cx - case_half_w + 2, case_top + 12), (cx + case_half_w - 2, case_top + 12)], fill=(170, 90, 60, 255), width=1)

# 弹头高光
d.line([(cx - 2, bullet_top + 4), (cx - 4, case_top - 1)], fill=(255, 240, 210, 255), width=1)

# --- 底部血珠（生命汲取来源）---
drop_cy = bullet_bottom + 5
d.polygon([
    (cx, drop_cy - 5),
    (cx - 4, drop_cy + 2),
    (cx, drop_cy + 6),
    (cx + 4, drop_cy + 2),
], fill=(210, 30, 30, 255), outline=(255, 90, 90, 255))
d.ellipse([cx - 1, drop_cy - 1, cx + 1, drop_cy + 1], fill=(255, 140, 140, 255))

# --- 弧形回流箭头：从血珠环绕缠绕上升到弹壳 ---
import math
arrow_pts = []
for i in range(9):
    t = i / 8
    ang = math.pi * (0.15 + 1.2 * t)  # 环绕右侧上升
    r = 11 - t * 2
    ax = cx + r * math.sin(ang)
    ay = drop_cy - t * (drop_cy - case_top - 6)
    arrow_pts.append((ax, ay))
d.line(arrow_pts, fill=(255, 70, 70, 255), width=2)
# 箭头头部（指向弹壳）
hx, hy = arrow_pts[-1]
d.polygon([(hx, hy - 4), (hx - 4, hy + 2), (hx + 3, hy + 3)], fill=(255, 70, 70, 255))

out = os.path.join(os.path.dirname(os.path.abspath(__file__)), "mod_siphon_rounds.png")
img.save(out)
print("saved", out)
