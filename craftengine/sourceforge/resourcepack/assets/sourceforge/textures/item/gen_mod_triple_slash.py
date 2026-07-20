"""MOD卡·三段斩(triple_slash) 图标生成脚本。tags=skill，画框走技能紫色系(150,90,220)。
构图：三道由粗到细、由亮到暗的斜向弧形斩击线，从左上到右下依次排开，模拟"三连斩"的
连续挥击轨迹；线条末端加小三角箭头强调挥砍方向感，背景留白呼吸。
"""
import sys, os
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from mod_card_base import new_card
from PIL import ImageDraw

img, d, (x0, y0, x1, y1) = new_card(border_rgb=(150, 90, 220))

# 三条斜向斩击弧线，从左上到右下依次错开，颜色由亮紫到暗紫，营造挥砍先后感
slashes = [
    # (起点, 终点, 宽度, 颜色)
    ((x0 + 3, y0 + 6), (x1 - 10, y0 + 20), 4, (235, 210, 255, 255)),
    ((x0 + 2, y0 + 16), (x1 - 6, y0 + 30), 5, (190, 140, 240, 255)),
    ((x0 + 1, y0 + 27), (x1 - 3, y0 + 41), 6, (140, 80, 210, 255)),
]

for (p0, p1, w, col) in slashes:
    d.line([p0, p1], fill=col, width=w)
    # 圆头收尾，避免线段端点方块化
    for pt in (p0, p1):
        r = w // 2
        d.ellipse([pt[0]-r, pt[1]-r, pt[0]+r, pt[1]+r], fill=col)
    # 末端箭头，指示挥砍方向
    ax, ay = p1
    d.polygon([(ax, ay - w), (ax + 7, ay), (ax, ay + w)], fill=col)

img.save(os.path.join(os.path.dirname(os.path.abspath(__file__)), "mod_triple_slash.png"))
print("saved mod_triple_slash.png")
