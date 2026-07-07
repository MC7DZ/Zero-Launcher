package com.launcher.manager;

import java.awt.Font;
import java.awt.GraphicsEnvironment;
import java.io.File;
import java.io.InputStream;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Registers fonts with the JVM's {@link GraphicsEnvironment} so they're usable via
 * {@code new Font(familyName, ...)} anywhere in the app, and decides which fonts show up
 * in the Appearance tab's "Font Family" picker.
 *
 * By design the picker only ever offers:
 *   1) A curated list of common SANS-SERIF fonts (filtered down to whichever of them are
 *      actually installed on this system), plus the logical "SansSerif" font which is
 *      always available on every platform;
 *   2) The bundled "Minecraft" font, shipped inside the app itself so it's always available
 *      regardless of what's installed on the user's system;
 *   3) Any fonts the user has explicitly added via "Add Font…" (persisted in settings).
 */
public final class FontManager {

    private FontManager() {}

    /** Curated sans-serif font names to offer *if* they're actually installed on this system. */
    private static final String[] CANDIDATE_SANS_SERIF_FONTS = {
            "Segoe UI", "Arial", "Helvetica", "Verdana", "Tahoma", "Calibri",
            "Roboto", "Open Sans", "Noto Sans", "Ubuntu", "DejaVu Sans",
            "Liberation Sans", "Inter", "Cantarell", "San Francisco"
    };

    /** Family name of the bundled Minecraft font, once registered. Null until {@link #init} runs. */
    private static volatile String minecraftFamilyName = null;

    private static volatile boolean initialized = false;

    /** Registers the bundled Minecraft font and any user-added custom fonts from settings.
     *  Safe to call more than once (subsequent calls are no-ops). Call this once at startup,
     *  before building the UI, so every font is available immediately. */
    public static synchronized void init(com.launcher.model.LauncherSettings settings) {
        if (initialized) return;
        initialized = true;

        // 1) Bundled Minecraft font, shipped as a resource inside the jar.
        try (InputStream in = FontManager.class.getResourceAsStream("/com/launcher/fonts/Minecraft.ttf")) {
            if (in != null) {
                Font f = Font.createFont(Font.TRUETYPE_FONT, in);
                GraphicsEnvironment.getLocalGraphicsEnvironment().registerFont(f);
                minecraftFamilyName = f.getFamily();
            }
        } catch (Exception ignored) {
            // Missing/corrupt bundled font — just fall back to not offering it.
        }

        // 2) User-added custom fonts (persisted paths from a previous "Add Font…").
        if (settings != null) {
            for (String path : settings.customFontPathList()) {
                registerFontFile(path);
            }
        }
    }

    /** Registers a single font file (.ttf/.otf) with the JVM and returns its family name,
     *  or null if the file couldn't be loaded as a font. */
    public static String registerFontFile(String path) {
        if (path == null || path.isBlank()) return null;
        File file = new File(path);
        if (!file.isFile()) return null;
        int type = path.toLowerCase().endsWith(".otf") ? Font.TRUETYPE_FONT : Font.TRUETYPE_FONT;
        try {
            Font f = Font.createFont(type, file);
            GraphicsEnvironment.getLocalGraphicsEnvironment().registerFont(f);
            return f.getFamily();
        } catch (Exception e) {
            return null;
        }
    }

    /** The bundled Minecraft font's registered family name (usually "Minecraft"), or null
     *  if it failed to load. */
    public static String getMinecraftFamilyName() {
        return minecraftFamilyName;
    }

    /** Builds the ordered, de-duplicated list of font family names to show in the Font Family
     *  picker: logical SansSerif, whichever curated sans-serif fonts are installed, the bundled
     *  Minecraft font (if it loaded), then any user-added custom fonts (by their registered
     *  family name, resolved fresh from settings so renamed/re-added fonts stay in sync). */
    public static java.util.List<String> buildFontChoices(com.launcher.model.LauncherSettings settings) {
        Set<String> available = Set.of(GraphicsEnvironment.getLocalGraphicsEnvironment().getAvailableFontFamilyNames());
        LinkedHashSet<String> choices = new LinkedHashSet<>();

        choices.add("SansSerif"); // always available on every platform (logical font)
        for (String candidate : CANDIDATE_SANS_SERIF_FONTS) {
            if (available.contains(candidate)) choices.add(candidate);
        }

        if (minecraftFamilyName != null) choices.add(minecraftFamilyName);

        if (settings != null) {
            for (String path : settings.customFontPathList()) {
                String family = registerFontFile(path); // cheap if already registered
                if (family != null) choices.add(family);
            }
        }

        return new java.util.ArrayList<>(choices);
    }
}
