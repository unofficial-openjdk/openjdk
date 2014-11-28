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
import java.io.FilePermission;
import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.net.MalformedURLException;
import java.net.URLStreamHandler;
import java.net.URLStreamHandlerFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.AccessController;
import java.security.PrivilegedAction;
import java.security.PrivilegedExceptionAction;
import java.security.AccessControlContext;
import java.security.PermissionCollection;
import java.security.Permissions;
import java.security.Permission;
import java.security.ProtectionDomain;
import java.security.CodeSource;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import sun.net.www.ParseUtil;
import sun.security.util.SecurityConstants;

/**
 * This class is used by the system to launch the main application.
 */
public class Launcher {

    // ensure URLClassPath for boot loader is initialized first
    static {
        URLClassPath ucp = BootClassPathHolder.bcp;
    }

    private static URLStreamHandlerFactory factory = new Factory();
    private static Launcher launcher = new Launcher();

    public static Launcher getLauncher() {
        return launcher;
    }

    private final ExtClassLoader extcl;
    private final AppClassLoader loader;

    public Launcher() {
        // Create the extension class loader
        try {
            extcl = ExtClassLoader.getExtClassLoader();
        } catch (IOException e) {
            throw new InternalError(
                "Could not create extension class loader", e);
        }

        // Now create the class loader to use to launch the application
        try {
            loader = AppClassLoader.getAppClassLoader(extcl);
        } catch (IOException e) {
            throw new InternalError(
                "Could not create application class loader", e);
        }

        // Also set the context class loader for the primordial thread.
        Thread.currentThread().setContextClassLoader(loader);

        // Finally, install a security manager if requested
        String s = System.getProperty("java.security.manager");
        if (s != null) {
            SecurityManager sm = null;
            if ("".equals(s) || "default".equals(s)) {
                sm = new java.lang.SecurityManager();
            } else {
                try {
                    sm = (SecurityManager)loader.loadClass(s).newInstance();
                } catch (IllegalAccessException e) {
                } catch (InstantiationException e) {
                } catch (ClassNotFoundException e) {
                } catch (ClassCastException e) {
                }
            }
            if (sm != null) {
                System.setSecurityManager(sm);
            } else {
                throw new InternalError(
                    "Could not create SecurityManager: " + s);
            }
        }
    }

    /*
     * Returns the class loader used to launch the main application.
     */
    public ClassLoader getClassLoader() {
        return loader;
    }

    /**
     * Returns the extensions class loader.
     */
    public ClassLoader getExtClassLoader() {
        return extcl;
    }

    /*
     * The class loader used for loading installed extensions.
     */
    static class ExtClassLoader extends URLClassLoader implements ModuleLoader {
        static {
            ClassLoader.registerAsParallelCapable();
        }
        static final List<String> modules = Launcher.readModulesList("ext.modules");
        static final Path extmodules =
            Paths.get(System.getProperty("java.home"), "lib", "modules", "extmodules.jimage");

        /**
         * create an ExtClassLoader. The ExtClassLoader is created
         * within a context that limits which files it can read
         */
        static ExtClassLoader getExtClassLoader() throws IOException {
            try {
                // Prior implementations of this doPrivileged() block supplied
                // aa synthesized ACC via a call to the private method
                // ExtClassLoader.getContext().

                return AccessController.doPrivileged(
                    new PrivilegedExceptionAction<ExtClassLoader>() {
                        public ExtClassLoader run() throws IOException {
                            List<String> locations;
                            if (Files.exists(extmodules)) {
                                locations = new ArrayList<>();
                                locations.add(extmodules.toString());
                            } else {
                                locations = toModuleLocations(modules);
                            }
                            return new ExtClassLoader(locations);
                        }
                    });
            } catch (java.security.PrivilegedActionException e) {
                throw (IOException) e.getException();
            }
        }

        private final URLClassPath ucp = SharedSecrets.getJavaNetAccess().getURLClassPath(this);

        @Override
        public List<String> installedModules() {
            return modules;
        }

        @Override
        public void addURL(Set<String> packages, URL url) {
            ucp.addURL(packages, url);
        }

        @Override
        public void prependURL(URL url) {
            ucp.prependURL(url);
        }

        void addExtURL(URL url) {
            super.addURL(url);
        }

        /*
         * Creates a new ExtClassLoader for the specified module locations and
         * ext  directories.
         */
        public ExtClassLoader(List<String> modLocations) throws IOException {
            super(getExtURLs(modLocations), null, factory);
        }

