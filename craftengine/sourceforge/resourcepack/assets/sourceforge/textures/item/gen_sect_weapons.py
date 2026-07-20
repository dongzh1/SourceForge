from pathlib import Path

from PIL import Image, ImageDraw


ROOT = Path(__file__).resolve().parent


def weapon_texture(kind):
    source = Image.open(ROOT / "duskgold_sword.png").convert("RGBA")
    image = Image.new("RGBA", source.size, (0, 0, 0, 0))
    pixels = source.load()
    output = image.load()

    for y in range(source.height):
        for x in range(source.width):
            red, green, blue, alpha = pixels[x, y]
            if alpha == 0:
                continue
            brightness = max(red, green, blue)
            if kind == "golden":
                if brightness > 180:
                    color = (255, 221, 78, alpha)
                elif red > green + 25:
                    color = (210, 142, 28, alpha)
                else:
                    color = (red, green, blue, alpha)
            elif brightness > 180:
                color = (255, 92 + brightness // 8, 28, alpha)
            elif red > green + 25:
                color = (154, 42, 34, alpha)
            else:
                shade = max(28, min(178, brightness + 14))
                color = (shade, shade, min(190, shade + 12), alpha)
            output[x, y] = color

    draw = ImageDraw.Draw(image)
    if kind == "golden":
        draw.point((2, 7), fill=(45, 190, 137, 255))
        draw.point((3, 8), fill=(156, 255, 199, 255))
        draw.point((11, 4), fill=(255, 247, 156, 255))
    else:
        draw.point((8, 7), fill=(255, 171, 35, 255))
        draw.point((11, 4), fill=(255, 91, 29, 255))
        draw.point((13, 1), fill=(255, 139, 38, 255))

    return image

    return image


def blueprint_texture(kind):
    source = Image.open(ROOT / "blueprint_sword.png").convert("RGBA")
    image = source.copy()
    pixels = image.load()
    for y in range(image.height):
        for x in range(image.width):
            red, green, blue, alpha = pixels[x, y]
            if alpha == 0:
                continue
            if kind == "golden":
                pixels[x, y] = (max(30, red // 2), max(50, green // 2), min(255, blue + 20), alpha)
            else:
                pixels[x, y] = (min(255, red + 65), max(24, green // 2), max(35, blue // 2), alpha)
    return image


outputs = {
    "golden_merchant_blade.png": weapon_texture("golden"),
    "skyfire_ember_blade.png": weapon_texture("skyfire"),
    "blueprint_golden_merchant_blade.png": blueprint_texture("golden"),
    "blueprint_skyfire_ember_blade.png": blueprint_texture("skyfire"),
}

for filename, image in outputs.items():
    image.save(ROOT / filename)
