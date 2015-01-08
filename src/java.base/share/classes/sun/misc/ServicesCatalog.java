/*
 * Copyright (c) 2014, Oracle and/or its affiliates. All rights reserved.
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

import jdk.jigsaw.module.ModuleDescriptor;

import java.lang.reflect.Module;
import java.util.*;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * A services catalog. Each {@code ClassLoader} has an optional {@code
 * ServicesCatalog} for modules that provide services.
 *
 * @implNote The ServicesCatalog for the null class loader is defined here
 * rather than java.lang.ClassLoader to avoid early initialization.
 */
public class ServicesCatalog {

    // ServiceCatalog for the null class loader
    private static final ServicesCatalog SYSTEM_SERVICES_CATALOG = new ServicesCatalog();

    // use RW locks as register is rare
    private final ReadWriteLock lock = new ReentrantReadWriteLock();
    private final Lock readLock = lock.readLock();
    private final Lock writeLock = lock.writeLock();

    // service providers
    private final Map<String, Set<String>> loaderServices = new HashMap<>();

    /**
     * Returns the ServiceCatalog for modules associated with the boot class loader.
     */
    public static ServicesCatalog getSystemServicesCatalog() {
        return SYSTEM_SERVICES_CATALOG;
    }

    /**
     * Creates a new module catalog.
     */
    public ServicesCatalog() { }

    /**
     * Registers the module in this module catalog.
     */
    public void register(Module m) {
        ModuleDescriptor descriptor = m.descriptor();

        writeLock.lock();
        try {
            // extend the services map
            for (Map.Entry<String, Set<String>> entry: descriptor.services().entrySet()) {
                String service = entry.getKey();
                Set<String> providers = entry.getValue();

                // if there are already service providers for this service
                // then just create a new set that has the existing plus new
                Set<String> existing = loaderServices.get(service);
                if (existing != null) {
                    Set<String> set = new HashSet<>();
                    set.addAll(existing);
                    set.addAll(providers);
                    providers = set;
                }
                loaderServices.put(service, Collections.unmodifiableSet(providers));
            }

        } finally {
            writeLock.unlock();
        }
    }

    /**
     * Returns the (possibly empty) set of service providers that implement the
     * given service type.
     *
     * @see java.util.ServiceLoader
     */
    public Set<String> findServices(String service) {
        readLock.lock();
        try {
            return loaderServices.getOrDefault(service, Collections.emptySet());
        } finally {
            readLock.unlock();
        }
    }
}
