/*
 * Copyright (c) 2003, 2015, Oracle and/or its affiliates. All rights reserved.
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

package sun.security.jca;

import java.io.File;
import java.lang.reflect.*;
import java.util.*;

import java.security.*;

import sun.security.util.PropertyExpander;

/**
 * Class representing a configured provider which encapsulates configuration
 * (name plus optional arguments), the provider loading logic, and
 * the loaded Provider object itself.
 *
 * @author  Andreas Sterbenz
 * @since   1.5
 */
final class ProviderConfig {

    private final static sun.security.util.Debug debug =
        sun.security.util.Debug.getInstance("jca", "ProviderConfig");

    // provider name of the SunPKCS11-Solaris provider
    private static final String P11_SOL_NAME =
        "SunPKCS11-Solaris";

    // maximum number of times to try loading a provider before giving up
    private final static int MAX_LOAD_TRIES = 30;

    // name of the provider
    private final String provName;

    // arguments to the Provider.configure() call
    private final String[] arguments;

    // number of times we have already tried to load this provider
    private int tries;

    // Provider object, if loaded
    private volatile Provider provider;

    // flag indicating if we are currently trying to load the provider
    // used to detect recursion
    private boolean isLoading;

    ProviderConfig(String provName, String arguments) {
        if (provName.equals(P11_SOL_NAME)) {
            checkSunPKCS11Solaris();
        }
        this.provName = provName;
        if (arguments != null) {
            this.arguments = arguments.split(" ");
            for (int i = 0; i < this.arguments.length; i++) {
                this.arguments[i] = expand(this.arguments[i]);
            }
        } else {
            this.arguments = null;
        }
    }

    ProviderConfig(String provName) {
        this(provName, null);
    }

    ProviderConfig(Provider provider) {
        this.provName = provider.getName();
        this.arguments = null;
        this.provider = provider;
    }

    // check if we should try to load the SunPKCS11-Solaris provider
    // avoid if not available (pre Solaris 10) to reduce startup time
    // or if disabled via system property
    private void checkSunPKCS11Solaris() {
        Boolean o = AccessController.doPrivileged(
                                new PrivilegedAction<Boolean>() {
            public Boolean run() {
                File file = new File("/usr/lib/libpkcs11.so");
                if (file.exists() == false) {
                    return Boolean.FALSE;
                }
                if ("false".equalsIgnoreCase(System.getProperty
                        ("sun.security.pkcs11.enable-solaris"))) {
                    return Boolean.FALSE;
                }
                return Boolean.TRUE;
            }
        });
        if (o == Boolean.FALSE) {
            tries = MAX_LOAD_TRIES;
        }
    }

    // should we try to load this provider?
    private boolean shouldLoad() {
        return (tries < MAX_LOAD_TRIES);
    }

    // do not try to load this provider again
    private void disableLoad() {
        tries = MAX_LOAD_TRIES;
    }

    boolean isLoaded() {
        return (provider != null);
    }

    public boolean equals(Object obj) {
        if (this == obj) {
            return true;
        }
        if (obj instanceof ProviderConfig == false) {
            return false;
        }
        ProviderConfig other = (ProviderConfig)obj;
        return this.provName.equals(other.provName)
            && Arrays.equals(this.arguments, other.arguments);
    }

    public int hashCode() {
        return provName.hashCode() + arguments.hashCode();
    }

    public String toString() {
        if (arguments != null) {
            return provName + "('" + Arrays.deepToString(arguments) + "')";
        } else {
            return provName;
        }
    }

    /**
     * Get the provider object. Loads the provider if it is not already loaded.
     */
    synchronized Provider getProvider() {
        // volatile variable load
        Provider p = provider;
        if (p != null) {
            return p;
        }
        if (shouldLoad() == false) {
            return null;
        }

        // Create providers which are in java.base directly
        if (provName.equals("SUN")) {
            p = new sun.security.provider.Sun();
        } else if (provName.equals("SunRsaSign")) {
            p = new sun.security.rsa.SunRsaSign();
        } else if (provName.equals("SunJCE")) {
            p = new com.sun.crypto.provider.SunJCE();
        } else if (provName.equals("SunJSSE")) {
            p = new com.sun.net.ssl.internal.ssl.Provider();
        } else {
            if (isLoading) {
                // because this method is synchronized, this can only
                // happen if there is recursion.
                if (debug != null) {
                    debug.println("Recursion loading provider: " + this);
                    new Exception("Call trace").printStackTrace();
                }
                return null;
            }
            try {
                isLoading = true;
                tries++;
                p = doLoadProvider();
            } finally {
                isLoading = false;
            }
        }
        provider = p;
        return p;
    }

