# MOD卡·处决本能(execution) 图标生成脚本。tags=stat，纯属性词条，画框用默认暗金(new_card(None))。
# 构图：一枚倒插入靶心的匕首——外圈红色准星/靶环代表"锁定弱点"，匕首刃尖对准靶心并渗出血滴，
# 直白表达"处决本能=对目标要害的致命一击"。64x64，比 mod_hearth_whisper 的16x16版本线条加粗、
# 分出刃身高光/暗部两层色阶增加立体感。
import sys, os
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from mod_card_base import new_card

img, d, (x0, y0, x1, y1) = new_card(border_rgb=None)

cx = (x0 + x1) // 2
cy = (y0 + y1) // 2

# 靶环（红色系，代表锁定/处决目标）
ring_col_outer = (140, 30, 30, 255)
ring_col_mid = (200, 50, 50, 255)
ring_col_inner = (230, 90, 70, 255)

r_outer = 20
r_mid = 14
r_inner = 7

d.ellipse([cx - r_outer, cy - r_outer, cx + r_outer, cy + r_outer], outline=ring_col_outer, width=2)
d.ellipse([cx - r_mid, cy - r_mid, cx + r_mid, cy + r_mid], outline=ring_col_mid, width=2)
d.ellipse([cx - r_inner, cy - r_inner, cx + r_inner, cy + r_inner], fill=(60, 10, 10, 255), outline=ring_col_inner, width=1)

# 准星短线（十字标记，四方向）
tick = 5
gap = r_outer + 2
lines = [
    (cx, cy - gap - tick, cx, cy - gap),
    (cx, cy + gap, cx, cy + gap + tick),
    (cx - gap - tick, cy, cx - gap, cy),
    (cx + gap, cy, cx + gap + tick, cy),
]
for (a, b, c, e) in lines:
    d.line([a, b, c, e], fill=ring_col_mid, width=2)

# 匕首：刃身（竖直，尖端朝下插入靶心），从上方刺入
blade_top_y = cy - 22
blade_tip_y = cy + 3
blade_half_w = 4

blade_hi = (235, 235, 240, 255)
blade_mid = (190, 195, 205, 255)
blade_lo = (110, 115, 125, 255)

# 刃身主体（多边形，左边高光，右边暗部，尖端朝下）
d.polygon([
    (cx - blade_half_w, blade_top_y),
    (cx, blade_tip_y),
    (cx + blade_half_w, blade_top_y),
], fill=blade_mid, outline=blade_lo)
# 中线高光
d.line([(cx, blade_top_y), (cx, blade_tip_y)], fill=blade_hi, width=1)

# 护手（十字挡格）
guard_y = blade_top_y
d.rectangle([cx - 8, guard_y - 2, cx + 8, guard_y + 1], fill=(120, 90, 40, 255), outline=(60, 45, 20, 255))

# 刀柄
d.rectangle([cx - 2, guard_y - 9, cx + 2, guard_y - 2], fill=(90, 60, 30, 255), outline=(50, 32, 16, 255))
d.ellipse([cx - 3, guard_y - 12, cx + 3, guard_y - 8], fill=(170, 130, 60, 255))

# 血滴（从靶心两侧滴落，强化"处决"意象）
drop_col_dark = (150, 20, 20, 255)
drop_col_hi = (210, 50, 40, 255)
for (dx, dy, s) in [(-9, 10, 3), (11, 13, 2), (2, 18, 2)]:
    px, py = cx + dx, cy + dy
    d.ellipse([px - s, py - s, px + s, py + s], fill=drop_col_dark)
    d.ellipse([px - s // 2, py - s, px + s // 2, py], fill=drop_col_hi)

img.save(os.path.join(os.path.dirname(os.path.abspath(__file__)), "mod_execution.png"))
print("saved mod_execution.png")
