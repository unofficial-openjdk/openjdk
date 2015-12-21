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

import java.io.DataOutputStream;
import java.util.List;
import jdk.tools.jlink.plugins.Pool.ModuleData;

/**
 * Implement this interface to develop your own image layout. First the jimage
 * is written onto the output stream returned by getOutputStream then storeFiles
 * is called.
 */
public interface ImageBuilder extends Plugin {

    /**
     * Store the external files.
     *
     * @param files Pool of files.
     * @param removed List of files that have been removed (if any).
     * @param bom The options used to build the image
     * @param resources Pool of resources that have been stored in the jimage
     * file.
     * @throws PluginException
     */
    public void storeFiles(Pool files, List<ModuleData> removed,
            String bom, Pool resources);

    /**
     * The OutputStream to store the jimage file.
     *
     * @return The output stream
     * @throws PluginException
     */
    public DataOutputStream getJImageOutputStream();

    /**
     * Gets executable image.
     *
     * @return The executable image.
     * @throws PluginException
     */
    public ExecutableImage getExecutableImage();

    /**
     * Store the options that would have bee nadded by the post processing
     * @param image
     * @param args
     * @throws PluginException
     */
    public void storeJavaLauncherOptions(ExecutableImage image, List<String> args);
}
