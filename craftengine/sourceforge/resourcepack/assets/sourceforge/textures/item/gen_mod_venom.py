"""生成 mod_venom.png（MOD卡·剧毒）。
tags: stat,elemental(毒) -> 画框走毒元素绿 (110,180,70)。
图标构图：一颗蜘蛛眼(暗紫瞳孔+黄绿虹膜)上方悬垂两滴毒液水滴，
底部再补一滴溅落痕迹，呼应 minecraft:spider_eye 材质与"剧毒"主题。
"""
import sys, os
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from mod_card_base import new_card

img, d, (x0, y0, x1, y1) = new_card(border_rgb=(110, 180, 70))

cx = (x0 + x1) // 2
cy = (y0 + y1) // 2 - 3

# 蜘蛛眼球体（外壳暗红棕，呼应原版spider_eye配色，但整体基调偏毒绿）
eye_r = 13
d.ellipse([cx - eye_r, cy - eye_r, cx + eye_r, cy + eye_r], fill=(70, 20, 24, 255), outline=(30, 8, 10, 255), width=1)
d.ellipse([cx - eye_r + 2, cy - eye_r + 2, cx + eye_r - 2, cy + eye_r - 2], outline=(120, 40, 40, 255), width=1)

# 虹膜（毒绿色，带放射纹理）
iris_r = 8
d.ellipse([cx - iris_r, cy - iris_r, cx + iris_r, cy + iris_r], fill=(140, 210, 60, 255), outline=(90, 150, 40, 255), width=1)
for ang in range(0, 360, 30):
    import math
    rad = math.radians(ang)
    x_out = cx + int(iris_r * 0.95 * math.cos(rad))
    y_out = cy + int(iris_r * 0.95 * math.sin(rad))
    x_in = cx + int(iris_r * 0.4 * math.cos(rad))
    y_in = cy + int(iris_r * 0.4 * math.sin(rad))
    d.line([x_in, y_in, x_out, y_out], fill=(100, 170, 45, 255), width=1)

# 瞳孔（竖裂缝，暗紫黑，毒系特征）
d.ellipse([cx - 2, cy - 6, cx + 2, cy + 6], fill=(20, 10, 30, 255))
d.ellipse([cx - 1, cy - 5, cx, cy + 5], fill=(50, 20, 60, 255))

# 高光
d.ellipse([cx - 5, cy - 6, cx - 2, cy - 3], fill=(220, 255, 200, 200))

# 悬垂毒液滴（左右各一，眼球下方边缘渗出）
def drip(px, py, s, col_hi, col_lo):
    d.polygon([(px, py), (px - s, py + s * 2), (px, py + s * 3), (px + s, py + s * 2)], fill=col_lo)
    d.ellipse([px - s, py + s, px + s, py + s * 3], fill=col_lo)
    d.ellipse([px - s // 2, py + s, px, py + s * 2], fill=col_hi)

drip(cx - 9, cy + eye_r - 3, 3, (170, 230, 90, 230), (110, 180, 70, 230))
drip(cx + 10, cy + eye_r - 1, 2, (170, 230, 90, 220), (110, 180, 70, 220))

# 底部溅落斑点
splat_y = y1 - 5
d.ellipse([cx - 3, splat_y - 2, cx + 3, splat_y + 2], fill=(110, 180, 70, 180))
d.ellipse([cx + 7, splat_y - 1, cx + 10, splat_y + 2], fill=(110, 180, 70, 150))
d.ellipse([cx - 11, splat_y, cx - 8, splat_y + 2], fill=(110, 180, 70, 140))

out = os.path.join(os.path.dirname(os.path.abspath(__file__)), "mod_venom.png")
img.save(out)
print("saved", out)
