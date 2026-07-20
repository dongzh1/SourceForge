"""共享卡片底板生成器：64x64，暗金画框(呼应已有 mod_hearth_whisper.png 定的暗金基调) + 内嵌暗色
面板。57张MOD图标各自 import 这个模块，把专属图标画进 new_card() 返回的 inner_box 区域，整体
img.save() 成一张独立烤死的完整贴图——不再用 CE 的 layer0(共享frame)+layer1(专属图标) 运行时合成，
每张卡自己背景+图标一次画完，避免谁盖谁的层序问题(见 craftengine-item-overlay-composite 记忆)。

用法：
    import sys, os
    sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
    from mod_card_base import new_card
    img, d, (x0, y0, x1, y1) = new_card()              # 默认暗金画框
    img, d, box = new_card(border_rgb=(90, 170, 230))   # 传色调 = 按MOD类型/元素换画框颜色
    # ... 在 (x0,y0,x1,y1) 范围内用 d.<draw方法> 或直接 img.paste/putpixel 画专属图标 ...
    img.save("mod_<id>.png")

border_rgb 建议按 MOD 类型选取（非强制，图标本身颜色更重要）：
    属性(stat)      = None(默认暗金)
    元素(elemental)  = 按元素色：热=橙红 冷=冰蓝 电=黄 毒=绿 混合=紫
    技能(skill)      = 紫色系 (150,90,220)
    护甲被动(passive) = 青绿色系 (70,190,170)
    基石天赋(keystone)= 按英雄联盟系别：精密金(200,170,90) 主宰红(190,60,60) 巫术蓝(90,150,220) 坚决绿(80,150,90)
"""
from PIL import Image, ImageDraw

SIZE = 64
BORDER = 4

DARK_BG = (24, 20, 24, 255)
BRONZE_HI = (222, 168, 45, 255)
BRONZE_MID = (168, 122, 38, 255)
BRONZE_LO = (96, 68, 22, 255)
RIVET = (255, 214, 120, 255)


def _shades(rgb):
    r, g, b = rgb
    hi = (min(255, int(r * 1.3) + 25), min(255, int(g * 1.3) + 25), min(255, int(b * 1.3) + 25), 255)
    mid = (r, g, b, 255)
    lo = (max(0, int(r * 0.5)), max(0, int(g * 0.5)), max(0, int(b * 0.5)), 255)
    return hi, mid, lo


def new_card(border_rgb=None, bg_rgb=None):
    """返回 (img, draw, inner_box)。inner_box=(x0,y0,x1,y1) 是画框内可画图标的区域(含2px留白)。"""
    img = Image.new("RGBA", (SIZE, SIZE), (0, 0, 0, 0))
    d = ImageDraw.Draw(img)
    hi, mid, lo = (BRONZE_HI, BRONZE_MID, BRONZE_LO) if border_rgb is None else _shades(border_rgb)

    d.rectangle([0, 0, SIZE - 1, SIZE - 1], outline=lo, width=1)
    d.rectangle([1, 1, SIZE - 2, SIZE - 2], outline=mid, width=1)
    d.rectangle([2, 2, SIZE - 3, SIZE - 3], outline=hi, width=1)
    d.rectangle([3, 3, SIZE - 4, SIZE - 4], outline=mid, width=1)

    x0, y0, x1, y1 = BORDER, BORDER, SIZE - BORDER - 1, SIZE - BORDER - 1
    d.rectangle([x0, y0, x1, y1], fill=(bg_rgb + (255,)) if bg_rgb else DARK_BG)

    for (cx, cy) in [(3, 3), (SIZE - 4, 3), (3, SIZE - 4), (SIZE - 4, SIZE - 4)]:
        d.ellipse([cx - 1, cy - 1, cx + 1, cy + 1], fill=RIVET)

    return img, d, (x0 + 2, y0 + 2, x1 - 1, y1 - 1)


if __name__ == "__main__":
    img, d, box = new_card()
    img.save("_preview_card_base.png")
    print("inner box:", box, "-> _preview_card_base.png")
