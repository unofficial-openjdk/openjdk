/*
 * Copyright (c) 1999, 2013, Oracle and/or its affiliates. All rights reserved.
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

package java.lang.reflect;

import java.lang.ref.WeakReference;
import java.security.AccessController;
import java.security.PrivilegedAction;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BiFunction;
import java.util.stream.Stream;
import java.util.stream.Collectors;

import sun.misc.BootLoader;
import sun.misc.Modules;
import sun.misc.ProxyGenerator;
import sun.misc.VM;
import sun.reflect.CallerSensitive;
import sun.reflect.Reflection;
import sun.reflect.misc.ReflectUtil;
import sun.security.util.SecurityConstants;

/**
 * {@code Proxy} provides static methods for creating dynamic proxy
 * classes and instances, and it is also the superclass of all
 * dynamic proxy classes created by those methods.
 *
 * <p>To create a proxy for some interface {@code Foo}:
 * <pre>
 *     InvocationHandler handler = new MyInvocationHandler(...);
 *     Class&lt;?&gt; proxyClass = Proxy.getProxyClass(Foo.class.getClassLoader(), Foo.class);
 *     Foo f = (Foo) proxyClass.getConstructor(InvocationHandler.class).
 *                     newInstance(handler);
 * </pre>
 * or more simply:
 * <pre>
 *     Foo f = (Foo) Proxy.newProxyInstance(Foo.class.getClassLoader(),
 *                                          new Class&lt;?&gt;[] { Foo.class },
 *                                          handler);
 * </pre>
 *
 * <p>To create a proxy in a named module, use the
 * {@link Proxy#getModuleProxyClass(Module, Class[])} method.
 *
 * <p>A <i>dynamic proxy class</i> (simply referred to as a <i>proxy
 * class</i> below) is a class that implements a list of interfaces
 * specified at runtime when the class is created, with behavior as
 * described below.
 *
 * A <i>proxy interface</i> is such an interface that is implemented
 * by a proxy class.
 *
 * A <i>proxy instance</i> is an instance of a proxy class.
 *
 * Each proxy instance has an associated <i>invocation handler</i>
 * object, which implements the interface {@link InvocationHandler}.
 * A method invocation on a proxy instance through one of its proxy
 * interfaces will be dispatched to the {@link InvocationHandler#invoke
 * invoke} method of the instance's invocation handler, passing the proxy
 * instance, a {@code java.lang.reflect.Method} object identifying
 * the method that was invoked, and an array of type {@code Object}
 * containing the arguments.  The invocation handler processes the
 * encoded method invocation as appropriate and the result that it
 * returns will be returned as the result of the method invocation on
 * the proxy instance.
 *
 * <p>A proxy class has the following properties:
 *
 * <ul>
 * <li>Proxy classes are <em>public, final, and not abstract</em> if
 * all proxy interfaces are public.</li>
 *
 * <li>Proxy classes are <em>non-public, final, and not abstract</em> if
 * any of the proxy interfaces is non-public.</li>
 *
 * <li>The unqualified name of a proxy class is unspecified.  The space
 * of class names that begin with the string {@code "$Proxy"}
 * should be, however, reserved for proxy classes.
 *
 * <li>A proxy class extends {@code java.lang.reflect.Proxy}.
 *
 * <li>A proxy class implements exactly the interfaces specified at its
 * creation, in the same order.
 *
 * <li>If a proxy class implements a non-public interface, then it will
 * be defined in the same package as that interface.  Otherwise, the
 * package of a proxy class is also unspecified.  Note that package
 * sealing will not prevent a proxy class from being successfully defined
 * in a particular package at runtime, and neither will classes already
 * defined by the same class loader and the same package with particular
 * signers.
 *
 * <li>Since a proxy class implements all of the interfaces specified at
 * its creation, invoking {@code getInterfaces} on its
 * {@code Class} object will return an array containing the same
 * list of interfaces (in the order specified at its creation), invoking
 * {@code getMethods} on its {@code Class} object will return
 * an array of {@code Method} objects that include all of the
 * methods in those interfaces, and invoking {@code getMethod} will
 * find methods in the proxy interfaces as would be expected.
 *
 * <li>The {@link Proxy#isProxyClass Proxy.isProxyClass} method will
 * return true if it is passed a proxy class-- a class returned by
 * {@code Proxy.getProxyClass} or the class of an object returned by
 * {@code Proxy.newProxyInstance}-- and false otherwise.
 *
 * <li>The {@code java.security.ProtectionDomain} of a proxy class
 * is the same as that of system classes loaded by the bootstrap class
 * loader, such as {@code java.lang.Object}, because the code for a
 * proxy class is generated by trusted system code.  This protection
 * domain will typically be granted
 * {@code java.security.AllPermission}.
 *
 * <li>Each proxy class has one public constructor that takes one argument,
 * an implementation of the interface {@link InvocationHandler}, to set
 * the invocation handler for a proxy instance.  Rather than having to use
 * the reflection API to access the public constructor, a proxy instance
 * can be also be created by calling the {@link Proxy#newProxyInstance
 * Proxy.newProxyInstance} method, which combines the actions of calling
 * {@link Proxy#getProxyClass Proxy.getProxyClass} with invoking the
 * constructor with an invocation handler.
 * </ul>
 *
 * <p>A proxy instance has the following properties:
 *
 * <ul>
 * <li>Given a proxy instance {@code proxy} and one of the
 * interfaces implemented by its proxy class {@code Foo}, the
 * following expression will return true:
 * <pre>
 *     {@code proxy instanceof Foo}
 * </pre>
 * and the following cast operation will succeed (rather than throwing
 * a {@code ClassCastException}):
 * <pre>
 *     {@code (Foo) proxy}
 * </pre>
 *
 * <li>Each proxy instance has an associated invocation handler, the one
 * that was passed to its constructor.  The static
 * {@link Proxy#getInvocationHandler Proxy.getInvocationHandler} method
 * will return the invocation handler associated with the proxy instance
 * passed as its argument.
 *
 * <li>An interface method invocation on a proxy instance will be
 * encoded and dispatched to the invocation handler's {@link
 * InvocationHandler#invoke invoke} method as described in the
 * documentation for that method.
 *
 * <li>An invocation of the {@code hashCode},
 * {@code equals}, or {@code toString} methods declared in
 * {@code java.lang.Object} on a proxy instance will be encoded and
 * dispatched to the invocation handler's {@code invoke} method in
 * the same manner as interface method invocations are encoded and
 * dispatched, as described above.  The declaring class of the
 * {@code Method} object passed to {@code invoke} will be
 * {@code java.lang.Object}.  Other public methods of a proxy
 * instance inherited from {@code java.lang.Object} are not
 * overridden by a proxy class, so invocations of those methods behave
 * like they do for instances of {@code java.lang.Object}.
 * </ul>
 *
 * <h3>Methods Duplicated in Multiple Proxy Interfaces</h3>
 *
 * <p>When two or more interfaces of a proxy class contain a method with
 * the same name and parameter signature, the order of the proxy class's
 * interfaces becomes significant.  When such a <i>duplicate method</i>
 * is invoked on a proxy instance, the {@code Method} object passed
 * to the invocation handler will not necessarily be the one whose
 * declaring class is assignable from the reference type of the interface
 * that the proxy's method was invoked through.  This limitation exists
 * because the corresponding method implementation in the generated proxy
 * class cannot determine which interface it was invoked through.
 * Therefore, when a duplicate method is invoked on a proxy instance,
 * the {@code Method} object for the method in the foremost interface
 * that contains the method (either directly or inherited through a
 * superinterface) in the proxy class's list of interfaces is passed to
 * the invocation handler's {@code invoke} method, regardless of the
 * reference type through which the method invocation occurred.
 *
 * <p>If a proxy interface contains a method with the same name and
 * parameter signature as the {@code hashCode}, {@code equals},
 * or {@code toString} methods of {@code java.lang.Object},
 * when such a method is invoked on a proxy instance, the
 * {@code Method} object passed to the invocation handler will have
 * {@code java.lang.Object} as its declaring class.  In other words,
 * the public, non-final methods of {@code java.lang.Object}
 * logically precede all of the proxy interfaces for the determination of
 * which {@code Method} object to pass to the invocation handler.
 *
 * <p>Note also that when a duplicate method is dispatched to an
 * invocation handler, the {@code invoke} method may only throw
 * checked exception types that are assignable to one of the exception
 * types in the {@code throws} clause of the method in <i>all</i> of
 * the proxy interfaces that it can be invoked through.  If the
 * {@code invoke} method throws a checked exception that is not
 * assignable to any of the exception types declared by the method in one
 * of the proxy interfaces that it can be invoked through, then an
 * unchecked {@code UndeclaredThrowableException} will be thrown by
 * the invocation on the proxy instance.  This restriction means that not
 * all of the exception types returned by invoking
 * {@code getExceptionTypes} on the {@code Method} object
 * passed to the {@code invoke} method can necessarily be thrown
 * successfully by the {@code invoke} method.
 *
 * @author      Peter Jones
 * @see         InvocationHandler
 * @since       1.3
 */
