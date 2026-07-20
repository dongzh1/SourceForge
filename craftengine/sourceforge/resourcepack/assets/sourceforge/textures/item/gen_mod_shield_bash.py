# MOD卡·盾击反震（shield_bash）图标生成脚本。仿 gen_mod_hearth_whisper.py 风格：
# 用 mod_card_base.new_card() 出画框(技能类=紫色系 (150,90,220))，再在内圈用简单几何拼一个
# "盾牌正面 + 反震冲击弧线" 的构图：中央一面竖立的盾(五边形轮廓+十字棱线)，盾面前方炸开
# 几道放射状的白紫色冲击线，象征格挡后的反震效果。64x64，一次烤死背景+图标。
import sys, os
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from mod_card_base import new_card

SKILL_PURPLE = (150, 90, 220)

img, d, (x0, y0, x1, y1) = new_card(border_rgb=SKILL_PURPLE)

cx = (x0 + x1) / 2
cy = (y0 + y1) / 2 + 2

# --- 盾牌轮廓（五边形：圆顶+尖底）---
shield_w = 20
shield_top = cy - 16
shield_bot = cy + 16
shield_pts = [
    (cx - shield_w / 2, shield_top + 4),
    (cx, shield_top),
    (cx + shield_w / 2, shield_top + 4),
    (cx + shield_w / 2, shield_top + 16),
    (cx, shield_bot),
    (cx - shield_w / 2, shield_top + 16),
]

STEEL_HI = (200, 205, 220, 255)
STEEL_MID = (140, 148, 170, 255)
STEEL_LO = (70, 76, 95, 255)
GOLD_TRIM = (220, 190, 90, 255)

d.polygon(shield_pts, fill=STEEL_MID, outline=STEEL_LO)
# 内层浮雕面
inner_pts = [(px * 0.8 + cx * 0.2, py * 0.8 + cy * 0.2) for (px, py) in shield_pts]
d.polygon(inner_pts, outline=STEEL_HI)
# 十字棱线
d.line([(cx, shield_top + 2), (cx, shield_bot - 2)], fill=GOLD_TRIM, width=2)
d.line([(cx - shield_w / 2 + 2, shield_top + 10), (cx + shield_w / 2 - 2, shield_top + 10)], fill=GOLD_TRIM, width=2)
# 中心宝石
d.ellipse([cx - 3, shield_top + 7, cx + 3, shield_top + 13], fill=(230, 210, 255, 255), outline=(255, 255, 255, 255))

# --- 反震冲击：从盾面向外辐射的折线（闪电感的短促线段）---
IMPACT = (225, 190, 255, 255)
IMPACT_CORE = (255, 255, 255, 255)
import math
for ang_deg in (-55, -20, 15, 50):
    ang = math.radians(ang_deg - 90)
    ox = cx + math.cos(ang) * 10
    oy = cy + math.sin(ang) * 10 - 6
    ex = cx + math.cos(ang) * 24
    ey = cy + math.sin(ang) * 24 - 6
    mx = (ox + ex) / 2 + math.cos(ang + 1.2) * 4
    my = (oy + ey) / 2 + math.sin(ang + 1.2) * 4
    d.line([(ox, oy), (mx, my)], fill=IMPACT, width=2)
    d.line([(mx, my), (ex, ey)], fill=IMPACT_CORE, width=2)

img.save(os.path.join(os.path.dirname(os.path.abspath(__file__)), "mod_shield_bash.png"))
print("saved mod_shield_bash.png")
