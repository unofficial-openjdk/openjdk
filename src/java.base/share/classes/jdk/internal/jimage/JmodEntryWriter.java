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

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.zip.ZipFile;

/**
 * A Consumer suitable for processing each Archive Entry and writing it to the
 * appropriate location.
 */
public class JmodEntryWriter implements Consumer<Archive.Entry> {
    private static final int BUF_SIZE = 8192;
    private static final String MODULE_NAME = "module";
    private static final String CLASSES     = "classes";
    private static final String NATIVE_LIBS = "native";
    private static final String NATIVE_CMDS = "bin";
    private static final String CONFIG      = "conf";
    private static final String SERVICES    = "module/services";
    private final Path root;
    private final OutputStream out;
    private final boolean compress;

    JmodEntryWriter(Path root, OutputStream out) {
        this(root, out, false);
    }

    JmodEntryWriter(Path root, OutputStream out, boolean compress) {
        this.root = root;
        this.out = out;
        this.compress = compress;
    }

    @Override
    public void accept(Archive.Entry entry) {
        try {
            if (entry.isDirectory()) {
                return;
            }

            String name = entry.getName();
            String section = name.substring(0, name.indexOf('/'));
            String filename = name.substring(name.indexOf('/') + 1);
            try (InputStream in = entry.getInputStream()) {
                switch (section) {
                    case CLASSES:
                        if (!filename.startsWith("_")) {
                            writeEntry(in);
                        }
                        break;
                    case NATIVE_LIBS:
                        writeEntry(in, destFile(nativeDir(), filename));
                        break;
                    case NATIVE_CMDS:
                        Path path = destFile("bin", filename);
                        writeEntry(in, path);
                        path.toFile().setExecutable(true);
                        break;
                    case CONFIG:
                        writeEntry(in, destFile("lib", filename));
                        break;
                    case MODULE_NAME:
                        // skip
                        break;
                    case SERVICES:
                        //throw new UnsupportedOperationException(name + " in " + zipfile.toString()); //TODO
                        throw new UnsupportedOperationException(name + " in " + name);
                    default:
                        //throw new InternalError("unexpected entry: " + name + " " + zipfile.toString()); //TODO
                        throw new InternalError("unexpected entry: " + name + " " + name);
                }
            }
        } catch (IOException x) {
            throw new UncheckedIOException(x);
        }
    }

    private Path destFile(String dir, String filename) {
        return root.resolve(dir).resolve(filename);
    }

    private void writeEntry(InputStream in, Path dstFile) throws IOException {
        if (Files.notExists(dstFile.getParent())) {
            Files.createDirectories(dstFile.getParent());
        }
        Files.copy(in, dstFile);
    }

    static long calcCompressedSize(InputStream is) {
        try {
            byte[] inBytes = readAllBytes(is);
            return ImageFile.Compressor.compress(inBytes).length;
        } catch (IOException ioe) {
            throw new UncheckedIOException(ioe);
        }
    }

    private static byte[] readAllBytes(InputStream is) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        byte[] buf = new byte[BUF_SIZE];
        while(true) {
            int n = is.read(buf);
            if( n < 0 ) break;
            baos.write(buf,0,n);
        }
        return baos.toByteArray();
    }

    private void writeEntry(InputStream in) throws IOException {
        if (compress) {
            byte[] inBytes = readAllBytes(in);
            byte[] outBytes = ImageFile.Compressor.compress(inBytes);
            out.write(outBytes, 0, outBytes.length);
        } else {
            byte[] buf = new byte[BUF_SIZE];
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
            }
        }
    }

    private String nativeDir() {
        if (System.getProperty("os.name").startsWith("Windows")) {
            return "bin";
        } else {
            return "lib";
        }
    }
}
