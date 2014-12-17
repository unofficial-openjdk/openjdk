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

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FilePermission;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.MalformedURLException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.AccessController;
import java.security.Permission;
import java.security.PrivilegedAction;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import jdk.jigsaw.module.ModuleArtifact;
import jdk.internal.jimage.ImageLocation;
import jdk.internal.jimage.ImageReader;

/**
 * Find resources in modules defined to the boot class loader or resources
 * on the "boot class path" specified via -Xbootclasspath/a.
 */
public class BootResourceFinder {

    // the name of the jimage file that backs this finder
    private final String JIMAGE_FILE = "bootmodules.jimage";

    // handle to image reader, null if exploded build
    private final ImageReader JIMAGE;

    // ${java.home/modules if exploded build, otherwise null
    private final Path MODULES_DIR;

    // -Xoverride
    private final String OVERRIDE_DIRECTORY;

    // -Xbootclasspth/a (or -javaagent Boot-Class-Path)
    private final URLClassPath BCP;

    // maps package name to module for the modules defined to the boot loader
    private final Map<String, String> packageToModule = new ConcurrentHashMap<>();

    /**
     * Initializes the only instance of this class.
     */
    private BootResourceFinder() {

        // open the jimage file or determine the modules directory if
        // this is an exploded build
        String home = System.getProperty("java.home");
        Path bootmodules = Paths.get(home, "lib", "modules", JIMAGE_FILE);
        ImageReader jimage = null;
        Path top = null;
        if (Files.isRegularFile(bootmodules)) {
            try {
                jimage = ImageReader.open(bootmodules.toString());
            } catch (IOException ioe) {
                throw new UncheckedIOException(ioe);
            }
        } else {
            Path base = Paths.get(home, "modules", "java.base");
            if (Files.isDirectory(base)) {
                top = base.getParent();
            } else {
                throw new InternalError("Not an images or exploded build");
            }
        }
        JIMAGE = jimage;
        MODULES_DIR = top;

        // override directory
        OVERRIDE_DIRECTORY = System.getProperty("jdk.runtime.override");

        // -Xbootclasspth/a or -javaagent Boot-Class-Path
        String bcp = System.getProperty("sun.boot.class.path");
        if (bcp != null) {
            // we're only interested in the suffix for now.
            int index = bcp.indexOf(JIMAGE_FILE);
            if (index >= 0) {
                index += JIMAGE_FILE.length() + 1;
                if (index >= bcp.length()) {
                    bcp = null;
                } else {
                    bcp = bcp.substring(index);
                }
            }
        }
        BCP = (bcp != null) ? new URLClassPath(toClassPath(bcp)) : null;
    }

    // the singleton BootResourceFinder
    private static final BootResourceFinder INSTANCE;

    static {
        SecurityManager sm = System.getSecurityManager();
        if (sm == null) {
            INSTANCE = new BootResourceFinder();
        } else {
            PrivilegedAction<BootResourceFinder> pa = BootResourceFinder::new;
            INSTANCE = AccessController.doPrivileged(pa);
        }
    }

    /**
     * Returns the singleton BootResourceFinder
     */
    public static BootResourceFinder get() {
        return INSTANCE;
    }

    /**
     * Make visible the resources in the given module artifact.
     */
    public void defineModule(ModuleArtifact artifact) {
        String module = artifact.descriptor().name();
        artifact.packages().forEach(p -> packageToModule.put(p, module));
    }

    /**
     * Returns the URL to the given resource if visible to this boot loader.
     */
    public URL findResource(String name) {
        try {
            URL url = findInModule(name);
            if (url != null)
                return url;
        } catch (SecurityException e) {
            // ignore as the resource may be on BCP
        }

        // -Xbootclasspath/a
        if (BCP != null) {
            Resource r = BCP.getResource(name);
            if (r != null)
                return r.getURL();
        }

        return null;
    }

