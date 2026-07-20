"""MOD卡·炽焰(hellfire) 图标生成脚本。64x64，背景+图标一次烤进同一张图(不再是运行时layer0/1合成)。
tags: stat,elemental(热) effects: heat_damage,status_chance -> 画一团分层火焰(外层暗红/中层橙/内层
高光黄白)代表 heat_damage，火焰周围飘几粒小火星(不同大小/透明度)代表 status_chance(灼烧几率)。
画框色沿用元素-热 配色 (200,80,40)。仿 gen_mod_hearth_whisper.py 的写法：import mod_card_base.new_card
拿到内圈范围，用 ImageDraw polygon/ellipse 拼出图标。
"""
import sys, os
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from mod_card_base import new_card

BORDER_RGB = (200, 80, 40)

img, d, (x0, y0, x1, y1) = new_card(border_rgb=BORDER_RGB)

cx = (x0 + x1) / 2
top = y0 + 4
bottom = y1 - 3

OUTER = (150, 40, 20, 255)
MID = (230, 100, 30, 255)
INNER = (255, 170, 40, 255)
CORE = (255, 230, 140, 255)

# 外层暗红火焰轮廓
d.polygon([
    (cx, top),
    (cx + 9, top + 14),
    (cx + 11, top + 24),
    (cx + 7, bottom),
    (cx, bottom + 1),
    (cx - 7, bottom),
    (cx - 11, top + 24),
    (cx - 9, top + 14),
], fill=OUTER)

# 中层橙色火焰
d.polygon([
    (cx, top + 6),
    (cx + 6, top + 16),
    (cx + 8, top + 26),
    (cx + 5, bottom - 2),
    (cx, bottom - 1),
    (cx - 5, bottom - 2),
    (cx - 8, top + 26),
    (cx - 6, top + 16),
], fill=MID)

# 内层亮橙
d.polygon([
    (cx, top + 12),
    (cx + 4, top + 20),
    (cx + 5, top + 28),
    (cx + 3, bottom - 5),
    (cx, bottom - 4),
    (cx - 3, bottom - 5),
    (cx - 5, top + 28),
    (cx - 4, top + 20),
], fill=INNER)

# 内核高光(黄白)
d.polygon([
    (cx, top + 18),
    (cx + 2, top + 24),
    (cx + 2, top + 30),
    (cx, bottom - 8),
    (cx - 2, top + 30),
    (cx - 2, top + 24),
], fill=CORE)

# 飘散的火星/灼烧几率(status_chance)：几个不同大小/透明度的小圆点
sparks = [
    (cx + 13, top + 2, 2, (255, 150, 60, 220)),
    (cx - 14, top + 6, 1, (255, 190, 90, 190)),
    (cx + 10, top - 3, 1, (255, 210, 120, 160)),
    (cx - 10, top + 1, 2, (255, 140, 50, 200)),
    (cx + 3, top - 6, 1, (255, 200, 110, 150)),
]
for sx, sy, r, col in sparks:
    d.ellipse([sx - r, sy - r, sx + r, sy + r], fill=col)

out_path = os.path.join(os.path.dirname(os.path.abspath(__file__)), "mod_hellfire.png")
img.save(out_path)
print("saved", out_path)
