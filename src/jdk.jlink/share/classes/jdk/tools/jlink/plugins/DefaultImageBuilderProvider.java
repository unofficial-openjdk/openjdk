/*
 * Copyright (c) 2015, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.  Oracle designates this
 * particular file as subject to the "Classpath" exception as provided
 * by Oracle in the LICENSE file that accompanied this code.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 * Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA
 * or visit www.oracle.com if you need additional information or have any
 * questions.
 */
package jdk.tools.jlink.plugins;

import java.io.FileInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import jdk.tools.jlink.internal.plugins.PluginsResourceBundle;
import static jdk.tools.jlink.plugins.DefaultImageBuilder.isWindows;

/**
 * Default Image Builder provider.
 */
public class DefaultImageBuilderProvider implements ImageBuilderProvider {

    public static final String GEN_BOM = "genbom";
    public static final String JIMAGE_NAME_PROPERTY = "jimage.name";
    public static final String NAME = "default-image-builder";

    private static final Map<String, String> OPTIONS = new HashMap<>();

    static {
        OPTIONS.put(GEN_BOM, PluginsResourceBundle.getOption(NAME, GEN_BOM));
    }

    @Override
    public Map<String, String> getOptions() {
        return OPTIONS;
    }

    @Override
    public boolean hasArgument(String option) {
        return false;
    }

    @Override
    public String getName() {
        return NAME;
    }

    @Override
    public String getDescription() {
        return PluginsResourceBundle.getDescription(NAME);
    }

    @Override
    public ImageBuilder newBuilder(Map<String, Object> config, Path imageOutDir)
            throws IOException {
        if (Files.exists(imageOutDir)) {
            throw new IOException(PluginsResourceBundle.
                    getMessage("err.dir.already.exits", imageOutDir));
        }
        return new DefaultImageBuilder(config, imageOutDir);
    }

    @Override
    public ExecutableImage canExecute(Path root) {
        if (Files.exists(root.resolve("bin").resolve(getJavaProcessName()))) {
            return new DefaultImageBuilder.DefaultExecutableImage(root,
                    retrieveModules(root));
        }
        return null;
    }

    static String getJavaProcessName() {
        return isWindows() ? "java.exe" : "java";
    }

    private static Set<String> retrieveModules(Path root) {
        Path releaseFile = root.resolve("release");
        Set<String> modules = new HashSet<>();
        if (Files.exists(releaseFile)) {
            Properties release = new Properties();
            try (FileInputStream fi = new FileInputStream(releaseFile.toFile())) {
                release.load(fi);
            } catch (IOException ex) {
                System.err.println("Cant read release file " + ex);
            }
            String mods = release.getProperty("MODULES");
            if (mods != null) {
                String[] arr = mods.split(",");
                for (String m : arr) {
                    modules.add(m);
                }

            }
        }
        return modules;
    }

    @Override
    public void storeLauncherOptions(ExecutableImage image, List<String> arguments)
            throws IOException {
        DefaultImageBuilder.patchScripts(image, arguments);
    }
}
