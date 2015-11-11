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
import java.io.IOException;
import java.util.List;
import java.util.Set;
import jdk.tools.jlink.plugins.ResourcePool.Resource;

/**
 * Implement this interface to develop your own image layout. First the jimage
 * is written onto the output stream returned by getOutputStream then storeFiles
 * is called.
 */
public interface ImageBuilder {

    /**
     * Retrieves resource. Retrieved resource content is, whatever the
     * configuration, uncompressed.
     */
    public interface ResourceRetriever {

        /**
         * Retrieve a resource.
         *
         * @param path Resource path.
         * @return A resource or null if not found.
         * @throws java.io.IOException
         */
        public Resource retrieves(String path) throws IOException;

        /**
         * Returns the set of modules present in the jimage,
         *
         * @return
         */
        public Set<String> getModules();
    }

    /**
     * Store the external files.
     *
     * @param files Set of module names that are composing this image.
     * @param removed List of files that have been removed (if any).
     * @param bom The options used to build the image
     * @param retriever To retrieve any resource that had been stored in the
     * jimage file.
     * @throws java.io.IOException
     */
    public void storeFiles(ImageFilePool files, List<ImageFilePool.ImageFile> removed,
            String bom, ResourceRetriever retriever) throws IOException;

    /**
     * The OutputStream to store the jimage file.
     *
     * @return The output stream
     * @throws java.io.IOException
     */
    public DataOutputStream getJImageOutputStream() throws IOException;

    /**
     * Gets executable image.
     *
     * @return The executable image.
     * @throws java.io.IOException
     */
    public ExecutableImage getExecutableImage() throws IOException;

    /**
     * Store the options that would have bee nadded by the post processing
     * @param image
     * @param args
     * @throws java.io.IOException
     */
    public void storeJavaLauncherOptions(ExecutableImage image, List<String> args)
            throws IOException;
}