public class Proxy implements java.io.Serializable {
    private static final long serialVersionUID = -2222568056686623797L;

    /** parameter types of a proxy class constructor */
    private static final Class<?>[] constructorParams =
        { InvocationHandler.class };

    /**
     * the invocation handler for this proxy instance.
     * @serial
     */
    protected InvocationHandler h;

    /**
     * Prohibits instantiation.
     */
    private Proxy() {
    }

    /**
     * Constructs a new {@code Proxy} instance from a subclass
     * (typically, a dynamic proxy class) with the specified value
     * for its invocation handler.
     *
     * @param  h the invocation handler for this proxy instance
     *
     * @throws NullPointerException if the given invocation handler, {@code h},
     *         is {@code null}.
     */
    protected Proxy(InvocationHandler h) {
        Objects.requireNonNull(h);
        this.h = h;
    }

    /**
     * Returns the {@code java.lang.Class} object for a proxy class
     * given a class loader and an array of interfaces.  The proxy class
     * will be defined by the specified class loader and will implement
     * all of the supplied interfaces.  If any of the given interfaces
     * is non-public, the proxy class will be non-public. If a proxy class
     * for the same permutation of interfaces has already been defined by the
     * class loader, then the existing proxy class will be returned; otherwise,
     * a proxy class for those interfaces will be generated dynamically
     * and defined by the class loader.
     *
     * <p>All proxy interfaces must be visible to the given class loader
     * and also accessible to the proxy class; otherwise {@code IllegalArgumentException}
     * will be thrown.
     *
     * <p>There are several restrictions on the parameters that may be
     * passed to {@code Proxy.getProxyClass}:
     *
     * <ul>
     * <li>All of the {@code Class} objects in the
     * {@code interfaces} array must represent interfaces, not
     * classes or primitive types.
     *
     * <li>No two elements in the {@code interfaces} array may
     * refer to identical {@code Class} objects.
     *
     * <li>All of the interface types must be visible by name through the
     * specified class loader.  In other words, for class loader
     * {@code cl} and every interface {@code i}, the following
     * expression must be true:
     * <pre>
     *     Class.forName(i.getName(), false, cl) == i
     * </pre>
     *
     * <li>All non-public interfaces must be in the same package;
     * otherwise, it would not be possible for the proxy class to
     * implement all of the interfaces, regardless of what package it is
     * defined in.
     *
     * <li>For any set of member methods of the specified interfaces
     * that have the same signature:
     * <ul>
     * <li>If the return type of any of the methods is a primitive
     * type or void, then all of the methods must have that same
     * return type.
     * <li>Otherwise, one of the methods must have a return type that
     * is assignable to all of the return types of the rest of the
     * methods.
     * </ul>
     *
     * <li>The resulting proxy class must not exceed any limits imposed
     * on classes by the virtual machine.  For example, the VM may limit
     * the number of interfaces that a class may implement to 65535; in
     * that case, the size of the {@code interfaces} array must not
     * exceed 65535.
     * </ul>
     *
     * <p>If any of these restrictions are violated,
     * {@code Proxy.getProxyClass} will throw an
     * {@code IllegalArgumentException}.  If the {@code interfaces}
     * array argument or any of its elements are {@code null}, a
     * {@code NullPointerException} will be thrown.
     *
     * <p>
     * <em>Module membership</em>
     *
     * <p>
     * A proxy class may be defined in the unnamed module or a module-private
     * member of a named module.  This method will map to a target module
     * per the following rules:
     * <ul>
     * <li>If all of the given interfaces are exported, the proxy class
     *     will be defined in the unnamed module.</li>
     * <li>If any one of the given interfaces is non-public, the proxy class
     *     will be defined in the same module as the non-public interface.</li>
     * <li>If all proxy interfaces are public and some are module-private
     * <ol>
     *     <li>if module-private proxy interfaces are all defined from
     *         one single named module, say {@code M}, and the given class loader
     *         is {@code M}'s class loader, and if all proxy interfaces are accessible
     *         to {@code M}, the proxy class will be defined in module {@code M}.</li>
     *     <li>if module-private proxy interfaces are defined in the caller's module {@code M1},
     *         the module of a proxy interface is module {@code M} whose class loader is
     *         the given class loader such that {@code M} can read {@code M1} and
     *         all proxy interfaces are accessible to {@code M},
     *         the proxy class will be defined in module {@code M}.
     *         For each module-private proxy interface in {@code M1}, say {@code p.I},
     *         a qualified export of package {@code p} to module {@code M} will be added
     *         on behalf of the caller.</li>
     *     <li>if module-private proxy interfaces are defined in the caller's module {@code M1},
     *         all other proxy interfaces are exported from named or unnamed module,
     *         the proxy class will be defined in unnamed module.
     *         For each module-private proxy interface in {@code M1}, say {@code p.I},
     *         a qualified export of package {@code p} to module {@code M} will be added
     *         on behalf of the caller.</li>
     * </ol>
     * <li>otherwise, the proxy class will be generated in the unnamed module.</li>
     * </ul>
     *
     * <p>Note that the order of the specified proxy interfaces is
     * significant: two requests for a proxy class with the same combination
     * of interfaces but in a different order will result in two distinct
     * proxy classes.
     *
     * @param   loader the class loader to define the proxy class
     * @param   interfaces the list of interfaces for the proxy class
     *          to implement
     * @return  a proxy class that is defined in the specified class loader
     *          and that implements the specified interfaces
     * @throws  IllegalArgumentException if any of the restrictions on the
     *          parameters that may be passed to {@code getProxyClass}
     *          are violated
     * @throws  SecurityException if a security manager, <em>s</em>, is present
     *          and any of the following conditions is met:
     *          <ul>
     *             <li> the given {@code loader} is {@code null} and
     *             the caller's class loader is not {@code null} and the
     *             invocation of {@link SecurityManager#checkPermission
     *             s.checkPermission} with
     *             {@code RuntimePermission("getClassLoader")} permission
     *             denies access.</li>
     *             <li> for each proxy interface, {@code intf},
     *             the caller's class loader is not the same as or an
     *             ancestor of the class loader for {@code intf} and
     *             invocation of {@link SecurityManager#checkPackageAccess
     *             s.checkPackageAccess()} denies access to {@code intf}.</li>
     *          </ul>

     * @throws  NullPointerException if the {@code interfaces} array
     *          argument or any of its elements are {@code null}
     */
    @CallerSensitive
         public static Class<?> getProxyClass(ClassLoader loader,
                                              Class<?>... interfaces)
        throws IllegalArgumentException
    {
        final List<Class<?>> intfs = Arrays.asList(interfaces.clone());
        final SecurityManager sm = System.getSecurityManager();
        final Class<?> caller = Reflection.getCallerClass();
        if (sm != null) {
            checkProxyAccess(caller, loader, intfs);
        }

        ProxyBuilder builder = new ProxyBuilder(caller, loader, intfs);
        return builder.proxyClass();
    }

