"""MOD卡·群魄图腾(legion_banner) 图标生成脚本。64x64背景+图标一次烤死，共享画框来自 mod_card_base.new_card。
纯属性词条(tags:stat，关联 summon_max_count) -> 画框用默认暗金 (border_rgb=None)。
构图：一根竖直图腾木杆 + 顶端骷髅头(召唤军团的象征) + 左右各一个小幽灵/魂魄剪影环绕图腾，
暗示"可召唤上限提升"的主题，底部一小撮杆脚装饰。紫色幽魂配暗金杆身，呼应"群魄"二字。
"""
import sys, os
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from mod_card_base import new_card

img, d, (x0, y0, x1, y1) = new_card(border_rgb=None)

cx = (x0 + x1) // 2
cy = (y0 + y1) // 2

POLE_HI = (200, 150, 60, 255)
POLE_MID = (150, 108, 40, 255)
POLE_LO = (96, 68, 22, 255)

pole_top = y0 + 10
pole_bottom = y1 - 4

d.line([cx, pole_top, cx, pole_bottom], fill=POLE_MID, width=4)
d.line([cx - 1, pole_top, cx - 1, pole_bottom], fill=POLE_HI, width=1)
d.line([cx + 1, pole_top, cx + 1, pole_bottom], fill=POLE_LO, width=1)

# base plate
d.rectangle([cx - 7, pole_bottom - 1, cx + 7, pole_bottom + 2], fill=POLE_LO)
d.rectangle([cx - 7, pole_bottom - 1, cx + 7, pole_bottom], fill=POLE_MID)

# crossbar
bar_y = cy + 2
d.line([cx - 10, bar_y, cx + 10, bar_y], fill=POLE_MID, width=3)
d.line([cx - 10, bar_y - 1, cx + 10, bar_y - 1], fill=POLE_HI, width=1)

# skull totem head atop pole
SKULL = (225, 220, 200, 255)
SKULL_SH = (170, 160, 140, 255)
skull_cy = pole_top - 6
d.ellipse([cx - 8, skull_cy - 8, cx + 8, skull_cy + 8], fill=SKULL)
d.ellipse([cx - 8, skull_cy + 2, cx + 8, skull_cy + 10], fill=SKULL_SH)
# eye sockets
d.ellipse([cx - 5, skull_cy - 2, cx - 1, skull_cy + 3], fill=(30, 20, 30, 255))
d.ellipse([cx + 1, skull_cy - 2, cx + 5, skull_cy + 3], fill=(30, 20, 30, 255))
# nose
d.polygon([(cx, skull_cy + 2), (cx - 2, skull_cy + 5), (cx + 2, skull_cy + 5)], fill=(30, 20, 30, 255))
# jaw lines
for jx in (cx - 3, cx, cx + 3):
    d.line([jx, skull_cy + 7, jx, skull_cy + 9], fill=SKULL_SH, width=1)

# flanking spirit wisps (summoned legion)
GHOST = (150, 90, 220, 200)
GHOST_LT = (190, 150, 240, 180)

def draw_ghost(gx, gy, scale=1.0):
    w = int(6 * scale)
    h = int(8 * scale)
    d.ellipse([gx - w, gy - h, gx + w, gy + h // 2], fill=GHOST)
    # wavy bottom
    d.polygon([
        (gx - w, gy + h // 2), (gx - w // 2, gy + h), (gx, gy + h // 2),
        (gx + w // 2, gy + h), (gx + w, gy + h // 2),
    ], fill=GHOST)
    d.ellipse([gx - w // 2, gy - h // 2, gx + w // 2, gy], fill=GHOST_LT)
    # tiny eyes
    d.ellipse([gx - 2, gy - h // 3, gx - 1, gy - h // 3 + 1], fill=(20, 10, 30, 255))
    d.ellipse([gx + 1, gy - h // 3, gx + 2, gy - h // 3 + 1], fill=(20, 10, 30, 255))

draw_ghost(x0 + 8, cy + 6, 0.9)
draw_ghost(x1 - 8, cy + 8, 0.75)

out_path = os.path.join(os.path.dirname(os.path.abspath(__file__)), "mod_legion_banner.png")
img.save(out_path)
print("saved:", out_path)
