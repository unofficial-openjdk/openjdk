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
package sun.misc;

import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Enumeration;

import jdk.internal.jimage.ImageReader;
import jdk.internal.jimage.ImageReaderFactory;
import jdk.jigsaw.module.ModuleArtifact;

/**
 * Find resources in modules defined to the boot class loader or resources
 * on the "boot class path" specified via -Xbootclasspath/a.
 */
public class BootLoader {

    private static final JavaLangAccess jla = SharedSecrets.getJavaLangAccess();

    // the names of the jimage file with the resources for the boot loader
    private static final String BOOT_MODULES = "bootmodules.jimage";

    // the ClassLoader to find resources
    private final BuiltinClassLoader loader;

    /**
     * Initializes the only instance of this class.
     */
    private BootLoader() {
        String home = System.getProperty("java.home");
        Path libModules = Paths.get(home, "lib", "modules");

        ImageReader imageReader = null;

        // open image files if images build, otherwise detect an exploded image
        if (Files.isDirectory(libModules)) {
            imageReader = openImageIfExists(libModules.resolve(BOOT_MODULES));
        } else {
            Path base = Paths.get(home, "modules", "java.base");
            if (!Files.isDirectory(base)) {
                throw new InternalError("Unable to determine runtime image type");
            }
        }

        // -Xbootclasspth/a or -javaagent Boot-Class-Path
        URLClassPath bcp = null;
        String s = System.getProperty("sun.boot.class.path");
        if (s != null) {
            // HotSpot currently includes $JAVA_HOME/lib/modules/boot.jimages in
            // the value of sun.boot.class.path. The BCP is the path elements
            // that follow it.
            int index = s.indexOf(BOOT_MODULES);
            if (index >= 0) {
                index += BOOT_MODULES.length() + 1;
                if (index >= s.length()) {
                    s = null;
                } else {
                    s = s.substring(index);
                }
            }
            if (s != null && s.length() > 0)
                bcp = toURLClassPath(s);
        }

        // is -Xoverride specified?
        s = System.getProperty("jdk.runtime.override");
        Path overrideDir = (s != null) ? Paths.get(s) : null;

        // create the boot loader
        loader = new BuiltinClassLoader(null, imageReader, overrideDir, bcp) {
            @Override
            public Class<?> loadClass(String cn) {
                return jla.findBootstrapClassOrNull(loader, cn);
            }
        };
    }

    // the singleton BootLoader
    private static final BootLoader INSTANCE = new BootLoader();

    /**
     * Returns the ClassLoader to find resources in modules defined to the boot
     * class loader.
     */
    static BuiltinClassLoader loader() {
        return INSTANCE.loader;
    }

    /**
     * Make visible the resources in the given module artifact.
     */
    public static void defineModule(ModuleArtifact artifact) {
        loader().defineModule(artifact);
    }

    /**
     * Returns the URL to the given resource if visible to this boot loader.
     */
    public static URL findResource(String name) {
        return loader().findResource(name);
    }

    /**
     * Returns an Iterator to iterate over the resources of the given name
     * that are visible to the boot loader.
     */
    public static Enumeration<URL> findResources(String name) throws IOException {
        return loader().findResources(name);
    }

    /**
     * Returns a Resource for the given module/resource-name if visible to the
     * boot loader.
     */
    public static Resource findResource(String module, String name) {
       return loader().findResource(module, name);
    }

    /**
     * Returns an {@code ImageReader} to read from the given image file or
     * {@code null} if the image file does not exist.
     *
     * @throws UncheckedIOException if an I/O error occurs
     */
    private static ImageReader openImageIfExists(Path path) {
        try {
            return ImageReaderFactory.get(path);
        } catch (NoSuchFileException ignore) {
            return null;
        } catch (IOException ioe) {
            throw new UncheckedIOException(ioe);
        }
    }

    /**
     * Returns a {@code URLClassPath} of file URLs to each of the elements in
     * the given class path.
     */
    private static URLClassPath toURLClassPath(String cp) {
        URLClassPath ucp = new URLClassPath(new URL[0]);
        for (String s: cp.split(File.pathSeparator)) {
            try {
                URL url = Paths.get(s).toRealPath().toUri().toURL();
                ucp.addURL(url);
            } catch (InvalidPathException | IOException ignore) {
                // malformed path string or class path element does not exist
            }
        }
        return ucp;
    }
}
