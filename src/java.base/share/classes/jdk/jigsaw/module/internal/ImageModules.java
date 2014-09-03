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

package jdk.jigsaw.module.internal;

import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintStream;
import java.util.Collections;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import jdk.jigsaw.module.Configuration;
import jdk.jigsaw.module.ExtendedModuleDescriptor;
import jdk.jigsaw.module.ModuleDependence;
import jdk.jigsaw.module.ModuleDependence.Modifier;
import jdk.jigsaw.module.ModuleDescriptor;
import jdk.jigsaw.module.ModuleExport;
import jdk.jigsaw.module.ModuleId;
import jdk.jigsaw.module.ServiceDependence;

import static jdk.jigsaw.module.internal.ImageModules.Loader.*;

/**
 * Installed Modules stored in the modular image on disk format.
 *
 * ImageModules {
 *   u4          magic;
 *   u2          major_version;
 *   u2          minor_version;
 *   module_data module_data;
 *   graph       readability_graph;
 * }
 *
 * module_data {
 *   u4   module_count;
 *   utf8 module_names[module_count+1];       // the 0th entry is not used.
 *   u4   package_count;
 *   utf8 package_names[package_count+1];     // the 0th entry is not used
 *   u2   service_count;
 *   utf8 service_names[service_count+1];     // the 0th entry is not used
 *   module_info module_info[module_count+1];
 * }
 *
 * utf8 {
 *   u2 length;
 *   u1 bytes[length];
 * }
 *
 * module_info {
 *   u4  module_id;                // index to module_name array
 *   u4  module_dependence_count;
 *   module_dependence  module_dependences[module_dependence_count];
 *   u4  export_count;
 *   package_info exports[export_count];
 *   u2  use_count;
 *   u4  uses[use_count];          // index to service_names table
 *   u2  provider_count;
 *   provider_info providers[provider_count];
 *   u4  pkg_count;
 *   u4  local_package[pkg_count]; // index to package_names table
 * }
 *
 * module_dependence {
 *   u2 modifier;   // 1: PUBLIC
 *   u4 module_id;
 * }
 *
 * service_info {
 *   utf8 service_name;
 * }
 *
 * provider_info {
 *   u4   service_id;     // index to the services table
 *   utf8 provider_impl;
 * }
 *
 * package_info {
 *   u4   package_name;   // index to the package_names table
 *   u4   module_id;      // 0 if unqualified export when representing exports
 * }
 *
 * // Each module is a node in the readability graph
 * // grouped by class loader
 * graph {
 *   u4   module_count;
 *   node readability_graph[module_count];
 * }
 *
 * node {
 *   u4  module_id;                // index to module_names array
 *   u4  readable_module_count;
 *   u4  readable_modules[readable_module_count];
 * }
 *
 * loader_data {
 *   u2  loader_count;
 *   loader_info loader_info[loader_count];
 * }
 *
 * loader_info {
 *   u2  loader_id;
 *   u4  module_count;
 *   u4  modules[module_count];
 * }
 *
 * loader_id must be:
 *    0: bootstrap class loader
 *    1: extension class loader
 *    2: Other (class loader mapping TBD)
 */
public final class ImageModules {
    public static final String FILE = "modules.jdata";
    private static final int MAGIC = 0xcafe00fa;
    private static final int MAJOR_VERSION = 0;
    private static final int MINOR_VERSION = 1;

    private final Map<Loader, LoaderModuleData> loaders = new LinkedHashMap<>();
    private final Map<String, ExtendedModuleDescriptor> nameToModule = new LinkedHashMap<>(); // ordered
    private final Map<String, Set<String>> services = new HashMap<>();
    private final Map<String, Set<String>> localPkgs = new HashMap<>();
    private final Map<String, Map<String, String>> exports = new HashMap<>();
    private final Map<String, Set<String>> readableModules = new HashMap<>();

    private ImageModules() { }

