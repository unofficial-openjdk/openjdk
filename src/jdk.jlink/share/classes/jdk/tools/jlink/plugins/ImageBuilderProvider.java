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

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import jdk.tools.jlink.internal.plugins.PluginsResourceBundle;

/**
 * Extend this class and make your class available to the ServiceLoader in order
 * to expose your ImageBuilder.
 */
public abstract class ImageBuilderProvider extends PluginProvider {

    public static final String IMAGE_PATH_KEY = "jlink.image.path";

    public ImageBuilderProvider(String name, String description) {
        super(name, description);
    }

    @Override
    public abstract List<? extends ImageBuilder> newPlugins(Map<String, Object> config);

    /**
     * Image builder provider can execute the image located in the passed dir
     *
     * @param root The image directory.
     * @return An ExecutableImage if runnable, otherwise null.
     */
    public abstract ExecutableImage canExecute(Path root);

    /**
     * Ask the provider to store some options in the image launcher(s).
     *
     * @param image
     * @param arguments The arguments to add to the launcher.
     * @throws PluginException
     */
    public abstract void storeLauncherOptions(ExecutableImage image, List<String> arguments)
            throws PluginException;

    /**
     * Options to configure the image builder.
     *
     * @return The option name / description mapping
     */
    public abstract Map<String, String> getOptions();

    /**
     * Check if an option expects an argument.
     *
     * @param option
     * @return true if an argument is expected. False otherwise.
     */
    public abstract boolean hasArgument(String option);
}