    /**
     * Returns the {@code java.lang.Class} object for a proxy class that
     * will be defined by the class loader of the specified module
     * and implement all of the specified interfaces.  The proxy class
     * will be a module-private member of the given module.
     *
     * @param   module the module of the proxy class
     * @param   interfaces the list of interfaces for the proxy class
     *          to implement
     * @return  a proxy class that is defined as a module-private member of
     *          the given module and that implements the specified interfaces
     * @throws  IllegalArgumentException if any of the restrictions on the
     *          parameters that may be passed to {@link #getProxyClass(ClassLoader, Class[])}
     *          are violated
     * @throws  SecurityException if a security manager, <em>s</em>, is present
     *          and any of the following conditions is met:
     *          <ul>
     *             <li> the given {@code loader} is {@code null} and
     *             the caller's class loader is not {@code null} and the
     *             invocation of {@link SecurityManager#checkPermission
     *             s.checkPermission} with
     *             {@code RuntimePermission("getClassLoader")} permission
     *             denies access.</li>
     *             <li> for each proxy interface, {@code intf},
     *             the caller's class loader is not the same as or an
     *             ancestor of the class loader for {@code intf} and
     *             invocation of {@link SecurityManager#checkPackageAccess
     *             s.checkPackageAccess()} denies access to {@code intf}.</li>
     *          </ul>
     * @throws  NullPointerException if the {@code module} argument or
     *          the {@code interfaces} array argument is {@code null}
     * @throws  IllegalArgumentException if the {@code module} argument is
     *          not a named module
     * @since 1.9
     */
    @CallerSensitive
    public static Class<?> getModuleProxyClass(Module module,
                                               Class<?>... interfaces)
        throws IllegalArgumentException
    {
        final List<Class<?>> intfs = Arrays.asList(interfaces.clone());
        final SecurityManager sm = System.getSecurityManager();
        final Class<?> caller = Reflection.getCallerClass();
        if (sm != null) {
            checkProxyAccess(caller, getLoader(module), intfs);
        }

        ProxyBuilder builder = new ProxyBuilder(module, intfs);
        return builder.proxyClass();
    }

    /*
     * Check permissions required to create a Proxy class.
     *
     * To define a proxy class, it performs the access checks as in
     * Class.forName (VM will invoke ClassLoader.checkPackageAccess):
     * 1. "getClassLoader" permission check if loader == null
     * 2. checkPackageAccess on the interfaces it implements
     *
     * To get a constructor and new instance of a proxy class, it performs
     * the package access check on the interfaces it implements
     * as in Class.getConstructor.
     *
     * If an interface is non-public, the proxy class must be defined by
     * the defining loader of the interface.  If the caller's class loader
     * is not the same as the defining loader of the interface, the VM
     * will throw IllegalAccessError when the generated proxy class is
     * being defined via the defineClass0 method.
     */
    private static void checkProxyAccess(Class<?> caller,
                                         ClassLoader loader,
                                         List<Class<?>> interfaces)
    {
        SecurityManager sm = System.getSecurityManager();
        if (sm != null) {
            ClassLoader ccl = caller.getClassLoader();
            if (VM.isSystemDomainLoader(loader) && !VM.isSystemDomainLoader(ccl)) {
                sm.checkPermission(SecurityConstants.GET_CLASSLOADER_PERMISSION);
            }
            ReflectUtil.checkProxyPackageAccess(ccl, interfaces.toArray(EMPTY_CLASS_ARRAY));
        }
    }

