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
package jdk.jigsaw.tools.jlink.internal.plugins;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import jdk.jigsaw.tools.jlink.plugins.Plugin;
import jdk.jigsaw.tools.jlink.plugins.PluginProvider;
import jdk.jigsaw.tools.jlink.internal.ImagePluginConfiguration;

/**
 *
 * Exclude resources plugin provider
 */
public final class ExcludeProvider extends PluginProvider {
    public static final String NAME = "exclude";
    public ExcludeProvider() {
        super(NAME, "Exclude resources");
    }

    @Override
    public Plugin newPlugin(Properties properties) throws IOException {
        List<Pattern> patterns = new ArrayList<>();
        for (String a : properties.stringPropertyNames()) {
            switch (a) {
                case CONFIGURATION_PROPERTY: {
                    String[] items = properties.getProperty(CONFIGURATION_PROPERTY).
                            split(",");
                    for(String p : items) {
                        Pattern pattern = Pattern.compile(escape(p));
                        patterns.add(pattern);
                    }
                    break;
                }
            }
        }
        return new ExcludePlugin(patterns);
    }

    private static String escape(String s) {
        s = s.replaceAll(" ", "");
        s = s.replaceAll("\\$", Matcher.quoteReplacement("\\$"));
        s = s.replaceAll("\\.", Matcher.quoteReplacement("\\."));
        s = s.replaceAll("\\*", ".+");
        return s;
    }

    @Override
    public String getCategory() {
        return ImagePluginConfiguration.FILTER;
    }

    @Override
    public String getConfiguration() {
        return "list of file names to escape. eg: *.jcov, */META-INF/*";
    }

    @Override
    public String getToolOption() {
        return NAME;
    }
}
