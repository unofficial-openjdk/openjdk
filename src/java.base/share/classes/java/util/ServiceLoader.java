/*
 * Copyright (c) 2005, 2016, Oracle and/or its affiliates. All rights reserved.
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

package java.util;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Layer;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Module;
import java.net.URL;
import java.net.URLConnection;
import java.security.AccessControlContext;
import java.security.AccessController;
import java.security.PrivilegedAction;
import java.security.PrivilegedActionException;
import java.security.PrivilegedExceptionAction;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

import jdk.internal.loader.BootLoader;
import jdk.internal.misc.JavaLangAccess;
import jdk.internal.misc.JavaLangReflectModuleAccess;
import jdk.internal.misc.SharedSecrets;
import jdk.internal.misc.VM;
import jdk.internal.module.ServicesCatalog;
import jdk.internal.module.ServicesCatalog.ServiceProvider;
import jdk.internal.reflect.CallerSensitive;
import jdk.internal.reflect.Reflection;


/**
 * A simple service-provider loading facility.
 *
 * <p> A <i>service</i> is a well-known set of interfaces and (usually
 * abstract) classes.  A <i>service provider</i> is a specific implementation
 * of a service.  The classes in a provider typically implement the interfaces
 * and subclass the classes defined in the service itself.
 * Providers may be developed and deployed as modules and made available using
 * the application module path. Providers may alternatively be packaged as JAR
 * files and made available by adding them to the application class path. The
 * advantage of developing a provider as a module is that the provider can be
 * fully encapsulated to hide all details of its implementation.
 *
 * <p> For the purpose of loading, a service is represented by a single type,
 * that is, a single interface or abstract class.  (A concrete class can be
 * used, but this is not recommended.)  A provider of a given service contains
 * one or more concrete classes that extend this <i>service type</i> with data
 * and code specific to the provider.  The <i>provider class</i> is typically
 * not the entire provider itself but rather a proxy which contains enough
 * information to decide whether the provider is able to satisfy a particular
 * request together with code that can create the actual provider on demand.
 * The details of provider classes tend to be highly service-specific; no
 * single class or interface could possibly unify them, so no such type is
 * defined here.
 *
 * <p> A requirement enforced by this facility is that providers deployed as
 * explicit modules on the module path must define a static factory method
 * to obtain the provider instance or a public zero-argument constructor.
 * Providers deployed on the class path or as {@link
 * java.lang.module.ModuleDescriptor#isAutomatic automatic-modules} on the
 * module path must have a public zero-argument constructor. If an
 * explicit module defines a static factory method then the method is a
 * public static zero-argument method named "{@code provider}" with a return
 * type that is assignable to the service type. If an explicit module on
 * the module path defines both a static factory method and a public zero
 * argument constructor then the static factory method is preferred.
 *
 * <p> An application or library using this loading facility and developed
 * and deployed as a named module must have an appropriate <i>uses</i> clause
 * in its <i>module descriptor</i> to declare that the module uses
 * implementations of the service. A corresponding requirement is that a
 * provider deployed as a named module must have an appropriate
 * <i>provides</i> clause in its module descriptor to declare that the module
 * provides an implementation of the service. The <i>uses</i> and
 * <i>provides</i> allow consumers of a service to be <i>linked</i> to
 * providers of the service.
 *
 * <p> A service provider that is packaged as a JAR file for the class path is
 * identified by placing a <i>provider-configuration file</i> in the resource
 * directory <tt>META-INF/services</tt>. The file's name is the fully-qualified
 * <a href="../lang/ClassLoader.html#name">binary name</a> of the service's
 * type. The file contains a list of fully-qualified binary names of concrete
 * provider classes, one per line.  Space and tab characters surrounding each
 * name, as well as blank lines, are ignored.  The comment character is
 * <tt>'#'</tt> (<tt>'&#92;u0023'</tt>,
 * <font style="font-size:smaller;">NUMBER SIGN</font>); on
 * each line all characters following the first comment character are ignored.
 * The file must be encoded in UTF-8.
 * If a particular concrete provider class is named in more than one
 * configuration file, or is named in the same configuration file more than
 * once, then the duplicates are ignored.  The configuration file naming a
 * particular provider need not be in the same JAR file or other distribution
 * unit as the provider itself. The provider must be visible from the same
 * class loader that was initially queried to locate the configuration file;
 * note that this is not necessarily the class loader from which the file was
 * actually loaded.
 *
 * <p> Providers are located and instantiated lazily, that is, on demand.  A
 * service loader maintains a cache of the providers that have been loaded so
 * far. Each invocation of the {@link #iterator iterator} method returns an
 * iterator that first yields all of the elements cached from previous
 * iteration, in instantiation order, and then lazily locates and instantiates
 * any remaining providers, adding each one to the cache in turn.  Similarly,
 * each invocation of the {@link #stream stream} method returns a stream that
 * first processes all providers loaded by previous stream operations, in load
 * order, and then lazily locates any remaining providers. Caches are cleared
 * via the {@link #reload reload} method.
 *
 * <h2> Locating providers </h2>
 *
 * <p> The {@code load} methods locate providers using a class loader or module
 * {@link Layer layer}. When locating providers using a class loader then
 * providers in both named and unnamed modules may be located. When locating
 * providers using a module layer then only providers in named modules in
 * the layer (or parent layers) are located.
 *
 * <p> When locating providers using a class loader then any providers in named
 * modules defined to the class loader, or any class loader that is reachable
 * via parent delegation, are located. Additionally, providers in module layers
 * other than the {@link Layer#boot() boot} layer, where the module layer
 * contains modules defined to the class loader, or any class loader reachable
 * via parent delegation, are also located. For example, suppose there is a
 * module layer where each module is defined to its own class loader (see {@link
 * Layer#defineModulesWithManyLoaders defineModulesWithManyLoaders}). If the
 * {@code load} method is invoked to locate providers using any of these class
 * loaders for this layer then then it will locate all of the providers in that
 * layer, irrespective of their defining class loader.
 *
 * <p> In the case of unnamed modules then the service configuration files are
 * located using the class loader's {@link ClassLoader#getResources(String)
 * ClassLoader.getResources(String)} method. Any providers listed should be
 * visible via the class loader specified to the {@code load} method. If a
 * provider in a named module is listed then it is ignored - this is to avoid
 * duplicates that would otherwise arise when a module has both a
 * <i>provides</i> clause and a service configuration file in {@code
 * META-INF/services} that lists the same provider.
 *
 * <h2> Ordering </h2>
 *
 * <p> Service loaders created to locate providers using a {@code ClassLoader}
 * locate providers as follows:
 * <ul>
 *     <li> Providers in named modules are located before providers on the
 *     class path (or more generally, unnamed modules). </li>
 *
 *     <li> When locating providers in named modules then the service loader
 *     will locate providers in modules defined to the class loader, then its
 *     parent class loader, its parent parent, and so on to the bootstrap class
 *     loader. If a {@code ClassLoader}, or any class loader in the parent
 *     delegation chain, defines modules in a custom module {@link Layer} then
 *     all providers in that layer are located, irrespective of their class
 *     loader. The ordering of modules defined to the same class loader, or the
 *     ordering of modules in a layer, is not defined. </li>
 *
 *     <li> When locating providers in unnamed modules then the ordering is
 *     based on the order that the class loader's {@link
 *     ClassLoader#getResources(String) ClassLoader.getResources(String)}
 *     method finds the service configuration files. </li>
 * </ul>
 *
 * <p> Service loaders created to locate providers in a module {@link Layer}
 * will first locate providers in the layer, then its parent layer, then its
 * parent, and so on to the boot layer. The ordering of modules in a layer is
 * not defined.
 *
 * <h2> Selection and filtering </h2>
 *
 * <p> Selecting a provider or filtering providers will usually involve invoking
 * a provider method. Where selection or filtering based on the provider class is
 * needed then it can be done using a {@link #stream() stream}. For example, the
 * following locates the providers that have a specific annotation:
 * <pre>{@code
 *     Stream<CodecSet> providers = ServiceLoader.load(CodecSet.class)
 *            .stream()
 *            .filter(p -> p.type().isAnnotationPresent(Managed.class))
 *            .map(Provider::get);
 * }</pre>
 *
 * <h2> Security </h2>
 *
 * <p> Service loaders always execute in the security context of the caller
 * of the iterator or stream methods and may also be restricted by the security
 * context of the caller that created the service loader.
 * Trusted system code should typically invoke the methods in this class, and
 * the methods of the iterators which they return, from within a privileged
 * security context.
 *
 * <h2> Concurrency </h2>
 *
 * <p> Instances of this class are not safe for use by multiple concurrent
 * threads.
 *
 * <h2> Null handling </h2>
 *
 * <p> Unless otherwise specified, passing a {@code null} argument to any
 * method in this class will cause a {@link NullPointerException} to be thrown.
 *
 * <h2> Example </h2>
 * <p> Suppose we have a service type <tt>com.example.CodecSet</tt> which is
 * intended to represent sets of encoder/decoder pairs for some protocol.  In
 * this case it is an abstract class with two abstract methods:
 *
 * <blockquote><pre>
 * public abstract Encoder getEncoder(String encodingName);
 * public abstract Decoder getDecoder(String encodingName);</pre></blockquote>
 *
 * Each method returns an appropriate object or <tt>null</tt> if the provider
 * does not support the given encoding.  Typical providers support more than
 * one encoding.
 *
 * <p> The <tt>CodecSet</tt> class creates and saves a single service instance
 * at initialization:
 *
 * <pre>{@code
 * private static ServiceLoader<CodecSet> codecSetLoader
 *     = ServiceLoader.load(CodecSet.class);
 * }</pre>
 *
 * <p> To locate an encoder for a given encoding name it defines a static
 * factory method which iterates through the known and available providers,
 * returning only when it has located a suitable encoder or has run out of
 * providers.
 *
 * <pre>{@code
 * public static Encoder getEncoder(String encodingName) {
 *     for (CodecSet cp : codecSetLoader) {
 *         Encoder enc = cp.getEncoder(encodingName);
 *         if (enc != null)
 *             return enc;
 *     }
 *     return null;
 * }}</pre>
 *
 * <p> A {@code getDecoder} method is defined similarly.
 *
 * <p> If the code creating and using the service loader is developed as
 * a module then its module descriptor will declare the usage with:
 * <pre>{@code uses com.example.CodecSet;}</pre>
 *
 * <p> Now suppose that {@code com.example.impl.StandardCodecs} is an
 * implementation of the {@code CodecSet} service and developed as a module.
 * In that case then the module with the service provider module will declare
 * this in its module descriptor:
 * <pre>{@code provides com.example.CodecSet with com.example.impl.StandardCodecs;
 * }</pre>
 *
 * <p> On the other hand, suppose {@code com.example.impl.StandardCodecs} is
 * packaged in a JAR file for the class path then the JAR file will contain a
 * file named:
 * <pre>{@code META-INF/services/com.example.CodecSet}</pre>
 * that contains the single line:
 * <pre>{@code com.example.impl.StandardCodecs    # Standard codecs}</pre>
 *
 * <p><span style="font-weight: bold; padding-right: 1em">Usage Note</span> If
 * the class path of a class loader that is used for provider loading includes
 * remote network URLs then those URLs will be dereferenced in the process of
 * searching for provider-configuration files.
 *
 * <p> This activity is normal, although it may cause puzzling entries to be
 * created in web-server logs.  If a web server is not configured correctly,
 * however, then this activity may cause the provider-loading algorithm to fail
 * spuriously.
 *
 * <p> A web server should return an HTTP 404 (Not Found) response when a
 * requested resource does not exist.  Sometimes, however, web servers are
 * erroneously configured to return an HTTP 200 (OK) response along with a
 * helpful HTML error page in such cases.  This will cause a {@link
 * ServiceConfigurationError} to be thrown when this class attempts to parse
 * the HTML page as a provider-configuration file.  The best solution to this
 * problem is to fix the misconfigured web server to return the correct
 * response code (HTTP 404) along with the HTML error page.
 *
 * @param  <S>
 *         The type of the service to be loaded by this loader
 *
 * @author Mark Reinhold
 * @since 1.6
 */