    private static String packageName(Class<?> c) {
        String cn = c.getName();
        int last = cn.lastIndexOf(".");
        return (last != -1) ? cn.substring(0, last) : "";
    }

    /*
     * a key used for proxy class with 0 implemented interfaces
     */
    private static final Object key0 = new Object();

    /*
     * Key1 and Key2 are optimized for the common use of dynamic proxies
     * that implement 1 or 2 interfaces.
     */

    /*
     * a key used for proxy class with 1 implemented interface
     */
    private static final class Key1 extends WeakReference<Class<?>> {
        private final int hash;

        Key1(Class<?> intf) {
            super(intf);
            this.hash = intf.hashCode();
        }

        @Override
        public int hashCode() {
            return hash;
        }

        @Override
        public boolean equals(Object obj) {
            Class<?> intf;
            return this == obj ||
                   obj != null &&
                   obj.getClass() == Key1.class &&
                   (intf = get()) != null &&
                   intf == ((Key1) obj).get();
        }
    }

    /*
     * a key used for proxy class with 2 implemented interfaces
     */
    private static final class Key2 extends WeakReference<Class<?>> {
        private final int hash;
        private final WeakReference<Class<?>> ref2;

        Key2(Class<?> intf1, Class<?> intf2) {
            super(intf1);
            hash = 31 * intf1.hashCode() + intf2.hashCode();
            ref2 = new WeakReference<>(intf2);
        }

        @Override
        public int hashCode() {
            return hash;
        }

        @Override
        public boolean equals(Object obj) {
            Class<?> intf1, intf2;
            return this == obj ||
                   obj != null &&
                   obj.getClass() == Key2.class &&
                   (intf1 = get()) != null &&
                   intf1 == ((Key2) obj).get() &&
                   (intf2 = ref2.get()) != null &&
                   intf2 == ((Key2) obj).ref2.get();
        }
    }

    /*
     * a key used for proxy class with any number of implemented interfaces
     * (used here for 3 or more only)
     */
    private static final class KeyX {
        private final int hash;
        private final WeakReference<Class<?>>[] refs;

        @SuppressWarnings("unchecked")
        KeyX(List<Class<?>> interfaces) {
            hash = Arrays.hashCode(interfaces.toArray());
            refs = (WeakReference<Class<?>>[])new WeakReference<?>[interfaces.size()];
            int i = 0;
            for (Class<?> intf : interfaces) {
                refs[i++] = new WeakReference<>(intf);
            }
        }

        @Override
        public int hashCode() {
            return hash;
        }

        @Override
        public boolean equals(Object obj) {
            return this == obj ||
                   obj != null &&
                   obj.getClass() == KeyX.class &&
                   equals(refs, ((KeyX) obj).refs);
        }

        private static boolean equals(WeakReference<Class<?>>[] refs1,
                                      WeakReference<Class<?>>[] refs2) {
            if (refs1.length != refs2.length) {
                return false;
            }
            for (int i = 0; i < refs1.length; i++) {
                Class<?> intf = refs1[i].get();
                if (intf == null || intf != refs2[i].get()) {
                    return false;
                }
            }
            return true;
        }
    }

    /**
     * A function that maps an array of interfaces to an optimal key where
     * Class objects representing interfaces are weakly referenced.
     */
    private static final class KeyFactory<T>
        implements BiFunction<T, List<Class<?>>, Object> {
        @Override
        public Object apply(T t, List<Class<?>> interfaces) {
            switch (interfaces.size()) {
                case 1: return new Key1(interfaces.get(0)); // the most frequent
                case 2: return new Key2(interfaces.get(0), interfaces.get(1));
                case 0: return key0;
                default: return new KeyX(interfaces);
            }
        }
    }

    /**
     * A factory function that generates, defines and returns the proxy class
     * given the ClassLoader and array of interfaces.
     */
    private static final class ProxyClassFactory {
        // prefix for all proxy class names
        private static final String proxyClassNamePrefix = "$Proxy";

        // next number to use for generation of unique proxy class names
        private static final AtomicLong nextUniqueNumber = new AtomicLong();

        static Class<?> defineProxyClass(Module m, ClassLoader loader, List<Class<?>> interfaces) {
            if (getLoader(m) != loader) {
                throw new IllegalArgumentException(
                    "proxy class must be defined with same class loader as " + m +
                    " : " + toMessage(interfaces));
            }

            String proxyPkg = null;     // package to define proxy class in
            int accessFlags = Modifier.PUBLIC | Modifier.FINAL;

            /*
             * Record the package of a non-public proxy interface so that the
             * proxy class will be defined in the same package.  Verify that
             * all non-public proxy interfaces are in the same package.
             */
            for (Class<?> intf : interfaces) {
                int flags = intf.getModifiers();
                if (!Modifier.isPublic(flags)) {
                    accessFlags = Modifier.FINAL;
                    String pkg = packageName(intf);
                    if (proxyPkg == null) {
                        proxyPkg = pkg;
                    } else if (!pkg.equals(proxyPkg)) {
                        throw new IllegalArgumentException(
                            "non-public interfaces from different packages");
                    }
                    if (intf.getModule() != m && intf.getClassLoader() != loader) {
                        throw new IllegalArgumentException(
                            "proxy class must be defined with same loader and module" +
                            "as non-public interface: " + intf.getName());
                    }
                }
            }

            if (proxyPkg == null) {
                // all proxy interfaces are public
                if (m.isNamed()) {
                    proxyPkg = ReflectUtil.PROXY_PACKAGE + "." + m.getName();
                } else {
                    proxyPkg = ReflectUtil.PROXY_PACKAGE;
                }
            } else if (proxyPkg.isEmpty() && m.isNamed()) {
                throw new IllegalArgumentException(
                        "Unnamed package cannot be added to " + m);
            }

            // add the package to the runtime module if not exists
            if (m.isNamed() && !Stream.of(m.getPackages()).anyMatch(proxyPkg::equals)) {
                m.addPackage(proxyPkg);
            }

            /*
             * Choose a name for the proxy class to generate.
             */
            long num = nextUniqueNumber.getAndIncrement();
            String proxyName = proxyPkg.isEmpty() ? proxyClassNamePrefix + num
                                                  : proxyPkg + "." + proxyClassNamePrefix + num;

            if (ProxyBuilder.isDebug()) {
                System.out.println("define proxy class in \"" + proxyName + "\" " +
                        (m != null ? m.toString() : "unnamed module") +
                        " " + toMessage(interfaces));
            }
            /*
             * Generate the specified proxy class.
             */
            byte[] proxyClassFile = ProxyGenerator.generateProxyClass(
                    proxyName, interfaces.toArray(EMPTY_CLASS_ARRAY), accessFlags);
            try {
                return defineClass0(loader, proxyName,
                                    proxyClassFile, 0, proxyClassFile.length);
            } catch (ClassFormatError e) {
                /*
                 * A ClassFormatError here means that (barring bugs in the
                 * proxy class generation code) there was some other
                 * invalid aspect of the arguments supplied to the proxy
                 * class creation (such as virtual machine limitations
                 * exceeded).
                 */
                throw new IllegalArgumentException(e.toString());
            }
        }

