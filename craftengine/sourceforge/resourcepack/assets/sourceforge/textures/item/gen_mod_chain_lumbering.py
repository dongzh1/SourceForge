"""Generate the chain lumbering MOD card icon."""
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from mod_card_base import new_card

img, draw, (x0, y0, x1, y1) = new_card(border_rgb=None)

WOOD_DARK = (70, 42, 22, 255)
WOOD_MID = (143, 88, 40, 255)
WOOD_LIGHT = (211, 151, 78, 255)
STEEL_DARK = (57, 63, 74, 255)
STEEL_MID = (151, 161, 177, 255)
STEEL_LIGHT = (236, 240, 245, 255)
CHAIN = (192, 199, 150, 255)
CHAIN_HI = (241, 224, 154, 255)
CUT = (111, 204, 119, 255)

tree_left = x0 + 8
tree_right = x0 + 25
tree_top = y0 + 27
tree_bottom = y1 - 5
draw.rectangle((tree_left, tree_top, tree_right, tree_bottom), fill=WOOD_MID, outline=WOOD_DARK)
draw.rectangle((tree_left + 3, tree_top + 2, tree_left + 6, tree_bottom - 2), fill=WOOD_LIGHT)
draw.line((tree_left + 11, tree_top + 3, tree_left + 11, tree_bottom - 2), fill=WOOD_DARK, width=2)
draw.line((tree_left + 14, tree_top + 5, tree_left + 14, tree_bottom - 4), fill=WOOD_LIGHT, width=1)

draw.ellipse((tree_left - 2, tree_top - 7, tree_right + 2, tree_top + 7), fill=WOOD_MID, outline=WOOD_DARK)
draw.ellipse((tree_left + 4, tree_top - 3, tree_left + 13, tree_top + 4), outline=WOOD_LIGHT, width=1)
draw.line((tree_left + 9, tree_top, tree_left + 6, tree_top - 3), fill=WOOD_LIGHT, width=1)
draw.line((tree_left + 9, tree_top, tree_left + 12, tree_top + 3), fill=WOOD_LIGHT, width=1)

handle_start = (tree_left + 4, tree_bottom + 2)
handle_end = (x0 + 34, y0 + 18)
draw.line((handle_start, handle_end), fill=WOOD_DARK, width=5)
draw.line((handle_start[0] + 1, handle_start[1] - 1, handle_end[0] + 1, handle_end[1] - 1), fill=WOOD_LIGHT, width=2)

axe_head = [
    (x0 + 30, y0 + 20), (x0 + 33, y0 + 9),
    (x0 + 39, y0 + 6), (x0 + 50, y0 + 9),
    (x0 + 53, y0 + 15), (x0 + 47, y0 + 23),
    (x0 + 38, y0 + 24),
]
draw.polygon(axe_head, fill=STEEL_MID, outline=STEEL_DARK)
draw.polygon([(x0 + 40, y0 + 8), (x0 + 50, y0 + 10), (x0 + 52, y0 + 15), (x0 + 46, y0 + 21)], fill=STEEL_LIGHT)
draw.line((x0 + 34, y0 + 10, x0 + 38, y0 + 22), fill=STEEL_LIGHT, width=1)
draw.rectangle((x0 + 31, y0 + 19, x0 + 38, y0 + 24), fill=STEEL_DARK)

draw.line((tree_left + 3, tree_top + 12, tree_left + 10, tree_top + 5), fill=CUT, width=2)
draw.line((tree_left + 8, tree_top + 17, tree_left + 15, tree_top + 10), fill=CUT, width=2)

for link_x, link_y in ((x1 - 12, y0 + 8), (x1 - 7, y0 + 16), (x1 - 13, y0 + 24)):
    box = (link_x - 5, link_y - 3, link_x + 5, link_y + 3)
    draw.ellipse(box, outline=CHAIN, width=2)
    draw.arc(box, 205, 340, fill=CHAIN_HI, width=1)

img.save(os.path.join(os.path.dirname(os.path.abspath(__file__)), "mod_chain_lumbering.png"))
print("saved mod_chain_lumbering.png")
