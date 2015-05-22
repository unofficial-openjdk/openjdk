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

import java.io.File;
import java.io.FilePermission;
import java.io.IOException;
import java.io.InputStream;
import java.lang.module.ModuleReference;
import java.lang.module.ModuleReader;
import java.net.MalformedURLException;
import java.net.URL;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.AccessController;
import java.security.CodeSigner;
import java.security.CodeSource;
import java.security.Permission;
import java.security.PermissionCollection;
import java.security.PrivilegedAction;
import java.security.PrivilegedActionException;
import java.security.PrivilegedExceptionAction;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.jar.Attributes;
import java.util.jar.Manifest;


/**
 * The extension or application class loader. Resources loaded from modules
 * defined to the boot class loader are also loaded via an instance of this
 * ClassLoader type.
 *
 * <p> This ClassLoader supports loading of classes and resources from modules.
 * Modules are defined to the ClassLoader by invoking the {@link #defineModule}
 * method. Defining a module to this ClassLoader has the effect of making the
 * types in the module visible. </p>
 *
 * <p> This ClassLoader also supports loading of classes and resources from a
 * class path of URLs that are specified to the ClassLoader at construction
 * time. The class path may expand at runtime (the Class-Path attribute in JAR
 * files or via instrumentation agents). </p>
 *
 * <p> The delegation model used by this ClassLoader differs to the regular
 * delegation model. When requested to load a class then this ClassLoader first
 * checks the modules defined to the ClassLoader. If not found then it delegates
 * the search to the parent class loader and if not found in the parent then it
 * searches the class path. The rational for this approach is that modules defined
 * to this ClassLoader are assumed to be in the boot Layer and so should not have
 * any overlapping packages with modules defined to the parent or the boot class
 * loader. </p>
 */
class BuiltinClassLoader extends ModuleClassLoader {

    static {
        ClassLoader.registerAsParallelCapable();
    }

    // the initial module reader for modules defined to this class loader
    private static final ModuleReader NULL_MODULE_READER = new NullModuleReader();

    // parent ClassLoader
    private final BuiltinClassLoader parent;

    // -Xoverride directory, can be null
    private final Path overrideDir;

    // the URL class path or null if there is no class path
    private final URLClassPath ucp;

    /**
     * A module defined/loaded by a built-in class loader.
     *
     * A LoadedModule encapsulates a ModuleReference along with its location in
     * URL form, which is needed to avoid invocations of {@link
     * java.net.URI#toURL() toURL()} and the loading of protocol handlers when
     * defining classes or packages.
     */
    private static class LoadedModule {
        private final ModuleReference mref;
        private final URL url;          // May be null

        LoadedModule(ModuleReference mref) {
            URL url = null;
            if (mref.location().isPresent()) {
                try {
                    url = mref.location().get().toURL();
                } catch (MalformedURLException e1) {
                }
            }
            this.mref = mref;
            this.url = url;
        }

        ModuleReference mref() { return mref; }
        URL location() { return url; }
    }

    // maps package name to a loaded module for the modules defined to this class loader
    private final Map<String, LoadedModule> packageToModule = new ConcurrentHashMap<>();

    // maps a module name to a module reference
    private final Map<String, ModuleReference> nameToModule = new ConcurrentHashMap<>();

    // maps a module reference to a module reader
    private final Map<ModuleReference, ModuleReader> moduleToReader = new ConcurrentHashMap<>();

    /**
     * Create a new instance.
     */
    BuiltinClassLoader(BuiltinClassLoader parent,
                       Path overrideDir,
                       URLClassPath ucp)
    {
        // ensure getParent() returns null when the parent is the boot loader
        super(parent == null || parent == ClassLoaders.bootLoader() ? null : parent);

        this.parent = parent;
        this.overrideDir = overrideDir;
        this.ucp = ucp;
    }

    /**
     * Define the referenced module to this class loader.  This has the effect
     * of making the types in the module visible.
     */
    @Override
    public void defineModule(ModuleReference mref) {
        String mn = mref.descriptor().name();
        if (nameToModule.containsKey(mn))
            throw new IllegalStateException("Module " + mn
                                            + " already defined in this class loader");
        nameToModule.put(mn, mref);
        LoadedModule loadedModule = new LoadedModule(mref);
        mref.descriptor().packages()
            .forEach(p -> packageToModule.put(p, loadedModule));

        // Use NULL_MODULE_READER initially to avoid opening eagerly
        moduleToReader.put(mref, NULL_MODULE_READER);
    }

    // -- finding resources