    public ImageModules(Configuration cf,
                        Set<ModuleDescriptor> bootModules,
                        Set<ModuleDescriptor> extModules,
                        Set<ModuleDescriptor> appModules) throws IOException {
        mapModulesToLoader(BOOT_LOADER, bootModules);
        mapModulesToLoader(EXT_LOADER, extModules);
        mapModulesToLoader(APP_LOADER, appModules);

        // build readableModules map
        cf.descriptors().stream()
            .forEach(md -> {
                Set<String> rms = cf.readDependences(md).stream()
                                       .map(ModuleDescriptor::name)
                                       .collect(Collectors.toSet());
                readableModules.put(md.name(), rms);
            });
    }
    /**
     * Returns ImageModules that loads the installed modules
     * from the given InputStream.
     */
    public static ImageModules load(InputStream in) throws IOException {
        ImageModules imf = new ImageModules();
        imf.readInstalledModules(in);
        return imf;
    }

    private void readInstalledModules(InputStream in) throws IOException {
        Reader reader = new Reader();
        reader.load(in);
    }

    /**
     * Returns the modules installed in the image.
     */
    public Set<ExtendedModuleDescriptor> modules() {
        return new HashSet<>(nameToModule.values());
    }

    public Map<String, Set<String>> packages() {
        return localPkgs;
    }

    // ## FIXME: should be package-private
    // When jlink legacy format support is removed, it should
    // use the package table in the jimage.
    public void setPackages(String mn, Set<String> pkgs) {
        localPkgs.put(mn, pkgs);
    }

    /*
     * Returns the name of modules mapped to a given class loader in the image
     */
    Set<String> getModules(Loader type) {
        return loaders.get(type).modules();
    }

    /**
     * Store the modules installed in the image to the given OutputStream.
     */
    public void store(OutputStream out) throws IOException {
        Writer writer = new Writer();
        writer.buildIndex();
        writer.store(out);
    }

    private void mapModulesToLoader(Loader loader, Set<ModuleDescriptor> modules) {
        if (modules.isEmpty())
            return;

        // put java.base first
        Set<String> mods = new LinkedHashSet<>();
        modules.stream()
               .filter(md ->  md.name().equals("java.base"))
               .forEach(m -> {
                    String mn = m.name();
                    nameToModule.put(mn, toExtendedModuleDescriptor(m));
                    mods.add(mn);
                });
        modules.stream().sorted(Comparator.comparing(m -> m.name()))
               .filter(m ->  !m.name().equals("java.base"))
               .forEach(m -> {
                   String mn = m.name();
                   nameToModule.put(mn, toExtendedModuleDescriptor(m));
                   mods.add(mn);
                });

        // service and providers
        modules.stream()
            .forEach(m -> {
                m.serviceDependences().stream()
                    .map(ServiceDependence::service)
                    .forEach(s -> services.computeIfAbsent(s, _k -> new HashSet<>()));
                // ## duplicated provider names?
                m.services().entrySet().stream()
                    .forEach(s -> services.computeIfAbsent(s.getKey(), _k -> new HashSet<>())
                                          .addAll(s.getValue()));
            });
        loaders.put(loader, new LoaderModuleData(loader, mods));
    }

    private ExtendedModuleDescriptor toExtendedModuleDescriptor(ModuleDescriptor descriptor) {
        String mn = descriptor.name();
        ExtendedModuleDescriptor.Builder builder = new ExtendedModuleDescriptor.Builder(mn);
        descriptor.moduleDependences().forEach(builder::requires);
        descriptor.serviceDependences().forEach(builder::requires);
        descriptor.exports().forEach(builder::export);
        descriptor.services().entrySet().forEach(e
                -> e.getValue().forEach(impl -> builder.service(e.getKey(), impl)));
        return builder.build();
    }


    enum Loader {
        BOOT_LOADER(0, "bootmodules"),
        EXT_LOADER(1, "extmodules"),
        APP_LOADER(2, "appmodules");  // ## may be more than 1 loader

        final int id;
        final String name;
        Loader(int id, String name) {
            this.id = id;
            this.name = name;
        }

        String getName() {
            return name;
        }
        static Loader get(int id) {
            switch (id) {
                case 0: return BOOT_LOADER;
                case 1: return EXT_LOADER;
                case 2: return APP_LOADER;
                default:
                    throw new IllegalArgumentException("invalid loader id: " + id);
            }
        }
    }