        /**
         * Test if the given class is a proxy class
         */
        static boolean isProxyClass(Class<?> c) {
            return proxyCache.containsValue(c);
        }

        /**
         * Returns the proxy class.  It will return the cached proxy class
         * if exists; otherwise, it will create the proxy class and store in
         * the cache.
         */
        static Class<?> get(Module module, ClassLoader loader, List<Class<?>> interfaces) {
            return proxyCache.get(module, interfaces);
        }

        /**
         * a cache of proxy classes in the named and unnamed module
         */
        private static final WeakCache<Module, List<Class<?>>, Class<?>> proxyCache =
            new WeakCache<>(new KeyFactory<Module>(),
                            new BiFunction<Module, List<Class<?>>, Class<?>>()  {
                @Override
                public Class<?> apply(Module m, List<Class<?>> interfaces) {
                    Objects.requireNonNull(m);
                    return ProxyClassFactory.defineProxyClass(m, getLoader(m), interfaces);
                }
            });

        private static String toMessage(List<Class<?>> interfaces) {
            StringBuilder sb = new StringBuilder();
            for (Class<?> intf : interfaces) {
                sb.append("[").append(intf).append(" ").append(intf.getModule()).append("] ");
            }
            return sb.toString();
        }
    }

    /**
     * Builder for a proxy class.
     *
     * If the module is not specified in this ProxyBuilder constructor,
     * it will map from the given loader and interfaces to the module
     * in which the proxy class will be defined.
     */
    private static class ProxyBuilder {
        final Class<?> caller;
        final ClassLoader loader;
        final List<Class<?>> interfaces;
        final Set<Class<?>> exportedTypes = new HashSet<>();
        final Set<Class<?>> packagePrivateTypes = new HashSet<>();
        final Set<Class<?>> modulePrivateTypes = new HashSet<>();

        private Module module;  // null unknown
        ProxyBuilder(Module m, List<Class<?>> interfaces) {
            this(null, getLoader(m), interfaces);
            this.module = m;
        }

        ProxyBuilder(Class<?> caller, ClassLoader loader, List<Class<?>> interfaces) {
            this.caller = caller;
            this.loader = loader;
            this.interfaces = interfaces;

            // ensure all proxy interfaces are visible to the class loader
            ensureVisible(loader, interfaces);
            for (Class<?> intf : interfaces) {
                Module m = intf.getModule();
                if (!Modifier.isPublic(intf.getModifiers())) {
                    // non-public types
                    packagePrivateTypes.add(intf);
                } else {
                    // public types
                    if (isExported(intf)) {
                        // exported types from a named module or public types from an unnamed module
                        exportedTypes.add(intf);
                    } else if (m.isNamed()) {
                        modulePrivateTypes.add(intf);
                    }
                }
            }
        }

        /**
         * Generate a proxy class.  Must call the checkProxyAccess method
         * to perform permission checks before calling this.
         *
         * If the module is not specified in this ProxyBuilder, it will
         * {@linkplain #getModule map} from the given loader and interfaces
         * to the module in which the proxy class will be defined.
         *
         * @throws IllegalArgumentException if any of the given {@code interfaces}
         * is not accessible to the proxy class.
         *
         * @see #getModule()
         */
        Class<?> proxyClass() {
            Module proxyModule = getModule();
            ensureAccess(proxyModule, loader, interfaces);
            return ProxyClassFactory.get(proxyModule, loader, interfaces);
        }

        /**
         * Returns the module that the generated proxy class belongs to.
         *
         * If any of proxy interface is package-private, the proxy class
         * must be in the same named module of the package-private interface.
         *
         * If all proxy interfaces are public and some of them are private
         * and not exported from a named module (i.e. module-private), if
         * these module-private interfaces are from the same module whose
         * class loader is also the defining class loader of the proxy class,
         * the proxy class will be in the same module as the module-private
         * interfaces.
         *
         * If proxy interfaces are defined in module M1 and M2 where the caller's
         * module is M2 and M1 can read M2, the defining class loader of the proxy
         * class is M1's class loader, the proxy class will be defined in M1.  If
         * any proxy interface in M2 is module-private, say {@code p.I},
         * a qualified export of package p to module M1 will be added
         * on behalf of the caller.
         *
         * Otherwise, it will map to an unnamed module.
         */
        private synchronized Module getModule() {
            if (module != null) {
                return module;
            }

            // proxy class in the unnamed module if all proxy interfaces are exported
            if (exportedTypes.size() == interfaces.size()) {
                debug("exported");
                return unnamedModule();
            }

            if (isPackagePrivateProxy()) {
                // proxy class in the module of the package-private type
                debug("package-private");
                return moduleOf(packagePrivateTypes);
            } else if (isModulePrivateProxy()) {
                Module m = moduleOf(modulePrivateTypes);
                debug("module-private target: " + m);
                return m;
            } else if (modulePrivateTypes.size() > 0 &&
                            caller != null && caller.getModule().isNamed()) {
                /*
                 * The caller is in a named module and module-private
                 * proxy interfaces are either defined in the caller's module or
                 * defined by the given class loader.
                 *
                 * The proxy class will be defined in a named module and the module-private
                 * types defined in the caller's module will be exported to it to access
                 */
                Module callerModule = caller.getModule();
                Set<Class<?>> callerPrivates = modulePrivateTypes.stream()
                    .filter(t -> t.getModule() == callerModule)
                    .collect(Collectors.toSet());

                // module-private types defined by a class loader different than
                // the proxy's defining class loader
                Set<Class<?>> foreignTypes = modulePrivateTypes.stream()
                    .filter(t -> t.getClassLoader() != loader)
                    .collect(Collectors.toSet());

                // caller is potentially framework library
                // if no module-private type is in a foreign module/class loader
                if (callerPrivates.isEmpty() || foreignTypes.stream()
                                                            .map(Class::getModule)
                                                            .anyMatch(m -> m != callerModule)) {
                    debug("unnamed");
                    return unnamedModule();
                }

                Set<Class<?>> types = interfaces.stream()
                    .filter(t -> !callerPrivates.contains(t))
                    .collect(Collectors.toSet());

                // named modules that can read the caller's module
                Set<Module> modules = types.stream()
                    .map(Class::getModule)
                    .filter(m -> m != null && m.canRead(callerModule))
                    .filter(m -> m.getClassLoader() == loader)
                    .collect(Collectors.toSet());
                Optional<Module> any = modules.stream()
                    .filter(m -> isAccessible(types, m))
                    .findFirst();
                if (any.isPresent()) {
                    Module m = any.get();
                    exportsCallerModulePrivateTypes(m);
                    debug("caller: " + caller.getModule() + " module-private target: " + m);
                    return m;
                }

                // if all module-private types are from the caller's module
                // all other proxy interfaces are exported from named or unnamed module
                if (callerPrivates.equals(modulePrivateTypes)) {
                    // ## maybe the proxy class be defined in an anonymous module
                    exportsCallerModulePrivateTypes(null);
                    debug("unnamed");
                    return unnamedModule();
                }
            }

            // a module-private type
            debug("unnamed");
            return unnamedModule();
        }