    /**
     * Returns an input stream to a resource of the given name in a module
     * defined to this class loader.
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
                    });
            } catch (PrivilegedActionException pae) {
                throw (IOException) pae.getCause();
            }
        }
        // module not defined to this class loader or not found
        return null;
    }

    /**
     * Finds the resource with the given name on the class path of this class
     * loader.
     */
    @Override
    public URL findResource(String name) {
        if (ucp != null) {
            PrivilegedAction<URL> pa = () -> ucp.findResource(name, false);
            URL url = AccessController.doPrivileged(pa);
            return checkURL(url);
        } else {
            return null;
        }
    }

    /**
     * Returns an enumeration of URL objects to all the resources with the
     * given name on the class path of this class loader.
     */
    @Override
    public Enumeration<URL> findResources(String name) throws IOException {
        if (ucp != null) {
            List<URL> result = new ArrayList<>();
            PrivilegedAction<Enumeration<URL>> pa = () -> ucp.findResources(name, false);
            Enumeration<URL> e = AccessController.doPrivileged(pa);
            while (e.hasMoreElements()) {
                URL url = checkURL(e.nextElement());
                if (url != null) {
                    result.add(url);
                }
            }
            return Collections.enumeration(result); // checked URLs
        } else {
            return Collections.emptyEnumeration();
        }
    }

    // -- finding/loading classes

