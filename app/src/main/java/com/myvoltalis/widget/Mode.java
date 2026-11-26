package com.myvoltalis.widget;

/** Modes the widget can display / apply. AUTO = follow the Voltalis programming (no manual override). */
public enum Mode {
    AUTO("Auto", "📊", 0xFF607D8B),
    COMFORT("Confort", "🔥", 0xFFE8590C),
    ECO("Eco", "🌿", 0xFF2F9E44),
    FROST("Hors-gel", "❄", 0xFF1C7ED6),
    UNKNOWN("?", "?", 0xFF868E96);

    public final String label;
    public final String glyph;
    public final int color;

    Mode(String label, String glyph, int color) {
        this.label = label;
        this.glyph = glyph;
        this.color = color;
    }

    /** Modes offered in the picker, in cycling order. */
    public static final Mode[] SELECTABLE = {AUTO, COMFORT, ECO, FROST};

    public static Mode fromApi(String apiMode) {
        if (apiMode == null) return UNKNOWN;
        switch (apiMode) {
            case "CONFORT":
            case "NORMAL":
            case "TEMPERATURE":
                return COMFORT;
            case "ECO":
            case "ECOV":
                return ECO;
            case "HORS_GEL":
                return FROST;
            default:
                return UNKNOWN;
        }
    }
}
