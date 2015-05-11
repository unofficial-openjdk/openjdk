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
package sun.misc;

import java.lang.module.ModuleArtifact;
import java.io.File;
import java.io.IOException;
import java.net.URL;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.CodeSource;
import java.security.PermissionCollection;
import java.util.jar.Manifest;

/**
 * Creates and provides access to the built-in extension and application class
 * loaders. It also creates the class loader that is used to locate resources
 * in modules defined to the boot class loader.
 */

public class ClassLoaders {
    private ClassLoaders() { }

    // the built-in class loaders
    private static final BootClassLoader BOOT_LOADER;
    private static final ExtClassLoader EXT_LOADER;
    private static final AppClassLoader APP_LOADER;

    /**
     * Creates the built-in class loaders
     */
    static {

        // -Xbootclasspth/a or -javaagent Boot-Class-Path
        URLClassPath bcp = null;
        String s = System.getProperty("jdk.boot.class.path.append");
        if (s != null && s.length() > 0)
            bcp = toURLClassPath(s);

        // we have a class path if -cp is specified or -m is not specified
        URLClassPath ucp = null;
        String mainMid = System.getProperty("java.module.main");
        String defaultClassPath = (mainMid == null) ? "." : null;
        String cp = System.getProperty("java.class.path", defaultClassPath);
        if (cp != null && cp.length() > 0)
            ucp = toURLClassPath(cp);

        // is -Xoverride specified?
        s = System.getProperty("jdk.runtime.override");
        Path overrideDir = (s != null) ? Paths.get(s) : null;

        // create the class loaders
        BOOT_LOADER = new BootClassLoader(overrideDir, bcp);
        EXT_LOADER = new ExtClassLoader(BOOT_LOADER, overrideDir);
        APP_LOADER = new AppClassLoader(EXT_LOADER, overrideDir, ucp);
    }

    /**
     * Returns the class loader that is used to find resources in modules
     * defined to the boot class loader.
     *
     * @apiNote This method is not public, it should instead be used
     * via sun.misc.BootLoader that provides a restricted API to this
     * class loader.
     */
    static BuiltinClassLoader bootLoader() {
        return BOOT_LOADER;
    }

    /**
     * Returns the extension class loader.
     */
    public static ClassLoader extClassLoader() {
        return EXT_LOADER;
    }

    /**
     * Returns the application class loader.
     */
    public static ClassLoader appClassLoader() {
        return APP_LOADER;
    }

    /**
     * The class loader that is used to find resources in modules defined to
     * the boot class loader. It is not used for class loading.
     */
    private static class BootClassLoader extends BuiltinClassLoader {
        private static final JavaLangAccess jla = SharedSecrets.getJavaLangAccess();

        BootClassLoader(Path overrideDir, URLClassPath bcp) {
            super(null, overrideDir, bcp);
        }

        @Override
        public Class<?> findClassInModule(String cn) {
            Class<?> c = jla.findBootstrapClassOrNull(this, cn);
            // findBootstrapClassOrNull may load class from -Xbootclasspath/a path.
            // Return null if the class is in unnamed module.
            return c != null && c.getModule().isUnnamed() ? null : c;
        }

        @Override
        protected Class<?> loadClassOrNull(String cn) {
            return jla.findBootstrapClassOrNull(this, cn);
        }
    };

    /**
     * The extension class loader, a unique type to make it easier to distinguish
     * from the application class loader.
     */
    private static class ExtClassLoader extends BuiltinClassLoader {
        ExtClassLoader(BootClassLoader parent, Path overrideDir) {
            super(parent, overrideDir, null);
        }
    }

    /**
     * The application class loader that is a {@code BuiltinClassLoader} with
     * customizations to be compatible with long standing behavior.
     */
    private static class AppClassLoader extends BuiltinClassLoader {
        final URLClassPath ucp;

        AppClassLoader(ExtClassLoader parent, Path overrideDir, URLClassPath ucp) {
            super(parent, overrideDir, ucp);
            this.ucp = ucp;
        }

        @Override
        protected Class<?> loadClass(String cn, boolean resolve)
            throws ClassNotFoundException
        {
            // for compatibility reasons, say where restricted package list has
            // been updated to list API packages in the unnamed module.
            SecurityManager sm = System.getSecurityManager();
            if (sm != null) {
                int i = cn.lastIndexOf('.');
                if (i != -1) {
                    sm.checkPackageAccess(cn.substring(0, i));
                }
            }

            return super.loadClass(cn, resolve);
        }

        @Override
        protected PermissionCollection getPermissions(CodeSource cs) {
            PermissionCollection perms = super.getPermissions(cs);
            perms.add(new RuntimePermission("exitVM"));
            return perms;
        }

        /**
         * Called by the VM to support dynamic additions to the class path
         *
         * @see java.lang.instrument.Instrumentation#appendToSystemClassLoaderSearch
         */
        void appendToClassPathForInstrumentation(String path) {
            appendToUCP(path, ucp);
        }

        /**
         * Called by the VM to support define package.
         */
        protected Package defineOrCheckPackage(String pn, Manifest man, URL url) {
            return super.defineOrCheckPackage(pn, man, url);
        }
    }

    /**
     * Returns a {@code URLClassPath} of file URLs to each of the elements in
     * the given class path.
     */
    private static URLClassPath toURLClassPath(String cp) {
        URLClassPath ucp = new URLClassPath(new URL[0]);
        appendToUCP(cp, ucp);
        return ucp;
    }

    /**
     * Converts the elements in the given class path to file URLs and adds
     * them to the given URLClassPath.
     */
    private static void appendToUCP(String cp, URLClassPath ucp) {
        String[] elements = cp.split(File.pathSeparator);
        if (elements.length == 0) {
            // contains path separator(s) only, default to current directory
            // to be compatible with long standing behavior
            elements = new String[] { "" };
        }
        for (String s: elements) {
            try {
                URL url = Paths.get(s).toRealPath().toUri().toURL();
                ucp.addURL(url);
            } catch (InvalidPathException | IOException ignore) {
                // malformed path string or class path element does not exist
            }
        }
    }
}
