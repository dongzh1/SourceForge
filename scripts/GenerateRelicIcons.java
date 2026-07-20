import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import javax.imageio.ImageIO;

public final class GenerateRelicIcons {
    private static final int SCALE = 2;

    private GenerateRelicIcons() {
    }

    public static void main(String[] arguments) throws IOException {
        Path outputDirectory = Path.of(arguments.length == 0 ? "." : arguments[0]);
        Files.createDirectories(outputDirectory);
        ImageIO.write(armorRelic(), "png", outputDirectory.resolve("relic_armor_duskgold.png").toFile());
        ImageIO.write(weaponRelic(), "png", outputDirectory.resolve("relic_weapon_duskgold.png").toFile());
    }

    private static BufferedImage armorRelic() {
        String[] pattern = {
            "................",
            "......gggg......",
            ".....gGYYGg.....",
            "....gGYvvYGg....",
            "...gGvppppvGg...",
            "...gGvpLLpvGg...",
            "...gGvppppvGg...",
            "....gGYvvYGg....",
            "...ggGvppvGgg...",
            "..ggGvppppvGgg..",
            "..gGYvppppvYGg..",
            "...gGvppppvGg...",
            "....gGvppvGg....",
            ".....gGvvGg.....",
            "......gGGg......",
            "................"
        };
        return render(pattern, Map.of(
            '.', 0x00000000,
            'g', 0xFF684313,
            'G', 0xFFD8A326,
            'Y', 0xFFFFE189,
            'v', 0xFF8B4DAD,
            'p', 0xFF45245E,
            'L', 0xFFD8A9FF
        ));
    }

    private static BufferedImage weaponRelic() {
        String[] pattern = {
            "................",
            ".o............o.",
            "..ob........bo..",
            "...oB......Bo...",
            "....oB....Bo....",
            ".g...oCCCCo...g.",
            ".gG...CYYC...Gg.",
            "..gG..CYYC..Gg..",
            "...g..oCCCCo..g.",
            "....oB....Bo....",
            "...oB......Bo...",
            "..ob........bo..",
            ".o............o.",
            "................",
            "................",
            "................"
        };
        return render(pattern, Map.of(
            '.', 0x00000000,
            'o', 0xFF1B2433,
            'b', 0xFF134466,
            'B', 0xFF3B9AC8,
            'C', 0xFF75E3F2,
            'Y', 0xFFFFF1A2,
            'g', 0xFF6E4216,
            'G', 0xFFD49C29
        ));
    }

    private static BufferedImage render(String[] pattern, Map<Character, Integer> palette) {
        int width = pattern[0].length();
        BufferedImage image = new BufferedImage(width * SCALE, pattern.length * SCALE, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < pattern.length; y++) {
            String row = pattern[y];
            if (row.length() != width) throw new IllegalArgumentException("Inconsistent icon row width");
            for (int x = 0; x < width; x++) {
                int color = palette.getOrDefault(row.charAt(x), 0x00000000);
                for (int offsetY = 0; offsetY < SCALE; offsetY++) {
                    for (int offsetX = 0; offsetX < SCALE; offsetX++) {
                        image.setRGB(x * SCALE + offsetX, y * SCALE + offsetY, color);
                    }
                }
            }
        }
        return image;
    }
}
