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

package container;

import java.io.File;
import java.io.FilePermission;
import java.io.InputStream;
import java.io.IOException;
import java.lang.module.ModuleReference;
import java.lang.module.ModuleReader;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URI;
import java.nio.ByteBuffer;
import java.security.CodeSigner;
import java.security.CodeSource;
import java.security.Permission;
import java.security.PermissionCollection;
import java.security.SecureClassLoader;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * A simple class loader for loading classes and resources from modules.
 *
 * <p> The delegation model used by this ClassLoader differs to the regular
 * delegation model. When requested to load a class then this ClassLoader first
 * checks the modules defined to the ClassLoader. If not found then it delegates
 * the search to the parent class loader.
 */
class ModuleClassLoader
    extends SecureClassLoader implements ModuleCapableLoader
{

    static {
        ClassLoader.registerAsParallelCapable();
    }

    // the initial module reader for modules defined to this class loader
    private static final ModuleReader NULL_MODULE_READER = new NullModuleReader();

    // parent ClassLoader
    private final ClassLoader parent;

    // maps package name to a loaded module for the modules defined to this class loader
    private final Map<String, ModuleReference> packageToReference = new ConcurrentHashMap<>();

    // maps a module name to a module reference
    private final Map<String, ModuleReference> nameToReference = new ConcurrentHashMap<>();

    // maps a module reference to a module reader
    private final Map<ModuleReference, ModuleReader> mrefToReader = new ConcurrentHashMap<>();

    /**
     * Create a new instance.
     */
    ModuleClassLoader(ClassLoader parent) {
        super(parent);
        this.parent = parent;
    }

    ModuleClassLoader() {
        this(ClassLoader.getSystemClassLoader());
    }

    /**
     * Define the module in the given module reference to this class loader.
     * This has the effect of making the types in the module visible.
     */
    public void register(ModuleReference mref) {
        nameToReference.put(mref.descriptor().name(), mref);
        mref.descriptor().packages().forEach(p -> packageToReference.put(p, mref));

        // Use NULL_MODULE_READER initially to avoid opening eagerly
        mrefToReader.put(mref, NULL_MODULE_READER);
    }

    /**
     * Finds the resource of the given name in a module defined to this class
     * loader.
     */
    @Override
    public InputStream getResourceAsStream(String moduleName, String name)
        throws IOException
    {
        ModuleReference mref = nameToReference.get(moduleName);
        if (mref != null)
            return moduleReaderFor(mref).open(name).orElse(null);
        else
            return null;
    }

    /**
     * Finds the class with the specified binary name.
     */
    @Override
    protected Class<?> findClass(String cn) throws ClassNotFoundException {
        Class<?> c = null;

        // find the candidate module for this class
        ModuleReference mref = findModule(cn);
        if (mref != null) {
            // attempt to find the class in the module
            c = defineClass(cn, mref);
        }

        // not found
        if (c == null)
            throw new ClassNotFoundException(cn);

        return c;
    }

    /**
     * Loads the class with the specified binary name.
     */
    @Override
    protected Class<?> loadClass(String cn, boolean resolve)
        throws ClassNotFoundException
    {
        synchronized (getClassLoadingLock(cn)) {
            // check if already loaded
            Class<?> c = findLoadedClass(cn);

            if (c == null) {

                // find the candidate module for this class
                ModuleReference mref = findModule(cn);
                if (mref != null) {
                    // attempt to find the class in the module
                    c = defineClass(cn, mref);
                } else {
                    // check parent
                    if (parent != null) {
                        c = parent.loadClass(cn);
                    }
                }
            }

            if (c == null)
                throw new ClassNotFoundException(cn);

            if (resolve)
                resolveClass(c);

            return c;
        }
    }

    /**
     * Find the candidate module for the given class name.
     * Returns {@code null} if none of the modules defined to this
     * class loader contain the API package for the class.
     */
    private ModuleReference findModule(String cn) {
        int pos = cn.lastIndexOf('.');
        if (pos < 0)
            return null; // unnamed package

        String pn = cn.substring(0, pos);
        return packageToReference.get(pn);
    }

    /**
     * Defines the given binary class name to the VM, loading the class
     * bytes from the given module reference.
     *
     * @return the resulting Class or {@code null} if an I/O error occurs
     */
    private Class<?> defineClass(String cn, ModuleReference mref) {
        ModuleReader reader = moduleReaderFor(mref);
        try {
            URL url = mref.location().get().toURL();

            // read class file
            String rn = cn.replace('.', '/').concat(".class");
            ByteBuffer bb = reader.read(rn).orElse(null);
            if (bb == null)
                return null;
            try {
                // define a package in the named module
                int pos = cn.lastIndexOf('.');
                String pn = cn.substring(0, pos);
                if (getPackage(pn) == null) {
                    definePackage(pn, null, null, null, null, null, null, url);
                }
                // define class to VM
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

    /**
     * Returns the permissions for the given CodeSource.
     */
    @Override
    protected PermissionCollection getPermissions(CodeSource cs) {
        PermissionCollection perms = super.getPermissions(cs);

        // add the permission to access the resource
        URL url = cs.getLocation();
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
        } catch (IOException ioe) { }

        return perms;
    }

    /**
     * Returns the ModuleReader for the given reference, creating it
     * and replacing the NULL_MODULE_READER if needed.
     */
    private ModuleReader moduleReaderFor(ModuleReference mref) {
        ModuleReader reader = mrefToReader.get(mref);
        if (reader == NULL_MODULE_READER) {
            // replace NULL_MODULE_READER with an actual module reader
            reader = mrefToReader.computeIfPresent(mref, (k, v) -> {
                if (v == NULL_MODULE_READER) {
                    return createModuleReader(mref);
                } else {
                    return v;
                }
            });
        }
        return reader;
    }

    /**
     * Creates a ModuleReader for the given reference.
     */
    private ModuleReader createModuleReader(ModuleReference mref) {
        try {
            return mref.open();
        } catch (IOException e) {
            // We can't return NULL_MODULE_READER here as that would cause
            // a future class load to attempt to open the module again.
            return new NullModuleReader();
        }
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
    };

}
