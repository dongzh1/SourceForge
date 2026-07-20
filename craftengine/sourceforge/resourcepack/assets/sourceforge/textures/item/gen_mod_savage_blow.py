"""MOD卡·凶蛮打击(savage_blow) 图标生成脚本。tags=stat(纯属性词条,关联critical_damage)，
按配色规则用默认暗金画框(border_rgb=None)。图标构图：一把银灰色斧刃劈砍出的对角伤痕，
背后一颗猩红色的暴击冲击星爆(放射线+菱形碎片)，直观表达"凶蛮打击→提升暴击伤害"。
64x64，画在 mod_card_base.new_card() 返回的内圈区域里。
"""
import sys, os
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from mod_card_base import new_card

img, d, (x0, y0, x1, y1) = new_card(border_rgb=None)

cx, cy = (x0 + x1) / 2, (y0 + y1) / 2

RED_HI = (255, 90, 70, 255)
RED_MID = (200, 40, 30, 255)
RED_LO = (110, 15, 15, 255)
STEEL_HI = (230, 230, 235, 255)
STEEL_MID = (170, 175, 185, 255)
STEEL_LO = (90, 92, 100, 255)

# --- 暴击冲击星爆(放在斧刃后方，作为背景层) ---
import math
for i in range(8):
    ang = i * (math.pi / 4) + math.pi / 16
    length = 13 if i % 2 == 0 else 9
    ex = cx + math.cos(ang) * length
    ey = cy + math.sin(ang) * length
    d.line([cx, cy, ex, ey], fill=RED_MID, width=2)
    tx = cx + math.cos(ang) * (length + 2)
    ty = cy + math.sin(ang) * (length + 2)
    d.line([ex, ey, tx, ty], fill=RED_HI, width=1)

# 中心小菱形冲击核心
d.polygon([(cx, cy - 4), (cx + 4, cy), (cx, cy + 4), (cx - 4, cy)], fill=RED_HI, outline=RED_LO)

# --- 斧刃(对角劈砍姿态，从左上到右下) ---
# 斧柄(短，木色)
handle_color = (120, 80, 45, 255)
d.line([cx - 12, cy + 14, cx - 2, cy + 4], fill=handle_color, width=3)

# 斧头：一个新月形斧刃，位于斧柄顶端偏右上
axe_head = [
    (cx - 4, cy + 2),
    (cx + 2, cy - 10),
    (cx + 12, cy - 14),
    (cx + 14, cy - 8),
    (cx + 6, cy - 2),
    (cx + 2, cy + 4),
]
d.polygon(axe_head, fill=STEEL_MID, outline=STEEL_LO)
# 刃口高光边
d.line([(cx + 12, cy - 14), (cx + 14, cy - 8), (cx + 6, cy - 2)], fill=STEEL_HI, width=1)

# 劈砍伤痕线(贯穿斧刃前方，表示"打击"动作轨迹)
d.line([cx - 14, cy - 14, cx + 15, cy + 15], fill=RED_HI, width=2)
d.line([cx - 14, cy - 12, cx + 13, cy + 15], fill=RED_LO, width=1)

img.save(os.path.join(os.path.dirname(os.path.abspath(__file__)), "mod_savage_blow.png"))
print("saved mod_savage_blow.png")
