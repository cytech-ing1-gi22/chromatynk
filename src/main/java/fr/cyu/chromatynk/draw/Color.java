package fr.cyu.chromatynk.draw;

import java.util.Map;

/**
 * A RGB color.
 *
 * @param red the red component between 0 and 1
 * @param green the green component between 0 and 1
 * @param blue the blue component between 0 and 1
 */
public record Color(double red, double green, double blue) {

    /**
     * Get this color's brightness.
     *
     * @return this color's brightness according to YUV conversion
     */
    public double getBrightness() {
        return 0.257 * red + 0.504 * green + 0.098 * blue + 16;
    }

    /**
     * Get this color's blue projection.
     *
     * @return this color's blue projection according to YUV conversion
     */
    public double getBlueProjection() {
        return -0.148 * red - 0.291 * green + 0.439 * blue + 128;
    }

    /**
     * Get this color's red projection.
     *
     * @return this color's red projection according to YUV conversion
     */
    public double getRedProjection() {
        return 0.439 * red - 0.368 * green - 0.071 * blue + 128;
    }

    private double distanceSquaredTo(Color color) {
        double dy = Math.abs(getBrightness() - color.getBrightness());
        double du = Math.abs(getBlueProjection() - color.getBlueProjection());
        double dv = Math.abs(getRedProjection() - color.getRedProjection());

        return dy * dy + du * du + dv * dv;
    }

    /**
     * Convert this color to ANSI.
     *
     * @return the closest corresponding ANSI background color code
     */
    public String toANSI() {
        return ANSI_COLORS
                .entrySet()
                .stream()
                .min((a, b) -> Double.compare(distanceSquaredTo(a.getKey()), distanceSquaredTo(b.getKey())))
                .map(Map.Entry::getValue)
                .get();
    }

    private static final Map<Color, String> ANSI_COLORS = Map.ofEntries(
            Map.entry(new Color(0, 0, 0), "\u001B[40m"),
            Map.entry(new Color(1, 0, 0), "\u001B[41m"),
            Map.entry(new Color(0, 1, 0), "\u001B[42m"),
            Map.entry(new Color(1, 1, 0), "\u001B[43m"),
            Map.entry(new Color(0, 0, 1), "\u001B[44m"),
            Map.entry(new Color(1, 0, 1), "\u001B[45m"),
            Map.entry(new Color(0, 1, 1), "\u001B[46m"),
            Map.entry(new Color(1, 1, 1), "\u001B[47m")
    );
}