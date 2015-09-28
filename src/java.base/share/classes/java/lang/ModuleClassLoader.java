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

package java.lang;

import java.io.File;
import java.io.FilePermission;
import java.io.IOException;
import java.io.InputStream;
import java.lang.module.Configuration;
import java.lang.module.ModuleReference;
import java.lang.module.ModuleReader;
import java.net.URL;
import java.nio.ByteBuffer;
import java.security.AccessControlContext;
import java.security.AccessController;
import java.security.CodeSigner;
import java.security.CodeSource;
import java.security.Permission;
import java.security.PermissionCollection;
import java.security.PrivilegedAction;
import java.security.PrivilegedActionException;
import java.security.PrivilegedExceptionAction;
import java.security.SecureClassLoader;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import jdk.internal.misc.BootLoader;


/**
 * A {@code ClassLoader} that loads classes and resources from a set of modules
 * in a configuration.
 *
 * <p> The delegation model used by this {@code ModuleClassLoader} differs to
 * the regular delegation model. When requested to load a class then this
 * {@code ModuleClassLoader} first checks the modules defined to this class
 * loader. If not found then it delegates the search to the parent class
 * loader.</p>
 *
 * @apiNote This is an experimental API at this time. It is intended for
 * simple plugin-like scenarios where all modules in the plugin are in a layer
 * that has the boot Layer as its parent. It does not handle interference.
 * An alternative to this class loader is a set of factory methods that create
 * ClassLoaderFinder implementations that map modules to loader in
 * topologies needed to support the modules in the configuration.
 *
 * @since 1.9
 */