        private Module unnamedModule() {
            return loader != null ? loader.getUnnamedModule()
                                  : BootLoader.getUnnamedModule();
        }
        private Module moduleOf(Set<Class<?>> types) {
            assert sameModuleLoader(loader, types);
            Module m = null;
            for (Class<?> t : types) {
                if (exportedTypes.contains(t)) {
                    continue;
                }
                m = t.getModule();
            }
            return m;
        }

        private void exportsCallerModulePrivateTypes(Module m) {
            Module callerModule = caller.getModule();
            // add qualified export from the caller's module to the module of the proxy class
            Set<String> pkgs = modulePrivateTypes.stream()
                    .filter(t -> t.getModule() == callerModule)
                    .map(Proxy::packageName)
                    .distinct()
                    .collect(Collectors.toSet());
            if (isDebug("debug")) {
                pkgs.stream().forEach(pn ->
                    System.out.format("exports caller's module-private %s in %s to %s%n",
                                      pn, callerModule, m));
            }
            pkgs.stream().forEach(pn -> Modules.addExports(callerModule, pn, m));
        }

        /**
         * Returns true if all package-access types are defined by loader
         * and in the same module.
         *
         * The proxy class must be defined in the same module to access
         * the package private types
         */
        private boolean isPackagePrivateProxy() {
            return packagePrivateTypes.size() > 0 && sameModuleLoader(loader, packagePrivateTypes);
        }

        /**
         * Returns true if all proxy interfaces are public (i.e. no package
         * access type) and there is one or more module-private interfaces
         * that are all in the same module defined by loader
         *
         * The proxy class must be defined in the same module to access
         * module-private types.
         *
         * Note that other proxy interfaces may be exported from other modules.
         */
        private boolean isModulePrivateProxy() {
            if (!packagePrivateTypes.isEmpty() || modulePrivateTypes.isEmpty()) {
                return false;
            }

            List<Module> modules = modulePrivateTypes.stream()
                .map(Class::getModule)
                .collect(Collectors.toList());
            if (modules.size() != 1) {
                return false;
            }

            if (!sameModuleLoader(loader, modulePrivateTypes)) {
                return false;
            }

            // all proxy interfaces must be accessible to the target module
            Module target = modules.get(0);
            isAccessible(interfaces, target);
            return true;
        }

        private boolean isAccessible(Iterable<Class<?>> types, Module target) {
            for (Class<?> intf : types) {
                Module m = intf.getModule();
                assert Modifier.isPublic(intf.getModifiers());
                if (target == m) {
                    continue;
                }
                if (!target.canRead(m) || !m.isExported(packageName(intf), target)) {
                    return false;
                }
            }
            return true;
        }

        /**
         * Ensures the given interfaces are visible to the given loader
         */
        private static void ensureVisible(ClassLoader loader, List<Class<?>> interfaces) {
            if (interfaces.size() > 65535) {
                throw new IllegalArgumentException("interface limit exceeded");
            }

            String packageName = null;
            Map<Class<?>, Boolean> interfaceSet = new IdentityHashMap<>(interfaces.size());
            for (Class<?> intf : interfaces) {
                /*
                 * Verify that the class loader resolves the name of this
                 * interface to the same Class object.
                 */
                Class<?> interfaceClass = null;
                try {
                    interfaceClass = Class.forName(intf.getName(), false, loader);
                } catch (ClassNotFoundException e) {
                }
                if (interfaceClass != intf) {
                    throw new IllegalArgumentException(
                            intf + " is not visible from class loader");
                }

                /*
                 * Verify that the Class object actually represents an
                 * interface.
                 */
                if (!interfaceClass.isInterface()) {
                    throw new IllegalArgumentException(
                            interfaceClass.getName() + " is not an interface");
                }
                /*
                 * Verify that this interface is not a duplicate.
                 */
                if (interfaceSet.put(interfaceClass, Boolean.TRUE) != null) {
                    throw new IllegalArgumentException(
                            "repeated interface: " + interfaceClass.getName());
                }

                if (!Modifier.isPublic(intf.getModifiers())) {
                    String pkg = packageName(intf);
                    if (packageName != null && !pkg.equals(packageName)) {
                        throw new IllegalArgumentException(
                                "non-public interfaces from different packages");
                    }
                    packageName = pkg;
                }
            }
        }

        /**
         * Ensures the given proxy interfaces are accessible to the given module.
         *
         * @param module a named module or {@code null} for unnamed module
         * @param interfaces the list of interfaces for the proxy class to implement
         *
         * @throws IllegalArgumentException if the given module is a named module
         *         and loader is not the class loader for the module
         * @throws IllegalArgumentException if any one of the interfaces is not
         *         accessible to the module
         */
        private static void ensureAccess(Module module, ClassLoader loader, List<Class<?>> interfaces) {
            if (getLoader(module) != loader) {
                throw new IllegalArgumentException("class loader not allowed " +
                        " to define proxy class in " + module);
            }

            for (Class<?> intf : interfaces) {
                Module m = intf.getModule();
                if (module == m) continue;

                if (!module.canRead(m)) {
                    throw new IllegalArgumentException(module + " can't read " + m);
                }

                if (!m.isExported(packageName(intf), module)) {
                    throw new IllegalArgumentException(intf + " in " + m + " is not exported to "
                            + module);
                }
            }
        }

