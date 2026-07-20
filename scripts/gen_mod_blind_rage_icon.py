"""生成 blind_rage (暴戾无常) MOD 卡 16x16 图标。
狂怒面具：暗铜金基调 + 面部纵向裂纹透出赤红怒焰(呼应"无常"失控)，怒目斜眼发红光，
顶部两只小尖角(魔性/暴戾)。配色取自 duskgold 系列(暗色系+古铜阴影+金高光)叠加怒焰红。
"""
from PIL import Image

W = H = 16
img = Image.new("RGBA", (W, H), (0, 0, 0, 0))
px = img.load()

# ---- 调色板 ----
OUTLINE   = (16, 14, 18, 255)     # #100e12 轮廓
BRONZE_DK = (52, 39, 26, 255)     # 暗古铜阴影(比 #523f26 略压暗做描边内侧)
BRONZE    = (131, 96, 33, 255)    # #836021 古铜阴影
BRONZE_MD = (168, 122, 42, 255)   # 古铜中间调(过渡)
GOLD      = (222, 168, 45, 255)   # #dea82d
GOLD_HI   = (255, 205, 70, 255)   # #ffcd46 金高光(边缘反光)

RAGE_CORE = (255, 140, 60, 255)   # 眼核/裂纹最亮处(橙红)
RAGE_MID  = (214, 60, 40, 255)    # 中层赤红
RAGE_DK   = (120, 20, 20, 255)    # 暗红(眼窝阴影侧)
EMBER     = (198, 50, 30, 200)    # 面具外飘散的余烬火星(半透明)

def setp(x, y, c):
    if 0 <= x < W and 0 <= y < H:
        px[x, y] = c

# ---- 主体轮廓：按行给 (xmin,xmax) 区间，天然左右对称(镜像轴 x=7.5) ----
rows = {
    2:  (5, 10),
    3:  (4, 11),
    4:  (3, 12),
    5:  (3, 12),
    6:  (3, 12),
    7:  (3, 12),
    8:  (3, 12),
    9:  (4, 11),
    10: (4, 11),
    11: (5, 10),
    12: (6, 9),
    13: (6, 9),
    14: (7, 8),
}
silhouette = set()
for y, (xmin, xmax) in rows.items():
    for x in range(xmin, xmax + 1):
        silhouette.add((x, y))

# 顶部两只小尖角
horn_pixels = [(4, 0), (11, 0), (3, 1), (4, 1), (11, 1), (12, 1)]
for p in horn_pixels:
    silhouette.add(p)

# ---- 轮廓描边：silhouette 中四邻有空格的像素记为描边 ----
def neighbors4(x, y):
    return [(x + 1, y), (x - 1, y), (x, y + 1), (x, y - 1)]

outline_set = set()
for (x, y) in silhouette:
    if any(n not in silhouette for n in neighbors4(x, y)):
        outline_set.add((x, y))
interior_set = silhouette - outline_set

# ---- 先填充：描边一律 OUTLINE，内部按行做明暗渐变 ----
for (x, y) in outline_set:
    setp(x, y, OUTLINE)

for (x, y) in interior_set:
    if y <= 3:
        c = BRONZE_DK           # 额头：暗
    elif y <= 8:
        c = BRONZE               # 颊/眼带：主古铜色
    elif y <= 11:
        c = BRONZE_MD             # 下颌上段：过渡
    else:
        c = BRONZE_DK            # 下巴：暗(阴影收尖)
    setp(x, y, c)

# ---- 内侧金色反光描边(左侧一列，呼应 duskgold 高光风格) ----
rim = [(4, 4), (4, 5), (4, 6), (4, 7), (4, 8), (5, 9), (5, 10)]
for (x, y) in rim:
    if (x, y) in interior_set:
        setp(x, y, GOLD_HI if y in (5, 6, 7) else GOLD)

# ---- 怒目(斜眼缝，右下角略挑，凶相)：左右对称 ----
# 左眼
setp(5, 6, RAGE_CORE)
setp(6, 6, RAGE_MID)
setp(4, 7, RAGE_DK)
setp(6, 7, RAGE_MID)
# 右眼(镜像 x -> 15-x)
setp(15 - 5, 6, RAGE_CORE)
setp(15 - 6, 6, RAGE_MID)
setp(15 - 4, 7, RAGE_DK)
setp(15 - 6, 7, RAGE_MID)

# ---- 纵向裂纹：从额头贯穿到下巴，透出赤红怒焰，锯齿走位 ----
crack = [
    (7, 2), (8, 3), (7, 4), (8, 5),
    (7, 6, RAGE_DK),  # 裂纹经过眼间鼻梁，颜色压暗避免抢眼睛风头
    (8, 8), (7, 9), (8, 10), (7, 11), (8, 12), (7, 13),
]
for entry in crack:
    if len(entry) == 2:
        x, y = entry
        c = RAGE_CORE if y in (2, 3, 12, 13) else RAGE_MID
    else:
        x, y, c = entry
    setp(x, y, c)

# ---- 面具外的余烬火星(稀疏点缀，暗示狂怒气息外泄) ----
for (x, y) in [(1, 3), (14, 4), (2, 9), (13, 10)]:
    setp(x, y, EMBER)

out_paths = [
    r"C:\Users\32394\IdeaProjects\SourceForge\craftengine\sourceforge\resourcepack\assets\sourceforge\textures\item\mod_blind_rage.png",
    r"C:\game\Server\Beta_Server\plugins\CraftEngine\resources\sourceforge\resourcepack\assets\sourceforge\textures\item\mod_blind_rage.png",
]
for p in out_paths:
    img.save(p)
    print("saved", p)