public final class ModuleClassLoader
    extends SecureClassLoader
{

    static {
        ClassLoader.registerAsParallelCapable();
    }

    // parent ClassLoader
    private final ClassLoader parent;

    // maps a module name to a module reference
    private final Map<String, ModuleReference> nameToModule;

    // maps package name to a module loaded by this class loader
    private final Map<String, ModuleReference> packageToModule;

    // maps a module reference to a module reader, populated lazily
    private final Map<ModuleReference, ModuleReader> moduleToReader
        = new ConcurrentHashMap<>();

    /* The context to be used when loading classes and resources */
    private final AccessControlContext acc;


    private ModuleClassLoader(Void unused,
                              ClassLoader parent,
                              Set<ModuleReference> modules)
    {
        super(parent);

        Map<String, ModuleReference> nameToModule = new HashMap<>();
        Map<String, ModuleReference> packageToModule = new HashMap<>();

        for (ModuleReference mref : modules) {
            nameToModule.put(mref.descriptor().name(), mref);
            for (String pn: mref.descriptor().packages()) {
                ModuleReference other = packageToModule.putIfAbsent(pn, mref);
                if (other != null) {
                    String mn1 = mref.descriptor().name();
                    String mn2 = other.descriptor().name();
                    throw new IllegalArgumentException("Modules "
                        + mn1 + " and " + mn2 + " both contain package " + pn);
                }
            }
        }

        this.parent = parent;
        this.packageToModule = packageToModule;
        this.nameToModule = nameToModule;
        this.acc = AccessController.getContext();
    }


    /**
     * Create a new {@code ModuleClassLoader} that loads classes and resources
     * from the modules in the given {@code Configuration}. The class loader's
     * parent is the system class loader.
     *
     * <p> If there is a security manager then its {@code checkCreateClassLoader}
     * is invoked to ensure that creation of a class loader is allowed. </p>
     *
     * @throws IllegalArgumentException
     *         If two or more modules in the configuration have the same package
     *         (exported or concealed)
     * @throws SecurityException
     *         If denied by the security manager
     */
    public ModuleClassLoader(Configuration cf) {
        this(ClassLoader.getSystemClassLoader(), cf);
    }

    /**
     * Create a new {@code ModuleClassLoader} that loads classes and resources
     * from the modules in the given {@code Configuration}. The class loader's
     * parent is the given class loader.
     *
     * <p> If there is a security manager then its {@code checkCreateClassLoader}
     * is invoked to ensure that creation of a class loader is allowed. </p>
     *
     * @throws IllegalArgumentException
     *         If two or more modules in the configuration have the same package
     *         (exported or concealed)
     * @throws SecurityException
     *         If denied by the security manager
     */
    public ModuleClassLoader(ClassLoader parent, Configuration cf) {
        this(checkCreateClassLoader(), parent, cf.modules());
    }


    private static Void checkCreateClassLoader() {
        SecurityManager security = System.getSecurityManager();
        if (security != null) {
            security.checkCreateClassLoader();
        }
        return null;
    }


    // -- finding resources

    /**
     * Returns an input stream to a resource in a module defined to this class
     * loader.
     *
     * @return An input stream to the resource; {@code null} if the resource
     * could not be found or there isn't a module of the given name defined to
     * this class loader.
     *
     * @throws IOException if I/O error occurs
     *
     * @see java.lang.reflect.Module#getResourceAsStream(String)
     */
    @Override
    public InputStream getResourceAsStream(String moduleName, String name)
        throws IOException
    {
        ModuleReference mref = nameToModule.get(moduleName);
        if (mref != null) {
            try {
                return AccessController.doPrivileged(
                    new PrivilegedExceptionAction<InputStream>() {
                        @Override
                        public InputStream run() throws IOException {
                            return moduleReaderFor(mref).open(name).orElse(null);
                        }
                    }, acc);
            } catch (PrivilegedActionException pae) {
                throw (IOException) pae.getCause();
            }
        }
        // module not defined to this class loader or not found
        return null;
    }

    /**
     * Returns {@code null}.
     * Resources in a named module are private to that module. This method does
     * not find resources in named modules defined to this class loader.
     *
     * @param name The resource name
     * @return {@code null}
     */
    @Override
    public URL findResource(String name) {
        return null;
    }

    /**
     * Returns an empty enumeration of {@code URL} objects.
     * Resources in a named module are private to that module. This method does
     * not find resources in named modules defined to this class loader.
     *
     * @param name The resource name
     * @return An empty enumeration of {@code URL} objects.
     * @throws IOException If I/O errors occur
     */
    @Override
    public Enumeration<URL> findResources(String name) throws IOException {
        return Collections.emptyEnumeration();
    }

    // -- finding/loading classes

    private Class<?> findClassOrNull(String cn) {
        // find the candidate module for this class
        ModuleReference mref = findModule(cn);
        if (mref != null) {
            return findClassInModuleOrNull(mref, cn);
        }
        return null;
    }

    @Override
    protected Class<?> findClass(String cn)throws ClassNotFoundException {
        Class<?> c = findClassOrNull(cn);

        // not found
        if (c == null)
            throw new ClassNotFoundException(cn);

        return c;
    }

    /**
     * Loads the class with the specified <a href="ClassLoader.html#name">binary
     * name</a> from a module defined to this class loader.
     *
     * <p> This {@code ModuleClassLoader} first checks the modules defined to
     * this class loader. If not found then it delegates the search to the
     * parent class loader.
     *
     * @inheritDoc
     */
    @Override
    protected Class<?> loadClass(String name, boolean resolve)
        throws ClassNotFoundException
    {
        SecurityManager sm = System.getSecurityManager();
        if (sm != null) {
            int i = name.lastIndexOf('.');
            if (i != -1) {
                sm.checkPackageAccess(name.substring(0, i));
            }
        }

        Class<?> c = loadClassOrNull(name, resolve);
        if (c == null)
            throw new ClassNotFoundException(name);

        return c;
    }


    /**
     * Loads the class with the specified <a href="ClassLoader.html#name">binary
     * name</a> from a module defined to this class loader.
     *
     * <p> This {@code ModuleClassLoader} first checks the modules defined to
     * this class loader. If not found then it delegates the search to the
     * parent class loader.  If not found, it returns {@code null}.
     */
    private Class<?> loadClassOrNull(String name, boolean resolve) {
        synchronized (getClassLoadingLock(name)) {
            // check if already loaded
            Class<?> c = findLoadedClass(name);

            if (c == null) {

                // find the candidate module for this class
                ModuleReference mref = findModule(name);
                if (mref != null) {
                    c = findClassInModuleOrNull(mref, name);
                } else {
                    // check parent
                    try {
                        if (parent != null) {
                            c = parent.loadClass(name, resolve);
                        } else {
                            c = BootLoader.loadClassOrNull(name);
                        }
                    } catch (ClassNotFoundException e) {
                        c = null;
                    }
                }
            }

            if (resolve && c != null)
                resolveClass(c);

            return c;
        }
    }

    /**
     * Find the candidate loaded module for the given class name.
     * Returns {@code null} if none of the modules defined to this
     * class loader contain the API package for the class.
     */
    private ModuleReference findModule(String cn) {
        int pos = cn.lastIndexOf('.');
        if (pos < 0)
            return null; // unnamed package

        String pn = cn.substring(0, pos);
        return packageToModule.get(pn);
    }

    /**
     * Finds the class with the specified binary name if in a module
     * defined to this ClassLoader.
     *
     * @return the resulting Class or {@code null} if not found
     */
    private Class<?> findClassInModuleOrNull(ModuleReference mref, String cn) {
        PrivilegedAction<Class<?>> pa = () -> defineClass(cn, mref);
        return AccessController.doPrivileged(pa, acc);
    }

    /**
     * Defines the given binary class name to the VM, loading the class
     * bytes from the given module.
     *
     * @return the resulting Class or {@code null} if an I/O error occurs
     */
    private Class<?> defineClass(String cn, ModuleReference mref) {
        ModuleReader reader = moduleReaderFor(mref);

        try {
            // read class file
            String rn = cn.replace('.', '/').concat(".class");
            ByteBuffer bb = reader.read(rn).orElse(null);
            if (bb == null) {
                // class not found
                return null;
            }

            try {

                // define a package in the named module
                int pos = cn.lastIndexOf('.');
                String pn = cn.substring(0, pos);
                if (getDefinedPackage(pn) == null) {
                    definePackage(pn, mref);
                }

                // define class to VM
                URL url = mref.location().get().toURL();
                CodeSource cs = new CodeSource(url, (CodeSigner[]) null);
                return defineClass(cn, bb, cs);

            } finally {
                reader.release(bb);
            }

        } catch (IOException ioe) {
            // TBD on how I/O errors should be propagated
            return null;
        }
    }

    // -- packages

    /**
     * Defines a package of the give name.  mref can be {@code null}
     * if it's defined in the unnamed module.
     */
    private Package definePackage(String name, ModuleReference mref) {
        Package pkg = getDefinedPackage(name);
        try {
            URL url = mref != null ? mref.location().get().toURL() : null;
            if (pkg == null) {
                pkg = definePackage(name, null, null, null, null, null, null, url);
            }
            if (!pkg.isSealed(url)) {
                throw new IllegalArgumentException(name +
                    " already defined and not sealed with " +  mref.location());
            }
        } catch (IOException e) {
            // TBD on how I/O errors should be propagated
            return null;
        }
        return pkg;
    }

    // -- permissions

    @Override
    protected PermissionCollection getPermissions(CodeSource cs) {
        PermissionCollection perms = super.getPermissions(cs);

        // add the permission to access the resource
        URL url = cs.getLocation();
        if (url == null)
            return perms;
        Permission p = null;
        try {
            p = url.openConnection().getPermission();
            if (p != null) {
                // for directories then need recursive access
                if (p instanceof FilePermission) {
                    String path = p.getName();
                    if (path.endsWith(File.separator)) {
                        path += "-";
                        p = new FilePermission(path, "read");
                    }
                }
                perms.add(p);
            }
        } catch (IOException ioe) {
        }

        return perms;
    }

    // -- miscellaneous supporting methods

    /**
     * Returns the ModuleReader for the given module.
     */
    private ModuleReader moduleReaderFor(ModuleReference mref) {
        return moduleToReader.computeIfAbsent(mref, m -> createModuleReader(mref));
    }

    /**
     * Creates a ModuleReader for the given module.
     */
    private ModuleReader createModuleReader(ModuleReference mref) {
        ModuleReader reader;

        try {
            reader = mref.open();
        } catch (IOException e) {
            // Return a null module reader to avoid a future class load
            // attempting to open the module again.
            return new NullModuleReader();
        }
        return reader;
    }

    /**
     * A ModuleReader that doesn't read any resources.
     */
    private static class NullModuleReader implements ModuleReader {
        @Override
        public Optional<InputStream> open(String name) {
            return Optional.empty();
        }

        @Override
        public void close() {
            throw new InternalError("Should not get here");
        }
    }
}
