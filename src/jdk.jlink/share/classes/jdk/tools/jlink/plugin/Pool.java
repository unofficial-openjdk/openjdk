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
package jdk.tools.jlink.plugin;

import jdk.tools.jlink.plugin.PluginException;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.lang.module.ModuleDescriptor;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import jdk.tools.jlink.internal.ImageFileCreator;

/**
 * Pool of module data.
 *
 */
public abstract class Pool {

    public interface Visitor {

        /**
         * Called for each visited file.
         *
         * @param content The file to deal with.
         * @return A resource or null if the passed resource is to be removed
         * from the image.
         * @throws PluginException
         */
        public ModuleData visit(ModuleData content);
    }

    public static enum ModuleDataType {

        CLASS_OR_RESOURCE,
        CONFIG,
        NATIVE_CMD,
        NATIVE_LIB,
        OTHER;
    }

    public interface Module {
         public String getName();

        public ModuleData get(String path);

        public ModuleDescriptor getDescriptor();

        public void add(ModuleData data);

        public Set<String> getAllPackages();

        public Collection<ModuleData> getContent();

    }

    private class ModuleImpl implements Module {
        private final Map<String, ModuleData> moduleContent = new LinkedHashMap<>();
        private ModuleDescriptor descriptor;
        private final String name;
        private ModuleImpl(String name) {
            this.name = name;
        }

        @Override
        public String getName() {
            return name;
        }

        @Override
        public ModuleData get(String path) {
            if (!path.startsWith("/")) {
                path = "/" + path;
            }
            if (!path.startsWith("/" + name)) {
                path = "/" + name + path;
            }
            return moduleContent.get(path);
        }

        @Override
        public ModuleDescriptor getDescriptor() {
            if(descriptor == null) {
                String p = "/" + name + "/module-info.class";
                ModuleData content = moduleContent.get(p);
                if(content == null) {
                    throw new PluginException("No module-info for " + name +
                            " module");
                }
                ByteBuffer bb = ByteBuffer.wrap(content.getBytes());
                descriptor = ModuleDescriptor.read(bb);
            }
            return descriptor;
        }

        @Override
        public void add(ModuleData data) {
            if (isReadOnly()) {
                throw new PluginException("pool is readonly");
            }
            Objects.requireNonNull(data);
            if (!data.getModule().equals(name)) {
                throw new PluginException("Can't add resource " + data.getPath()
                        + " to module " + name);
            }
            Pool.this.add(data);
        }

        @Override
        public Set<String> getAllPackages() {
            Set<String> pkgs = new HashSet<>();
            moduleContent.values().stream().filter(m -> m.getType().
                    equals(ModuleDataType.CLASS_OR_RESOURCE)).forEach((res) -> {
                // Module metadata only contains packages with .class files
                if (ImageFileCreator.isClassPackage(res.getPath())) {
                    String[] split = ImageFileCreator.splitPath(res.getPath());
                    String pkg = split[1];
                    if (pkg != null && !pkg.isEmpty()) {
                        pkgs.add(pkg);
                    }
                }
            });
            return pkgs;
        }

        @Override
        public String toString() {
            return getName();
        }

        @Override
        public Collection<ModuleData> getContent() {
            return Collections.unmodifiableCollection(moduleContent.values());
        }
    }

    public static class ModuleData {

        private final ModuleDataType type;
        private final String path;
        private final String module;
        private final long length;
        private final InputStream stream;

        private byte[] buffer;
        public ModuleData(String module, String path, ModuleDataType type,
                InputStream stream, long length) {
            Objects.requireNonNull(module);
            Objects.requireNonNull(path);
            Objects.requireNonNull(type);
            Objects.requireNonNull(stream);
            this.path = path;
            this.type = type;
            this.module = module;
            this.stream = stream;
            this.length = length;
        }

        public final String getModule() {
            return module;
        }

        public final String getPath() {
            return path;
        }

        public final ModuleDataType getType() {
            return type;
        }

        public byte[] getBytes() {
            if (buffer == null) {
                try {
                    buffer = stream.readAllBytes();
                } catch (IOException ex) {
                    throw new UncheckedIOException(ex);
                }
            }
            return buffer;
        }

