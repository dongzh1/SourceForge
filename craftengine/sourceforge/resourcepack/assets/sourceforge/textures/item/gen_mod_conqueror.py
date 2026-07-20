# MOD卡·征服者（conqueror）图标生成脚本。基石天赋(keystone)，英雄联盟精密系原名直用，
# 画框按"精密金"配色 (200,170,90)。图标构图：一顶王冠 + 冠内一柄向上的剑，象征"以持续
# 攻击换取统治(征服)"的乱斗天赋——剑刃贴脸缠斗，王冠代表天赋顶端的统治地位。
import sys, os
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from mod_card_base import new_card

BORDER_RGB = (200, 170, 90)  # 精密金

img, d, (x0, y0, x1, y1) = new_card(border_rgb=BORDER_RGB)
cx = (x0 + x1) // 2
cy = (y0 + y1) // 2

GOLD_HI = (255, 224, 140, 255)
GOLD_MID = (220, 180, 90, 255)
GOLD_LO = (150, 110, 40, 255)
JEWEL_RED = (200, 50, 50, 255)
BLADE_HI = (235, 235, 245, 255)
BLADE_LO = (170, 170, 185, 255)
HILT = (150, 110, 40, 255)

# --- 王冠 (上半部分) ---
crown_top = y0 + 6
crown_base = cy - 2
crown_left = x0 + 4
crown_right = x1 - 4

# 冠体底座（梯形）
d.polygon([
    (crown_left, crown_base),
    (crown_right, crown_base),
    (crown_right - 2, crown_top + 8),
    (crown_left + 2, crown_top + 8),
], fill=GOLD_MID, outline=GOLD_LO)

# 冠齿尖（三个尖角）
tooth_w = (crown_right - crown_left) // 3
pts_y_top = crown_top
for i in range(3):
    tx0 = crown_left + i * tooth_w
    tx1 = tx0 + tooth_w
    tmid = (tx0 + tx1) // 2
    peak_y = pts_y_top if i == 1 else pts_y_top + 4
    d.polygon([
        (tx0, crown_top + 8),
        (tmid, peak_y),
        (tx1, crown_top + 8),
    ], fill=GOLD_HI, outline=GOLD_LO)

# 冠底横梁高光线
d.line([(crown_left, crown_base), (crown_right, crown_base)], fill=GOLD_HI, width=1)

# 中央宝石
d.ellipse([cx - 2, crown_base - 6, cx + 2, crown_base - 2], fill=JEWEL_RED, outline=GOLD_LO)

# --- 剑 (下半部分，剑尖穿入王冠底部) ---
blade_top = crown_base - 3
blade_bottom = y1 - 10
blade_hw = 2

d.polygon([
    (cx, blade_top),
    (cx + blade_hw, blade_top + 5),
    (cx + blade_hw, blade_bottom),
    (cx, blade_bottom + 3),
    (cx - blade_hw, blade_bottom),
    (cx - blade_hw, blade_top + 5),
], fill=BLADE_HI, outline=BLADE_LO)
d.line([(cx, blade_top + 5), (cx, blade_bottom)], fill=BLADE_LO, width=1)

# 护手
d.line([(cx - 6, blade_bottom + 1), (cx + 6, blade_bottom + 1)], fill=HILT, width=2)

# 剑柄 + 柄头
d.rectangle([cx - 1, blade_bottom + 2, cx + 1, y1 - 4], fill=HILT)
d.ellipse([cx - 2, y1 - 5, cx + 2, y1 - 1], fill=GOLD_HI, outline=GOLD_LO)

out_path = os.path.join(os.path.dirname(os.path.abspath(__file__)), "mod_conqueror.png")
img.save(out_path)
print("saved:", out_path)
