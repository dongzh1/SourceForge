"""MOD卡·摸尸(desecrate) 图标生成脚本。64x64，共享暗金/主题画框 + 专属图标一次烤死。
tags: skill -> 紫色系画框 (150,90,220)。
构图：一只骷髅手爪从下方伸出，攫取/摸索一根横置的白骨(肋骨状)，营造"盗墓/摸尸掘取"的意象；
爪尖用亮紫描边勾出灵能/邪术感，呼应技能类卡片的紫色主题。
"""
import sys, os
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from mod_card_base import new_card

BORDER_RGB = (150, 90, 220)

img, d, (x0, y0, x1, y1) = new_card(border_rgb=BORDER_RGB)

BONE = (225, 220, 200, 255)
BONE_SHADE = (170, 160, 135, 255)
CLAW = (60, 40, 80, 255)
CLAW_HI = (150, 90, 220, 255)
CLAW_EDGE = (200, 160, 255, 255)
GLOW = (190, 130, 255, 180)

cx = (x0 + x1) // 2
cy = (y0 + y1) // 2

# 横置的骨头（一根长骨，两端球状髁）
bone_y = cy - 6
bone_left = x0 + 8
bone_right = x1 - 8
d.line([(bone_left + 4, bone_y), (bone_right - 4, bone_y)], fill=BONE, width=6)
d.line([(bone_left + 4, bone_y - 2), (bone_right - 4, bone_y - 2)], fill=BONE_SHADE, width=1)
for ex in (bone_left, bone_right):
    d.ellipse([ex - 4, bone_y - 6, ex + 4, bone_y - 1], fill=BONE, outline=BONE_SHADE)
    d.ellipse([ex - 4, bone_y + 1, ex + 4, bone_y + 6], fill=BONE, outline=BONE_SHADE)

# 下方伸出的骷髅爪手（掌心 + 三根弯曲手指向上攫取骨头）
palm_cx = cx
palm_cy = y1 - 10
d.ellipse([palm_cx - 6, palm_cy - 4, palm_cx + 6, palm_cy + 6], fill=CLAW, outline=CLAW_EDGE)

finger_bases = [(-6, -2), (0, -5), (6, -2)]
for dx, dy in finger_bases:
    bx, by = palm_cx + dx, palm_cy + dy
    tipx, tipy = bx + (dx // 2), bone_y + 4
    d.line([(bx, by), (bx + dx // 3, (by + tipy) // 2), (tipx, tipy)], fill=CLAW_EDGE, width=3)
    d.ellipse([tipx - 1, tipy - 1, tipx + 1, tipy + 1], fill=CLAW_HI)

# 拇指从侧面钩住骨头
d.line([(palm_cx - 8, palm_cy), (palm_cx - 12, bone_y + 3)], fill=CLAW_EDGE, width=3)

# 灵能微光点缀（几个小紫点表示邪术/汲取）
for gx, gy in [(cx - 12, cy + 2), (cx + 14, cy - 2), (cx + 2, cy + 10)]:
    d.ellipse([gx - 1, gy - 1, gx + 1, gy + 1], fill=GLOW)

out_path = os.path.join(os.path.dirname(os.path.abspath(__file__)), "mod_desecrate.png")
img.save(out_path)
print("saved", out_path)