        public long getLength() {
            return length;
        }

        public InputStream stream() {
            return stream;
        }

        @Override
        public int hashCode() {
            int hash = 7;
            hash = 89 * hash + Objects.hashCode(this.path);
            return hash;
        }

        @Override
        public boolean equals(Object other) {
            if (!(other instanceof ModuleData)) {
                return false;
            }
            ModuleData f = (ModuleData) other;
            return f.path.equals(path);
        }

        @Override
        public String toString() {
            return getPath();
        }
    }

    private final Map<String, ModuleData> resources = new LinkedHashMap<>();
    private final Map<String, ModuleImpl> modules = new LinkedHashMap<>();

    private final ByteOrder order;

    protected Pool() {
        this(ByteOrder.nativeOrder());
    }

    protected Pool(ByteOrder order) {
        Objects.requireNonNull(order);
        this.order = order;
    }

    /**
     * Read only state.
     *
     * @return true if readonly false otherwise.
     */
    public abstract boolean isReadOnly();

    /**
     * Add a resource.
     *
     * @param resource The Resource to add.
     */
    public void add(ModuleData resource) {
        if (isReadOnly()) {
            throw new PluginException("pool is readonly");
        }
        Objects.requireNonNull(resource);
        if (resources.get(resource.getPath()) != null) {
            throw new PluginException("Resource " + resource.getPath()
                    + " already present");
        }
        ModuleImpl m = modules.get(resource.getModule());
        if(m == null) {
            m = new ModuleImpl(resource.getModule());
            modules.put(resource.getModule(), m);
        }
        resources.put(resource.getPath(), resource);
        m.moduleContent.put(resource.getPath(), resource);
    }

    /**
     * Retrieves the module of the provided name.
     * @param name The module name
     * @return the module or null if the module doesn't exist.
     */
    public Module getModule(String name) {
        Objects.requireNonNull(name);
        return modules.get(name);
    }

    /**
     * The collection of modules contained in this pool.
     * @return The collection of modules.
     */
    public Collection<Module> getModules() {
        return Collections.unmodifiableCollection(modules.values());
    }

    /**
     * Get all resources contained in this pool instance.
     *
     * @return The collection of resources;
     */
    public Collection<ModuleData> getContent() {
        return Collections.unmodifiableCollection(resources.values());
    }

    /**
     * Get the resource for the passed path.
     *
     * @param path A resource path
     * @return A Resource instance or null if the resource is not found
     */
    public ModuleData get(String path) {
        Objects.requireNonNull(path);
        return resources.get(path);
    }

    public boolean contains(ModuleData res) {
        Objects.requireNonNull(res);
        return get(res.getPath()) != null;
    }

    public boolean isEmpty() {
        return resources.isEmpty();
    }

    public void visit(Visitor visitor, Pool output) {
        for (ModuleData resource : getContent()) {
            ModuleData res = visitor.visit(resource);
            if (res != null) {
                output.add(res);
            }
        }
    }

    public ByteOrder getByteOrder() {
        return order;
    }

    public void addTransformedResource(ModuleData original, InputStream transformed, long length) {
        if (isReadOnly()) {
            throw new PluginException("Pool is readonly");
        }
        Objects.requireNonNull(original);
        Objects.requireNonNull(transformed);
        if (get(original.getPath()) != null) {
            throw new PluginException("Resource already present");
        }
        ModuleData res = new ModuleData(original.getModule(), original.getPath(),
                original.getType(), transformed, length);
        add(res);
    }

    public static ModuleData newResource(String path, InputStream content, long size) {
        Objects.requireNonNull(path);
        Objects.requireNonNull(content);
        String[] split = ImageFileCreator.splitPath(path);
        String module = split[0];
        return new ModuleData(module, path, ModuleDataType.CLASS_OR_RESOURCE, content, size);
    }

    public static ModuleData newResource(String path, byte[] content) {
        return newResource(path, new ByteArrayInputStream(content),
                content.length);
    }

    public static ModuleData newImageFile(String module, String path, ModuleDataType type,
            InputStream content, long size) {
        Objects.requireNonNull(path);
        Objects.requireNonNull(content);
        return new ModuleData(module, path, type, content, size);
    }

}
