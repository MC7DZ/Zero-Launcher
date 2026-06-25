package com.launcher.minecraft;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** Everything GameLauncher needs to actually build the java command line. */
public class ResolvedVersion {
    public String id;
    public String mainClass;
    public String assetIndexId;
    public List<Path> classpath = new ArrayList<>();
    public List<String> extraGameArgs = new ArrayList<>();
    public List<String> extraJvmArgs = new ArrayList<>();
    /** Pre-1.13 versions only express args as a single template string. */
    public String legacyMinecraftArguments;
}
