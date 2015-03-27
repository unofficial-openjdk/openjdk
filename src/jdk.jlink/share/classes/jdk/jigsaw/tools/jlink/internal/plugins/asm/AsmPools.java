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
package jdk.jigsaw.tools.jlink.internal.plugins.asm;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Objects;
import jdk.jigsaw.tools.jlink.plugins.ResourcePool.Resource;
import jdk.internal.org.objectweb.asm.ClassReader;
import jdk.internal.org.objectweb.asm.ClassWriter;
import jdk.jigsaw.tools.jlink.internal.ResourcePoolImpl;
import jdk.jigsaw.tools.jlink.internal.plugins.asm.AsmPool.Sorter;
import jdk.jigsaw.tools.jlink.plugins.ResourcePool;

/**
 * A container for pools of ClassReader and other resource files. A pool of all
 * the resources or a pool for a given module can be retrieved
 */
public final class AsmPools {

    /**
     * Sort the order in which the modules will be stored in the jimage file.
     */
    public interface ModuleSorter {

        /**
         * Sort the list of modules.
         *
         * @param modules The list of module names. The module will be stored in
         * the jimage following this order.
         * @return A list of module names that expresses the order in which the
         * modules are stored in the jimage.
         */
        public List<String> sort(List<String> modules);
    }

    private class AsmGlobalPoolImpl implements AsmGlobalPool {

        private ClassReader[] allReaders = null;
        private ResourceFile[] allFiles = null;
        private Sorter sorter = null;

        private class GlobalWritableClassPool implements WritableClassPool {

            @Override
            public void addClass(ClassWriter writer) throws IOException {
                visitFirstNonFailingPool((AsmModulePool pool) -> {
                    pool.getTransformedClasses().addClass(writer);
                });
            }

            @Override
            public void forgetClass(String className) throws IOException {
                visitFirstNonFailingPool((AsmModulePool pool) -> {
                    pool.getTransformedClasses().forgetClass(className);
                });
            }

            @Override
            public ClassReader getClassReader(String binaryName) {
                return visitPools((AsmModulePool pool) -> {
                    return pool.getTransformedClasses().getClassReader(binaryName);
                });
            }

            @Override
            public ClassReader[] getClassReaders() {
                List<ClassReader> all = new ArrayList<>();
                visitAllPools((AsmModulePool pool) -> {
                    for (ClassReader rf : pool.getTransformedClasses().getClassReaders()) {
                        all.add(rf);
                    }
                });
                ClassReader[] ret = new ClassReader[all.size()];
                all.toArray(ret);
                return ret;
            }

        }

        private class GlobalWritableResourcePool implements WritableResourcePool {

            @Override
            public void addResourceFile(ResourceFile resFile) throws IOException {
                visitFirstNonFailingPool((AsmModulePool pool) -> {
                    pool.getTransformedResourceFiles().addResourceFile(resFile);
                });
            }

            @Override
            public void forgetResourceFile(String resourceName) throws IOException {
                visitFirstNonFailingPool((AsmModulePool pool) -> {
                    pool.getTransformedResourceFiles().forgetResourceFile(resourceName);
                });
            }

            @Override
            public ResourceFile getResourceFile(String name) {
                return visitPools((AsmModulePool pool) -> {
                    return pool.getTransformedResourceFiles().getResourceFile(name);
                });
            }

            @Override
            public ResourceFile[] getResourceFiles() {
                List<ResourceFile> all = new ArrayList<>();
                visitAllPools((AsmModulePool pool) -> {
                    for (ResourceFile rf : pool.getTransformedResourceFiles().getResourceFiles()) {
                        all.add(rf);
                    }
                });
                ResourceFile[] ret = new ResourceFile[all.size()];
                all.toArray(ret);
                return ret;
            }

        }

        @Override
        public AsmPool.WritableClassPool getTransformedClasses() {
            return new GlobalWritableClassPool();
        }

