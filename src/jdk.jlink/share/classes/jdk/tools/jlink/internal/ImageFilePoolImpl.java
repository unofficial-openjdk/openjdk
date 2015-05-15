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
package jdk.tools.jlink.internal;

import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import jdk.tools.jlink.plugins.ImageFilePool;

/**
 * Pool of files. This class contain the content of a image in the
 * matter of ImageFile.
 */
public class ImageFilePoolImpl implements ImageFilePool {

    private final Map<String, ImageFile> files = new LinkedHashMap<>();

    private boolean isReadOnly;

    public ImageFilePoolImpl() {
    }

    /**
     * Make this pool instance read-only. No file can be added.
     */
    public void setReadOnly() {
        isReadOnly = true;
    }

    /**
     * Add a file.
     *
     * @param file The file to add.
     * @throws java.lang.Exception If the pool is read only.
     */
    @Override
    public void addFile(ImageFile file) throws Exception {
        if (isReadOnly) {
            throw new Exception("pool is readonly");
        }
        Objects.requireNonNull(file);
        if (files.get(file.getPath()) != null) {
            throw new Exception("Resource" + file.getPath() +
                    " already present");
        }
        files.put(file.getPath(), file);
    }

    /**
     * Check if a file is contained in the pool.
     *
     * @param file The file to check.
     * @return true if file is contained, false otherwise.
     */
    @Override
    public boolean contains(ImageFile file) {
        Objects.requireNonNull(file);
        try {
            getFile(file.getPath());
            return true;
        } catch (Exception ex) {
            return false;
        }
    }

    /**
     * Get all ImageFile contained in this pool instance.
     *
     * @return The collection of files;
     */
    @Override
    public Collection<ImageFile> getFiles() {
        return Collections.unmodifiableCollection(files.values());
    }

/**
     * Get the file for the passed path.
     *
     * @param path A file path
     * @return A ImageFile instance or null if the file is not found
     */
    @Override
    public ImageFile getFile(String path) {
        Objects.requireNonNull(path);
        return files.get(path);
    }

    /**
     * Check if this pool contains some files.
     *
     * @return True if contains some files.
     */
    @Override
    public boolean isEmpty() {
        return files.isEmpty();
    }

    /**
     * Visit the files contained in this ImageFilePool.
     *
     * @param visitor The visitor
     * @throws Exception
     */
    @Override
    public void visit(Visitor visitor, ImageFilePool output)
            throws Exception {
        for (ImageFile file : getFiles()) {
            ImageFile f = visitor.visit(file);
            if (f != null) {
                output.addFile(f);
            }
        }
    }
}
