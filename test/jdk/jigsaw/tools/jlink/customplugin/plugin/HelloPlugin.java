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

import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import jdk.tools.jlink.plugins.Pool;
import jdk.tools.jlink.plugins.Pool.ModuleData;
import jdk.tools.jlink.plugins.TransformerPlugin;

/**
 *
 * Strip debug attributes plugin
 */
final class HelloPlugin implements TransformerPlugin {

    private static final String OUTPUT_FILE = "customplugin.txt";

    @Override
    public String getName() {
        return HelloProvider.NAME;
    }

    @Override
    public void visit(Pool inResources, Pool outResources) {
        try {
            System.out.println("Hello!!!!!!!!!!");
            File f = new File(OUTPUT_FILE);
            f.createNewFile();
            for (ModuleData res : inResources.getContent()) {
                outResources.add(res);
            }
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
    }
}