        @Override
        public AsmPool.WritableResourcePool getTransformedResourceFiles() {
            return new GlobalWritableResourcePool();
        }

        @Override
        public void setSorter(AsmPool.Sorter sorter) {
            this.sorter = sorter;
        }

        @Override
        public ClassReader[] getClassReaders() {
            if (allReaders == null) {
                List<ClassReader> all = new ArrayList<>();
                visitAllPools((AsmModulePool pool) -> {
                    for (ClassReader rf : pool.getClassReaders()) {
                        all.add(rf);
                    }
                });
                allReaders = new ClassReader[all.size()];
                all.toArray(allReaders);
            }
            return allReaders;
        }

        @Override
        public AsmPool.ResourceFile[] getResourceFiles() {
            if (allFiles == null) {
                List<ResourceFile> all = new ArrayList<>();
                visitAllPools((AsmModulePool pool) -> {
                    for (ResourceFile rf : pool.getResourceFiles()) {
                        all.add(rf);
                    }
                });
                allFiles = new ResourceFile[all.size()];
                all.toArray(allFiles);
            }
            return allFiles;
        }

        @Override
        public AsmPool.ResourceFile getResourceFile(String binaryName) {
            return visitPools((AsmModulePool pool) -> {
                return pool.getResourceFile(binaryName);
            });
        }

        @Override
        public ClassReader getClassReader(String binaryName) throws IOException {
            return visitPoolsEx((AsmModulePool pool) -> {
                return pool.getClassReader(binaryName);
            });
        }

        @Override
        public void visitClassReaders(AsmPool.ClassReaderVisitor visitor)
                throws IOException {
            visitAllPoolsEx((AsmModulePool pool) -> {
                pool.visitClassReaders(visitor);
            });
        }

        @Override
        public void visitResourceFiles(AsmPool.ResourceFileVisitor visitor)
                throws IOException {
            visitAllPoolsEx((AsmModulePool pool) -> {
                pool.visitResourceFiles(visitor);
            });
        }

        @Override
        public void fillOutputResources(ResourcePool outputResources) throws Exception {
            AsmPools.this.fillOutputResources(outputResources);
        }

        @Override
        public void addPackageModuleMapping(String pkg, String module)
                throws Exception {
            AsmModulePool p = pools.get(module);
            if (p == null) {
                throw new Exception("Unknown module " + module);
            }
            p.addPackage(pkg);
        }
    }

    private interface VoidPoolVisitor {

        void visit(AsmModulePool pool);
    }

    private interface VoidPoolVisitorEx {

        void visit(AsmModulePool pool) throws IOException;
    }

    private interface RetPoolVisitor<P> {

        P visit(AsmModulePool pool) throws IOException;
    }

    private final ResourcePool inputResources;
    private final Map<String, AsmModulePool> pools = new LinkedHashMap<>();
    private final AsmModulePool[] poolsArray;
    private final AsmGlobalPoolImpl global;

    private ModuleSorter moduleSorter;

    /**
     * A new Asm pools.
     *
     * @param inputResources The raw resources to build the pool from.
     * @throws IOException
     */
    public AsmPools(ResourcePool inputResources) throws Exception {
        Objects.requireNonNull(inputResources);
        this.inputResources = inputResources;
        Map<String, ResourcePool> resPools = new LinkedHashMap<>();
        for (Resource res : inputResources.getResources()) {
            ResourcePool p = resPools.get(res.getModule());
            if (p == null) {
                p = new ResourcePoolImpl(inputResources.getByteOrder());
                resPools.put(res.getModule(), p);
            }
            p.addResource(res);
        }
        poolsArray = new AsmModulePool[resPools.size()];
        int i = 0;

        for (Entry<String, ResourcePool> entry : resPools.entrySet()) {
            AsmModulePool p = new AsmPoolImpl(entry.getValue(), entry.getKey());
            pools.put(entry.getKey(), p);
            poolsArray[i] = p;
            i += 1;
        }
        global = new AsmGlobalPoolImpl();
    }

