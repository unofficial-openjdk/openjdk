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
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import jdk.tools.jlink.api.plugin.PluginOption;
import jdk.tools.jlink.api.plugin.PluginOptionBuilder;
import jdk.tools.jlink.internal.PluginRepository;
import jdk.tools.jlink.api.plugin.transformer.TransformerPlugin;
import jdk.tools.jlink.api.plugin.transformer.TransformerPluginProvider;

import tests.Helper;

/*
 * @test
 * @summary Test jlink options
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
 * @run main JLinkOptionsTest
 */
public class JLinkOptionsTest {

    private static class TestProvider extends TransformerPluginProvider {

        private final PluginOption option;
        private final List<PluginOption> options;

        private TestProvider(String name, PluginOption option,
                List<PluginOption> options) {
            super(name, "");
            this.option = option;
            this.options = options;
        }

        @Override
        public String getCategory() {
            return null;
        }

        @Override
        public List<PluginOption> getAdditionalOptions() {
            return options;
        }

        @Override
        public PluginOption getOption() {
            return option;
        }

        @Override
        public Type getType() {
            return Type.RESOURCE_PLUGIN;
        }

        @Override
        public TransformerPlugin newPlugin(Map<PluginOption, Object> config) {
            return null;
        }
    }

    public static void main(String[] args) throws Exception {
        Helper helper = Helper.newHelper();
        if (helper == null) {
            System.err.println("Test not run");
            return;
        }
        helper.generateDefaultModules();
        String optionName = "test1";
        PluginOption option = new PluginOptionBuilder(optionName).build();
        PluginOption option2 = new PluginOptionBuilder("test2").build();
        {
            // multiple plugins with same option

            PluginRepository.
                    registerPluginProvider(new TestProvider("test1", option, null));
            PluginRepository.
                    registerPluginProvider(new TestProvider("test2", option, null));
            helper.generateDefaultImage("composite2").assertFailure("Error: More than one plugin enabled by test1 option");
            PluginRepository.unregisterPluginProvider("test1");
            PluginRepository.unregisterPluginProvider("test2");
        }

        {
            // option and optional options collision
            List<PluginOption> options = new ArrayList<>();
            options.add(option);
            PluginRepository.
                    registerPluginProvider(new TestProvider("test1", option, null));
            PluginRepository.
                    registerPluginProvider(new TestProvider("test2", option2, options));

            helper.generateDefaultImage("composite2").assertFailure("Error: More than one plugin enabled by test1 option");
            PluginRepository.unregisterPluginProvider("test1");
            PluginRepository.unregisterPluginProvider("test2");
        }
    }
}
