"""MOD卡·导能护盾(redirection) 图标生成脚本。64x64 背景+图标一次烤死(见 mod_card_base.py 头注释)。
纯属性卡(tags: stat)，效果 shield_capacity -> 用默认暗金画框(border_rgb=None)。
图标构图：内圈中央画一个青蓝色能量护盾轮廓(六边形盾牌)，盾面中心一道折线箭头表示"redirection"
(来袭伤害被护盾折射改道)，箭头颜色用亮青色与盾体区分，呼应"导能"主题。
"""
import sys, os
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from mod_card_base import new_card

img, d, (x0, y0, x1, y1) = new_card(border_rgb=None)

cx = (x0 + x1) / 2
cy = (y0 + y1) / 2

SHIELD_HI = (150, 220, 240, 255)
SHIELD_MID = (70, 160, 200, 255)
SHIELD_LO = (30, 90, 130, 255)
GLOW = (200, 240, 250, 255)
ARROW = (255, 230, 120, 255)
ARROW_LO = (200, 160, 60, 255)

# 六边形盾牌轮廓(尖顶朝上，像纹章盾)
top = cy - 17
bot = cy + 17
w = 13
shield_pts = [
    (cx, top),
    (cx + w, top + 8),
    (cx + w, bot - 10),
    (cx, bot),
    (cx - w, bot - 10),
    (cx - w, top + 8),
]
d.polygon(shield_pts, fill=SHIELD_LO, outline=SHIELD_MID)

inner_pts = [
    (cx, top + 4),
    (cx + w - 4, top + 10),
    (cx + w - 4, bot - 13),
    (cx, bot - 4),
    (cx - w + 4, bot - 13),
    (cx - w + 4, top + 10),
]
d.polygon(inner_pts, outline=SHIELD_HI)

# 盾面内的折线"折射"箭头：从左上射入，在中心急转折向右下射出
p1 = (cx - 10, cy - 12)
p2 = (cx - 2, cy - 2)
p3 = (cx + 10, cy - 6)
p4 = (cx + 4, cy + 10)
d.line([p1, p2], fill=ARROW, width=2)
d.line([p2, p3], fill=ARROW, width=2)
d.line([p3, p4], fill=ARROW, width=2)

# 折射转折点小光点
d.ellipse([p2[0] - 2, p2[1] - 2, p2[0] + 2, p2[1] + 2], fill=GLOW)
d.ellipse([p3[0] - 2, p3[1] - 2, p3[0] + 2, p3[1] + 2], fill=GLOW)

# 箭头终端小箭尖(在p4方向)
d.polygon([
    (p4[0] - 3, p4[1] - 1),
    (p4[0] + 4, p4[1] + 2),
    (p4[0] - 1, p4[1] + 5),
], fill=ARROW_LO)

# 顶部小尖角高光
d.line([(cx, top), (cx, top + 4)], fill=GLOW, width=1)

out_path = os.path.join(os.path.dirname(os.path.abspath(__file__)), "mod_redirection.png")
img.save(out_path)
print("saved:", out_path)
