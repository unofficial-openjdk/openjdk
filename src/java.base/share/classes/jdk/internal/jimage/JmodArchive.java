/*
 * Copyright (c) 2014, Oracle and/or its affiliates. All rights reserved.
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

package jdk.internal.jimage;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.function.Consumer;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import jdk.internal.jimage.Archive.Entry.EntryType;

/**
 * An Archive backed by a jmod file.
 */
public class JmodArchive implements Archive {

    /**
     * An entry located in a .jmod file.
     */
    private class JmodEntry extends Entry {

        private final long size;
        private final ZipEntry entry;
        private final ZipFile file;

        JmodEntry(String path, String name, EntryType type, ZipFile file, ZipEntry entry) {
            super(JmodArchive.this, path, name, type);
            this.entry = entry;
            this.file = file;
            size = entry.getSize();
        }

        /**
         * Returns the number of uncompressed bytes for this entry.
         */
        @Override
        public long size() {
            return size;
        }

        @Override
        public InputStream stream() throws IOException {
            return file.getInputStream(entry);
        }
    }

    private static final String JMOD_EXT = ".jmod";
    private static final String MODULE_NAME = "module";
    private static final String MODULE_INFO = "module-info.class";
    private static final String CLASSES     = "classes";
    private static final String NATIVE_LIBS = "native";
    private static final String NATIVE_CMDS = "bin";
    private static final String CONFIG      = "conf";
    private static final String SERVICES    = "module/services";
    private final Path jmod;
    private final String moduleName;
    // currently processed ZipFile
    private ZipFile zipFile;

    public JmodArchive(String mn, Path jmod) {
        String filename = jmod.getFileName().toString();
        if (!filename.endsWith(JMOD_EXT))
            throw new UnsupportedOperationException("Unsupported format: " + filename);
        this.moduleName = mn;
        this.jmod = jmod;
    }

    @Override
    public String moduleName() {
        return moduleName;
    }

    @Override
    public void visitEntries(Consumer<Entry> consumer) {
        try {
            if (zipFile == null) {
                open();
            }
        } catch (IOException ioe) {
            throw new UncheckedIOException(ioe);
        }
        zipFile.stream()
                .map(this::toEntry).filter(n -> n != null)
                .forEach(consumer::accept);
    }

    private static EntryType toResourceType(String section) {
        switch (section) {
            case CLASSES:
                return EntryType.CLASS_RESOURCE;
            case NATIVE_LIBS:
                return EntryType.NATIVE_LIB;
            case NATIVE_CMDS:
                return EntryType.NATIVE_CMD;
            case CONFIG:
                return EntryType.CONFIG;
            case MODULE_NAME:
                return EntryType.MODULE_NAME;
            case SERVICES:
                //throw new UnsupportedOperationException(name + " in " + zipfile.toString()); //TODO
                throw new UnsupportedOperationException(section);
            default:
                //throw new InternalError("unexpected entry: " + name + " " + zipfile.toString()); //TODO
                throw new InternalError("unexpected entry: " + section);
        }
    }

    private Entry toEntry(ZipEntry ze) {
        String name = ze.getName();
        String fn = name.substring(name.indexOf('/') + 1);

        if (ze.isDirectory() || fn.startsWith("_")) {
            return null;
        }
        if (fn.equals(MODULE_INFO)) {
            fn = moduleName + "/" + MODULE_INFO;
        }
        String section = name.substring(0, name.indexOf('/'));
        EntryType rt = toResourceType(section);

        return new JmodEntry(ze.getName(), fn, rt, zipFile, ze);
    }

    @Override
    public void close() throws IOException {
        zipFile.close();
    }

    @Override
    public void open() throws IOException {
        if (zipFile != null) {
            zipFile.close();
        }
        zipFile = new ZipFile(jmod.toFile());
    }
}