    /**
     * Finds the class with the specified binary name.
     */
    @Override
    protected Class<?> findClass(String cn) throws ClassNotFoundException {
        // no class loading until VM is fully initialized
        if (!VM.isModuleSystemInited())
            throw new ClassNotFoundException(cn);

        // find the candidate module for this class
        LoadedModule loadedModule = findModule(cn);

        Class<?> c = null;
        if (loadedModule != null) {
            c = findClassInModuleOrNull(loadedModule, cn);
        } else {
            // check class path
            if (ucp != null)
                c = findClassOnClassPathOrNull(cn);
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
        Class<?> c = loadClassOrNull(cn, resolve);
        if (c == null)
            throw new ClassNotFoundException(cn);
        return c;
    }

    /**
     * A variation of {@code loadCass} to load a class with the specified
     * binary name. This method returns {@code null} when the class is not
     * found.
     */
    protected Class<?> loadClassOrNull(String cn, boolean resolve) {
        synchronized (getClassLoadingLock(cn)) {
            // check if already loaded
            Class<?> c = findLoadedClass(cn);

            if (c == null) {

                // find the candidate module for this class
                LoadedModule loadedModule = findModule(cn);
                if (loadedModule != null) {
                    if (VM.isModuleSystemInited()) {
                        c = findClassInModuleOrNull(loadedModule, cn);
                    }
                } else {
                    // check parent
                    if (parent != null) {
                        c = parent.loadClassOrNull(cn);
                    }

                    // check class path
                    if (c == null && ucp != null && VM.isModuleSystemInited()) {
                        c = findClassOnClassPathOrNull(cn);
                    }
                }

            }

            if (resolve && c != null)
                resolveClass(c);

            return c;
        }
    }

    /**
     * A variation of {@code loadCass} to load a class with the specified
     * binary name. This method returns {@code null} when the class is not
     * found.
     */
    protected  Class<?> loadClassOrNull(String cn) {
        return loadClassOrNull(cn, false);
    }

    /**
     * Find the candidate loaded module for the given class name.
     * Returns {@code null} if none of the modules defined to this
     * class loader contain the API package for the class.
     */
    private LoadedModule findModule(String cn) {
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
    private Class<?> findClassInModuleOrNull(LoadedModule loadedModule, String cn) {
        PrivilegedAction<Class<?>> pa = () -> defineClass(cn, loadedModule);
        return AccessController.doPrivileged(pa);
    }

    /**
     * Finds the class with the specified binary name on the class path.
     *
     * @return the resulting Class or {@code null} if not found
     */
    private Class<?> findClassOnClassPathOrNull(String cn) {
        return AccessController.doPrivileged(
            new PrivilegedAction<Class<?>>() {
                public Class<?> run() {
                    String path = cn.replace('.', '/').concat(".class");
                    Resource res = ucp.getResource(path, false);
                    if (res != null) {
                        try {
                            return defineClass(cn, res);
                        } catch (IOException ioe) {
                            // TBD on how I/O errors should be propagated
                        }
                    }
                    return null;
                }
            });
    }

    /**
     * Defines the given binary class name to the VM, loading the class
     * bytes from the given module.
     *
     * @return the resulting Class or {@code null} if an I/O error occurs
     */
    private Class<?> defineClass(String cn, LoadedModule loadedModule) {
        ModuleReference mref = loadedModule.mref();
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
                if (getPackage(pn) == null) {
                    definePackage(pn, loadedModule);
                }

                // define class to VM
                URL url = loadedModule.location();
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
     * Defines the given binary class name to the VM, loading the class
     * bytes via the given Resource object.
     *
     * @return the resulting Class
     * @throws IOException if reading the resource fails
     * @throws SecurityException if there is a sealing violation (JAR spec)
     */
    private Class<?> defineClass(String cn, Resource res) throws IOException {
        URL url = res.getCodeSourceURL();

        // if class is in a named package then ensure that the package is defined
        int pos = cn.lastIndexOf('.');
        if (pos != -1) {
            String pn = cn.substring(0, pos);
            Manifest man = res.getManifest();
            defineOrCheckPackage(pn, man, url);
        }

        // defines the class to the runtime
        ByteBuffer bb = res.getByteBuffer();
        if (bb != null) {
            CodeSigner[] signers = res.getCodeSigners();
            CodeSource cs = new CodeSource(url, signers);
            return defineClass(cn, bb, cs);
        } else {
            byte[] b = res.getBytes();
            CodeSigner[] signers = res.getCodeSigners();
            CodeSource cs = new CodeSource(url, signers);
            return defineClass(cn, b, 0, b.length, cs);
        }
    }

    // -- packages

    /**
     * Define a Package this to this class loader if not already defined.
     * If the package name is in a module defined to this class loader then
     * the resulting Package is sealed with the code source that is the
     * module location.
     *
     * @param pn package name
     */
    @Override
    public Package definePackage(String pn) {
        Package pkg = getPackage(pn);
        if (pkg == null) {
            LoadedModule loadedModule = packageToModule.get(pn);
            if (loadedModule == null) {
                pkg = definePackage(pn, null, null, null, null, null, null, null);
            } else {
                pkg = definePackage(pn, loadedModule);
            }
        }
        return pkg;
    }

    /**
     * Define a Package this to this class loader. The resulting Package
     * is sealed with the code source that is the module location.
     */
    private Package definePackage(String pn, LoadedModule loadedModule) {
        URL url = loadedModule.location();
        return definePackage(pn, null, null, null, null, null, null, url);
    }

    /**
     * Defines a package in this ClassLoader. If the package is already defined
     * then its sealing needs to be checked if sealed by the legacy sealing
     * mechanism.
     *
     * @throws SecurityException if there is a sealing violation (JAR spec)
     */
    protected Package defineOrCheckPackage(String pn, Manifest man, URL url) {
        Package pkg = getAndVerifyPackage(pn, man, url);
        if (pkg == null) {
            try {
                if (man != null) {
                    pkg = definePackage(pn, man, url);
                } else {
                    pkg = definePackage(pn, null, null, null, null, null, null, null);
                }
            } catch (IllegalArgumentException iae) {
                // defined by another thread so need to re-verify
                pkg = getAndVerifyPackage(pn, man, url);
                if (pkg == null)
                    throw new InternalError("Cannot find package: " + pn);
            }
        }
        return pkg;
    }

    /**
     * Get the Package with the specified package name. If defined
     * then verify that it against the manifest and code source.
     *
     * @throws SecurityException if there is a sealing violation (JAR spec)
     */
    private Package getAndVerifyPackage(String pn, Manifest man, URL url) {
        Package pkg = getPackage(pn);
        if (pkg != null) {
            if (pkg.isSealed()) {
                if (!pkg.isSealed(url)) {
                    throw new SecurityException(
                        "sealing violation: package " + pn + " is sealed");
                }
            } else {
                // can't seal package if already defined without sealing
                if ((man != null) && isSealed(pn, man)) {
                    throw new SecurityException(
                        "sealing violation: can't seal package " + pn +
                        ": already defined");
                }
            }
        }
        return pkg;
    }

    /**
     * Defines a new package in this ClassLoader. The attributes in the specified
     * Manifest are use to get the package version and sealing information.
     *
     * @throws IllegalArgumentException if the package name duplicates an
     * existing package either in this class loader or one of its ancestors
     */
    private Package definePackage(String pn, Manifest man, URL url) {
        String specTitle = null;
        String specVersion = null;
        String specVendor = null;
        String implTitle = null;
        String implVersion = null;
        String implVendor = null;
        String sealed = null;
        URL sealBase = null;

        if (man != null) {
            Attributes attr = man.getAttributes(pn.replace('.', '/').concat("/"));
            if (attr != null) {
                specTitle = attr.getValue(Attributes.Name.SPECIFICATION_TITLE);
                specVersion = attr.getValue(Attributes.Name.SPECIFICATION_VERSION);
                specVendor = attr.getValue(Attributes.Name.SPECIFICATION_VENDOR);
                implTitle = attr.getValue(Attributes.Name.IMPLEMENTATION_TITLE);
                implVersion = attr.getValue(Attributes.Name.IMPLEMENTATION_VERSION);
                implVendor = attr.getValue(Attributes.Name.IMPLEMENTATION_VENDOR);
                sealed = attr.getValue(Attributes.Name.SEALED);
            }

            attr = man.getMainAttributes();
            if (attr != null) {
                if (specTitle == null)
                    specTitle = attr.getValue(Attributes.Name.SPECIFICATION_TITLE);
                if (specVersion == null)
                    specVersion = attr.getValue(Attributes.Name.SPECIFICATION_VERSION);
                if (specVendor == null)
                    specVendor = attr.getValue(Attributes.Name.SPECIFICATION_VENDOR);
                if (implTitle == null)
                    implTitle = attr.getValue(Attributes.Name.IMPLEMENTATION_TITLE);
                if (implVersion == null)
                    implVersion = attr.getValue(Attributes.Name.IMPLEMENTATION_VERSION);
                if (implVendor == null)
                    implVendor = attr.getValue(Attributes.Name.IMPLEMENTATION_VENDOR);
                if (sealed == null)
                    sealed = attr.getValue(Attributes.Name.SEALED);
            }

            // package is sealed
            if ("true".equalsIgnoreCase(sealed))
                sealBase = url;
        }
        return definePackage(pn,
                             specTitle,
                             specVersion,
                             specVendor,
                             implTitle,
                             implVersion,
                             implVendor,
                             sealBase);
    }

    /**
     * Returns {@code true} if the specified package name is sealed according to
     * the given manifest.
     */
    private boolean isSealed(String pn, Manifest man) {
        String path = pn.replace('.', '/').concat("/");
        Attributes attr = man.getAttributes(path);
        String sealed = null;
        if (attr != null)
            sealed = attr.getValue(Attributes.Name.SEALED);
        if (sealed == null && (attr = man.getMainAttributes()) != null)
            sealed = attr.getValue(Attributes.Name.SEALED);
        return "true".equalsIgnoreCase(sealed);
    }

    // -- permissions

    /**
     * Returns the permissions for the given CodeSource.
     */
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
        } catch (IOException ioe) { }

        return perms;
    }

    // -- miscellaneous supporting methods

    /**
     * Returns the ModuleReader for the given module, creating it
     * and replacing the NULL_MODULE_READER if needed.
     */
    private ModuleReader moduleReaderFor(ModuleReference mref) {
        ModuleReader reader = moduleToReader.get(mref);
        assert reader != null;
        if (reader == NULL_MODULE_READER) {
            // replace NULL_MODULE_READER with an actual module reader
            reader = moduleToReader.computeIfPresent(mref, (k, v) -> {
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
     * Creates a ModuleReader for the given module.
     */
    private ModuleReader createModuleReader(ModuleReference mref) {
        ModuleReader reader;

        try {
            reader = mref.open();
        } catch (IOException e) {
            // We can't return NULL_MODULE_READER here as that would cause
            // a future class load to attempt to open the module again.
            return new NullModuleReader();
        }

        // if -Xoverride is specified then wrap the ModuleReader so
        // that the override directory is checked first
        if (overrideDir != null) {
            String mn = mref.descriptor().name();
            reader = new OverrideModuleReader(mn, overrideDir, reader);
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
    };

    /**
     * A ModuleReader to prepend an override directory to another ModuleReader.
     */
    private static class OverrideModuleReader implements ModuleReader {
        private final String module;
        private final Path overrideDir;
        private final ModuleReader reader;

        OverrideModuleReader(String module, Path overrideDir, ModuleReader reader) {
            this.module = module;
            this.overrideDir = overrideDir;
            this.reader = reader;
        }

        /**
         * Returns the path to a .class file if overridden with -Xoverride
         * or {@code null} if not found.
         */
        private Path findOverriddenClass(String name) {
            if (overrideDir != null && name.endsWith(".class")) {
                String path = name.replace('/', File.separatorChar);
                Path file = overrideDir.resolve(module).resolve(path);
                if (Files.isRegularFile(file)) {
                    return file;
                }
            }
            return null;
        }

        @Override
        public Optional<InputStream> open(String name) throws IOException {
            Path path = findOverriddenClass(name);
            if (path != null) {
                return Optional.of(Files.newInputStream(path));
            } else {
                return reader.open(name);
            }
        }

        @Override
        public Optional<ByteBuffer> read(String name) throws IOException {
            Path path = findOverriddenClass(name);
            if (path != null) {
                return Optional.of(ByteBuffer.wrap(Files.readAllBytes(path)));
            } else {
                return reader.read(name);
            }
        }

        @Override
        public void release(ByteBuffer bb) {
            if (bb.isDirect())
                reader.release(bb);
        }

        @Override
        public void close() throws IOException {
            throw new InternalError("Should not get here");
        }
    }

    /**
     * Checks access to the given URL. We use URLClassPath for consistent
     * checking with java.net.URLClassLoader.
     */
    private static URL checkURL(URL url) {
        return URLClassPath.checkURL(url);
    }
}
