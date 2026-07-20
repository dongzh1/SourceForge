# MOD卡·铁壁架势(iron_stance) 图标生成脚本。64x64，共享暗框工具 mod_card_base.new_card()
# 画紫色技能画框(武器主动技能)。图标构图：中央一面铁灰色盾牌(竖直放置，代表"架势/格挡")，
# 盾面刻十字铆钉纹，盾牌两侧各画一道紫色能量弧线(向外扩散，代表蓄力/技能激活的架势感)，
# 底部加两道脚印/站桩短线暗示"架势"二字。仿 gen_mod_hearth_whisper.py 的写法：直接 PIL 几何拼接。
import sys, os
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from mod_card_base import new_card

SKILL_PURPLE = (150, 90, 220)

img, d, (x0, y0, x1, y1) = new_card(border_rgb=SKILL_PURPLE)

cx = (x0 + x1) // 2
cy = (y0 + y1) // 2

STEEL_HI = (200, 205, 215, 255)
STEEL_MID = (140, 148, 160, 255)
STEEL_LO = (70, 76, 88, 255)
RIM = (230, 235, 245, 255)
PURPLE_GLOW = (180, 130, 255, 255)
PURPLE_DEEP = (120, 60, 200, 255)

# --- 盾牌主体：竖直六边形盾形 (top flat, sides taper to point at bottom) ---
shield_w = 20
top = cy - 17
bottom = cy + 19
left = cx - shield_w // 2
right = cx + shield_w // 2

shield_poly = [
    (left, top),
    (right, top),
    (right, cy + 4),
    (cx, bottom),
    (left, cy + 4),
]
d.polygon(shield_poly, fill=STEEL_MID, outline=RIM)

# 内层暗面（左下阴影）
d.polygon([
    (left, top), (cx, top), (cx, bottom), (left, cy + 4)
], fill=STEEL_LO)

# 高光条（右上）
d.polygon([
    (cx, top), (right, top), (right, cy + 4), (cx + 3, cy + 10)
], fill=STEEL_HI)

# 外边框描边加粗
d.line(shield_poly + [shield_poly[0]], fill=RIM, width=2)

# 中央竖脊
d.line([(cx, top + 2), (cx, bottom - 2)], fill=RIM, width=2)

# 十字铆钉纹（盾面四个铆钉点）
for (rx, ry) in [(cx - 6, cy - 8), (cx + 6, cy - 8), (cx - 6, cy + 4), (cx + 6, cy + 4)]:
    d.ellipse([rx - 2, ry - 2, rx + 2, ry + 2], fill=STEEL_HI, outline=STEEL_LO)

# 中心宝石/纹章
d.ellipse([cx - 4, cy - 4, cx + 4, cy + 4], fill=PURPLE_DEEP, outline=PURPLE_GLOW)
d.ellipse([cx - 2, cy - 2, cx + 2, cy + 2], fill=PURPLE_GLOW)

# --- 两侧紫色能量弧线（架势蓄力感），从盾牌左右外扩 ---
def arc_box(side):
    if side == "L":
        return [x0 - 3, cy - 16, cx - 6, cy + 16]
    else:
        return [cx + 6, cy - 16, x1 + 3, cy + 16]

d.arc(arc_box("L"), start=100, end=260, fill=PURPLE_GLOW, width=2)
d.arc(arc_box("R"), start=280, end=80, fill=PURPLE_GLOW, width=2)

# 弧线上的小能量点
for ang_x, ang_y in [(x0 + 2, cy - 10), (x0 + 1, cy + 10), (x1 - 2, cy - 10), (x1 - 1, cy + 10)]:
    d.ellipse([ang_x - 1, ang_y - 1, ang_x + 1, ang_y + 1], fill=PURPLE_GLOW)

# --- 底部站桩脚印短线（架势姿态暗示） ---
foot_y = y1 - 3
d.line([(cx - 8, foot_y), (cx - 3, foot_y)], fill=STEEL_HI, width=2)
d.line([(cx + 3, foot_y), (cx + 8, foot_y)], fill=STEEL_HI, width=2)

out_path = os.path.join(os.path.dirname(os.path.abspath(__file__)), "mod_iron_stance.png")
img.save(out_path)
print("saved", out_path)