    private class LoaderModuleData {
        private final Loader loader;
        private final Set<String> modules;
        LoaderModuleData(Loader loader, Set<String> modules) {
            this.loader = loader;
            this.modules = Collections.unmodifiableSet(modules);
        }

        Set<String> modules() {
            return modules;
        }
    }

    private class Writer {
        private final Map<String, Integer> indexForModule = new LinkedHashMap<>();
        private final Map<String, Integer> indexForPackage = new LinkedHashMap<>();
        private final Map<String, Integer> indexForService = new LinkedHashMap<>();
        private int moduleIndex = 1;  // 1 reserved for java.base
        private int packageIndex = 1;
        private int serviceIndex = 1;
        //
        // index maps are stored and read in order when writing to the disk
        public void buildIndex() {
            nameToModule.keySet()
                    .forEach(mn -> indexForModule.put(mn, moduleIndex++));
            services.keySet().stream().sorted()
                    .forEach(s -> indexForService.put(s, serviceIndex++));
            localPkgs.values().stream()
                    .flatMap(v -> v.stream())
                    .distinct()
                    .sorted()
                    .forEach(pn -> indexForPackage.computeIfAbsent(pn, _i -> packageIndex++));
        }

        public void store(OutputStream os) throws IOException {
            try (DataOutputStream out = new DataOutputStream(os)) {
                // write header
                out.writeInt(MAGIC);
                out.writeShort(MAJOR_VERSION);
                out.writeShort(MINOR_VERSION);
                // write module data table
                storeModuleDataTable(out);
                // write loader data table
                storeLoaderDataTable(out);
                // write readability graph
                storeGraph(out);
                out.flush();
            }
        }

        void storeModuleDataTable(DataOutputStream out) throws IOException {
            // module index, module name, loader
            int nModule = indexForModule.size();
            out.writeInt(nModule);
            out.writeUTF("");       // 0th entry
            for (String mn : indexForModule.keySet()) {
                out.writeUTF(mn);
            }
            // local package table
            out.writeInt(indexForPackage.size());
            out.writeUTF("");   // 0th entry
            for (String pn : indexForPackage.keySet()) {
                out.writeUTF(pn);
            }
            // services
            out.writeInt(indexForService.size());
            out.writeUTF("");
            for (String s : indexForService.keySet()) {
                out.writeUTF(s);
            }
            // module-info
            for (String mn : indexForModule.keySet()) {
                storeModuleInfo(out, mn);
            }
        }

        void storeGraph(DataOutputStream out) throws IOException {
            // readability graph in per-class loader order
            out.writeInt(readableModules.keySet().size());
            for (LoaderModuleData l : loaders.values()) {
                for (String mn : l.modules()) {
                    int mid = indexForModule.get(mn);
                    out.writeInt(mid);
                    // readable modules
                    Set<String> readables = readableModules.get(mn);
                    out.writeInt(readables.size());
                    // for each module, modules readable by m
                    for (String rmn : readables) {
                        out.writeInt(indexForModule.get(rmn));
                    }
                }
            }
        }

        void storeLoaderDataTable(DataOutputStream out) throws IOException {
            // per classloader module map
            out.writeShort(loaders.size());
            for (LoaderModuleData l : loaders.values()) {
                out.writeShort(l.loader.id);
                out.writeInt(l.modules().size());
                for (String mn : l.modules()) {
                    out.writeInt(indexForModule.get(mn));
                }
            }
        }