    /**
     * Load and instantiate the Provider described by this class.
     *
     * NOTE use of doPrivileged().
     *
     * @return null if the Provider could not be loaded
     *
     * @throws ProviderException if executing the Provider's constructor
     * throws a ProviderException. All other Exceptions are ignored.
     */
    private Provider doLoadProvider() {
        return AccessController.doPrivileged(new PrivilegedAction<Provider>() {
            public Provider run() {
                if (debug != null) {
                    debug.println("Loading provider: " + ProviderConfig.this);
                }
                ProviderLoader pl = new ProviderLoader();
                try {
                    Provider p = pl.load(provName, arguments);
                    if (p != null) {
                        if (debug != null) {
                            debug.println("Loaded provider " + p);
                        }
                    } else {
                        if (debug != null) {
                            debug.println(provName + " is not a provider");
                        }
                        disableLoad();
                    }
                    return p;
                } catch (UnsupportedOperationException e) {
                    disableLoad();
                    return null;
                }
            }
        });
    }

    /**
     * Perform property expansion of the provider value.
     *
     * NOTE use of doPrivileged().
     */
    private static String expand(final String value) {
        // shortcut if value does not contain any properties
        if (value.contains("${") == false) {
            return value;
        }
        return AccessController.doPrivileged(new PrivilegedAction<String>() {
            public String run() {
                try {
                    return PropertyExpander.expand(value);
                } catch (GeneralSecurityException e) {
                    throw new ProviderException(e);
                }
            }
        });
    }

    // Inner class for loading security providers listed in java.security file
    private static final class ProviderLoader {
        ServiceLoader<Provider> services;

        ProviderLoader() {
            // VM should already been booted at this point, if not
            // - Only providers in java.base should be loaded, don't use
            //   ServiceLoader
            // - ClassLoader.getSystemClassLoader() will throw InternalError
            services = ServiceLoader.load(java.security.Provider.class,
                                          ClassLoader.getSystemClassLoader());
        }

        /**
         * Loads the provider with the specified name and arguments.
         *
         * @param name the name of the provider
         * @param arguments arguments for configuring the provider
         * @return the Provider, or null if it cannot be found or loaded
         */
        public Provider load(String name, String[] arguments) {
            Iterator<Provider> iter = services.iterator();
            while (iter.hasNext()) {
                try {
                    Provider p = iter.next();
                    if (debug != null) {
                        debug.println("Found SL Provider named " + p.getName());
                    }
                    if (p.getName().equals(name)) {
                        if (arguments != null) {
                            if (debug != null) {
                                debug.println("configure using " +
                                    Arrays.deepToString(arguments));
                            }
                            return p.configure(arguments);
                        } else {
                            return p;
                        }
                    }
                } catch (SecurityException | ServiceConfigurationError |
                         InvalidParameterException ex) {
                    // if provider loading fail due to security permission,
                    // log it and move on to next provider
                    if (debug != null) {
                        debug.println("Encountered " + ex +
                            " during provider instantiation, move on to next");
                            ex.printStackTrace();
                    }
                }
            }
            // No success with ServiceLoader. Try loading provider the legacy,
            // i.e. pre-module, way via reflection
            try {
                return legacyLoad(name, arguments);
            } catch (ProviderException ex) {
                return null;
            }
        }

        private Provider legacyLoad(String cn, String[] arguments) {

            if (debug != null) {
                debug.println("Loading provider: " + cn);
            }

            try {
                Class<?> provClass =
                    ClassLoader.getSystemClassLoader().loadClass(cn);

                // only continue if the specified class extends Provider
                if (!Provider.class.isAssignableFrom(provClass)) {
                    if (debug != null) {
                        debug.println(cn + " is not a provider");
                    }
                    return null;
                }

                Provider p = AccessController.doPrivileged
                    (new PrivilegedExceptionAction<Provider>() {
                    public Provider run() throws Exception {
                        Provider obj;
                        if (arguments != null && arguments.length == 1) {
                            Constructor<?> cons = provClass.getConstructor(String.class);
                            obj = (Provider) cons.newInstance(arguments[0]);
                        } else {
                            obj = (Provider) provClass.newInstance();
                            if (arguments != null) {
                                obj = obj.configure(arguments);
                            }
                        }
                        return obj;
                    }
                });
                return p;
            } catch (Exception e) {
                Throwable t;
                if (e instanceof InvocationTargetException) {
                    t = ((InvocationTargetException)e).getCause();
                } else {
                    t = e;
                }
                if (debug != null) {
                    debug.println("Error loading provider " + cn);
                    t.printStackTrace();
                }
                // provider indicates fatal error, pass through exception
                if (t instanceof ProviderException) {
                    throw (ProviderException) t;
                }
                return null;
            } catch (ExceptionInInitializerError err) {
                // no sufficient permission to initialize provider class
                if (debug != null) {
                    debug.println("Error loading provider " + cn);
                    err.printStackTrace();
                }
                return null;
            }
        }
    }
}
