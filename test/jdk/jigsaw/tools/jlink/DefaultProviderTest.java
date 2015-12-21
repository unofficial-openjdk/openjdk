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

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import jdk.tools.jlink.api.plugin.PluginOption;
import jdk.tools.jlink.api.plugin.PluginOptionBuilder;
import jdk.tools.jlink.internal.PluginRepository;
import jdk.tools.jlink.api.plugin.PluginProvider;
import jdk.tools.jlink.api.plugin.transformer.TransformerPlugin;
import jdk.tools.jlink.api.plugin.transformer.TransformerPluginProvider;
import tests.Helper;

/*
 * @test
 * @summary Test plugins enabled by default
 * @author Jean-Francois Denise
 * @library ../lib
 * @modules java.base/jdk.internal.jimage
 *          jdk.jdeps/com.sun.tools.classfile
 *          jdk.jlink/jdk.tools.jlink
 *          jdk.jlink/jdk.tools.jlink.internal
 *          jdk.jlink/jdk.tools.jmod
 *          jdk.jlink/jdk.tools.jimage
 *          jdk.compiler
 * @build tests.*
 * @run main/othervm DefaultProviderTest
 */
public class DefaultProviderTest {

    private final static List<PluginOption> options;
    private static final String NAME = "toto";
    private static final PluginOption NAME_OPTION
            = new PluginOptionBuilder(NAME).isEnabled().build();
    private final static Map<PluginOption, Object> expectedOptions = new HashMap<>();

    static {
        options = new ArrayList<>();
        options.add(NAME_OPTION);
        options.add(new PluginOptionBuilder("option1").description("value1").build());
        options.add(new PluginOptionBuilder("option2").description("value2").build());

        expectedOptions.put(NAME_OPTION, "on");
        expectedOptions.put(new PluginOptionBuilder("option1").
                description("value1").build(), "value1");
        expectedOptions.put(new PluginOptionBuilder("option2").
                description("value2").build(), "value2");

    }

    private static class CustomProvider extends TransformerPluginProvider {

        public CustomProvider() {
            super(NAME, NAME);
        }

        @Override
        public String getCategory() {
            return TRANSFORMER;
        }

        @Override
        public PluginOption getOption() {
            return NAME_OPTION;
        }

        @Override
        public List<PluginOption> getAdditionalOptions() {
            return options;
        }

        @Override
        public Type getType() {
            return Type.RESOURCE_PLUGIN;
        }

        @Override
        public TransformerPlugin newPlugin(Map<PluginOption, Object> config) {
            DefaultProviderTest.isNewPluginsCalled = true;
            DefaultProviderTest.receivedOptions = config;
            return null;
        }
    }

    private static class CustomProvider2 extends TransformerPluginProvider {

        public CustomProvider2() {
            super(NAME, NAME);
        }

        @Override
        public String getCategory() {
            return TRANSFORMER;
        }

        @Override
        public PluginOption getOption() {
            return NAME_OPTION;
        }

        @Override
        public List<PluginOption> getAdditionalOptions() {
            return options;
        }

        @Override
        public Type getType() {
            return Type.IMAGE_FILE_PLUGIN;
        }

        @Override
        public TransformerPlugin newPlugin(Map<PluginOption, Object> config) {
            DefaultProviderTest.isNewPluginsCalled = true;
            DefaultProviderTest.receivedOptions = config;
            return null;
        }
    }

    private static boolean isNewPluginsCalled;
    private static Map<PluginOption, Object> receivedOptions;

    private static void reset() {
        isNewPluginsCalled = false;
        receivedOptions = null;
    }

    public static void main(String[] args) throws Exception {
        Helper helper = Helper.newHelper();
        if (helper == null) {
            System.err.println("Test not run");
            return;
        }
        helper.generateDefaultModules();
        test(helper, new CustomProvider());
        test(helper, new CustomProvider2());
    }

    private static void test(Helper helper, PluginProvider provider) throws Exception {
        PluginRepository.registerPluginProvider(provider);

        {
            String[] userOptions = {};
            Path imageDir = helper.generateDefaultImage(userOptions, "composite2").assertSuccess();
            helper.checkImage(imageDir, "composite2", null, null);
            if (!isNewPluginsCalled) {
                throw new Exception("Should have been called");
            }
            reset();
        }

        {
            String[] userOptions = {"--option1", "value1", "--option2", "value2"};
            Path imageDir = helper.generateDefaultImage(userOptions, "composite2").assertSuccess();
            helper.checkImage(imageDir, "composite2", null, null);
            if (!isNewPluginsCalled) {
                throw new Exception("Should have been called");
            }
            if (!receivedOptions.equals(expectedOptions)) {
                throw new Exception("Optional options " + receivedOptions + " are not expected one "
                        + expectedOptions);
            }
            System.err.println("OPTIONS " + receivedOptions);
            reset();
        }

        {
            String[] userOptions = {"--toto", "off", "--option1", "value1"};
            Path imageDir = helper.generateDefaultImage(userOptions, "composite2").assertSuccess();
            helper.checkImage(imageDir, "composite2", null, null);
            if (isNewPluginsCalled) {
                throw new Exception("Should not have been called");
            }
            if (receivedOptions != null) {
                throw new Exception("Optional options are not expected");
            }
            reset();
        }

    }
}
