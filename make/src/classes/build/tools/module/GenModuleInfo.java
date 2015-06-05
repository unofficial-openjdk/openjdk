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

package build.tools.module;

import build.tools.module.Module.Dependence;
import com.sun.tools.classfile.*;
import com.sun.tools.classfile.Attribute;
import static com.sun.tools.classfile.Module_attribute.RequiresEntry;
import static com.sun.tools.classfile.Module_attribute.ExportsEntry;
import static com.sun.tools.classfile.Module_attribute.ProvidesEntry;

import static com.sun.tools.classfile.ConstantPool.*;

import javax.xml.stream.XMLStreamException;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * GenModuleInfo build tool will generate module-info.java of JDK modules.
 *
 * $ java build.tools.module.GenModuleInfo \
 *        -mp $JDK_OUTPUTDIR/modules \
 *        -o $TOP_REPO \
 *        top/modules.xml
 *
 * This will generate module-info.java of the JDK modules listed in
 * the given modules.xml merged with the module definition,
 * i.e. module-info.class, in the given modulepath.
 *
 */
public final class GenModuleInfo {
    private final static String USAGE =
        "Usage: GenModuleInfo [-diff] [-o <top-repo-path>] -mp <modulepath> <path-to-modules-xml>";

    public static void main(String[] args) throws Exception {
        Path outputdir = null;
        Path moduleinfos = null;
        boolean diff = false;
        boolean verbose = false;
        Stream<String> xmlfiles = null;
        int i = 0;
        while (i < args.length) {
            String arg = args[i++];
            switch (arg) {
                case "-o":
                    outputdir = Paths.get(args[i++]);
                    break;
                case "-mp":
                    moduleinfos = Paths.get(args[i++]);
                    if (!Files.isDirectory(moduleinfos)) {
                        System.err.println(moduleinfos + " is not a directory");
                        System.exit(1);
                    }
                    break;
                case "-diff":
                    diff = true;
                    break;
                case "-v":
                    verbose = true;
                    break;
                default:
                    xmlfiles = Arrays.stream(args, i-1, args.length);
                    i = args.length;
            }
        }
        if ((outputdir == null && !diff) || moduleinfos == null || xmlfiles == null) {
            System.err.println(USAGE);
            System.exit(-1);
        }
        GenModuleInfo tool = new GenModuleInfo(moduleinfos, outputdir, xmlfiles, verbose);
        if (diff) {
            tool.diffs();
        }
        if (outputdir != null) {
            tool.genModuleInfos();
        }
    }

    private static Set<Module> readModules(String xmlfile) {
        try {
            return ModulesXmlReader.readModules(Paths.get(xmlfile));
        } catch (XMLStreamException | IOException e) {
            throw new InternalError(e);
        }
    }

    private final Path modulepath;
    private final SourceLocation sourceLocation;
    private final Map<String, Module> modules;
    private final Map<String, Module> moduleInfoClasses;
    private final boolean verbose;
    private int modified = 0;
    private int created = 0;
    private int unchanged = 0;
    public GenModuleInfo(Path modulepath, Path sourceDir, Stream<String> xmls, boolean verbose)
            throws IOException
    {
        this.modulepath = modulepath;
        this.sourceLocation = new SourceLocation(sourceDir);
        this.modules = xmls.flatMap(f -> readModules(f).stream())
                           .collect(Collectors.toMap(Module::name, Function.identity()));
        this.moduleInfoClasses = readModuleInfos();
        this.verbose = verbose;
    }

    void genModuleInfos() {
        Stream.concat(modules.keySet().stream(), moduleInfoClasses.keySet().stream())
                .distinct().sorted()
                .forEach(this::genModuleInfo);
        System.out.format("%d module-info.java modified %d created %d unchanged%n",
                          modified, created, unchanged);
    }

    void diffs() {
        Stream.concat(modules.keySet().stream(), moduleInfoClasses.keySet().stream())
                .distinct().sorted()
                .filter(mn -> !DiffUtil.equals(modules.get(mn), moduleInfoClasses.get(mn)))
                .forEach(mn -> DiffUtil.diff(header(mn), modules.get(mn), moduleInfoClasses.get(mn)));
    }