        void storeModuleInfo(DataOutputStream out, String mn) throws IOException {
            int mid = indexForModule.get(mn);
            out.writeInt(mid);
            // module dependences
            ModuleDescriptor md = nameToModule.get(mn);
            out.writeInt(md.moduleDependences().size());
            // for each module, modules readable by m
            for (ModuleDependence d : md.moduleDependences()) {
                int mods = d.modifiers().contains(ModuleDependence.Modifier.PUBLIC) ? 1 : 0;
                out.writeShort(mods);
                out.writeInt(indexForModule.get(d.query().name()));
            }

            // exports
            Set<ModuleExport> exports = md.exports().stream()
                    .filter(e -> e.permit() == null || indexForModule.containsKey(e.permit()))
                    .collect(Collectors.toSet());
            out.writeInt(exports.size());
            for (ModuleExport export : exports) {
                String pn = export.pkg();
                out.writeInt(indexForPackage.get(pn));
                String permit = export.permit();
                if (permit == null) {
                    out.writeInt(0);
                } else {
                    out.writeInt(indexForModule.get(permit));
                }
            }

            // services
            out.writeShort(md.serviceDependences().size());
            for (ServiceDependence s : md.serviceDependences()) {
                String service = s.service();
                out.writeInt(indexForService.get(service));
            }
            // provides
            Map<String,Set<String>> services = md.services();
            int count = services.values().stream().mapToInt(Set::size).sum();
            out.writeShort(count);
            for (Map.Entry<String,Set<String>> s : services.entrySet()) {
                String service = s.getKey();
                int si = indexForService.get(service);
                for (String impl : s.getValue()) {
                    out.writeInt(si);
                    out.writeUTF(impl);
                }
            }
            // local packages
            Set<String> pkgs = localPkgs.get(mn);
            out.writeInt(pkgs.size());
            for (String pn : pkgs) {
                out.writeInt(indexForPackage.get(pn));
            }
        }
    }

    private class Reader {
        private final Map<Integer, String> indexToModule = new HashMap<>();
        private final Map<Integer, String> indexToPackage = new HashMap<>();
        private final Map<Integer, String> indexToService = new HashMap<>();
        void load(InputStream in) throws IOException {
            try (BufferedInputStream bin = new BufferedInputStream(in);
                 DataInputStream din = new DataInputStream(bin)) {
                if (din.readInt() != MAGIC) {
                    throw new IOException("Invalid magic number");
                }
                int maj = din.readShort();
                int min = din.readShort();
                if (maj > MAJOR_VERSION
                        || (maj == MAJOR_VERSION && min > MINOR_VERSION)) {
                    throw new IOException("invalid version number");
                }
                loadModuleDataTable(din);
                loadLoaderDataTable(din);
                loadGraph(din);
            }
        }

        void loadModuleDataTable(DataInputStream in) throws IOException {
            // module name table
            int nModules = in.readInt();
            // skip the 0th entry
            in.readUTF();
            for (int mid=1; mid <= nModules; mid++) {
                String mn = in.readUTF();
                indexToModule.put(mid, mn);
            }

            // local package table
            int nPkgs = in.readInt();

            // skip the 0th entry
            in.readUTF();
            for (int i=1; i <= nPkgs; i++) {
                String pn = in.readUTF();
                indexToPackage.put(i, pn);
            }

            // services
            int nServices = in.readInt();
            // skip the 0th entry
            in.readUTF();
            for (int i=1; i <= nServices; i++) {
                String s = in.readUTF();
                indexToService.put(i, s);
            }
            for (int i=0; i < nModules; i++) {
                loadModuleInfo(in);
            }
        }

        void loadGraph(DataInputStream in) throws IOException {
            // readability graph
            int n = in.readInt();
            for (int i=0; i < n; i++) {
                int mid = in.readInt();
                int nReadables = in.readInt();
                Set<String> readables = new HashSet<>();
                for (int j=0; j < nReadables; j++) {
                    int r = in.readInt();
                    readables.add(indexToModule.get(r));
                }
                String mn = indexToModule.get(mid);
                readableModules.put(mn, readables);
            }
        }

