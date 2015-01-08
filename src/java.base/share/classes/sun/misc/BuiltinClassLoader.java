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
import java.security.*;
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
 * The extension or application class loader.
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
 * delegation model. When requested to find a class or resource then this
 * ClassLoader first checks the modules defined to the ClassLoader. If not
 * found then it delegates the search to the parent class loader and if not
 * found in the parent then it searches the class path. The rational for this
 * approach is that modules defined to this ClassLoader are assumed to be in
 * the boot Layer and so should not have any overlapping packages with modules
 * defined to the parent or the boot class loader. </p>
 *
 * <p> TODO list</p>
 * <ol>
 *     <li>Security work to ensure that we have the right doPrivileged blocks
 *     and permission checks. Also need to double check that we don't need to
 *     support signers on the class path </li>
 *     <li>Need to add support for package sealing</li>
 *     <li>Need to check the URLs and usage when using -Xoverride</li>
 *     <li>Replace BootResourceFinder to avoid duplicate code</li>
 * </ol>
 */
class BuiltinClassLoader extends SecureClassLoader
    implements ModuleLoader, ResourceFinder
{
    private static final JavaLangAccess jla = SharedSecrets.getJavaLangAccess();

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
        super(parent);

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
        // check if resource is in module defined to this loader
        try {
            URL url = findResourceInModule(name);
            if (url != null)
                return url;
        } catch (SecurityException e) {
            // ignore as the resource may be in parent
        }

        // check parent
        URL url;
        if (parent == null) {
            url = BootResourceFinder.get().findResource(name);
        } else {
            url = parent.getResource(name);
        }

        // check class path
        if (url == null && ucp != null) {
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

        try {
            URL url = findResourceInModule(name);
            if (url != null)
                result.add(url);
        } catch (SecurityException e) {
            // ignore as the resource may be in parent
        }

        // check parent
        if (parent == null) {
            BootResourceFinder.get().findResources(name).forEachRemaining(result::add);
        } else {
            Enumeration<URL> e = parent.getResources(name);
            while (e.hasMoreElements()) {
                result.add(e.nextElement());
            }
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
     * Returns the URL to a resource if the resource is in a module
     * defined to this class loader. Returns {@code null} if not
     * found.
     *
     * @throws SecurityException if denied by security manager
     */
    private URL findResourceInModule(String name) {
        // get package name
        int pos = name.lastIndexOf('/');
        if (pos < 0)
            return null;  // unnamed package

        // package -> artifact
        String pkg = name.substring(0, pos).replace('/', '.');
        ModuleArtifact artifact = packageToArtifact.get(pkg);
        if (artifact == null)
            return null;

        // find resource in module
        ModuleReader reader = moduleReaderFor(artifact);
        return reader.findResource(artifact.descriptor().name(), name);
    }

    /**
     * Called by the jrt protocol handler to locate a resource (by name) in
     * the given module.
     *
     * ##FIXME need to check how this works with -Xoverride
     * ##FIXME need permission checks
     */
    @Override
    public Resource findResource(String mn, String name) {
        // for now this is for resources in the image only
        if (imageReader == null)
            return null;

        // check that the package is in module defined to this loader
        int pos = name.lastIndexOf('/');
        if (pos < 0)
            return null;  // unnamed package
        String pkg = name.substring(0, pos).replace('/', '.');
        if (packageToArtifact.get(pkg) == null)
            return null;

        ImageLocation location = imageReader.findLocation(name);
        if (location == null)
            return null;

        URL url = toJrtURL(mn, name);
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
                return toJrtURL(mn);
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
        // check modules defined to this class loader
        Class<?> c = findClassInModuleOrNull(cn);

        // check class path
        if (c == null && ucp != null)
            c = findClassOnClassPathOrNull(cn);

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
                // check modules defined to this class loader
                c = findClassInModuleOrNull(cn);

                if (c == null) {
                    // check parent
                    if (parent != null) {
                        try {
                            c = parent.loadClass(cn);
                        } catch (ClassNotFoundException e) { }
                    } else {
                        c = jla.findBootstrapClassOrNull(this, cn);
                    }

                    // check class path
                    if (c == null && ucp != null) {
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
     * Finds the class with the specified binary name if in a module
     * defined to this ClassLoader.
     *
     * @return the resulting Class or {@code null} if the package name is
     * an API package in any of the modules defined to this class loader
     *
     * @throws ClassNotFoundException if the package name is in an API
     * package defined to this class loader but the class is not found.
     */
    private Class<?> findClassInModuleOrNull(String cn)
        throws ClassNotFoundException
    {
        int pos = cn.lastIndexOf('.');
        if (pos < 0)
            return null; // unnamed package

        String pkg = cn.substring(0, pos);
        ModuleArtifact artifact = packageToArtifact.get(pkg);
        if (artifact != null) {
            PrivilegedAction<Class<?>> pa = () -> defineClass(cn, artifact);
            Class<?> c = AccessController.doPrivileged(pa);
            if (c == null)
                throw new ClassNotFoundException(cn);
            return c;
        }

        // not in a module
        return null;
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
        String mn = artifact.descriptor().name();

        try {
            byte[] bytes = null;
            URL url = null;

            // check if overridden
            if (overrideDir != null) {
                String path = cn.replace('.', File.separatorChar).concat(".class");
                Path file = overrideDir.resolve(mn).resolve(path);
                if (Files.exists(file)) {
                    bytes = Files.readAllBytes(file);
                    url = toFileURL(file);
                }
            }

            // not overridden so read from module artifact
            if (bytes == null) {
                ModuleReader reader = moduleReaderFor(artifact);
                String name = cn.replace('.', '/').concat(".class");
                bytes = reader.readResource(mn, name);
                url = reader.codeSourceLocation(mn);
            }

            // define class to VM
            int pos = cn.lastIndexOf('.');
            String pkg = cn.substring(0, pos);
            Package p = getPackage(pkg);
            if (p == null) {
                try {
                    definePackage(pkg, null, null, null, null, null, null, null);
                } catch (IllegalArgumentException iae) {
                    // someone else beat us to it
                }
            }
            CodeSource cs = new CodeSource(url, (CodeSigner[])null);
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
     */
    private Class<?> defineClass(String cn, Resource res) throws IOException {
        URL url = res.getCodeSourceURL();

        int pos = cn.lastIndexOf('.');
        if (pos != -1) {
            String pkg = cn.substring(0, pos);
            Package p = getPackage(pkg);
            if (p == null) {
                Manifest man = res.getManifest();
                try {
                    if (man != null) {
                        definePackage(pkg, man, url);
                    } else {
                        definePackage(pkg, null, null, null, null, null, null, null);
                    }
                } catch (IllegalArgumentException iae) {
                    // someone else beat us to it
                }
            }
        }

        ByteBuffer bb = res.getByteBuffer();
        if (bb != null) {
            // Use (direct) ByteBuffer:
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
     * Defines a new package in this ClassLoader. The attributes in the specified
     * Manifest are use to get the package version and sealing information.
     *
     * @throws IllegalArgumentException if the package name duplicates an existing
     * package either in this class loader or one of its ancestors
     */
    private Package definePackage(String pkg, Manifest man, URL url) {
        String specTitle = null;
        String specVersion = null;
        String specVendor = null;
        String implTitle = null;
        String implVersion = null;
        String implVendor = null;
        String sealed = null;
        URL sealBase = null;

        Attributes attr = man.getAttributes(pkg.replace('.', '/').concat("/"));
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

        // no support for sealing yet
        // if ("true".equalsIgnoreCase(sealed)) sealBase = url;

        return definePackage(pkg,
                             specTitle,
                             specVersion,
                             specVendor,
                             implTitle,
                             implVersion,
                             implVendor,
                             sealBase);
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
        if (!s.equalsIgnoreCase("jrt"))
            return ModuleReader.create(artifact);

        // special-case the runtime image here
        return new ModuleReader() {
            @Override
            public URL findResource(String mn, String name) {
                if (imageReader != null) {
                    ImageLocation location = imageReader.findLocation(name);
                    if (location != null)
                        return toJrtURL(mn, name);
                } else {
                    // exploded build
                    String path = name.replace('/', File.separatorChar).concat(".class");
                    Path file = modulesDir.resolve(mn).resolve(path);
                    if (Files.exists(file))
                        return toFileURL(file);
                }
                // not found
                return null;
            }

            @Override
            public byte[] readResource(String mn, String name) throws IOException {
                if (imageReader != null) {
                    ImageLocation location = imageReader.findLocation(name);
                    if (location != null)
                        return imageReader.getResource(location);
                } else {
                    // exploded build
                    String path = name.replace('/', File.separatorChar).concat(".class");
                    Path file = modulesDir.resolve(mn).resolve(path);
                    if (Files.exists(file))
                        return Files.readAllBytes(file);
                }
                throw new IOException(mn + "/" + name + " not found");
            }

            @Override
            public URL codeSourceLocation(String mn) {
                if (imageReader != null) {
                    return toJrtURL(mn);
                } else {
                    Path file = modulesDir.resolve(mn);
                    return toFileURL(file);
                }
            }
        };
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
