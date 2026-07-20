"""生成 MOD卡·月华斩(crescent_slash) 图标：64x64，紫色画框(技能类 border_rgb=(150,90,220))。
构图：一弯银白新月 + 一道斜贯而过的月白斩击弧线(带三条渐弱的动感残影)，呼应"月华斩"武器主动技能主题。
用法: python gen_mod_crescent_slash.py
"""
import sys, os
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from mod_card_base import new_card
from PIL import ImageDraw

img, d, (x0, y0, x1, y1) = new_card(border_rgb=(150, 90, 220))

cx, cy = (x0 + x1) / 2, (y0 + y1) / 2

# 新月：大圆(月白) 减去 偏移的暗色圆
moon_r = 15
moon_cx, moon_cy = cx - 3, cy - 3
d.ellipse([moon_cx - moon_r, moon_cy - moon_r, moon_cx + moon_r, moon_cy + moon_r],
          fill=(235, 225, 250, 255))
d.ellipse([moon_cx - moon_r + 7, moon_cy - moon_r - 4, moon_cx + moon_r + 7, moon_cy + moon_r - 4],
          fill=(24, 20, 24, 255))

# 斩击弧线：从左上到右下的粗弧，带发光核心
def arc_line(offset, width, color):
    d.arc([x0 - 6 + offset, y0 - 2 + offset, x1 + 6 + offset, y1 + 10 + offset],
          start=200, end=260, fill=color, width=width)

arc_line(6, 2, (150, 90, 220, 120))
arc_line(3, 3, (190, 150, 240, 180))
arc_line(0, 4, (250, 245, 255, 255))

# 弧线两端加尖锐三角形收尾，强调"斩"的锋利感
d.polygon([(x1 - 4, y0 + 6), (x1 + 2, y0 + 10), (x1 - 8, y0 + 14)], fill=(255, 255, 255, 255))
d.polygon([(x0 + 4, y1 - 6), (x0 - 2, y1 - 10), (x0 + 8, y1 - 14)], fill=(255, 255, 255, 255))

# 星点点缀
for (px, py) in [(x0 + 5, y0 + 8), (x1 - 6, y1 - 6), (x0 + 10, y1 - 10)]:
    d.ellipse([px - 1, py - 1, px + 1, py + 1], fill=(220, 200, 255, 220))

img.save(os.path.join(os.path.dirname(os.path.abspath(__file__)), "mod_crescent_slash.png"))
print("saved mod_crescent_slash.png")
