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

/*
 * @test
 * @summary Test on/off provider
 * @author Andrei Eremeev
 * @modules java.base/jdk.internal.jimage.decompressor
 *          jdk.jlink/jdk.tools.jlink.internal
 *          jdk.jlink/jdk.tools.jlink.internal.plugins
 * @run main OnOffProviderTest
 */

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;

import jdk.tools.jlink.internal.ImagePluginConfiguration;
import jdk.tools.jlink.plugins.CmdPluginProvider;
import jdk.tools.jlink.plugins.ImageFilePlugin;
import jdk.tools.jlink.plugins.OnOffImageFilePluginProvider;
import jdk.tools.jlink.plugins.OnOffResourcePluginProvider;
import jdk.tools.jlink.plugins.Plugin;
import jdk.tools.jlink.plugins.PluginProvider;
import jdk.tools.jlink.plugins.ResourcePlugin;

public class OnOffProviderTest {

    private interface ProviderFactory {

        PluginProvider newProvider();
    }

    private final static Map<String, String> additionalOptions;

    private static boolean isNewPluginsCalled = false;
    private final static String OPTION = "option";
    private final static String VALUE = "value";
    static {
        additionalOptions = new HashMap<>();
        additionalOptions.put(OPTION, VALUE);
    }


    public static void main(String[] args) throws IOException {
        test(() -> {
            return new CustomProvider();
        });
        test(() -> {
            return new CustomProvider2();
        });
    }

    private static void reset() {
        isNewPluginsCalled = false;
    }

    public static void test(ProviderFactory factory) throws IOException {
        {
            Map<String, Object> config = new HashMap<>();
            config.put(CmdPluginProvider.TOOL_ARGUMENT_PROPERTY, ImagePluginConfiguration.OFF_ARGUMENT);
            Plugin[] plugins = factory.newProvider().newPlugins(config);
            if (plugins.length != 0) {
                throw new AssertionError("Expected empty list of plugins");
            }
            reset();
        }
        {
            Map<String, Object> config = new HashMap<>();
            config.put(CmdPluginProvider.TOOL_ARGUMENT_PROPERTY, ImagePluginConfiguration.ON_ARGUMENT);
            config.put(OPTION, VALUE);
            factory.newProvider().newPlugins(config);
            if (!isNewPluginsCalled) {
                throw new AssertionError("newPlugins() was not called");
            }
            reset();
        }
        {
            Map<String, Object> config = new HashMap<>();
            config.put(CmdPluginProvider.TOOL_ARGUMENT_PROPERTY,
                    ImagePluginConfiguration.ON_ARGUMENT + "," + ImagePluginConfiguration.OFF_ARGUMENT);
            try {
                factory.newProvider().newPlugins(config);
                throw new AssertionError("IOException expected");
            } catch (IOException e) {
                assertException(e, "Invalid number of arguments expecting on|off");
            }
            reset();
        }
        {
            Map<String, Object> config = new HashMap<>();
            config.put(CmdPluginProvider.TOOL_ARGUMENT_PROPERTY, "INVALID");
            try {
                factory.newProvider().newPlugins(config);
                throw new AssertionError("IOException expected");
            } catch (IOException e) {
                assertException(e, "Invalid argument INVALID, expecting on or off");
            }
            reset();
        }
    }

    private static void assertException(IOException e, String expectedMessage) {
        String message = e.getMessage();
        if (!Objects.equals(message, expectedMessage)) {
            throw new AssertionError("Expected: " + expectedMessage + ", got: " + message);
        }
    }

    private static class CustomProvider extends OnOffResourcePluginProvider {

        public CustomProvider() {
            super("custom-on-off-provider", "custom-on-off-provider");
        }

        @Override
        public ResourcePlugin[] createPlugins(Map<String, String> otherOptions) throws IOException {
            if (!additionalOptions.equals(otherOptions)) {
                throw new AssertionError("Additional options: expected: " +
                        additionalOptions + ", got: " + otherOptions);
            }
            isNewPluginsCalled = true;
            return null;
        }

        @Override
        public String getCategory() {
            throw new UnsupportedOperationException();
        }

        @Override
        public String getToolOption() {
            throw new UnsupportedOperationException();
        }

        @Override
        public Map<String, String> getAdditionalOptions() {
            return additionalOptions;
        }
    }

    private static class CustomProvider2 extends OnOffImageFilePluginProvider {

        public CustomProvider2() {
            super("custom-on-off-provider", "custom-on-off-provider");
        }

        @Override
        public ImageFilePlugin[] createPlugins(Map<String, String> otherOptions) throws IOException {
            if (!additionalOptions.equals(otherOptions)) {
                throw new AssertionError("Additional options: expected: "
                        + additionalOptions + ", got: " + otherOptions);
            }
            isNewPluginsCalled = true;
            return null;
        }

        @Override
        public String getCategory() {
            throw new UnsupportedOperationException();
        }

        @Override
        public String getToolOption() {
            throw new UnsupportedOperationException();
        }

        @Override
        public Map<String, String> getAdditionalOptions() {
            return additionalOptions;
        }
    }
}