    /**
     * The pool containing all classes and other resources.
     *
     * @return The global pool
     */
    public AsmGlobalPool getGlobalPool() {
        return global;
    }

    /**
     * A pool for a given module
     *
     * @param name The module name
     * @return The pool that contains content of the passed module or null if
     * the module doesn't exist.
     */
    public AsmModulePool getModulePool(String name) {
        Objects.requireNonNull(name);
        return pools.get(name);
    }

    /**
     * The array of module pools.
     * @return The module pool array.
     */
    public AsmModulePool[] getModulePools() {
        return poolsArray;
    }

    /**
     * Set a module sorter. Sorter is used when computing the output resources.
     *
     * @param moduleSorter The module sorter
     */
    public void setModuleSorter(ModuleSorter moduleSorter) {
        Objects.requireNonNull(moduleSorter);
        this.moduleSorter = moduleSorter;
    }

    /**
     * Returns the pool of all the resources (transformed and unmodified). The
     * input resources are replaced by the transformed ones. If a sorter has
     * been set, it is used to sort in modules.
     *
     * @param outputResources The pool used to fill the jimage.
     * @throws Exception
     */
    public void fillOutputResources(ResourcePool outputResources) throws Exception {
        // First sort modules
        List<String> modules = new ArrayList<>();
        for (String k : pools.keySet()) {
            modules.add(k);
        }
        if (moduleSorter != null) {
            modules = moduleSorter.sort(modules);
        }
        ResourcePool output = new ResourcePoolImpl(outputResources.getByteOrder());
        for (String mn : modules) {
            AsmPool pool = pools.get(mn);
            pool.fillOutputResources(output);
        }
        sort(outputResources, output, global.sorter);
    }

    static void sort(ResourcePool outputResources,
            ResourcePool transientOutput, Sorter sorter) throws Exception {
        if (sorter != null) {
            List<String> order = sorter.sort(transientOutput);
            for (String s : order) {
                outputResources.addResource(transientOutput.getResource(s));
            }
        } else {
            for(Resource res : transientOutput.getResources()) {
                outputResources.addResource(res);
            }
        }
    }

    private void visitFirstNonFailingPool(VoidPoolVisitorEx pv) throws IOException {
        boolean found = false;
        for (Entry<String, AsmModulePool> entry : pools.entrySet()) {
            try {
                pv.visit(entry.getValue());
                found = true;
                break;
            } catch (Exception ex) {
                // XXX OK, try  another one.
            }
        }
        if (!found) {
            throw new IOException("No module found");
        }
    }

    private void visitAllPools(VoidPoolVisitor pv) {
        for (Entry<String, AsmModulePool> entry : pools.entrySet()) {
            pv.visit(entry.getValue());
        }
    }

    private void visitAllPoolsEx(VoidPoolVisitorEx pv) throws IOException {
        for (Entry<String, AsmModulePool> entry : pools.entrySet()) {
            pv.visit(entry.getValue());
        }
    }

    private <P> P visitPoolsEx(RetPoolVisitor<P> pv) throws IOException {
        P p = null;
        for (Entry<String, AsmModulePool> entry : pools.entrySet()) {
            try {
                p = pv.visit(entry.getValue());
                if (p != null) {
                    break;
                }
            } catch (Exception ex) {
                // XXX OK, try  another one.
            }
        }
        return p;
    }

    private <P> P visitPools(RetPoolVisitor<P> pv) {
        P p = null;
        for (Entry<String, AsmModulePool> entry : pools.entrySet()) {
            try {
                p = pv.visit(entry.getValue());
                if (p != null) {
                    break;
                }
            } catch (Exception ex) {
                // XXX OK, try  another one.
            }
        }
        return p;
    }
}