        void loadModuleInfo(DataInputStream in) throws IOException {
            String mn = indexToModule.get(in.readInt());
            ExtendedModuleDescriptor.Builder builder = new ExtendedModuleDescriptor.Builder(mn);

            ModuleId mid = ModuleId.parse(mn);

            int nRequires = in.readInt();
            for (int j = 0; j < nRequires; j++) {
                Set<Modifier> mods = in.readShort() == 0
                                         ? EnumSet.noneOf(Modifier.class)
                                         : EnumSet.of(Modifier.PUBLIC);
                int d = in.readInt();
                String dm = indexToModule.get(d);
                ModuleDependence md = new ModuleDependence(mods, dm);
                builder.requires(md);

            }
            int nExports = in.readInt();
            Map<String, String> exps =
                exports.computeIfAbsent(indexToModule.get(mid),
                                        _k -> new HashMap<>());
            for (int k = 0; k < nExports; k++) {
                int pi = in.readInt();
                String pkg = indexToPackage.get(pi);
                int to = in.readInt();
                String pmn = to == 0 ? "" : indexToModule.get(to);
                exps.put(pkg, pmn);
                if (to == 0) {
                    builder.export(pkg);
                } else {
                    builder.export(pkg, pmn);
                }
            }
            short nUses = in.readShort();
            for (int j = 0; j < nUses; j++) {
                int si = in.readInt();
                ServiceDependence sd =
                    new ServiceDependence(EnumSet.noneOf(ServiceDependence.Modifier.class),
                                          indexToService.get(si));
                builder.requires(sd);
            }
            short nServices = in.readShort();
            for (int j = 0; j < nServices; j++) {
                int si = in.readInt();
                String service = indexToService.get(si);
                String impl = in.readUTF();
                services.computeIfAbsent(service, _k -> new HashSet<>())
                        .add(impl);
                builder.service(service, impl);
            }

            // local packages
            localPkgs.computeIfAbsent(mn, _k -> new HashSet<>());
            int nPkgs = in.readInt();
            for (int j = 0; j < nPkgs; j++) {
                int pi = in.readInt();
                String pkg = indexToPackage.get(pi);
                localPkgs.get(mn).add(pkg);
            }
            ExtendedModuleDescriptor m = builder.build();
            nameToModule.put(mn, m);
        }

        void loadLoaderDataTable(DataInputStream in) throws IOException {
            // loader module map and local packages
            int numLoaders = in.readShort();
            for (int i=0; i < numLoaders; i++) {
                int loaderId = in.readShort();
                Loader loader = Loader.get(loaderId);
                int nMods = in.readInt();
                Set<String> mods = new LinkedHashSet<>();
                for (int j=0; j < nMods; j++) {
                    int mid = in.readInt();
                    String mn = indexToModule.get(mid);
                    mods.add(mn);
                }
                loaders.put(loader, new LoaderModuleData(loader, mods));
            }
        }

        // for debugging
        private void dumpModule(PrintStream out, String mn) {
            ModuleDescriptor m = nameToModule.get(mn);
            out.format("Module { id: %s%n", m.name());
            m.moduleDependences().forEach(md -> out.format("  requires %s%s%n",
                    md.modifiers().contains(Modifier.PUBLIC) ? "public" : "", md.query().name()));
            m.exports().stream().filter(e -> e.permit() == null)
                    .sorted(Comparator.comparing(ModuleExport::pkg))
                    .forEach(e -> out.format("  exports %s%n", e.pkg()));
            m.exports().stream().filter(e -> e.permit() != null)
                    .sorted(Comparator.comparing(ModuleExport::pkg))
                    .forEach(e -> out.format("  exports %s to %s%n", e.pkg(), e.permit()));
            m.serviceDependences().forEach(sd -> out.format("  uses %s%n", sd.service()));
            m.services().entrySet().stream()
                    .sorted(Map.Entry.comparingByKey())
                    .forEach(e -> e.getValue().stream()
                            .sorted().forEach(impl -> out.format("  provides %s with %s%n", e.getKey(), impl)));
        }

        public void dump(PrintStream out) {
            out.format("Total %d modules.%n", indexToModule.size());
            indexToModule.entrySet().stream()
                    .forEach(e -> out.format("%d: %s%n", e.getKey(), e.getValue()));
            out.format("Service providers:%n");
            indexToService.entrySet().stream()
                    .forEach(e -> out.format("%d: %s%n", e.getKey(), e.getValue()));
            services.entrySet().stream()
                    .sorted(Map.Entry.comparingByKey())
                    .forEach(e -> e.getValue().stream()
                            .sorted().forEach(impl -> out.format("  provides %s with %s%n", e.getKey(), impl)));
            out.println("Readability graph");
            readableModules.entrySet().stream()
                    .forEach(e -> {
                        out.format("  %s -> %s%n",
                                e.getKey().isEmpty() ? "unnamed" : e.getKey(),
                                e.getValue().stream().sorted().collect(Collectors.joining(", ")));
                    });
            loaders.values().stream().forEach(l
                    -> l.modules().stream().forEach(mn -> dumpModule(out, mn)));
        }
    }
}