public final class ServiceLoader<S>
    implements Iterable<S>
{
    // The class or interface representing the service being loaded
    private final Class<S> service;

    // The class of the service type
    private final String serviceName;

    // The module Layer used to locate providers; null when locating
    // providers using a class loader
    private final Layer layer;

    // The class loader used to locate, load, and instantiate providers;
    // null when locating provider using a module Layer
    private final ClassLoader loader;

    // The access control context taken when the ServiceLoader is created
    private final AccessControlContext acc;

    // The lazy-lookup iterator for iterator operations
    private Iterator<Provider<S>> lookupIterator1;
    private final List<S> instantiatedProviders = new ArrayList<>();

    // The lazy-lookup iterator for stream operations
    private Iterator<Provider<S>> lookupIterator2;
    private final List<Provider<S>> loadedProviders = new ArrayList<>();
    private boolean loadedAllProviders; // true when all providers loaded

    // Incremented when reload is called
    private int reloadCount;

    private static JavaLangAccess LANG_ACCESS;
    private static JavaLangReflectModuleAccess JLRM_ACCESS;
    static {
        LANG_ACCESS = SharedSecrets.getJavaLangAccess();
        JLRM_ACCESS = SharedSecrets.getJavaLangReflectModuleAccess();
    }


    /**
     * Represents a service provider located by {@code ServiceLoader}.
     *
     * <p> When using a loader's {@link ServiceLoader#stream() stream()} method
     * then the elements are of type {@code Provider}. This allows processing
     * to select or filter on the provider class without instantiating the
     * provider. </p>
     *
     * @param  <S> The service type
     * @since 9
     */
    public static interface Provider<S> extends Supplier<S> {
        /**
         * Returns the provider class. There is no guarantee that this type is
         * accessible and so attempting to instantiate it, by means of its
         * {@link Class#newInstance() newInstance()} method for example, will
         * fail when it is not accessible. The {@link #get() get()} method
         * should instead be used to obtain the provider.
         *
         * @return The provider class
         */
        Class<S> type();

        /**
         * Returns an instance of the provider.
         *
         * @return An instance of the provider.
         *
         * @throws ServiceConfigurationError
         *         If the service provider cannot be instantiated. The error
         *         cause will carry an appropriate cause.
         */
        @Override S get();
    }


    /**
     * Initializes a new instance of this class for locating service providers
     * in a module Layer.
     *
     * @throws ServiceConfigurationError
     *         If {@code svc} is not accessible to {@code caller} or that the
     *         caller's module does not declare that it uses the service type.
     */
    private ServiceLoader(Class<?> caller, Layer layer, Class<S> svc) {
        Objects.requireNonNull(caller);
        Objects.requireNonNull(layer);
        Objects.requireNonNull(svc);

        checkModule(caller.getModule(), svc);

        this.service = svc;
        this.serviceName = svc.getName();
        this.layer = layer;
        this.loader = null;
        this.acc = (System.getSecurityManager() != null)
                ? AccessController.getContext()
                : null;
    }

    /**
     * Initializes a new instance of this class for locating service providers
     * via a class loader.
     *
     * @throws ServiceConfigurationError
     *         If {@code svc} is not accessible to {@code caller} or that the
     *         caller's module does not declare that it uses the service type.
     */
    private ServiceLoader(Module callerModule, Class<S> svc, ClassLoader cl) {
        Objects.requireNonNull(svc);

        if (VM.isBooted()) {
            checkModule(callerModule, svc);
            if (cl == null) {
                cl = ClassLoader.getSystemClassLoader();
            }
        } else {

            // if we get here then it means that ServiceLoader is being used
            // before the VM initialization has completed. At this point then
            // only code in the java.base should be executing.
            Module base = Object.class.getModule();
            Module svcModule = svc.getModule();
            if (callerModule != base || svcModule != base) {
                fail(svc, "not accessible to " + callerModule + " during VM init");
            }

            // restricted to boot loader during startup
            cl = null;
        }

        this.service = svc;
        this.serviceName = svc.getName();
        this.layer = null;
        this.loader = cl;
        this.acc = (System.getSecurityManager() != null)
                ? AccessController.getContext()
                : null;
    }

    /**
     * Initializes a new instance of this class for locating service providers
     * via a class loader.
     *
     * @throws ServiceConfigurationError
     *         If {@code svc} is not accessible to {@code caller} or that the
     *         caller's module does not declare that it uses the service type.
     */
    private ServiceLoader(Class<?> caller, Class<S> svc, ClassLoader cl) {
        this(caller.getModule(), svc, cl);
    }


    /**
     * Checks that the given service type is accessible to types in the given
     * module, and check that the module declare that it uses the service type.
     */
    private static void checkModule(Module module, Class<?> svc) {

        // Check that the service type is in a package that is
        // exported to the caller.
        if (!Reflection.verifyModuleAccess(module, svc)) {
            fail(svc, "not accessible to " + module);
        }

        // If the caller is in a named module then it should "uses" the
        // service type
        if (!module.canUse(svc)) {
            fail(svc, "use not declared in " + module);
        }

    }

    private static void fail(Class<?> service, String msg, Throwable cause)
        throws ServiceConfigurationError
    {
        throw new ServiceConfigurationError(service.getName() + ": " + msg,
                                            cause);
    }

    private static void fail(Class<?> service, String msg)
        throws ServiceConfigurationError
    {
        throw new ServiceConfigurationError(service.getName() + ": " + msg);
    }

    private static void fail(Class<?> service, URL u, int line, String msg)
        throws ServiceConfigurationError
    {
        fail(service, u + ":" + line + ": " + msg);
    }

    /**
     * Uses Class.forName to load a provider in a module.
     *
     * @throws ServiceConfigurationError
     *         If the class cannot be loaded or is not a subtype of service
     */
    private Class<S> loadProviderInModule(Module module, String cn) {
        Class<?> clazz = null;
        if (acc == null) {
            try {
                clazz = Class.forName(module, cn);
            } catch (LinkageError e) {
                fail(service, "Unable to load " + cn, e);
            }
        } else {
            PrivilegedExceptionAction<Class<?>> pa = () -> Class.forName(module, cn);
            try {
                clazz = AccessController.doPrivileged(pa);
            } catch (PrivilegedActionException pae) {
                Throwable x = pae.getCause();
                fail(service, "Unable to load " + cn, x);
                return null;
            }
        }
        if (clazz == null)
            fail(service, "Provider " + cn  + " not found");
        if (!service.isAssignableFrom(clazz))
            fail(service, "Provider " + cn  + " not a subtype");
        @SuppressWarnings("unchecked")
        Class<S> result = (Class<S>) clazz;
        return result;
    }

    /**
     * A Provider implementation that supports invoking, with reduced
     * permissions, the provider's static factory method or its no-arg
     * constructor.
     */
    private final static class ProviderImpl<S> implements Provider<S> {
        final Class<?> service;
        final Class<S> type;
        final AccessControlContext acc;

        Method factoryMethod; // cached
        Constructor<S> ctor;

        /**
         * @throws ServiceConfigurationError
         *         If the provider class is not public
         */
        ProviderImpl(Class<?> service, Class<S> type, AccessControlContext acc) {
            int mods = type.getModifiers();
            if (!Modifier.isPublic(mods)) {
                fail(service, "Provider " + type + " is not public");
            }
            this.service = service;
            this.type = type;
            this.acc = acc;
        }

        @Override
        public Class<S> type() {
            return type;
        }

        @Override
        public S get() {
            if (factoryMethod == null && ctor == null) {
                if (isExplicitModule()) {
                    // look for the static "provider" method when the provider
                    // is an explicit named module
                    factoryMethod = getFactoryMethod();
                }
                if (factoryMethod == null) {
                    ctor = getConstructor();
                }
            }
            if (factoryMethod != null) {
                return invokeFactoryMethod();
            } else {
                return newInstance();
            }
        }

        /**
         * Returns {@code true} if the provider is in an explicit module
         */
        private boolean isExplicitModule() {
            Module module = type.getModule();
            return module.isNamed() && !module.getDescriptor().isAutomatic();
        }

        /**
         * Returns the method for the provider's "{@code public S provider()}"
         * method or {@code null} if the method does not exist.
         *
         * @throws ServiceConfigurationError if the provider defines the
         *         method but it's not static or public
         */
        private Method getFactoryMethod() {
            PrivilegedAction<Method> pa = new PrivilegedAction<>() {
                @Override
                public Method run() {
                    try {
                        // TBD: invoke getMethod0 directly to avoid exception
                        Method m = type.getMethod("provider");
                        m.setAccessible(true);
                        return m;
                    } catch (NoSuchMethodException e) {
                        return null;
                    }
                }
            };
            Method m = AccessController.doPrivileged(pa);
            if (m != null) {
                int mods = m.getModifiers();
                if (!Modifier.isStatic(mods)) {
                    fail(service, m + " is not a static method");
                }
                Class<?> returnType = m.getReturnType();
                if (!service.isAssignableFrom(returnType)) {
                    fail(service, m + " returns " + returnType);
                }
            }
            return m;
        }

        /**
         * Returns the provider's public no-arg constructor.
         *
         * @throws ServiceConfigurationError if the provider does not have
         *         public no-arg constructor
         */
        private Constructor<S> getConstructor() {
            PrivilegedAction<Constructor<S>> pa = new PrivilegedAction<>() {
                @Override
                public Constructor<S> run() {
                    Constructor<S> ctor = null;
                    try {
                        ctor = type.getConstructor();
                    } catch (NoSuchMethodException e) { }
                    if (ctor != null && isExplicitModule())
                        ctor.setAccessible(true);
                    return ctor;
                }
            };
            Constructor<S> ctor = AccessController.doPrivileged(pa);
            if (ctor == null) {
                fail(service, "Provider " + type
                     + " does not have a public no-arg constructor");
            }
            return ctor;
        }

        /**
         * Invokes the provider's "provider" method to instantiate a provider.
         * When running with a security manager then the method runs with
         * permissions that are restricted by the security context of whatever
         * created this loader.
         */
        @SuppressWarnings("unchecked")
        private S invokeFactoryMethod() {
            S p = null;
            Throwable exc = null;
            if (acc == null) {
                try {
                    p = (S) factoryMethod.invoke(null);
                } catch (Throwable x) {
                    exc = x;
                }
            } else {
                PrivilegedExceptionAction<S> pa = new PrivilegedExceptionAction<>() {
                    @Override
                    public S run() throws Exception {
                        return (S) factoryMethod.invoke(null);
                    }
                };
                // invoke factory method with permissions restricted by acc
                try {
                    p = AccessController.doPrivileged(pa, acc);
                } catch (PrivilegedActionException pae) {
                    exc = pae.getCause();
                }
            }
            if (exc != null) {
                if (exc instanceof InvocationTargetException)
                    exc = exc.getCause();
                String cn = type.getName();
                fail(service, "Provider " + cn + " could not be obtained", exc);
            }
            if (p == null) {
                fail(service, factoryMethod + " returned null");
            }
            return p;
        }

        /**
         * Invokes Constructor::newInstance to instantiate a provider. When running
         * with a security manager then the constructor runs with permissions that
         * are restricted by the security context of whatever created this loader.
         */
        private S newInstance() {
            S p = null;
            Throwable exc = null;
            if (acc == null) {
                try {
                    p = ctor.newInstance();
                } catch (Throwable x) {
                    exc = x;
                }
            } else {
                PrivilegedExceptionAction<S> pa = new PrivilegedExceptionAction<>() {
                    @Override
                    public S run() throws Exception {
                        return ctor.newInstance();
                    }
                };
                // invoke constructor with permissions restricted by acc
                try {
                    p = AccessController.doPrivileged(pa, acc);
                } catch (PrivilegedActionException pae) {
                    exc = pae.getCause();
                }
            }
            if (exc != null) {
                if (exc instanceof InvocationTargetException)
                    exc = exc.getCause();
                String cn = type.getName();
                fail(service,
                     "Provider " + cn + " could not be instantiated", exc);
            }
            return p;
        }

        // For now, equals/hashCode uses the access control context to ensure
        // that two Providers created with different contexts are not equal
        // when running with a security manager.

        @Override
        public int hashCode() {
            return Objects.hash(type, acc);
        }

        @Override
        public boolean equals(Object ob) {
            if (!(ob instanceof ProviderImpl))
                return false;
            @SuppressWarnings("unchecked")
            ProviderImpl<?> that = (ProviderImpl<?>)ob;
            return this.type == that.type
                    && Objects.equals(this.acc, that.acc);
        }
    }

    /**
     * Implements lazy service provider lookup of service providers that
     * are provided by modules in a module Layer (or parent layers)
     */
    private final class LayerLookupIterator<T>
        implements Iterator<Provider<T>>
    {
        Layer currentLayer;
        Iterator<ServiceProvider> iterator;
        ServiceProvider nextProvider;

        LayerLookupIterator() {
            currentLayer = layer;

            // need to get us started
            iterator = providers(currentLayer);
        }

        private Iterator<ServiceProvider> providers(Layer layer) {
            ServicesCatalog catalog = JLRM_ACCESS.getServicesCatalog(layer);
            return catalog.findServices(serviceName).iterator();
        }

        @Override
        public boolean hasNext() {

            // already have the next provider cached
            if (nextProvider != null)
                return true;

            while (true) {

                // next provider
                if (iterator != null && iterator.hasNext()) {
                    nextProvider = iterator.next();
                    return true;
                }

                // next layer
                Layer parent = currentLayer.parent().orElse(null);
                if (parent == null)
                    return false;

                currentLayer = parent;
                iterator = providers(currentLayer);
            }
        }

        @Override
        public Provider<T> next() {
            if (!hasNext())
                throw new NoSuchElementException();

            ServiceProvider provider = nextProvider;
            nextProvider = null;

            // attempt to load the provider
            Module module = provider.module();
            String cn = provider.providerName();
            Class<?> c = loadProviderInModule(module, cn);

            @SuppressWarnings("unchecked")
            Class<T> clazz = (Class<T>) c;
            return new ProviderImpl<T>(service, clazz, acc);
        }
    }

    /**
     * Implements lazy service provider lookup of service providers that
     * are provided by modules defined to a class loader or to modules in
     * layers with a module defined to the class loader.
     */
    private final class ModuleServicesLookupIterator<T>
        implements Iterator<Provider<T>>
    {
        ClassLoader currentLoader;
        Iterator<ServiceProvider> iterator;
        ServiceProvider nextProvider;

        ModuleServicesLookupIterator() {
            this.currentLoader = loader;
            this.iterator = iteratorFor(loader);
        }

        /**
         * Returns iterator to iterate over the implementations of {@code
         * service} in the given layer.
         */
        private Set<ServiceProvider> providers(Layer layer) {
            ServicesCatalog catalog = JLRM_ACCESS.getServicesCatalog(layer);
            return catalog.findServices(serviceName);
        }

        /**
         * Returns an iterator to iterate over the implementations of {@code
         * service} in modules defined to the given class loader or in custom
         * layers with a module defined to this class loader.
         */
        private Iterator<ServiceProvider> iteratorFor(ClassLoader loader) {

            // modules defined to this class loader
            ServicesCatalog catalog;
            if (loader == null) {
                catalog = BootLoader.getServicesCatalog();
            } else {
                catalog = LANG_ACCESS.getServicesCatalog(loader);
            }
            Stream<ServiceProvider> stream1;
            if (catalog == null) {
                stream1 = Stream.empty();
            } else {
                stream1 = catalog.findServices(serviceName).stream();
            }

            // modules in custom layers that define modules to the class loader
            Stream<ServiceProvider> stream2;
            if (loader == null) {
                stream2 = Stream.empty();
            } else {
                Layer bootLayer = Layer.boot();
                stream2 = loader.layers()
                        .filter(l -> (l != bootLayer))
                        .map(l -> providers(l))
                        .flatMap(Set::stream);
            }

            return Stream.concat(stream1, stream2).iterator();
        }

        @Override
        public boolean hasNext() {
            // already have the next provider cached
            if (nextProvider != null)
                return true;

            while (true) {
                if (iterator.hasNext()) {
                    nextProvider = iterator.next();
                    return true;
                }

                // move to the next class loader if possible
                if (currentLoader == null) {
                    return false;
                } else {
                    currentLoader = currentLoader.getParent();
                    iterator = iteratorFor(currentLoader);
                }
            }
        }

        @Override
        public Provider<T> next() {
            if (!hasNext())
                throw new NoSuchElementException();

            ServiceProvider provider = nextProvider;
            nextProvider = null;

            // attempt to load the provider
            Module module = provider.module();
            String cn = provider.providerName();
            Class<?> c = loadProviderInModule(module, cn);

            @SuppressWarnings("unchecked")
            Class<T> clazz = (Class<T>) c;
            return new ProviderImpl<T>(service, clazz, acc);
        }
    }

    /**
     * Implements lazy service provider lookup where the service providers are
     * configured via service configuration files. Service providers in named
     * modules are silently ignored by this lookup iterator.
     */
    private final class LazyClassPathLookupIterator<T>
        implements Iterator<Provider<T>>
    {
        static final String PREFIX = "META-INF/services/";

        Enumeration<URL> configs;
        Iterator<String> pending;
        Class<T> next;

        /**
         * Parse a single line from the given configuration file, adding the
         * name on the line to the names list.
         */
        private int parseLine(URL u, BufferedReader r, int lc, Set<String> names)
            throws IOException
        {
            String ln = r.readLine();
            if (ln == null) {
                return -1;
            }
            int ci = ln.indexOf('#');
            if (ci >= 0) ln = ln.substring(0, ci);
            ln = ln.trim();
            int n = ln.length();
            if (n != 0) {
                if ((ln.indexOf(' ') >= 0) || (ln.indexOf('\t') >= 0))
                    fail(service, u, lc, "Illegal configuration-file syntax");
                int cp = ln.codePointAt(0);
                if (!Character.isJavaIdentifierStart(cp))
                    fail(service, u, lc, "Illegal provider-class name: " + ln);
                int start = Character.charCount(cp);
                for (int i = start; i < n; i += Character.charCount(cp)) {
                    cp = ln.codePointAt(i);
                    if (!Character.isJavaIdentifierPart(cp) && (cp != '.'))
                        fail(service, u, lc, "Illegal provider-class name: " + ln);
                }
                names.add(ln);
            }
            return lc + 1;
        }

        /**
         * Parse the content of the given URL as a provider-configuration file.
         */
        private Iterator<String> parse(URL u) {
            Set<String> names = new LinkedHashSet<>(); // preserve insertion order
            try {
                URLConnection uc = u.openConnection();
                uc.setUseCaches(false);
                try (InputStream in = uc.getInputStream();
                     BufferedReader r
                         = new BufferedReader(new InputStreamReader(in, "utf-8")))
                {
                    int lc = 1;
                    while ((lc = parseLine(u, r, lc, names)) >= 0);
                }
            } catch (IOException x) {
                fail(service, "Error accessing configuration file", x);
            }
            return names.iterator();
        }

        @SuppressWarnings("unchecked")
        private boolean hasNextService() {
            if (next != null) {
                return true;
            }

            Class<?> clazz = null;
            do {
                if (configs == null) {
                    try {
                        String fullName = PREFIX + service.getName();
                        if (loader == null)
                            configs = ClassLoader.getSystemResources(fullName);
                        else
                            configs = loader.getResources(fullName);
                    } catch (IOException x) {
                        fail(service, "Error locating configuration files", x);
                    }
                }
                while ((pending == null) || !pending.hasNext()) {
                    if (!configs.hasMoreElements()) {
                        return false;
                    }
                    pending = parse(configs.nextElement());
                }
                String cn = pending.next();
                try {
                    clazz = Class.forName(cn, false, loader);
                } catch (ClassNotFoundException x) {
                    fail(service, "Provider " + cn + " not found");
                }

                if (!service.isAssignableFrom(clazz)) {
                    fail(service, "Provider " + cn  + " not a subtype");
                }

            } while (clazz.getModule().isNamed()); // ignore if in named module

            next = (Class<T>) clazz;
            return true;
        }

        @Override
        public boolean hasNext() {
            if (acc == null) {
                return hasNextService();
            } else {
                PrivilegedAction<Boolean> action = new PrivilegedAction<>() {
                    public Boolean run() { return hasNextService(); }
                };
                return AccessController.doPrivileged(action, acc);
            }
        }

        @Override
        public Provider<T> next() {
            if (!hasNext())
                throw new NoSuchElementException();

            Class<T> clazz = next;
            next = null;

            // Provider::get will invoke constructor will reduced permissions
            return new ProviderImpl<T>(service, clazz, acc);
        }
    }

    /**
     * Returns a new lookup iterator.
     */
    private Iterator<Provider<S>> newLookupIterator() {
        assert layer == null || loader == null;
        if (layer != null) {
            return new LayerLookupIterator<>();
        } else {
            Iterator<Provider<S>> first = new ModuleServicesLookupIterator<>();
            Iterator<Provider<S>> second = new LazyClassPathLookupIterator<>();
            return new Iterator<Provider<S>>() {
                @Override
                public boolean hasNext() {
                    return (first.hasNext() || second.hasNext());
                }
                @Override
                public Provider<S> next() {
                    if (first.hasNext()) {
                        return first.next();
                    } else if (second.hasNext()) {
                        return second.next();
                    } else {
                        throw new NoSuchElementException();
                    }
                }
            };
        }
    }

    /**
     * Lazily load and instantiate the available providers of this loader's
     * service.
     *
     * <p> The iterator returned by this method first yields all of the
     * elements of the provider cache, in the order that they were loaded.
     * It then lazily loads and instantiates any remaining providers,
     * adding each one to the cache in turn.
     *
     * <p> To achieve laziness the actual work of locating and instantiating
     * providers must be done by the iterator itself. Its {@link
     * java.util.Iterator#hasNext hasNext} and {@link java.util.Iterator#next
     * next} methods can therefore throw a {@link ServiceConfigurationError}
     * if a provider class cannot be loaded, doesn't have an appropriate static
     * factory method or constructor, can't be assigned to the service type or
     * if any other kind of exception or error is thrown as the next provider
     * is located and instantiated. To write robust code it is only necessary
     * to catch {@link ServiceConfigurationError} when using a service iterator.
     *
     * <p> If such an error is thrown then subsequent invocations of the
     * iterator will make a best effort to locate and instantiate the next
     * available provider, but in general such recovery cannot be guaranteed.
     *
     * <blockquote style="font-size: smaller; line-height: 1.2"><span
     * style="padding-right: 1em; font-weight: bold">Design Note</span>
     * Throwing an error in these cases may seem extreme.  The rationale for
     * this behavior is that a malformed provider-configuration file, like a
     * malformed class file, indicates a serious problem with the way the Java
     * virtual machine is configured or is being used.  As such it is
     * preferable to throw an error rather than try to recover or, even worse,
     * fail silently.</blockquote>
     *
     * <p> If this loader's provider caches are cleared by invoking the {@link
     * #reload() reload} method then existing iterators for this service
     * loader should be discarded.
     * The {@link java.util.Iterator#hasNext() hasNext} and {@link
     * java.util.Iterator#next() next} methods of the iterator throw {@link
     * java.util.ConcurrentModificationException ConcurrentModificationException}
     * if used after the provider cache has been cleared.
     *
     * <p> The iterator returned by this method does not support removal.
     * Invoking its {@link java.util.Iterator#remove() remove} method will
     * cause an {@link UnsupportedOperationException} to be thrown.
     *
     * @return  An iterator that lazily loads providers for this loader's
     *          service
     */
    public Iterator<S> iterator() {

        // create lookup iterator if needed
        if (lookupIterator1 == null) {
            lookupIterator1 = newLookupIterator();
        }

        return new Iterator<S>() {

            // record reload count
            final int expectedReloadCount = ServiceLoader.this.reloadCount;

            // index into the cached providers list
            int index;

            /**
             * Throws ConcurrentModificationException if the list of cached
             * providers has been cleared by reload.
             */
            private void checkReloadCount() {
                if (ServiceLoader.this.reloadCount != expectedReloadCount)
                    throw new ConcurrentModificationException();
            }

            @Override
            public boolean hasNext() {
                checkReloadCount();
                if (index < instantiatedProviders.size())
                    return true;
                return lookupIterator1.hasNext();
            }

            @Override
            @SuppressWarnings("unchecked")
            public S next() {
                checkReloadCount();
                S next;
                if (index < instantiatedProviders.size()) {
                    next = instantiatedProviders.get(index);
                } else {
                    next = lookupIterator1.next().get();
                    instantiatedProviders.add(next);
                }
                index++;
                return next;
            }

        };
    }

    /**
     * Returns a stream that lazily loads the available providers of this
     * loader's service. The stream elements are of type {@link Provider
     * Provider}, the {@code Provider}'s {@link Provider#get() get} method
     * must be invoked to get or instantiate the provider.
     *
     * <p> When processing the stream then providers that were previously
     * loaded by stream operations are processed first, in load order. It then
     * lazily loads any remaining providers. If a provider class cannot be
     * loaded, can't be assigned to the service type, or some other error is
     * thrown when locating the provider then it is wrapped with a {@code
     * ServiceConfigurationError} and thrown by whatever method caused the
     * provider to be loaded. </p>
     *
     * <p> If this loader's provider caches are cleared by invoking the {@link
     * #reload() reload} method then existing streams for this service
     * loader should be discarded. </p>
     *
     * <p> The following examples demonstrate usage. The first example
     * creates a stream of providers, the second example is the same except
     * that it sorts the providers by provider class name (and so locate all
     * providers).
     * <pre>{@code
     *    Stream<CodecSet> providers = ServiceLoader.load(CodecSet.class)
     *            .stream()
     *            .map(Provider::get);
     *
     *    Stream<CodecSet> providers = ServiceLoader.load(CodecSet.class)
     *            .stream()
     *            .sorted(Comparator.comparing(p -> p.type().getName()))
     *            .map(Provider::get);
     * }</pre>
     *
     * @return  A stream that lazily loads providers for this loader's service
     *
     * @since 9
     */
    public Stream<Provider<S>> stream() {
        // use cached providers as the source when all providers loaded
        if (loadedAllProviders) {
            return loadedProviders.stream();
        }

        // create lookup iterator if needed
        if (lookupIterator2 == null) {
            lookupIterator2 = newLookupIterator();
        }

        // use lookup iterator and cached providers as source
        Spliterator<Provider<S>> s = new ProviderSpliterator<>(lookupIterator2);
        return StreamSupport.stream(s, false);
    }

    private class ProviderSpliterator<T> implements Spliterator<Provider<T>> {
        final int expectedReloadCount = ServiceLoader.this.reloadCount;
        final Iterator<Provider<T>> iterator;
        int index;

        ProviderSpliterator(Iterator<Provider<T>> iterator) {
            this.iterator = iterator;
        }

        @Override
        public Spliterator<Provider<T>> trySplit() {
            return null;
        }

        @Override
        @SuppressWarnings("unchecked")
        public boolean tryAdvance(Consumer<? super Provider<T>> action) {
            if (ServiceLoader.this.reloadCount != expectedReloadCount)
                throw new ConcurrentModificationException();
            Provider<T> next = null;
            if (index < loadedProviders.size()) {
                next = (Provider<T>) loadedProviders.get(index);
            } else if (iterator.hasNext()) {
                next = iterator.next();
            } else {
                loadedAllProviders = true;
            }
            index++;
            if (next != null) {
                action.accept(next);
                return true;
            } else {
                return false;
            }
        }

        @Override
        public int characteristics() {
            // need to decide on DISTINCT
            // not IMMUTABLE as structural interference possible
            return Spliterator.ORDERED + Spliterator.NONNULL;
        }

        @Override
        public long estimateSize() {
            return Long.MAX_VALUE;
        }
    }

    /**
     * Creates a new service loader for the given service type, class
     * loader, and caller.
     *
     * @param  <S> the class of the service type
     *
     * @param  service
     *         The interface or abstract class representing the service
     *
     * @param  loader
     *         The class loader to be used to load provider-configuration files
     *         and provider classes, or <tt>null</tt> if the system class
     *         loader (or, failing that, the bootstrap class loader) is to be
     *         used
     *
     * @param  callerModule
     *         The caller's module for which a new service loader is created
     *
     * @return A new service loader
     */
    static <S> ServiceLoader<S> load(Class<S> service,
                                     ClassLoader loader,
                                     Module callerModule)
    {
        return new ServiceLoader<>(callerModule, service, loader);
    }

    /**
     * Creates a new service loader for the given service type and class
     * loader.
     *
     * @param  <S> the class of the service type
     *
     * @param  service
     *         The interface or abstract class representing the service
     *
     * @param  loader
     *         The class loader to be used to load provider-configuration files
     *         and provider classes, or {@code null} if the system class
     *         loader (or, failing that, the bootstrap class loader) is to be
     *         used
     *
     * @return A new service loader
     *
     * @throws ServiceConfigurationError
     *         if the service type is not accessible to the caller or the
     *         caller is in a named module and its module descriptor does
     *         not declare that it uses {@code service}
     */
    @CallerSensitive
    public static <S> ServiceLoader<S> load(Class<S> service,
                                            ClassLoader loader)
    {
        return new ServiceLoader<>(Reflection.getCallerClass(), service, loader);
    }

    /**
     * Creates a new service loader for the given service type, using the
     * current thread's {@linkplain java.lang.Thread#getContextClassLoader
     * context class loader}.
     *
     * <p> An invocation of this convenience method of the form
     * <pre>{@code
     * ServiceLoader.load(service)
     * }</pre>
     *
     * is equivalent to
     *
     * <pre>{@code
     * ServiceLoader.load(service, Thread.currentThread().getContextClassLoader())
     * }</pre>
     *
     * @apiNote Service loader objects obtained with this method should not be
     * cached VM-wide. For example, different applications in the same VM may
     * have different thread context class loaders. A lookup by one application
     * may locate a service provider that is only visible via its thread
     * context class loader and so is not suitable to be located by the other
     * application. Memory leaks can also arise.
     *
     * @param  <S> the class of the service type
     *
     * @param  service
     *         The interface or abstract class representing the service
     *
     * @return A new service loader
     *
     * @throws ServiceConfigurationError
     *         if the service type is not accessible to the caller or the
     *         caller is in a named module and its module descriptor does
     *         not declare that it uses {@code service}
     */
    @CallerSensitive
    public static <S> ServiceLoader<S> load(Class<S> service) {
        ClassLoader cl = Thread.currentThread().getContextClassLoader();
        return new ServiceLoader<>(Reflection.getCallerClass(), service, cl);
    }

    /**
     * Creates a new service loader for the given service type, using the
     * {@linkplain ClassLoader#getPlatformClassLoader() platform class loader}.
     *
     * <p> This convenience method is equivalent to: </p>
     *
     * <pre>{@code
     * ServiceLoader.load(service, ClassLoader.getPlatformClassLoader())
     * }</pre>
     *
     * <p> This method is intended for use when only installed providers are
     * desired.  The resulting service will only find and load providers that
     * have been installed into the current Java virtual machine; providers on
     * the application's module path or class path will be ignored.
     *
     * @param  <S> the class of the service type
     *
     * @param  service
     *         The interface or abstract class representing the service
     *
     * @return A new service loader
     *
     * @throws ServiceConfigurationError
     *         if the service type is not accessible to the caller or the
     *         caller is in a named module and its module descriptor does
     *         not declare that it uses {@code service}
     */
    @CallerSensitive
    public static <S> ServiceLoader<S> loadInstalled(Class<S> service) {
        ClassLoader cl = ClassLoader.getPlatformClassLoader();
        return new ServiceLoader<>(Reflection.getCallerClass(), service, cl);
    }

    /**
     * Creates a new service loader for the given service type that loads
     * service providers from modules in the given {@code Layer} and its
     * ancestors.
     *
     * @apiNote Unlike the other load methods defined here, the service type
     * is the second parameter. The reason for this is to avoid source
     * compatibility issues for code that uses {@code load(S, null)}.
     *
     * @param  <S> the class of the service type
     *
     * @param  layer
     *         The module Layer
     *
     * @param  service
     *         The interface or abstract class representing the service
     *
     * @return A new service loader
     *
     * @throws ServiceConfigurationError
     *         if the service type is not accessible to the caller or the
     *         caller is in a named module and its module descriptor does
     *         not declare that it uses {@code service}
     *
     * @since 9
     */
    @CallerSensitive
    public static <S> ServiceLoader<S> load(Layer layer, Class<S> service) {
        return new ServiceLoader<>(Reflection.getCallerClass(), layer, service);
    }

    /**
     * Load the first available provider of this loader's service. This
     * convenience method is equivalent to invoking the {@link #iterator()
     * iterator()} method and obtaining the first element. It therefore
     * returns the first element from the provider cache if possible, it
     * otherwise attempts to load and instantiate the first provider.
     *
     * @return The first provider or empty {@code Optional} if no providers
     *         are located
     *
     * @throws ServiceConfigurationError
     *         If a provider class cannot be loaded, doesn't have the
     *         appropriate static factory method or constructor, can't be
     *         assigned to the service type, or if any other kind of exception
     *         or error is thrown when locating or instantiating the provider.
     *
     * @since 9
     */
    public Optional<S> findFirst() {
        Iterator<S> iterator = iterator();
        if (iterator.hasNext()) {
            return Optional.of(iterator.next());
        } else {
            return Optional.empty();
        }
    }

    /**
     * Clear this loader's provider cache so that all providers will be
     * reloaded.
     *
     * <p> After invoking this method, subsequent invocations of the {@link
     * #iterator() iterator} or {@link #stream() stream} methods will lazily
     * look up providers (and instantiate in the case of {@code iterator})
     * from scratch, just as is done by a newly-created loader.
     *
     * <p> This method is intended for use in situations in which new providers
     * can be installed into a running Java virtual machine.
     */
    public void reload() {
        lookupIterator1 = null;
        instantiatedProviders.clear();

        lookupIterator2 = null;
        loadedProviders.clear();
        loadedAllProviders = false;

        // increment count to allow CME be thrown
        reloadCount++;
    }

    /**
     * Returns a string describing this service.
     *
     * @return  A descriptive string
     */
    public String toString() {
        return "java.util.ServiceLoader[" + service.getName() + "]";
    }

}