        /**
         * Returns true if the given interfaces are all in one module and
         * the given loader is their defining class loader.
         */
        private static boolean sameModuleLoader(ClassLoader loader, Set<Class<?>> interfaces) {
            if (interfaces.isEmpty()) {
                return false;
            }

            Map<Module, ClassLoader> modules = new HashMap<>(interfaces.size());
            for (Class<?> intf : interfaces) {
                Module m = intf.getModule();
                ClassLoader ld = intf.getClassLoader();
                if (ld != loader) {
                    return false;
                }
                // allow null key
                if (!modules.containsKey(m) && modules.size() > 0) {
                    // more than 1 module
                    return false;
                }
                modules.putIfAbsent(m, intf.getClassLoader());
            }
            return true;
        }

        /**
         * Returns true if the given interface is public and exported in
         * a named module, or is public type of a named package in the unnamed
         * module; otherwise, returns false.
         */
        private static boolean isExported(Class<?> intf) {
            Module m = intf.getModule();
            String pn = packageName(intf);
            int modifiers = intf.getModifiers();
            if (m.isNamed() && !pn.isEmpty() && m.isExported(pn, null)) {
                return Modifier.isPublic(modifiers);
            }
            if (!m.isNamed() && !pn.isEmpty()) {
                return Modifier.isPublic(modifiers);
            }

            return false;
        }

        private void debug(String msg) {
            if (isDebug("debug")) {
                System.out.format("PROXY: %s%n", msg);
                interfaces.stream()
                    .forEach(c -> System.out.println(detail(c)));
            }
        }

        private String detail(Class<?> c) {
            String bucket = "unknown";
            if (exportedTypes.contains(c)) {
                bucket = "exported";
            } else if (packagePrivateTypes.contains(c)) {
                bucket = "package-private";
            } else if (modulePrivateTypes.contains(c)) {
                bucket = "module-private";
            }
            ClassLoader ld = c.getClassLoader();
            return String.format("class %s in %s %s loader %s",
                                 c.getName(), c.getModule(), bucket, ld);
        }

        static final String DEBUG =
            AccessController.doPrivileged(new PrivilegedAction<String>() {
                public String run() {
                    return System.getProperty("jdk.proxy.debug", "");
                }
            });
        static final boolean isDebug() {
            return !DEBUG.isEmpty();
        }
        static final boolean isDebug(String flag) {
            return DEBUG.equals(flag);
        }
    }

    /**
     * Returns an instance of a proxy class for the specified interfaces
     * that dispatches method invocations to the specified invocation
     * handler.
     *
     * <p>{@code Proxy.newProxyInstance} throws
     * {@code IllegalArgumentException} for the same reasons that
     * {@code Proxy.getProxyClass} does.
     *
     * @param   loader the class loader to define the proxy class
     * @param   interfaces the list of interfaces for the proxy class
     *          to implement
     * @param   h the invocation handler to dispatch method invocations to
     * @return  a proxy instance with the specified invocation handler of a
     *          proxy class that is defined by the specified class loader
     *          and that implements the specified interfaces
     * @throws  IllegalArgumentException if any of the restrictions on the
     *          parameters that may be passed to {@code getProxyClass}
     *          are violated
     * @throws  SecurityException if a security manager, <em>s</em>, is present
     *          and any of the following conditions is met:
     *          <ul>
     *          <li> the given {@code loader} is {@code null} and
     *               the caller's class loader is not {@code null} and the
     *               invocation of {@link SecurityManager#checkPermission
     *               s.checkPermission} with
     *               {@code RuntimePermission("getClassLoader")} permission
     *               denies access;</li>
     *          <li> for each proxy interface, {@code intf},
     *               the caller's class loader is not the same as or an
     *               ancestor of the class loader for {@code intf} and
     *               invocation of {@link SecurityManager#checkPackageAccess
     *               s.checkPackageAccess()} denies access to {@code intf};</li>
     *          <li> any of the given proxy interfaces is non-public and the
     *               caller class is not in the same {@linkplain Package runtime package}
     *               as the non-public interface and the invocation of
     *               {@link SecurityManager#checkPermission s.checkPermission} with
     *               {@code ReflectPermission("newProxyInPackage.{package name}")}
     *               permission denies access.</li>
     *          </ul>
     * @throws  NullPointerException if the {@code interfaces} array
     *          argument or any of its elements are {@code null}, or
     *          if the invocation handler, {@code h}, is
     *          {@code null}
     */
    @CallerSensitive
    public static Object newProxyInstance(ClassLoader loader,
                                          Class<?>[] interfaces,
                                          InvocationHandler h) {
        Objects.requireNonNull(h);

        final List<Class<?>> intfs = Arrays.asList(interfaces.clone());
        final SecurityManager sm = System.getSecurityManager();
        final Class<?> caller = Reflection.getCallerClass();
        if (sm != null) {
            checkProxyAccess(caller, loader, intfs);
        }

        /*
         * Look up or generate the designated proxy class.
         */
        ProxyBuilder builder = new ProxyBuilder(caller, loader, intfs);
        Class<?> cl = builder.proxyClass();

        return newProxyInstance(cl, caller, h);
    }