        private static URL[] getExtURLs(List<String> modLocations) throws IOException {
            int size = modLocations.size();
            URL[] urls = new URL[size];
            for (int i=0; i<size; i++) {
                urls[i] = getFileURL(new File(modLocations.get(i)));
            }
            return urls;
        }

        private static AccessControlContext getContext(File[] dirs)
            throws IOException
        {
            PathPermissions perms =
                new PathPermissions(dirs);

            ProtectionDomain domain = new ProtectionDomain(
                new CodeSource(perms.getCodeBase(),
                    (java.security.cert.Certificate[]) null),
                perms);

            AccessControlContext acc =
                new AccessControlContext(new ProtectionDomain[] { domain });

            return acc;
        }
    }

    /**
     * The class loader used for loading from java.class.path.
     * runs in a restricted security context.
     */
    static class AppClassLoader extends URLClassLoader implements ModuleLoader {
        static {
            ClassLoader.registerAsParallelCapable();
        }

        // experimental property
        static final boolean SKIP_DELEGATION =
            Boolean.getBoolean("skipDelegationToExtLoaderWhenPossible");

        static final List<String> systemModules = Launcher.readModulesList("system.modules");
        static final Path appmodules =
            Paths.get(System.getProperty("java.home"), "lib", "modules", "appmodules.jimage");

        public static AppClassLoader getAppClassLoader(final ClassLoader extcl)
            throws IOException
        {
            String cp = System.getProperty("java.class.path", ".");
            if (Files.exists(appmodules)) {
                cp = appmodules + File.pathSeparator + cp;
            } else {
                String mp = Launcher.toClassPath(systemModules);
                if (mp.length() > 0)
                    cp = mp + File.pathSeparator + cp;
            }
            final File[] path = getClassPath(cp, true);

            // Note: on bugid 4256530
            // Prior implementations of this doPrivileged() block supplied
            // a rather restrictive ACC via a call to the private method
            // AppClassLoader.getContext(). This proved overly restrictive
            // when loading  classes. Specifically it prevent
            // accessClassInPackage.sun.* grants from being honored.
            //
            return AccessController.doPrivileged(
                new PrivilegedAction<AppClassLoader>() {
                    @Override
                    public AppClassLoader run() {
                        URL[] urls = pathToURLs(path);
                        return new AppClassLoader(urls, extcl);
                    }
                });
        }

        private final URLClassPath ucp = SharedSecrets.getJavaNetAccess().getURLClassPath(this);

        /*
         * Creates a new AppClassLoader
         */
        AppClassLoader(URL[] urls, ClassLoader parent) {
            super(urls, parent, factory);
        }

        @Override
        public List<String> installedModules() {
            return systemModules;
        }

        @Override
        public void addURL(Set<String> packages, URL url) {
            ucp.addURL(packages, url);
        }

        @Override
        public void prependURL(URL url) {
            ucp.prependURL(url);
        }

        /**
         * Override loadClass so we can checkPackageAccess.
         */
        public Class<?> loadClass(String name, boolean resolve)
            throws ClassNotFoundException
        {
            int i = name.lastIndexOf('.');
            if (i != -1) {
                SecurityManager sm = System.getSecurityManager();
                if (sm != null) {
                    sm.checkPackageAccess(name.substring(0, i));
                }
            }

            boolean skipParent = SKIP_DELEGATION;
            if (skipParent) {
                boolean known;
                i = name.lastIndexOf('.');
                if (i > 0) {
                    String pkg = name.substring(0, i);
                    known = ucp.isKnownPackage(pkg);
                } else {
                    known = false;
                }
                if (!known)
                    skipParent = false;
            }


            if (!skipParent)
                return (super.loadClass(name, resolve));

            synchronized (getClassLoadingLock(name)) {
                // First, check if the class has already been loaded
                Class<?> c = findLoadedClass(name);
                if (c == null) {
                    c = findClass(name);
                }
                if (resolve) {
                    resolveClass(c);
                }
                return c;
            }
        }

        /**
         * allow any classes loaded from classpath to exit the VM.
         */
        protected PermissionCollection getPermissions(CodeSource codesource) {
            PermissionCollection perms = super.getPermissions(codesource);
            perms.add(new RuntimePermission("exitVM"));
            return perms;
        }

        /**
         * This class loader supports dynamic additions to the class path
         * at runtime.
         *
         * @see java.lang.instrument.Instrumentation#appendToSystemClassLoaderSearch
         */
        private void appendToClassPathForInstrumentation(String path) {
            assert(Thread.holdsLock(this));

            // addURL is a no-op if path already contains the URL
            super.addURL( getFileURL(new File(path)) );
        }

