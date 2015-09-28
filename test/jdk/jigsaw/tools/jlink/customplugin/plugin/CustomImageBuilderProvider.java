/*
 * Copyright (c) 2015, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.
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

package plugin;

import java.io.IOException;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

import jdk.tools.jlink.plugins.ImageBuilder;
import jdk.tools.jlink.plugins.ImageBuilderProvider;

public class CustomImageBuilderProvider implements ImageBuilderProvider {

    private static final String NAME = "custom-image-builder";
    static final String OPTION = "custom-image-option";
    private static final String OPTION_DESCRIPTION = OPTION + "-description";
    private static final Map<String, String> OPTIONS = new HashMap<>();

    static {
        for (int i = 1; i <= 2; ++i) {
            OPTIONS.put(OPTION + "-" + i, OPTION_DESCRIPTION);
        }
    }

    @Override
    public String getName() {
        return NAME;
    }

    @Override
    public String getDescription() {
        return NAME + "-description";
    }

    @Override
    public ImageBuilder newBuilder(Map<String, Object> config, Path imageOutDir) throws IOException {
        return new CustomImageBuilder(config, imageOutDir);
    }

    @Override
    public Map<String, String> getOptions() {
        return OPTIONS;
    }

    @Override
    public boolean hasArgument(String option) {
        return option.equals(OPTION + "-1");
    }
}
