package com.launcher.model;

public class LauncherSettings {
    // Appearance
    public String accentColor      = "#10b981";   // emerald green accent
    public String bgColor          = "#0a0a0f";   // near-black base
    public String panelBgColor     = "#13131a";   // slightly lighter panel
    public String textColor        = "#e2e2ea";   // off-white text
    public String logBgColor       = "#060608";   // very dark console bg
    public String fontFamily       = "Inter";
    public boolean useBackgroundImage   = false;
    public String backgroundImagePath   = "";
    public boolean enableBlurEffect     = false;
    public int blurStrength             = 10;     // GaussianBlur radius (1–40)

    // Behavior
    /** Minimize the launcher while Minecraft is running — game opens in its own OS window. */
    public boolean minimizeOnLaunch     = true;
    /** Close the launcher entirely after Minecraft launches (saves RAM). */
    public boolean closeAfterLaunch     = false;
    /** Keep the log console visible while Minecraft is running. */
    public boolean showConsoleOnLaunch  = true;
    public boolean scanOnStartup        = true;
    public boolean showHiddenInstances  = false;

    // Performance
    /** Default RAM in GB for new instances. */
    public int defaultRamGb             = 3;
    /** Extra JVM arguments appended to every launch command. */
    public String extraJvmArgs          = "";

    // Window size (0 = use defaults 960×660)
    public int launcherWidth            = 0;
    public int launcherHeight           = 0;

    // Privacy & Security
    public boolean hideUsername         = false;
    public boolean redactPaths          = false;
    public boolean redactTokens         = true;
    public boolean clearSessionOnExit   = false;
}