    /**
     * Returns an instance of a proxy class for the specified interfaces
     * that dispatches method invocations to the specified invocation
     * handler.  The proxy class will be defined as a module-private member
     * of the specified module.
     *
     * @param   module the module of the proxy class
     * @param   h the invocation handler to dispatch method invocations to
     * @param   interfaces the list of interfaces for the proxy class
     *          to implement
     * @return  a proxy instance with the specified invocation handler of a
     *          proxy class that is defined in the given module
     *          and that implements the specified interfaces
     * @throws  IllegalArgumentException if any of the given interfaces is
     *          not visible or accessible to the given module
     * @throws  SecurityException if a security manager, <em>s</em>, is present
     *          and any of the following conditions is met:
     *          <ul>
     *          <li> the given {@code module}'s class loader is {@code null} and
     *               the caller's class loader is not {@code null} and the
     *               invocation of {@link SecurityManager#checkPermission
     *               s.checkPermission} with
     *               {@code RuntimePermission("getClassLoader")} permission
     *               denies access;</li>
     *          <li> for each proxy interface, {@code intf},
     *               the caller's class loader is not the same as or an
     *               ancestor of the class loader for {@code intf} and
     *               invocation of {@link SecurityManager#checkPackageAccess
     *               s.checkPackageAccess()} denies access to {@code intf};</li>
     *          <li> any of the given proxy interfaces is non-public and the
     *               caller class is not in the same runtime package
     *               of the non-public interface and the invocation of
     *               {@link SecurityManager#checkPermission s.checkPermission} with
     *               {@code ReflectPermission("newProxyInPackage.{package name}")}
     *               permission denies access.</li>
     *          </ul>
     * @throws  NullPointerException if the given {@code module} is {@code null},
     *          the given {@code interfaces} array argument or any of its elements
     *          are {@code null}, or if the invocation handler, {@code h}, is
     *          {@code null}
     * @throws IllegalArgumentException if the give {@code module} is not a named module.
     *
     * @since 1.9
     */
    @CallerSensitive
    public static Object newModuleProxyInstance(Module module, InvocationHandler h,
                                                Class<?>... interfaces)
    {
        Objects.requireNonNull(module);
        Objects.requireNonNull(h);
        if (!module.isNamed()) {
            throw new IllegalArgumentException(module.getName() + " not a named module");
        }

        final ClassLoader loader = getLoader(module);
        final List<Class<?>> intfs = Arrays.asList(interfaces.clone());
        final SecurityManager sm = System.getSecurityManager();
        final Class<?> caller;
        if (sm != null) {
            caller = Reflection.getCallerClass();
            checkProxyAccess(caller, loader, intfs);
        } else {
            caller = null;
        }

        ProxyBuilder builder = new ProxyBuilder(module, intfs);
        Class<?> cl = builder.proxyClass();
        return newProxyInstance(cl, caller, h);
    }

    private static Object newProxyInstance(Class<?> proxyClass, Class<?> caller, InvocationHandler h) {
        /*
         * Invoke its constructor with the designated invocation handler.
         */
        try {
            final SecurityManager sm = System.getSecurityManager();
            if (sm != null) {
                checkNewProxyPermission(caller, proxyClass);
            }

            final Constructor<?> cons = proxyClass.getConstructor(constructorParams);
            // TBD: Are there cases where we can avoid this?
            AccessController.doPrivileged(new PrivilegedAction<Void>() {
                public Void run() {
                    cons.setAccessible(true);
                    return null;
                }
            });
            return cons.newInstance(new Object[]{h});
        } catch (IllegalAccessException | InstantiationException | NoSuchMethodException e) {
            throw new InternalError(e.toString(), e);
        } catch (InvocationTargetException e) {
            Throwable t = e.getCause();
            if (t instanceof RuntimeException) {
                throw (RuntimeException) t;
            } else {
                throw new InternalError(t.toString(), t);
            }
        }
    }

    // ## this permission check may need to extend for module-private types
    private static void checkNewProxyPermission(Class<?> caller, Class<?> proxyClass) {
        SecurityManager sm = System.getSecurityManager();
        if (sm != null) {
            if (ReflectUtil.isNonPublicProxyClass(proxyClass)) {
                ClassLoader ccl = caller.getClassLoader();
                ClassLoader pcl = proxyClass.getClassLoader();

                // do permission check if the caller is in a different runtime package
                // of the proxy class
                int n = proxyClass.getName().lastIndexOf('.');
                String pkg = (n == -1) ? "" : proxyClass.getName().substring(0, n);

                n = caller.getName().lastIndexOf('.');
                String callerPkg = (n == -1) ? "" : caller.getName().substring(0, n);

                if (pcl != ccl || !pkg.equals(callerPkg)) {
                    sm.checkPermission(new ReflectPermission("newProxyInPackage." + pkg));
                }
            }
        }
    }

    /**
     * Returns the class loader for the given module.
     */
    private static ClassLoader getLoader(Module m) {
        PrivilegedAction<ClassLoader> pa = m::getClassLoader;
        return AccessController.doPrivileged(pa);
    }

    /**
     * Returns true if and only if the specified class was dynamically
     * generated to be a proxy class using the {@code getProxyClass}
     * method or the {@code newProxyInstance} method.
     *
     * <p>The reliability of this method is important for the ability
     * to use it to make security decisions, so its implementation should
     * not just test if the class in question extends {@code Proxy}.
     *
     * @param   cl the class to test
     * @return  {@code true} if the class is a proxy class and
     *          {@code false} otherwise
     * @throws  NullPointerException if {@code cl} is {@code null}
     */
    public static boolean isProxyClass(Class<?> cl) {
        return Proxy.class.isAssignableFrom(cl) && ProxyClassFactory.isProxyClass(cl);
    }

    /**
     * Returns the invocation handler for the specified proxy instance.
     *
     * @param   proxy the proxy instance to return the invocation handler for
     * @return  the invocation handler for the proxy instance
     * @throws  IllegalArgumentException if the argument is not a
     *          proxy instance
     * @throws  SecurityException if a security manager, <em>s</em>, is present
     *          and the caller's class loader is not the same as or an
     *          ancestor of the class loader for the invocation handler
     *          and invocation of {@link SecurityManager#checkPackageAccess
     *          s.checkPackageAccess()} denies access to the invocation
     *          handler's class.
     */
    @CallerSensitive
    public static InvocationHandler getInvocationHandler(Object proxy)
        throws IllegalArgumentException
    {
        /*
         * Verify that the object is actually a proxy instance.
         */
        if (!isProxyClass(proxy.getClass())) {
            throw new IllegalArgumentException("not a proxy instance");
        }

        final Proxy p = (Proxy) proxy;
        final InvocationHandler ih = p.h;
        if (System.getSecurityManager() != null) {
            Class<?> ihClass = ih.getClass();
            Class<?> caller = Reflection.getCallerClass();
            if (ReflectUtil.needsPackageAccessCheck(caller.getClassLoader(),
                                                    ihClass.getClassLoader()))
            {
                ReflectUtil.checkPackageAccess(ihClass);
            }
        }

        return ih;
    }

    private static native Class<?> defineClass0(ClassLoader loader, String name,
                                                byte[] b, int off, int len);

    private static Class<?>[] EMPTY_CLASS_ARRAY = new Class<?>[0];

}
