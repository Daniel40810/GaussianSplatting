package com.dan.fgaussian.shade;

/**
 * Flaechenarten und ihre naturnahen Grundfarben.
 *
 * <p>Die Reihenfolge ist zugleich die Zeichenreihenfolge beim Rastern: eine
 * spaeter eingetragene Art ueberschreibt eine frueher eingetragene. Deshalb
 * stehen grossflaechige Arten vorn und kleinteilige hinten - sonst deckt der
 * Wald die Wege zu, die in ihm liegen.</p>
 *
 * <p>{@link #UNKNOWN} bedeutet: keine Flaechenangabe vorhanden, es gilt die
 * Hoehenrampe.</p>
 *
 * @author com.dan.fgaussian
 */
public enum FLandCover {

    /** Keine Zuordnung - Farbe kommt aus der Hoehenrampe. */
    UNKNOWN(0f, 0f, 0f, false),

    /** Offene Ostsee. */
    SEA(0.086f, 0.267f, 0.318f, false),

    /** Hafenbecken, ruhiger und truebrer als die offene See. */
    HARBOUR_BASIN(0.098f, 0.224f, 0.255f, false),

    /** Duenen- und Badestrand. */
    BEACH(0.824f, 0.776f, 0.643f, false),

    /** Gruenland, Duenengras. */
    GRASS(0.400f, 0.478f, 0.337f, false),

    /** Wald und geschlossenes Gehoelz - Flaeche, keine Extrusion (Baumbewuchs waere ein eigener Generator). */
    FOREST(0.220f, 0.306f, 0.216f, false),

    /** Befestigte Flaeche: Kaikante, Mole, Parkplatz. */
    QUAY(0.565f, 0.557f, 0.525f, false),

    /** Strasse, Weg, Promenade. */
    ROAD(0.451f, 0.443f, 0.427f, false),

    /** Gebaeude - wird extrudiert, wenn eine Hoehe vorliegt. */
    BUILDING(0.671f, 0.608f, 0.541f, true);

    private final float r, g, b;
    private final boolean raised;

    FLandCover(float r, float g, float b, boolean raised) {
        this.r = r;
        this.g = g;
        this.b = b;
        this.raised = raised;
    }

    public float red()   { return r; }
    public float green() { return g; }
    public float blue()  { return b; }

    /** Steht die Flaeche ueber dem Gelaende, kommt also eine Extrusion in Frage? */
    public boolean isRaised() {
        return raised;
    }

    /** Schreibt die Grundfarbe nach {@code out} (Laenge 3). */
    public void color(float[] out) {
        out[0] = r;
        out[1] = g;
        out[2] = b;
    }

    /**
     * Ordnet einen Tabellen- oder Schluesselnamen einer Flaechenart zu.
     * Deckt die OGP-Tabellen und die ueblichen OSM-Schluessel ab; unbekannte
     * Namen liefern {@link #UNKNOWN}.
     */
    public static FLandCover fromName(String name) {
        if (name == null) {
            return UNKNOWN;
        }
        String key = name.trim().toLowerCase();
        switch (key) {
            case "hafen":
            case "harbour":
            case "marina":
            case "basin":
                return HARBOUR_BASIN;
            case "seen":
            case "water":
            case "sea":
            case "ostsee":
                return SEA;
            case "strand":
            case "beach":
            case "sand":
                return BEACH;
            case "wald":
            case "forest":
            case "wood":
                return FOREST;
            case "parks":
            case "park":
            case "grass":
            case "meadow":
            case "gruenland":
                return GRASS;
            case "parkplatz":
            case "quay":
            case "pier":
            case "mole":
            case "kai":
                return QUAY;
            case "strasse":
            case "road":
            case "highway":
            case "promenade":
                return ROAD;
            case "gebaeude":
            case "building":
            case "haus":
                return BUILDING;
            default:
                return UNKNOWN;
        }
    }
}
