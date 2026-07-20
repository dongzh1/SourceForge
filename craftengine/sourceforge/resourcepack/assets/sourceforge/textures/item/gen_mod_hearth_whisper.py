"""MOD卡·篝火低语(hearth_whisper) 图标生成脚本。64x64背景+图标一次烤死，共享画框来自 mod_card_base.new_card。
被动技能(passive-skill:true) -> 画框青绿色系 (70,190,170)。
构图：交叉原木(篝火底座) + 中央柔和跳动的火焰 + 火焰上方一颗小爱心(呼应"低语"的温暖治愈/守护意象)，
两侧点缀几点飘散暖色火星，暗示光环/低语的扩散感。承接旧版16x16的交叉原木+火焰+爱心手法，放大加粗。
"""
import sys, os
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from mod_card_base import new_card

img, d, (x0, y0, x1, y1) = new_card(border_rgb=(70, 190, 170))

cx = (x0 + x1) // 2
cy = (y0 + y1) // 2

LOG_HI = (150, 100, 60, 255)
LOG_MID = (110, 72, 42, 255)
LOG_LO = (70, 46, 26, 255)

log_y = cy + 10

def draw_log(lx1, ly1, lx2, ly2, w=6):
    d.line([lx1, ly1, lx2, ly2], fill=LOG_MID, width=w)
    d.line([lx1, ly1 - 1, lx2, ly2 - 1], fill=LOG_HI, width=2)
    d.line([lx1, ly1 + w // 2, lx2, ly2 + w // 2], fill=LOG_LO, width=2)

draw_log(cx - 16, log_y + 6, cx + 16, log_y - 4)
draw_log(cx - 16, log_y - 4, cx + 16, log_y + 6)

for (nx, ny) in [(cx - 12, log_y + 3), (cx + 12, log_y - 1)]:
    d.ellipse([nx - 2, ny - 2, nx + 2, ny + 2], fill=LOG_LO)

flame_base_y = log_y - 6
FLAME_OUTER = (200, 90, 40, 255)
FLAME_MID = (235, 150, 50, 255)
FLAME_INNER = (255, 210, 110, 255)

outer = [
    (cx, flame_base_y - 30),
    (cx - 11, flame_base_y - 10),
    (cx - 8, flame_base_y),
    (cx + 8, flame_base_y),
    (cx + 11, flame_base_y - 10),
]
d.polygon(outer, fill=FLAME_OUTER)

mid = [
    (cx, flame_base_y - 23),
    (cx - 7, flame_base_y - 8),
    (cx - 5, flame_base_y),
    (cx + 5, flame_base_y),
    (cx + 7, flame_base_y - 8),
]
d.polygon(mid, fill=FLAME_MID)

inner = [
    (cx, flame_base_y - 14),
    (cx - 4, flame_base_y - 4),
    (cx + 4, flame_base_y - 4),
]
d.polygon(inner, fill=FLAME_INNER)

HEART = (255, 170, 190, 255)
heart_cy = flame_base_y - 34
d.ellipse([cx - 6, heart_cy - 3, cx, heart_cy + 3], fill=HEART)
d.ellipse([cx, heart_cy - 3, cx + 6, heart_cy + 3], fill=HEART)
d.polygon([(cx - 6, heart_cy + 1), (cx + 6, heart_cy + 1), (cx, heart_cy + 8)], fill=HEART)

SPARK = (255, 200, 130, 255)
for (sx, sy, r) in [(cx - 14, flame_base_y - 20, 1), (cx + 15, flame_base_y - 16, 1), (cx - 10, flame_base_y - 28, 1)]:
    d.ellipse([sx - r, sy - r, sx + r, sy + r], fill=SPARK)

out_path = os.path.join(os.path.dirname(os.path.abspath(__file__)), "mod_hearth_whisper.png")
img.save(out_path)
print("saved:", out_path)
