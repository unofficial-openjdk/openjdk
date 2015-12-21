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

import java.io.BufferedWriter;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import jdk.tools.jlink.api.plugin.PluginOption;
import jdk.tools.jlink.api.plugin.builder.ExecutableImage;

import jdk.tools.jlink.api.plugin.builder.ImageBuilder;
import jdk.tools.jlink.api.plugin.transformer.Pool;

public class CustomImageBuilder implements ImageBuilder {

    private final Path image;
    private final Map<PluginOption, Object> config;

    public CustomImageBuilder(Map<PluginOption, Object> config, Path image) throws IOException {
        this.image = image;
        this.config = config;
        System.err.println(config);
        config.forEach((k, v) -> System.err.println(k + " " + v));

        handleOption(CustomImageBuilderProvider.OPTION1);
        handleOption(CustomImageBuilderProvider.OPTION2);
    }

    private void handleOption(PluginOption option) throws IOException {
        if (config.containsKey(option)) {
            String firstValue = (String) config.get(option);
            Files.write(image.resolve(option.getName()), Objects.toString(firstValue == null ? "" : firstValue).getBytes());
        }
    }

    @Override
    public void storeFiles(Pool files, List<Pool.ModuleData> removed, String bom, Pool resources) {
        try (BufferedWriter writer = Files.newBufferedWriter(image.resolve("files.txt"))) {
            writer.write(Integer.toString(files.getContent().size()));
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
    }

    @Override
    public DataOutputStream getJImageOutputStream() {
        try {
            return new DataOutputStream(Files.newOutputStream(image.resolve("image.jimage")));
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
    }

    @Override
    public ExecutableImage getExecutableImage() {
        return new ExecutableImage(image, Collections.emptySet(),
                Collections.emptyList()) {
                };
    }

    @Override
    public void storeJavaLauncherOptions(ExecutableImage image, List<String> args) {
    }

    @Override
    public String getName() {
        return CustomImageBuilderProvider.NAME;
    }
}
