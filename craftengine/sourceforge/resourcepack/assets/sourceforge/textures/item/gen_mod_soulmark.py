"""MOD卡·夺魄印记(soulmark)图标生成脚本。64x64，共享暗金画框(new_card默认，因 tags 只有 stat，
非elemental/skill)。effects=vulnerability_amp（提升目标受到伤害的易伤/破防效果），图标构图：
一个紫色破碎的灵魂印记(眼形符文，中间裂开一道竖向裂纹，四周飘出两缕紫色灵魂雾)，呼应"夺魄"
主题与易伤词条(裂开的护盾/印记暗示防御被削弱)。
"""
import sys, os
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from mod_card_base import new_card

img, d, (x0, y0, x1, y1) = new_card(border_rgb=None)

cx = (x0 + x1) // 2
cy = (y0 + y1) // 2

PURPLE_HI = (200, 140, 240, 255)
PURPLE_MID = (150, 80, 200, 255)
PURPLE_LO = (90, 40, 130, 255)
PURPLE_GLOW = (170, 110, 220, 120)
WHITE = (235, 220, 250, 255)

# 外层眼形轮廓(菱形眼廓)
eye_pts = [
    (cx, cy - 15),
    (cx + 12, cy),
    (cx, cy + 15),
    (cx - 12, cy),
]
d.polygon(eye_pts, outline=PURPLE_HI)
eye_pts_in = [
    (cx, cy - 12),
    (cx + 9, cy),
    (cx, cy + 12),
    (cx - 9, cy),
]
d.polygon(eye_pts_in, fill=PURPLE_LO, outline=PURPLE_MID)

# 中央瞳孔(印记核心)
d.ellipse([cx - 4, cy - 4, cx + 4, cy + 4], fill=PURPLE_HI, outline=WHITE)
d.ellipse([cx - 1, cy - 1, cx + 1, cy + 1], fill=WHITE)

# 竖向裂纹贯穿眼形(印记破碎/易伤意象)
crack = [
    (cx - 1, cy - 14),
    (cx + 2, cy - 6),
    (cx - 2, cy - 1),
    (cx + 2, cy + 6),
    (cx - 1, cy + 14),
]
for i in range(len(crack) - 1):
    d.line([crack[i], crack[i + 1]], fill=WHITE, width=1)

# 左右两缕飘出的灵魂雾(小圆点渐远渐淡)
for sign in (-1, 1):
    for i, r in enumerate([2, 2, 1, 1]):
        px = cx + sign * (14 + i * 4)
        py = cy - 6 - i * 3
        alpha = 200 - i * 45
        d.ellipse([px - r, py - r, px + r, py + r], fill=(170, 110, 220, max(40, alpha)))

img.save(os.path.join(os.path.dirname(os.path.abspath(__file__)), "mod_soulmark.png"))
print("saved mod_soulmark.png")