    /**
     * Returns an Iterator to iterate over the resources of the given name
     * that are visible to the boot loader.
     */
    public Iterator<URL> findResources(String name) {
        List<URL> result = new ArrayList<>();

        try {
            URL url = findInModule(name);
            if (url != null)
                result.add(url);
        } catch (SecurityException e) {
            // ignore as there may be resources on BCP that can be returned
        }

        // -Xbootclasspath/a
        if (BCP != null) {
            Enumeration<Resource> e = BCP.getResources(name);
            while (e.hasMoreElements()) {
                result.add(e.nextElement().getURL());
            }
        }

        return result.iterator();
    }

    /**
     * Returns a Resource for the given module/resource-name if visible to the
     * boot loader.
     *
     * @implNote This method is for use by the jrt protocol handler
     */
    public Resource findResourceAsResource(String module, String name) {
        // jrt is image build only for now
        if (JIMAGE == null)
            return null;

        // check that module is defined to runtime
        if (module == null || !module.equals(getModule(name)))
            return null;

        // get location of resource
        ImageLocation location = JIMAGE.findLocation(name);
        if (location == null)
            return null;

        return new Resource() {
            @Override
            public String getName() { return name; }
            @Override
            public URL getURL() { return toJrtURL(module, name); }
            @Override
            public URL getCodeSourceURL() {
                try {
                    return new URL("jrt:/" + module);
                } catch (MalformedURLException e) {
                    throw new InternalError(e);
                }
            }
            @Override
            public InputStream getInputStream() throws IOException {
                byte[] resource = JIMAGE.getResource(location);
                return new ByteArrayInputStream(resource);
            }
            public int getContentLength() {
                long size = location.getUncompressedSize();
                return (size > Integer.MAX_VALUE) ? -1 : (int)size;
            }
        };
    }

    /**
     * Returns the URL to a resource if the resource is in a module
     * defined to the boot loader.
     *
     * @throws SecurityException if denied by security manager
     */
    private URL findInModule(String name) {
        String module = getModule(name);
        if (module == null)
            return null;

        if (OVERRIDE_DIRECTORY != null) {
            name = name.replace('/', File.separatorChar);
            Path file = Paths.get(OVERRIDE_DIRECTORY, module, name);
            if (Files.exists(file))
                return toURL(file);
        }

        if (JIMAGE != null) {
            ImageLocation location = JIMAGE.findLocation(name);
            if (location != null) {
                checkPermissionToAccessImage();
                return toJrtURL(module, name);
            }
        } else {
            // exploded build
            Path file = MODULES_DIR
                    .resolve(module)
                    .resolve(name.replace('/', File.separatorChar));
            if (Files.exists(file))
                return toURL(file);
        }

        return null;
    }

    /**
     * Returns the name of the module for the given resource.
     */
    private String getModule(String name) {
        int pos = name.lastIndexOf('/');
        if (pos < 0)
            return null;

        String pkg = name.substring(0, pos).replace('/', '.');
        return packageToModule.get(pkg);
    }

    /**
     * Returns an array of URLs to correspond to the elements of a PATH.
     */
    private URL[] toClassPath(String path) {
        String[] elements = path.split(File.pathSeparator);
        int count = elements.length;
        URL[] urls = new URL[count];
        for (int i=0; i<count; i++) {
            urls[i] = toURL(Paths.get(elements[i]));
        }
        return urls;
    }

    /**
     * Returns a file URL for the given file path.
     */
    private URL toURL(Path path) {
        try {
            return path.toUri().toURL();
        } catch (MalformedURLException e) {
            throw new InternalError(e);
        }
    }

    /**
     * Returns a jrt URL for the given module and resource name.
     */
    private URL toJrtURL(String module, String name) {
        try {
            return new URL("jrt:/" + module + "/" + name);
        } catch (MalformedURLException e) {
            throw new InternalError(e);
        }
    }

    /**
     * Checks permission to access the jimage. A no-op if there is no
     * security manager set.
     */
    private static void checkPermissionToAccessImage()  {
        SecurityManager sm = System.getSecurityManager();
        if (sm == null)
            return;

        Permission p = permission;
        if (p == null) {
            PrivilegedAction<String> pa = () -> System.getProperty("java.home");
            String home = AccessController.doPrivileged(pa);
            p = new FilePermission(home + File.separator + "-", "read");
            permission = p;
        }
        sm.checkPermission(p);
    }
    private static Permission permission;
}
