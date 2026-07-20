"""MOD卡·黑曜石爆破(obsidian_blast) 图标生成脚本。64x64，共享暗色画框(mod_card_base.new_card)，
border_rgb=(150,90,220) 紫色系(skill:true 武器主动技能)。图标构图：中心一块深紫黑色多边形黑曜石碎块，
外围四散的橙红爆炸射线+几圈冲击环，表现"黑曜石+爆破"的主题。运行 `python gen_mod_obsidian_blast.py`
即可重新生成 mod_obsidian_blast.png。
"""
import sys, os, math
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from mod_card_base import new_card

img, d, (x0, y0, x1, y1) = new_card(border_rgb=(150, 90, 220))

cx = (x0 + x1) / 2
cy = (y0 + y1) / 2

BLAST_HI = (255, 150, 60, 255)
BLAST_MID = (230, 90, 30, 255)
BLAST_LO = (150, 40, 20, 200)
OBS_DARK = (25, 10, 40, 255)
OBS_MID = (55, 25, 80, 255)
OBS_HI = (110, 70, 160, 255)

# 爆炸射线（从中心向外，长短交错）
n_rays = 10
for i in range(n_rays):
    ang = (2 * math.pi / n_rays) * i + 0.15
    length = 20 if i % 2 == 0 else 13
    x2 = cx + math.cos(ang) * length
    y2 = cy + math.sin(ang) * length
    xin = cx + math.cos(ang) * 6
    yin = cy + math.sin(ang) * 6
    d.line([xin, yin, x2, y2], fill=BLAST_MID, width=2)
    d.ellipse([x2 - 1, y2 - 1, x2 + 1, y2 + 1], fill=BLAST_HI)

# 冲击环（两圈弧线, 用点近似的椭圆轮廓）
d.ellipse([cx - 17, cy - 17, cx + 17, cy + 17], outline=BLAST_LO, width=1)

# 中心黑曜石碎块：不规则六边形
obs_pts = [
    (cx - 9, cy - 12),
    (cx + 2, cy - 15),
    (cx + 12, cy - 5),
    (cx + 9, cy + 10),
    (cx - 3, cy + 14),
    (cx - 13, cy + 3),
]
d.polygon(obs_pts, fill=OBS_DARK, outline=OBS_HI)

# 黑曜石内部切面高光（几条细折线，制造晶体质感）
d.line([(cx - 9, cy - 12), (cx - 1, cy - 2), (cx + 2, cy - 15)], fill=OBS_MID, width=1)
d.line([(cx - 1, cy - 2), (cx + 12, cy - 5)], fill=OBS_MID, width=1)
d.line([(cx - 1, cy - 2), (cx - 3, cy + 14)], fill=OBS_MID, width=1)
d.line([(cx - 1, cy - 2), (cx + 9, cy + 10)], fill=OBS_HI, width=1)

# 顶点微高光点，增加"发光碎裂"的观感
for (px, py) in obs_pts:
    d.point((px, py), fill=BLAST_HI)

out_path = os.path.join(os.path.dirname(os.path.abspath(__file__)), "mod_obsidian_blast.png")
img.save(out_path)
print("saved", out_path)
