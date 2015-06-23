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

import java.io.IOException;
import java.io.InputStream;
import java.util.Collection;
import java.util.Objects;

/**
 * Pool of files. This class contain the content of a image files in the
 * matter of ImageFile.
 */
public interface ImageFilePool {
    /**
     * Files visitor
     */
    public interface Visitor {

        /**
         * Called for each visited file.
         *
         * @param file The file to deal with.
         * @return A file or null if the passed resource is to be removed
         * from the image.
         * @throws Exception
         */
        public ImageFile visit(ImageFile file) throws Exception;
    }

    /**
     * An Image File. Fully identified by its path.
     */
    public abstract class ImageFile {

        public static enum ImageFileType {

            NATIVE_LIB,
            NATIVE_CMD,
            CONFIG;
        }

        private final ImageFileType type;
        private final String name;
        private final String path;
        private final String module;

        public ImageFile(String module, String path, String name, ImageFileType type) {
            Objects.requireNonNull(module);
            Objects.requireNonNull(path);
            Objects.requireNonNull(name);
            Objects.requireNonNull(type);
            this.path = path;
            this.name = name;
            this.type = type;
            this.module = module;
        }

        public String getModule() {
            return module;
        }

        public String getPath() {
            return path;
        }

        public ImageFileType getType() {
            return type;
        }

        public String getName() {
            return name;
        }

        @Override
        public String toString() {
            return "type " + type.name() + " path " + path;
        }

        @Override
        public int hashCode() {
            int hash = 7;
            hash = 89 * hash + Objects.hashCode(this.path);
            return hash;
        }

        @Override
        public boolean equals(Object other) {
            if (!(other instanceof ImageFile)) {
                return false;
            }
            ImageFile f = (ImageFile) other;
            return f.path.equals(path);
        }

        public abstract long size();

        public abstract InputStream stream() throws IOException;
    }

    /**
     * Add a resource.
     *
     * @param file The File to add.
     * @throws java.lang.Exception If the pool is read only.
     */
    public void addFile(ImageFile file) throws Exception;

    /**
     * Check if a resource is contained in the pool.
     *
     * @param res The resource to check.
     * @return true if res is contained, false otherwise.
     */
    public boolean contains(ImageFile res);

    /**
     * Get all files contained in this pool instance.
     *
     * @return The collection of files;
     */
    public Collection<ImageFile> getFiles();

    /**
     * Get the file for the passed path.
     *
     * @param path A file path
     * @return An ImageFile instance or null if the file is not found
     */
    public ImageFile getFile(String path);

    /**
     * Check if this pool contains some resources.
     *
     * @return True if contains some resources.
     */
    public boolean isEmpty();

    /**
     * Visit the resources contained in this ImageFilePool.
     *
     * @param visitor The visitor
     * @param output The pool to store files.
     * @throws Exception
     */
    public void visit(Visitor visitor, ImageFilePool output)
            throws Exception;
}