        /**
         * create a context that can read any directories (recursively)
         * mentioned in the class path. In the case of a jar, it has to
         * be the directory containing the jar, not just the jar, as jar
         * files might refer to other jar files.
         */

        private static AccessControlContext getContext(File[] cp)
            throws java.net.MalformedURLException
        {
            PathPermissions perms =
                new PathPermissions(cp);

            ProtectionDomain domain =
                new ProtectionDomain(new CodeSource(perms.getCodeBase(),
                    (java.security.cert.Certificate[]) null),
                perms);

            AccessControlContext acc =
                new AccessControlContext(new ProtectionDomain[] { domain });

            return acc;
        }
    }

    private static class BootClassPathHolder {
        static final URLClassPath bcp;
        static {
            URL[] urls = AccessController.doPrivileged(
                    new PrivilegedAction<URL[]>() {
                        public URL[] run() {
                            String bootClassPath = System.getProperty("sun.boot.class.path");
                            if (bootClassPath == null)
                                return new URL[0];
                            // Skip empty path in boot class path i.e. not default to use CWD
                            File[] classPath = getClassPath(bootClassPath, false);
                            int len = classPath.length;
                            Set<File> seenDirs = new HashSet<File>();
                            for (int i = 0; i < len; i++) {
                                File curEntry = classPath[i];
                                // Negative test used to properly handle
                                // nonexistent jars on boot class path
                                if (!curEntry.isDirectory()) {
                                    curEntry = curEntry.getParentFile();
                                }
                                if (curEntry != null && seenDirs.add(curEntry)) {
                                    MetaIndex.registerDirectory(curEntry);
                                }
                            }
                            return pathToURLs(classPath);
                        }
                    }
            );
            bcp = new URLClassPath(urls, factory);
        }
    }

    public static URLClassPath getBootstrapClassPath() {
        return BootClassPathHolder.bcp;
    }

    private static URL[] pathToURLs(File[] path) {
        URL[] urls = new URL[path.length];
        for (int i = 0; i < path.length; i++) {
            urls[i] = getFileURL(path[i]);
        }
        // DEBUG
        //for (int i = 0; i < urls.length; i++) {
        //  System.out.println("urls[" + i + "] = " + '"' + urls[i] + '"');
        //}
        return urls;
    }

    private static File[] getClassPath(String cp, boolean defaultToCwd) {
        File[] path;
        if (cp != null) {
            int count = 0, maxCount = 1;
            int pos = 0, lastPos = 0;
            // Count the number of separators first
            while ((pos = cp.indexOf(File.pathSeparator, lastPos)) != -1) {
                maxCount++;
                lastPos = pos + 1;
            }
            path = new File[maxCount];
            lastPos = pos = 0;
            // Now scan for each path component
            while ((pos = cp.indexOf(File.pathSeparator, lastPos)) != -1) {
                if (pos > lastPos) {
                    path[count++] = new File(cp.substring(lastPos, pos));
                } else if (defaultToCwd) {
                    // empty path component translates to "."
                    path[count++] = new File(".");
                }
                lastPos = pos + 1;
            }
            // Make sure we include the last path component
            if (lastPos < cp.length()) {
                path[count++] = new File(cp.substring(lastPos));
            } else if (defaultToCwd) {
                path[count++] = new File(".");
            }
            // Trim array to correct size
            if (count != maxCount) {
                File[] tmp = new File[count];
                System.arraycopy(path, 0, tmp, 0, count);
                path = tmp;
            }
        } else {
            path = new File[0];
        }
        // DEBUG
        //for (int i = 0; i < path.length; i++) {
        //  System.out.println("path[" + i + "] = " + '"' + path[i] + '"');
        //}
        return path;
    }

    private static URLStreamHandler fileHandler;

    static URL getFileURL(File file) {
        try {
            file = file.getCanonicalFile();
        } catch (IOException e) {}

        try {
            return ParseUtil.fileToEncodedURL(file);
        } catch (MalformedURLException e) {
            // Should never happen since we specify the protocol...
            throw new InternalError(e);
        }
    }

    /*
     * The stream handler factory for loading system protocol handlers.
     */
    private static class Factory implements URLStreamHandlerFactory {
        private static String PREFIX = "sun.net.www.protocol";

        public URLStreamHandler createURLStreamHandler(String protocol) {
            String name = PREFIX + "." + protocol + ".Handler";
            try {
                Class<?> c = Class.forName(name);
                return (URLStreamHandler)c.newInstance();
            } catch (ReflectiveOperationException e) {
                throw new InternalError("could not load " + protocol +
                                        "system protocol handler", e);
            }
        }
    }

