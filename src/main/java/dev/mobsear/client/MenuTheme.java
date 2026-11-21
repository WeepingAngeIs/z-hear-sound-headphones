package dev.mobsear.client;

import java.util.Locale;

/** Families share their frame and controls. Each optional scene is a local texture. */
public enum MenuTheme {
    UWU("uwu", "Original"), LOTUS("uwu_lotus", "Lotus Pond"), BLOSSOM("uwu_blossom", "Blossom Branches"),
    PETALS("uwu_petals", "Petal Rain"), HEARTS("uwu_hearts", "Heart Garden"),
    EMO("emo", "Original"), SKULLS("emo_skulls", "Moonlit Crypt"), THORNS("emo_thorns", "Thorn Roses"),
    SHARDS("emo_shards", "Shattered Hearts"), ECLIPSE("emo_eclipse", "Solar Eclipse"),
    VANILLA("vanilla", "Original"), FERNS("vanilla_ferns", "Fern Forest"), GROVE("vanilla_grove", "Melon Grove"),
    JUNGLE("vanilla_jungle", "Coconut Jungle"), BEACH("vanilla_beach", "Pixel Beach"),
    AMETHYST("amethyst", "Original"), NEBULA("amethyst_nebula", "Nebula"), RINGS("amethyst_rings", "Ringed Worlds"),
    VOID("amethyst_void", "Event Horizon"), STARS("amethyst_stars", "Starfield");

    private final String id;
    private final String label;
    private final String family;
    MenuTheme(String id, String label) {
        this.id = id;
        this.label = label;
        this.family = id.split("_", 2)[0];
    }
    public String id() { return id; }
    public String label() { return label; }
    public String family() { return family; }
    public boolean hasScene() { return !id.equals(family); }
    public MenuTheme next() {
        MenuTheme next = values()[(ordinal() + 1) % values().length];
        return next.family.equals(family) ? next : fromId(family);
    }
    public static MenuTheme fromId(String id) {
        if (id != null) {
            String normalized = id.toLowerCase(Locale.ROOT);
            for (MenuTheme candidate : values()) if (candidate.id.equals(normalized)) return candidate;
        }
        return UWU;
    }
}
