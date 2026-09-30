package dev.dragonegg;

public enum Legendary {
    TUNIC("tunic", "The Legendary Tunic"),
    FORK("fork", "FORK"),
    GRIFFIN("griffin", "Griffin"),
    HAMMER("hammer", "THE HAMMER");

    public final String id;
    public final String display;

    Legendary(String id, String display) {
        this.id = id;
        this.display = display;
    }

    public static Legendary byId(String s) {
        if (s == null) return null;
        for (Legendary l : values()) {
            if (l.id.equalsIgnoreCase(s)) return l;
        }
        return null;
    }
}
