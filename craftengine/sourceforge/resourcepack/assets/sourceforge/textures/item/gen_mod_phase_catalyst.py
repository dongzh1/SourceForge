# MOD卡·相位催化（phase_catalyst）图标生成脚本。tags=[stat, elemental]，元素为"组合元素"
# (病毒/腐蚀/毒气/辐射/爆裂的组合触发)，非单一冷/热/电/毒，按配色规则归为"混合多元素"紫色画框。
# 图标构图：一颗棱形相位水晶悬浮在内圈中央，晶体内部有一道竖向高光分割出明暗两面，
# 周围三层不同半径/角度的菱形相位环层层外扩(暗示"催化多种元素组合"的相位共振)，环上点缀
# 四色小光点(热橙/冷蓝/毒绿/电黄)象征它催化的多种元素组合。
import sys, os
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from mod_card_base import new_card

BORDER_RGB = (150, 90, 200)  # 混合多元素紫

img, d, (x0, y0, x1, y1) = new_card(border_rgb=BORDER_RGB)

cx = (x0 + x1) // 2
cy = (y0 + y1) // 2

# 三层相位菱形环(从外到内，半透明层次感用递增亮度模拟)
ring_defs = [
    (22, (90, 60, 130, 255)),
    (16, (130, 85, 175, 255)),
    (10, (170, 120, 220, 255)),
]
for r, col in ring_defs:
    d.polygon([(cx, cy - r), (cx + r, cy), (cx, cy + r), (cx - r, cy)], outline=col, width=2)

# 中央相位水晶(菱形，双色分面模拟晶体明暗)
cw, ch = 9, 13
# 左暗面
d.polygon([(cx, cy - ch), (cx - cw, cy), (cx, cy + ch)], fill=(120, 70, 170, 255))
# 右亮面
d.polygon([(cx, cy - ch), (cx + cw, cy), (cx, cy + ch)], fill=(200, 150, 235, 255))
# 竖向高光分割线
d.line([(cx, cy - ch), (cx, cy + ch)], fill=(235, 210, 250, 255), width=1)
# 顶部尖光点
d.ellipse([cx - 2, cy - ch - 2, cx + 2, cy - ch + 2], fill=(255, 240, 255, 255))

# 四色小光点，象征催化的多种元素组合(热/冷/毒/电)，挂在外环四角
dot_r = 2
dots = [
    (cx, cy - 22, (220, 110, 60, 255)),   # 热
    (cx + 22, cy, (90, 170, 230, 255)),   # 冷
    (cx, cy + 22, (110, 190, 80, 255)),   # 毒
    (cx - 22, cy, (225, 205, 70, 255)),   # 电
]
for dx, dy, col in dots:
    d.ellipse([dx - dot_r, dy - dot_r, dx + dot_r, dy + dot_r], fill=col)

out_path = os.path.join(os.path.dirname(os.path.abspath(__file__)), "mod_phase_catalyst.png")
img.save(out_path)
print("saved", out_path)
