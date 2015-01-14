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

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FilePermission;
import java.io.IOException;
import java.io.InputStream;
import java.net.MalformedURLException;
import java.net.URL;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.AccessController;
import java.security.CodeSigner;
import java.security.CodeSource;
import java.security.Permission;
import java.security.PermissionCollection;
import java.security.PrivilegedAction;
import java.security.PrivilegedActionException;
import java.security.PrivilegedExceptionAction;
import java.security.SecureClassLoader;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.jar.Attributes;
import java.util.jar.Manifest;

import jdk.internal.jimage.ImageLocation;
import jdk.internal.jimage.ImageReader;
import jdk.jigsaw.module.ModuleArtifact;

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
 *
 * <p> TODO list</p>
 * <ol>
 *     <li>Security work to ensure that we have the right doPrivileged blocks
 *     and permission checks. Also need to double check that we don't need to
 *     support signers on the class path </li>
 * </ol>
 */
class BuiltinClassLoader extends SecureClassLoader
    implements ModuleLoader, ResourceFinder
{
    static {
        ClassLoader.registerAsParallelCapable();
    }

    // parent ClassLoader
    private final ClassLoader parent;

    // ImageReader or null if an exploded build
    private final ImageReader imageReader;

    // The modules directory when an exploded build, otherwise null
    private final Path modulesDir;

    // -Xoverride directory, can be null
    private final Path overrideDir;

    // the URL class path or null if there is no class path
    private final URLClassPath ucp;

    // maps package name to module for the modules defined to this class loader
    private final Map<String, ModuleArtifact> packageToArtifact = new ConcurrentHashMap<>();

    // maps module artifacts to module readers (added to lazily on first usage)
    private final Map<ModuleArtifact, ModuleReader> readers = new ConcurrentHashMap<>();

    /**
     * Create a new instance.
     */
    BuiltinClassLoader(ClassLoader parent,
                       ImageReader imageReader,
                       Path modulesDir,
                       Path overrideDir,
                       URLClassPath ucp)
    {
        // ensure getParent() returns null when the parent is the boot loader
        super(parent == null || parent == BootLoader.loader() ? null : parent);

        this.parent = parent;
        this.imageReader = imageReader;
        this.modulesDir = modulesDir;
        this.overrideDir = overrideDir;
        this.ucp = ucp;
    }

    /**
     * Define the module in the given module artifact to this class loader.
     * This has the effect of making the types in the module visible.
     */
    @Override
    public void defineModule(ModuleArtifact artifact) {
        artifact.packages().forEach(p -> packageToArtifact.put(p, artifact));
    }


    // -- finding/loading resources

    /**
     * Finds the resource with the given name. This method is overridden so
     * that resources in modules defined by this class loader are located
     * first.
     */
    @Override
    public URL getResource(String name) {
        URL url = null;

        // is resource in a package of a module defined to this class loader?
        ModuleArtifact artifact = findModuleForResource(name);
        if (artifact != null) {
            try {
                url = findResource(artifact, name);
                if (url != null)
                    return url;
            } catch (SecurityException e) {
                // ignore as the resource may be in parent
            }
        }

        // check parent
        if (parent != null) {
            url = parent.getResource(name);
            if (url != null) {
                return url;
            }
        }

        // search all modules defined to this class loader
        if (artifact == null) {
            Iterator<ModuleArtifact> i = packageToArtifact.values()
                                                          .stream()
                                                          .distinct()
                                                          .iterator();
            while (i.hasNext()) {
                try {
                    url = findResource(i.next(), name);
                    if (url != null) {
                        return url;
                    }
                } catch (SecurityException e) {
                    // ignore as the resource may be in parent
                }
            }
        }

        // check class path
        if (ucp != null) {
            // FIXME: doPriv && permission check??
            url = ucp.findResource(name, true);
        }

        return url;
    }

    /**
     * Finds all resources with the given name. This method is overridden so
     * resources in modules defined to this class loader are located first.
     */
    @Override
    public Enumeration<URL> getResources(String name) throws IOException {
        List<URL> result = new ArrayList<>();

        // for consistency with getResource then we must check if the resource
        // is in a package of a module defined to this class loader
        ModuleArtifact artifact = findModuleForResource(name);
        if (artifact != null) {
            try {
                URL url = findResource(artifact, name);
                if (url != null)
                    result.add(url);
            } catch (SecurityException e) {
                // ignore as the resource may be in parent
            }
        }

        // check parent
        if (parent != null) {
            Enumeration<URL> e = parent.getResources(name);
            while (e.hasMoreElements()) {
                result.add(e.nextElement());
            }
        }

        // search all modules defined to this class loader
        if (artifact == null) {
            packageToArtifact.values().stream().distinct().forEach(a -> {
                try {
                    URL url = findResource(a, name);
                    if (url != null)
                        result.add(url);
                } catch (SecurityException e) {
                    // ignore as the resource may be in parent
                }
            });
        }

        // class path
        if (ucp != null) {
            // FIXME: doPriv && permission check??
            Enumeration<URL> e = ucp.findResources(name, true);
            while (e.hasMoreElements()) {
                result.add(e.nextElement());
            }
        }

        // return enumeration
        Iterator<URL> i = result.iterator();
        return new Enumeration<URL> () {
            public URL nextElement() {
                return i.next();
            }
            public boolean hasMoreElements() {
                return i.hasNext();
            }
        };
    }

    @Override
    public URL findResource(String name) {
        throw new InternalError("Should not get here");
    }

    @Override
    public Enumeration<URL> findResources(String name) {
        throw new InternalError("Should not get here");
    }

    /**
     * Maps the package name of the given resource to a module, returning
     * the ModuleArtifact if the module is defined to this class loader.
     * Returns {@code null} if there no mapping.
     */
    private ModuleArtifact findModuleForResource(String name) {
        int pos = name.lastIndexOf('/');
        if (pos < 0)
            return null;

        // package -> artifact
        String pn = name.substring(0, pos).replace('/', '.');
        return packageToArtifact.get(pn);
    }

    /**
     * Returns a URL to a resource in the given module or {@code null}
     * if not found.
     */
    private URL findResource(ModuleArtifact artifact, String name) {
        return moduleReaderFor(artifact).findResource(name);
    }

    /**
     * Called by the jrt protocol handler to locate a resource (by name) in
     * the given module.
     *
     * ##FIXME should only find resources that are in defined modules
     * ##FIXME need to check how this works with -Xoverride
     * ##FIXME need permission checks
     */
    @Override
    public Resource findResource(String module, String name) {
        // for now this is for resources in the image only
        if (imageReader == null)
            return null;

        String rn = "/" + module + "/" + name;
        ImageLocation location = imageReader.findLocation(rn);
        if (location == null)
            return null;

        URL url = toJrtURL(module, name);
        return new Resource() {
            @Override
            public String getName() {
                return name;
            }
            @Override
            public URL getURL() {
                return url;
            }
            @Override
            public URL getCodeSourceURL() {
                return toJrtURL(module);
            }
            @Override
            public InputStream getInputStream() throws IOException {
                byte[] resource = imageReader.getResource(location);
                return new ByteArrayInputStream(resource);
            }
            @Override
            public int getContentLength() {
                long size = location.getUncompressedSize();
                return (size > Integer.MAX_VALUE) ? -1 : (int) size;
            }
        };
    }


    // -- finding/loading classes

    /**
     * Finds the class with the specified binary name.
     */
    @Override
    protected Class<?> findClass(String cn) throws ClassNotFoundException {

        // find the candidate module for this class
        ModuleArtifact artifact = findModule(cn);

        Class<?> c = null;
        if (artifact != null) {
            c = findClassInModuleOrNull(artifact, cn);
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
        synchronized (getClassLoadingLock(cn)) {
            // check if already loaded
            Class<?> c = findLoadedClass(cn);

            if (c == null) {
                // find the candidate module for this class
                ModuleArtifact artifact = findModule(cn);
                if (artifact != null)
                    c = findClassInModuleOrNull(artifact, cn);

                if (c == null) {
                    // check parent
                    if (parent != null) {
                        try {
                            c = parent.loadClass(cn);
                        } catch (ClassNotFoundException e) {
                        }
                    }

                    // check class path
                    if (c == null && artifact == null && ucp != null) {
                        c = findClassOnClassPathOrNull(cn);
                    }

                    // not found
                    if (c == null)
                        throw new ClassNotFoundException(cn);
                }
            }

            if (resolve)
                resolveClass(c);
            return c;
        }
    }

    /**
     * Find the candidate module artifact for the given class name.
     * Returns {@code null} if none of the modules defined to this
     * class loader contain the API package for the class.
     */
    private ModuleArtifact findModule(String cn) {
        int pos = cn.lastIndexOf('.');
        if (pos < 0)
            return null; // unnamed package

        String pn = cn.substring(0, pos);
        return packageToArtifact.get(pn);
    }

    /**
     * Finds the class with the specified binary name if in a module
     * defined to this ClassLoader.
     *
     * @return the resulting Class or {@code null} if not found
     */
    private Class<?> findClassInModuleOrNull(ModuleArtifact artifact, String cn) {
        PrivilegedAction<Class<?>> pa = () -> defineClass(cn, artifact);
        return AccessController.doPrivileged(pa);
    }

    /**
     * Finds the class with the specified binary name on the class path.
     *
     * @return the resulting Class or {@code null} if not found
     *
     * @throws ClassNotFoundException with an appropriate cause if
     * an there is an error reading the class file
     */
    private Class<?> findClassOnClassPathOrNull(String cn)
        throws ClassNotFoundException
    {
        try {
            return AccessController.doPrivileged(
                new PrivilegedExceptionAction<Class<?>>() {
                    public Class<?> run() throws ClassNotFoundException {
                        String path = cn.replace('.', '/').concat(".class");
                        Resource res = ucp.getResource(path, false);
                        if (res != null) {
                            try {
                                return defineClass(cn, res);
                            } catch (IOException ioe) {
                                throw new ClassNotFoundException(cn, ioe);
                            }
                        } else {
                            return null;
                        }
                    }
                });
        } catch (PrivilegedActionException pae) {
            throw (ClassNotFoundException) pae.getCause();
        }
    }

    /**
     * Defines the given binary class name to the VM, loading the class
     * bytes from the given module artifact.
     *
     * @return the resulting Class or {@code null} if an I/O error occurs
     */
    private Class<?> defineClass(String cn, ModuleArtifact artifact) {
        ModuleReader reader = moduleReaderFor(artifact);
        try {
            // read class file
            String rn = cn.replace('.', '/').concat(".class");
            byte[] bytes = reader.readResource(rn);

            // define class to VM
            int pos = cn.lastIndexOf('.');
            String pn = cn.substring(0, pos);
            Package p = getPackage(pn);
            if (p == null) {
                try {
                    definePackage(pn, null, null, null, null, null, null, null);
                } catch (IllegalArgumentException iae) {
                    // someone else beat us to it
                }
            }
            CodeSource cs = new CodeSource(reader.codeBase(), (CodeSigner[])null);
            return defineClass(cn, bytes, 0, bytes.length, cs);

        } catch (IOException ignore) {
            // CNFE will be thrown by caller
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

    /**
     * Defines a package in this ClassLoader. If the package is already defined
     * then its sealing needs to be checked if sealed by the legacy sealing
     * mechanism.
     *
     * @throws SecurityException if there is a sealing violation (JAR spec)
     */
    private Package defineOrCheckPackage(String pn, Manifest man, URL url) {
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

        Attributes attr = man.getAttributes(pn.replace('.', '/').concat("/"));
        if (attr != null) {
            specTitle   = attr.getValue(Attributes.Name.SPECIFICATION_TITLE);
            specVersion = attr.getValue(Attributes.Name.SPECIFICATION_VERSION);
            specVendor  = attr.getValue(Attributes.Name.SPECIFICATION_VENDOR);
            implTitle   = attr.getValue(Attributes.Name.IMPLEMENTATION_TITLE);
            implVersion = attr.getValue(Attributes.Name.IMPLEMENTATION_VERSION);
            implVendor  = attr.getValue(Attributes.Name.IMPLEMENTATION_VENDOR);
            sealed      = attr.getValue(Attributes.Name.SEALED);
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


    // -- instrumentation

    /**
     * Called by the VM to support dynamic additions to the class path
     *
     * @see java.lang.instrument.Instrumentation#appendToSystemClassLoaderSearch
     */
    private void appendToClassPathForInstrumentation(String path) {
        assert ucp != null;
        ucp.addURL(toFileURL(Paths.get(path)));
    }


    // -- miscellaneous supporting methods

    /**
     * Returns the ModuleReader for the given artifact, creating it if needed.
     */
    private ModuleReader moduleReaderFor(ModuleArtifact artifact) {
        return readers.computeIfAbsent(artifact, k -> createModuleReader(artifact));
    }

    /**
     * Creates a ModuleReader for the given artifact.
     */
    private ModuleReader createModuleReader(ModuleArtifact artifact) {
        String s = artifact.location().getScheme();

        ModuleReader reader;
        if (!s.equalsIgnoreCase("jrt")) {
            reader = ModuleReader.create(artifact);
        } else {
            // special-case the runtime image
            reader = new ModuleReader() {
                private final String module = artifact.descriptor().name();
                private final URL codeBase = toJrtURL(module);

                /**
                 * Returns the ImageLocation for the given resource, {@code null}
                 * if not found.
                 */
                private ImageLocation findImageLocation(String name) {
                    if (imageReader == null) {
                        return null;
                    } else {
                        String rn = "/" + module + "/" + name;
                        return imageReader.findLocation(rn);
                    }
                }

                @Override
                public URL findResource(String name) {
                    if (findImageLocation(name) != null)
                        return toJrtURL(module, name);

                    // not found
                    return null;
                }

                @Override
                public byte[] readResource(String name) throws IOException {
                    ImageLocation location = findImageLocation(name);
                    if (location != null)
                        return imageReader.getResource(location);

                    throw new IOException(module + "/" + name + " not found");
                }

                @Override
                public URL codeBase() {
                    return codeBase;
                }
            };
        }

        // if -Xoverride is specified then wrap the ModuleReader
        if (overrideDir != null) {
            return new ModuleReader() {
                private final String module = artifact.descriptor().name();

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
                public URL findResource(String name) {
                    Path path = findOverriddenClass(name);
                    if (path != null) {
                        return toFileURL(path);
                    } else {
                        return reader.findResource(name);
                    }
                }
                @Override
                public byte[] readResource(String name) throws IOException {
                    Path path = findOverriddenClass(name);
                    if (path != null) {
                        return Files.readAllBytes(path);
                    } else {
                        return reader.readResource(name);
                    }
                }
                @Override
                public URL codeBase() {
                    return reader.codeBase();
                }
            };
        } else {
            // no -Xoverride
            return reader;
        }
    }

    /**
     * Returns a jrt URL for the given module and resource name.
     */
    private static URL toJrtURL(String module, String name) {
        try {
            return new URL("jrt:/" + module + "/" + name);
        } catch (MalformedURLException e) {
            throw new InternalError(e);
        }
    }

    /**
     * Returns a jrt URL for the given module.
     */
    private static URL toJrtURL(String module) {
        try {
            return new URL("jrt:/" + module);
        } catch (MalformedURLException e) {
            throw new InternalError(e);
        }
    }

    /**
     * Returns a file URL for the given file Path.
     */
    private static URL toFileURL(Path path) {
        try {
            return path.toUri().toURL();
        } catch (MalformedURLException e) {
            throw new InternalError(e);
        }
    }
}
