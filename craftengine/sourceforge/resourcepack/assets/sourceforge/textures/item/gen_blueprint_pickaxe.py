from pathlib import Path

from PIL import Image, ImageDraw


TRANSPARENT = (0, 0, 0, 0)
BORDER = (28, 64, 126, 255)
BLUEPRINT = (70, 134, 226, 255)
METAL = (210, 225, 238, 255)
WOOD = (96, 56, 32, 255)


image = Image.new("RGBA", (16, 16), TRANSPARENT)
draw = ImageDraw.Draw(image)
draw.rectangle((2, 2, 13, 13), fill=BORDER)
draw.rectangle((3, 3, 12, 12), fill=BLUEPRINT)


def paint_pixels(points, color):
    for pixel_x, pixel_y in points:
        image.putpixel((pixel_x, pixel_y), color)


paint_pixels(
    [
        (5, 3), (6, 3), (7, 3), (8, 3), (9, 3), (10, 3),
        (4, 4), (5, 4), (6, 4), (7, 4), (8, 4), (9, 4), (10, 4), (11, 4),
        (4, 5), (5, 5), (6, 5), (7, 5), (8, 5), (9, 5), (10, 5),
        (4, 6), (5, 6), (6, 6), (7, 6), (8, 6),
    ],
    METAL,
)

paint_pixels(
    [
        (8, 6), (8, 7), (7, 7), (7, 8), (6, 8), (6, 9),
        (5, 9), (5, 10), (4, 10), (4, 11), (3, 11),
    ],
    WOOD,
)

paint_pixels(
    [
        (4, 3), (11, 3), (3, 4), (12, 4), (3, 5), (11, 5),
        (3, 6), (9, 6), (3, 7), (8, 7), (3, 8), (7, 8),
        (3, 9), (6, 9), (3, 10), (5, 10), (3, 11),
    ],
    BORDER,
)

image.save(Path(__file__).with_name("blueprint_pickaxe.png"), optimize=True)
