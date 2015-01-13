/*
 * Copyright (c) 1998, 2014, Oracle and/or its affiliates. All rights reserved.
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
import java.security.CodeSource;
import java.security.PermissionCollection;

import jdk.internal.jimage.ImageReader;
import jdk.internal.jimage.ImageReaderFactory;
import sun.net.www.protocol.jrt.JavaRuntimeURLConnection;

/**
 * This class is used to create the extension and application class loaders
 * and do other setup in preparation for launching the main application.
 */
public class Launcher {

    // the names of the jimage files in the runtime image that are accessed
    // by the extension and application class loaders
    private static final String EXT_MODULES = "extmodules.jimage";
    private static final String APP_MODULES = "appmodules.jimage";

    // the system-wide Launcher object
    private static final Launcher LAUNCHER = new Launcher();

    public static Launcher getLauncher() { return LAUNCHER; }

    // extension class loader
    private final ClassLoader extClassLoader;

    // application class loader
    private final ClassLoader appClassLoader;

    private Launcher() {
        String home = System.getProperty("java.home");
        Path libModules = Paths.get(home, "lib", "modules");

        ImageReader extReader = null;
        ImageReader appReader = null;
        Path modulesDir = null;

        // open image files if images build, otherwise detect an exploded image
        if (Files.isDirectory(libModules)) {
            extReader = openImageIfExists(libModules.resolve(EXT_MODULES));
            appReader = openImageIfExists(libModules.resolve(APP_MODULES));
        } else {
            Path base = Paths.get(home, "modules", "java.base");
            if (Files.isDirectory(base)) {
                modulesDir = base.getParent();
            } else {
                throw new InternalError("Unable to determine runtime image type");
            }
        }

        // we have a class path if -cp is specified or -m is not specified
        URLClassPath ucp = null;
        String mainMid = System.getProperty("java.module.main");
        String defaultClassPath = (mainMid == null) ? "." : null;
        String cp = System.getProperty("java.class.path", defaultClassPath);
        if (cp != null && cp.length() > 0)
            ucp = toURLClassPath(cp);

        // is -Xoverride specified?
        String s = System.getProperty("jdk.runtime.override");
        Path overrideDir = (s != null) ? Paths.get(s) : null;

        // create the class loaders
        ExtClassLoader extCL =
            new ExtClassLoader(extReader, modulesDir, overrideDir);
        AppClassLoader appCL =
            new AppClassLoader(extCL, appReader, modulesDir, overrideDir, ucp);

        // register the class loaders with the jrt protocol handler so that
        // resources can be located.
        if (extReader != null)
            JavaRuntimeURLConnection.register(extCL);
        if (appReader != null)
            JavaRuntimeURLConnection.register(appCL);

        // Set the context class loader for the primordial thread.
        Thread.currentThread().setContextClassLoader(appCL);

        // Finally, set the security manager if requested
        s = System.getProperty("java.security.manager");
        if (s != null) {
            SecurityManager sm = null;
            if ("".equals(s) || "default".equals(s)) {
                sm = new SecurityManager();
            } else {
                try {
                    sm = (SecurityManager) appCL.loadClass(s).newInstance();
                } catch (Exception e) {
                    throw new InternalError("Could not create SecurityManager", e);
                }
            }
            System.setSecurityManager(sm);
        }

        this.extClassLoader = extCL;
        this.appClassLoader = appCL;
    }

    /**
     * Returns the extension class loader.
     */
    public ClassLoader getExtClassLoader() { return extClassLoader; }

    /**
     * Returns the application class loader.
     */
    public ClassLoader getAppClassLoader() { return appClassLoader; }

    /**
     * The extension class loader, a unique type to make it easier to distinguish
     * from the application class loader.
     */
    private static class ExtClassLoader extends BuiltinClassLoader {
        ExtClassLoader(ImageReader imageReader, Path modulesDir, Path overrideDir) {
            super(BootLoader.loader(), imageReader, modulesDir, overrideDir, null);
        }
    }

    /**
     * The application class loader, a unique type to make it easier to distinguish
     * from the extension class loader and also to grant exitVM permission to
     * code on the class path
     */
    private static class AppClassLoader extends BuiltinClassLoader {
        AppClassLoader(ExtClassLoader parent,
                       ImageReader imageReader,
                       Path modulesDir,
                       Path overrideDir,
                       URLClassPath ucp)
        {
            super(parent, imageReader, modulesDir, overrideDir, ucp);
        }

        @Override
        protected PermissionCollection getPermissions(CodeSource cs) {
            PermissionCollection perms = super.getPermissions(cs);
            perms.add(new RuntimePermission("exitVM"));
            return perms;
        }
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

