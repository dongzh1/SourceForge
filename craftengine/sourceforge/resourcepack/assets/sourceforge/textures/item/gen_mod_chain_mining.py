"""MOD卡·连锁挖矿(chain_mining) 图标生成脚本。tags=stat -> 默认暗金画框(border_rgb=None)。
构图：一把银灰镐头(斜置)咬入左下角一小簇方块矿石堆，右上方三个灰铁链环首尾相接向外延伸，
呼应"挖到一个方块后连锁触发相邻同类方块"的效果。64x64，几何图形直接 polygon/ellipse/rectangle 拼。
"""
import sys, os
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from mod_card_base import new_card

img, d, (x0, y0, x1, y1) = new_card(border_rgb=None)

STEEL_HI = (210, 215, 225, 255)
STEEL_MID = (150, 158, 172, 255)
STEEL_LO = (90, 96, 108, 255)
WOOD_HI = (150, 105, 60, 255)
WOOD_LO = (95, 65, 35, 255)
ORE_HI = (120, 190, 210, 255)
ORE_MID = (70, 140, 165, 255)
ORE_LO = (40, 90, 110, 255)
CHAIN = (175, 180, 190, 255)
CHAIN_HI = (225, 228, 235, 255)

# --- 左下方矿石堆(三个小方块,菱形俯视风格) ---
def draw_ore_block(cx, cy, s):
    d.polygon([(cx, cy - s), (cx + s, cy), (cx, cy + s), (cx - s, cy)], fill=ORE_MID, outline=ORE_LO)
    d.polygon([(cx - s, cy), (cx, cy + s), (cx, cy + s * 0.4), (cx - s * 0.4, cy)], fill=ORE_LO)
    d.line([(cx - s * 0.4, cy - s * 0.2), (cx, cy - s * 0.5)], fill=ORE_HI, width=1)

draw_ore_block(x0 + 12, y1 - 10, 6)
draw_ore_block(x0 + 20, y1 - 8, 5)
draw_ore_block(x0 + 15, y1 - 16, 4)

# --- 镐子：斜置45度,木柄+银灰镐头 ---
# 木柄 从右下到镐头交汇处
d.line([(x1 - 6, y1 - 6), (x0 + 22, y0 + 26)], fill=WOOD_LO, width=3)
d.line([(x1 - 6, y1 - 7), (x0 + 22, y0 + 25)], fill=WOOD_HI, width=1)

# 镐头(V字两尖角，绕在木柄顶端)
head_cx, head_cy = x0 + 20, y0 + 22
d.polygon([
    (head_cx, head_cy),
    (head_cx - 14, head_cy - 6),
    (head_cx - 10, head_cy - 10),
    (head_cx + 2, head_cy - 2),
], fill=STEEL_MID, outline=STEEL_LO)
d.polygon([
    (head_cx, head_cy),
    (head_cx + 12, head_cy + 10),
    (head_cx + 9, head_cy + 14),
    (head_cx - 3, head_cy + 3),
], fill=STEEL_MID, outline=STEEL_LO)
d.line([(head_cx - 12, head_cy - 7), (head_cx - 2, head_cy - 2)], fill=STEEL_HI, width=1)
d.line([(head_cx + 10, head_cy + 11), (head_cx, head_cy + 2)], fill=STEEL_HI, width=1)

# --- 右上方铁链，三环相接，象征"连锁" ---
def draw_link(cx, cy, rx, ry, ang=0):
    bbox = [cx - rx, cy - ry, cx + rx, cy + ry]
    d.ellipse(bbox, outline=CHAIN, width=2)
    d.arc(bbox, 200, 340, fill=CHAIN_HI, width=1)

draw_link(x1 - 18, y0 + 10, 6, 4)
draw_link(x1 - 9, y0 + 15, 6, 4)
draw_link(x1 - 18, y0 + 20, 6, 4)

img.save(os.path.join(os.path.dirname(os.path.abspath(__file__)), "mod_chain_mining.png"))
print("saved mod_chain_mining.png")