    /**
     * Reads the contents of the given modules file in {@code ${java.home}/lib}.
     */
    private static List<String> readModulesList(String name) {
        Path file = Paths.get(System.getProperty("java.home"), "lib", name);
        try {
            return Files.readAllLines(file);
        } catch (IOException ioe) {
            throw new java.io.UncheckedIOException(ioe);
        }
    }

    /**
     * Expand the given list of modules to a class path.
     */
    private static String toClassPath(List<String> modules) {
        String home = System.getProperty("java.home");
        Path dir = Paths.get(home, "lib", "modules");
        String suffix = null;
        if (Files.exists(dir)) {
            suffix = "classes";
        } else {
            dir = Paths.get(home, "modules");
            if (Files.notExists(dir))
                return "";
        }
        StringBuilder sb = new StringBuilder();
        for (String module : modules) {
            if (sb.length() > 0)
                sb.append(File.pathSeparator);
            sb.append(dir.toString());
            sb.append(File.separator);
            sb.append(module);
            if (suffix != null) {
                sb.append(File.separator);
                sb.append(suffix);
            }
        }
        return sb.toString();
    }

    /**
     * Expand the given list of modules to a list of module locations.
     */
    private static List<String> toModuleLocations(List<String> modules) {
        List<String> result = new ArrayList<>();
        String home = System.getProperty("java.home");
        Path dir = Paths.get(home, "lib", "modules");
        boolean image;
        if (Files.exists(dir)) {
            image = true;
        } else {
            dir = Paths.get(home, "modules");
            if (Files.notExists(dir))
                return result;
            image = false;
        }
        for (String m: modules) {
            if (image) {
                StringBuilder sb = new StringBuilder();
                sb.append(dir);
                sb.append(File.separator);
                sb.append(m);
                sb.append(File.separator);
                sb.append("classes");
                String s = sb.toString();
                result.add(s);
            } else {
                result.add(dir.resolve(m).toString());
            }
        }
        return result;
    }
}

class PathPermissions extends PermissionCollection {
    // use serialVersionUID from JDK 1.2.2 for interoperability
    private static final long serialVersionUID = 8133287259134945693L;

    private File path[];
    private Permissions perms;

    URL codeBase;

    PathPermissions(File path[])
    {
        this.path = path;
        this.perms = null;
        this.codeBase = null;
    }

    URL getCodeBase()
    {
        return codeBase;
    }

    public void add(java.security.Permission permission) {
        throw new SecurityException("attempt to add a permission");
    }

    private synchronized void init()
    {
        if (perms != null)
            return;

        perms = new Permissions();

        // this is needed to be able to create the classloader itself!
        perms.add(SecurityConstants.CREATE_CLASSLOADER_PERMISSION);

        // add permission to read any "java.*" property
        perms.add(new java.util.PropertyPermission("java.*",
            SecurityConstants.PROPERTY_READ_ACTION));

        AccessController.doPrivileged(new PrivilegedAction<Void>() {
            public Void run() {
                for (int i=0; i < path.length; i++) {
                    File f = path[i];
                    String path;
                    try {
                        path = f.getCanonicalPath();
                    } catch (IOException ioe) {
                        path = f.getAbsolutePath();
                    }
                    if (i == 0) {
                        codeBase = Launcher.getFileURL(new File(path));
                    }
                    if (f.isDirectory()) {
                        if (path.endsWith(File.separator)) {
                            perms.add(new FilePermission(path+"-",
                                SecurityConstants.FILE_READ_ACTION));
                        } else {
                            perms.add(new FilePermission(
                                path + File.separator+"-",
                                SecurityConstants.FILE_READ_ACTION));
                        }
                    } else {
                        int endIndex = path.lastIndexOf(File.separatorChar);
                        if (endIndex != -1) {
                            path = path.substring(0, endIndex+1) + "-";
                            perms.add(new FilePermission(path,
                                SecurityConstants.FILE_READ_ACTION));
                        } else {
                            // XXX?
                        }
                    }
                }
                return null;
            }
        });
    }

    public boolean implies(java.security.Permission permission) {
        if (perms == null)
            init();
        return perms.implies(permission);
    }

    public java.util.Enumeration<Permission> elements() {
        if (perms == null)
            init();
        synchronized (perms) {
            return perms.elements();
        }
    }

    public String toString() {
        if (perms == null)
            init();
        return perms.toString();
    }
}