    private void genModuleInfo(String mn) {
        Module m = modules.get(mn);
        Module minfo = moduleInfoClasses.get(mn);
        assert m != null || minfo != null;
        if (m != null && minfo != null && DiffUtil.equals(m, minfo)) {
            unchanged++;
            return;
        }
        Module module = (m != null && minfo != null)
                                ? new Module.Builder().merge(m, minfo).build()
                                : (m != null ? m : minfo);

        List<Path> paths = sourceLocation.findSourceLocation(mn);
        if (m == null) {
            paths.stream().forEach(p ->
                    System.err.format("Warning: module %s not in modules.xml source %s%n", mn, p));
            unchanged++;
            return;
        } else if (minfo == null) {
            System.err.format("Warning: module %s not found from %s%n", mn, modulepath);
            unchanged++;
            return;
        }
        if (verbose) {
            DiffUtil.diff(header(mn), m, minfo);
        }
        if (paths.isEmpty()) {
            Path p = sourceLocation.getSourcePath("jdk", mn);
            if (Files.notExists(p)) {
                try {
                    Files.createDirectories(p);
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            }
            Path newModuleInfoJava = p.resolve("module-info.java");
            System.err.format("Warning: module %s not found in the source under %s%n",
                               mn, sourceLocation);
            writeModuleInfo(module, newModuleInfoJava);
        } else {
            paths.stream().forEach(p -> writeModuleInfo(module, p));
        }
    }

    private void writeModuleInfo(Module m, Path p) {
        if (Files.exists(p)) {
            modified++;
        } else {
            created++;
        }
        if (verbose) {
            System.out.format("%s %s%n", p, Files.exists(p) ? "modified" : "created");
        }
        try (PrintWriter writer = new PrintWriter(p.toFile())) {
            writeCopyrightHeader(writer);
            writer.println(m.toString());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private Map<String,Module> readModuleInfos() throws IOException {
         return Files.find(modulepath, 2, (Path p, BasicFileAttributes attr)
                      -> p.getFileName().toString().equals("module-info.class"))
                     .map(this::parseModuleInfo)
                     .collect(Collectors.toMap(Module::name, Function.identity()));
    }

    private Module parseModuleInfo(Path p) {
        try {
            ClassFile cf = ClassFile.read(p);
            int i = cf.getName().indexOf("/module-info");
            if (i <= 0) {
                throw new RuntimeException("Not module-info: " + cf.getName());
            }
            String mn = cf.getName().substring(0, i).replace('/', '.');
            ModuleInfoBuilder builder = new ModuleInfoBuilder(mn, cf);
            return builder.build();
        } catch (IOException | ConstantPoolException e) {
            throw new InternalError(e);
        }
    }

    private String header(String mn) {
        return String.format("--- %s %s%n", mn,
                moduleInfoClasses.containsKey(mn)
                        ? modulepath.resolve(mn).resolve("module-info.class")
                        : "");
    }

    class ModuleInfoBuilder extends AttributeVisitor<Void, Void> {
        private final Module.Builder builder;
        private final ClassFile cf;
        ModuleInfoBuilder(String mn, ClassFile cf) {
            this.builder = new Module.Builder();
            this.builder.name(mn);
            this.cf = cf;
        }

        public Module build() {
            Module_attribute attr = (Module_attribute) cf.getAttribute(Attribute.Module);
            attr.accept(this, null);
            return builder.build();
        }

        public Void visitModule(Module_attribute attr, Void p) {
            if (attr.requires_count > 0) {
                Arrays.stream(attr.requires)
                        .forEach(this::addRequire);
            }
            if (attr.exports_count > 0) {
                Arrays.stream(attr.exports)
                        .forEach(this::addExport);
            }
            if (attr.uses_count > 0) {
                Arrays.stream(attr.uses_index)
                        .mapToObj(this::getClassName)
                        .forEach(builder::use);
            }
            if (attr.provides_count > 0) {
                Arrays.stream(attr.provides)
                        .forEach(this::addProvide);
            }
            return null;
        }

        void addRequire(RequiresEntry r) {
            boolean reexport = r.requires_flags == Module_attribute.ACC_PUBLIC;
            builder.require(getString(r.requires_index), reexport);
        }

        void addExport(ExportsEntry e) {
            String pn = getString(e.exports_index).replace('/', '.');
            if (e.exports_to_count == 0) {
                builder.export(pn);
            } else {
                Set<String> permits = Arrays.stream(e.exports_to_index)
                        .mapToObj(this::getString)
                        .collect(Collectors.toSet());
                builder.exportTo(pn, permits);
            }
        }

        void addProvide(ProvidesEntry p) {
            String s = getClassName(p.provides_index);
            String impl = getClassName(p.with_index);
            builder.provide(s, impl);
        }


        private String getString(int index) {
            try {
                return cf.constant_pool.getUTF8Value(index);
            } catch (InvalidIndex | UnexpectedEntry e) {
                throw new RuntimeException(e);
            }
        }

        private String getClassName(int index) {
            try {
                return cf.constant_pool.getClassInfo(index).getName().replace('/', '.');
            } catch (ConstantPoolException e) {
                throw new RuntimeException(e);
            }
        }
    }

    class AttributeVisitor<R, P> implements Attribute.Visitor<R, P> {
        public R visitBootstrapMethods(BootstrapMethods_attribute attr, P p) { return null; }
        public R visitDefault(DefaultAttribute attr, P p) { return null; }
        public R visitAnnotationDefault(AnnotationDefault_attribute attr, P p) { return null; }
        public R visitCharacterRangeTable(CharacterRangeTable_attribute attr, P p) { return null; }
        public R visitCode(Code_attribute attr, P p) { return null; }
        public R visitCompilationID(CompilationID_attribute attr, P p) { return null; }
        public R visitConcealedPackages(ConcealedPackages_attribute attr, P p) { return null; }
        public R visitConstantValue(ConstantValue_attribute attr, P p) { return null; }
        public R visitDeprecated(Deprecated_attribute attr, P p) { return null; }
        public R visitEnclosingMethod(EnclosingMethod_attribute attr, P p) { return null; }
        public R visitExceptions(Exceptions_attribute attr, P p) { return null; }
        public R visitHashes(Hashes_attribute attr, P p) { return null; }
        public R visitInnerClasses(InnerClasses_attribute attr, P p) { return null; }
        public R visitLineNumberTable(LineNumberTable_attribute attr, P p) { return null; }
        public R visitLocalVariableTable(LocalVariableTable_attribute attr, P p) { return null; }
        public R visitLocalVariableTypeTable(LocalVariableTypeTable_attribute attr, P p) { return null; }
        public R visitMethodParameters(MethodParameters_attribute attr, P p) { return null; }
        public R visitMainClass(MainClass_attribute attr, P p) { return null; }
        public R visitModule(Module_attribute attr, P p) {
            return null;
        }
        public R visitRuntimeVisibleAnnotations(RuntimeVisibleAnnotations_attribute attr, P p) { return null; }
        public R visitRuntimeInvisibleAnnotations(RuntimeInvisibleAnnotations_attribute attr, P p) { return null; }
        public R visitRuntimeVisibleParameterAnnotations(RuntimeVisibleParameterAnnotations_attribute attr, P p) { return null; }
        public R visitRuntimeInvisibleParameterAnnotations(RuntimeInvisibleParameterAnnotations_attribute attr, P p) { return null; }
        public R visitRuntimeVisibleTypeAnnotations(RuntimeVisibleTypeAnnotations_attribute attr, P p) { return null; }
        public R visitRuntimeInvisibleTypeAnnotations(RuntimeInvisibleTypeAnnotations_attribute attr, P p) { return null; }
        public R visitSignature(Signature_attribute attr, P p) { return null; }
        public R visitSourceDebugExtension(SourceDebugExtension_attribute attr, P p) { return null; }
        public R visitSourceFile(SourceFile_attribute attr, P p) { return null; }
        public R visitSourceID(SourceID_attribute attr, P p) { return null; }
        public R visitStackMap(StackMap_attribute attr, P p) { return null; }
        public R visitStackMapTable(StackMapTable_attribute attr, P p) { return null; }
        public R visitSynthetic(Synthetic_attribute attr, P p) { return null; }
        public R visitVersion(Version_attribute attr, P p) { return null; }
    }

    static class DiffUtil {
        private static final String LEFT = "< ";
        private static final String RIGHT = "> ";
        static <T extends Comparable> void diff(Set<T> o1, Set<T> o2) {
            Stream.concat(o1.stream(), o2.stream())
                    .distinct()
                    .sorted()
                    .filter(e -> (!o1.contains(e) || !o2.contains(e)))
                    .forEach(e -> {
                        String diff = o1.contains(e) ? LEFT : RIGHT;
                        System.err.format("%s    %s%n", diff, e.toString());
                    });
        }

        static <K extends Comparable, V extends Comparable>
            void diff(Map<K, Set<V>> m1, Map<K, Set<V>> m2, BiFunction<K, V, String> mapper)
        {
            Stream.concat(m1.keySet().stream(), m2.keySet().stream())
                    .distinct()
                    .sorted()
                    .forEach(k -> {
                        if (m1.containsKey(k) && m2.containsKey(k)) {
                            Set<V> v1 = m1.get(k);
                            Set<V> v2 = m2.get(k);
                            Stream.concat(v1.stream(), v2.stream())
                                    .distinct().sorted()
                                    .filter(v -> (!v1.contains(v) || !v2.contains(v)))
                                    .forEach(v -> {
                                        String diff = v1.contains(v) ? LEFT : RIGHT;
                                        System.err.format("%s    %s%n", diff, mapper.apply(k, v));
                                    });
                        } else {
                            String diff = m1.containsKey(k) ? LEFT : RIGHT;
                            Set<V> values = m1.containsKey(k) ? m1.get(k) : m2.get(k);
                            values.stream().sorted()
                                    .forEach(v -> System.err.format("%s    %s%n", diff, mapper.apply(k, v)));
                        }
                    });
        }

        static String exports(String k, String v) {
            return String.format("exports %s to %s;", k, v);
        }

        static void diff(String header, Module original, Module module) {
            assert original != null || module != null;
            if (original == null || module == null) {
                Module m = original != null ? original : module;
                System.err.print(header);
                String[] lines = original.toString().split("\n");
                String diff = original == m ? LEFT : RIGHT;
                Arrays.stream(lines).forEach(l -> System.err.println(diff + l));
            } else if (!equals(original, module)) {
                // uses and provides are not diff
                System.err.print(header);
                DiffUtil.diff(original.requires(), module.requires());
                DiffUtil.diff(original.exports(), module.exports(), DiffUtil::exports);
            }
        }

        static boolean equals(Module m1, Module m2) {
            if (m1 != null && m2 != null) {
                // filter requires java.base as it may be synthesized
                Set<Dependence> requires1 = m1.requires().stream()
                                              .filter(d -> !d.name().equals("java.base") || d.reexport())
                                              .collect(Collectors.toSet());
                Set<Dependence> requires2 = m2.requires().stream()
                                              .filter(d -> !d.name().equals("java.base") || d.reexport())
                                              .collect(Collectors.toSet());
                return requires2.equals(requires2) && m1.exports().equals(m2.exports());
            } else {
                return false;
            }
        }
    }

    static class SourceLocation {
        static final List<Path> repos = new ArrayList<>();
        static final List<String> dirs = Arrays.asList("share", "unix", "windows",
                                                       "macosx", "linux", "solaris");

        static {
            addRepo("jdk", "src");
            addRepo("langtools", "src");
            addRepo("jaxp", "src");
            addRepo("jaxws", "src");
            addRepo("corba", "src");
            addRepo("nashorn", "src");
            addRepo("hotspot", "agent", "src");
            addRepo(Paths.get("jdk", "src", "closed"));
        }

        private static void addRepo(String repo, String... paths) {
            addRepo(Paths.get(repo, paths));
        }
        private static void addRepo(Path repo) {
            repos.add(repo);
        }

        private final Path srcDir;
        SourceLocation(Path dir) {
            this.srcDir = dir;
        }

        private Stream<Path> expand(Path moduleSourcePath) {
            return dirs.stream()
                       .map(os -> moduleSourcePath.resolve(os)
                                                  .resolve("classes")
                                                  .resolve("module-info.java"));
        }
        private List<Path> findSourceLocation(String mn) {
            List<Path> minfos = repos.stream()
                    .map(srcDir::resolve)
                    .flatMap(p -> expand(p.resolve(mn)))
                    .filter(Files::exists)
                    .collect(Collectors.toList());
            return minfos;
        }

        private Path getSourcePath(String repo, String mn) {
            return srcDir.resolve(repo).resolve("src").resolve(mn)
                         .resolve("share").resolve("classes");
        }

        @Override
        public String toString() {
            return srcDir.toString();
        }
    }

    private static final String[] COPYRIGHT_HEADER = new String[]{
        "/*",
        " * Copyright (c) 2014, Oracle and/or its affiliates. All rights reserved.",
        " * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.",
        " *",
        " * This code is free software; you can redistribute it and/or modify it",
        " * under the terms of the GNU General Public License version 2 only, as",
        " * published by the Free Software Foundation.  Oracle designates this",
        " * particular file as subject to the \"Classpath\" exception as provided",
        " * by Oracle in the LICENSE file that accompanied this code.",
        " *",
        " * This code is distributed in the hope that it will be useful, but WITHOUT",
        " * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or",
        " * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License",
        " * version 2 for more details (a copy is included in the LICENSE file that",
        " * accompanied this code).",
        " *",
        " * You should have received a copy of the GNU General Public License version",
        " * 2 along with this work; if not, write to the Free Software Foundation,",
        " * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.",
        " *",
        " * Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA",
        " * or visit www.oracle.com if you need additional information or have any",
        " * questions.",
        " */",
        ""
    };

    private static void writeCopyrightHeader(PrintWriter writer) {
        Arrays.stream(COPYRIGHT_HEADER).forEach(writer::println);
    }
}
