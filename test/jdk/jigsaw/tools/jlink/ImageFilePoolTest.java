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
 * @summary Test ImageFilePool class
 * @author Andrei Eremeev
 * @modules jdk.jlink/jdk.tools.jlink.internal
 * @run build ImageFilePoolTest
 * @run main ImageFilePoolTest
 */

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;

import jdk.tools.jlink.internal.ImageFilePoolImpl;
import jdk.tools.jlink.plugins.ImageFilePool;
import jdk.tools.jlink.plugins.ImageFilePool.ImageFile;
import jdk.tools.jlink.plugins.ImageFilePool.ImageFile.ImageFileType;
import jdk.tools.jlink.plugins.ImageFilePool.Visitor;

public class ImageFilePoolTest {
    public static void main(String[] args) throws Exception {
        new ImageFilePoolTest().test();
    }

    public void test() throws Exception {
        checkNegative();
        checkVisitor();
    }

    private static final String SUFFIX = "END";

    private void checkVisitor() throws Exception {
        ImageFilePool input = new ImageFilePoolImpl();
        for (int i = 0; i < 1000; ++i) {
            String module = "module" + (i / 100);
            input.addFile(new InMemoryImageFile(module, "/" + module + "/java/class" + i,
                    "class" + i, ImageFileType.CONFIG, "class" + i));
        }
        if (input.getFiles().size() != 1000) {
            throw new AssertionError();
        }
        ImageFilePool output = new ImageFilePoolImpl();
        ResourceVisitor visitor = new ResourceVisitor();
        input.visit(visitor, output);
        if (visitor.getAmountBefore() == 0) {
            throw new AssertionError("Resources not found");
        }
        if (visitor.getAmountBefore() != input.getFiles().size()) {
            throw new AssertionError("Number of visited resources. Expected: " +
                    visitor.getAmountBefore() + ", got: " + input.getFiles().size());
        }
        if (visitor.getAmountAfter() != output.getFiles().size()) {
            throw new AssertionError("Number of added resources. Expected: " +
                    visitor.getAmountAfter() + ", got: " + output.getFiles().size());
        }
        for (ImageFile outFile : output.getFiles()) {
            String path = outFile.getPath().replaceAll(SUFFIX + "$", "");
            ImageFile inFile = input.getFile(path);
            if (inFile == null) {
                throw new AssertionError("Unknown resource: " + path);
            }
        }
    }

    private static class ResourceVisitor implements Visitor {

        private int amountBefore;
        private int amountAfter;

        @Override
        public ImageFile visit(ImageFile file) throws Exception {
            int index = ++amountBefore % 3;
            switch (index) {
                case 0:
                    ++amountAfter;
                    return new InMemoryImageFile(file.getModule(), file.getPath() + SUFFIX,
                            file.getName(), file.getType(), file.getName());
                case 1:
                    ++amountAfter;
                    return new InMemoryImageFile(file.getModule(), file.getPath(),
                            file.getName(), file.getType(), file.getName());
            }
            return null;
        }

        public int getAmountAfter() {
            return amountAfter;
        }

        public int getAmountBefore() {
            return amountBefore;
        }
    }

    private void checkNegative() throws Exception {
        ImageFilePoolImpl input = new ImageFilePoolImpl();
        try {
            input.addFile(null);
            throw new AssertionError("NullPointerException is not thrown");
        } catch (NullPointerException e) {
            // expected
        }
        try {
            input.contains(null);
            throw new AssertionError("NullPointerException is not thrown");
        } catch (NullPointerException e) {
            // expected
        }
        if (input.getFile("unknown") != null) {
            throw new AssertionError("ImageFilePool does not return null for unknown file");
        }
        if (input.contains(new InMemoryImageFile("", "unknown", "", ImageFileType.CONFIG, "unknown"))) {
            throw new AssertionError("'contain' returns true for unknown file");
        }
        input.addFile(new InMemoryImageFile("", "/aaa/bbb", "bbb", ImageFileType.CONFIG, ""));
        try {
            input.addFile(new InMemoryImageFile("", "/aaa/bbb", "bbb", ImageFileType.CONFIG, ""));
            throw new AssertionError("Exception expected");
        } catch (Exception e) {
            // expected
        }
        input.setReadOnly();
        try {
            input.addFile(new InMemoryImageFile("", "/aaa/ccc", "ccc", ImageFileType.CONFIG, ""));
            throw new AssertionError("Exception expected");
        } catch (Exception e) {
            // expected
        }
    }

    private static class InMemoryImageFile extends ImageFile {
        private final ByteArrayInputStream bais;
        private final long size;

        public InMemoryImageFile(String module, String path, String name, ImageFileType type, String content) {
            super(module, path, name, type);
            bais = new ByteArrayInputStream(content.getBytes());
            size = bais.available();
        }

        @Override
        public long size() {
            return size;
        }

        @Override
        public InputStream stream() throws IOException {
            return bais;
        }
    }
}
