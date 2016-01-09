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
package jdk.tools.jlink.internal.plugins;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.module.ModuleDescriptor.*;
import java.lang.module.ModuleDescriptor;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import jdk.internal.module.Checks;
import jdk.internal.module.ModuleInfoExtender;
import jdk.internal.org.objectweb.asm.ClassWriter;
import jdk.internal.org.objectweb.asm.MethodVisitor;
import jdk.internal.org.objectweb.asm.Opcodes;

import static jdk.internal.org.objectweb.asm.Opcodes.*;
import jdk.tools.jlink.plugin.PluginException;
import jdk.tools.jlink.plugin.PluginOption;
import jdk.tools.jlink.plugin.Pool;
import jdk.tools.jlink.plugin.TransformerPlugin;

/**
 * Jlink plugin to reconstitute module descriptors for installed modules.
 * It also determines the number of packages of the boot layer at link time.
 *
 * This plugin will override jdk.internal.module.InstalledModules class
 *
 * This plugin is enabled by default. This can be disabled via
 * jlink --disable-installed-modules off option.
 *
 * @see java.lang.module.InstalledModuleFinder
 * @see jdk.internal.module.InstalledModules
 */
public final class InstalledModuleDescriptorPlugin implements TransformerPlugin {
    private static final String OPTION_NAME = "disable-installed-modules";
    private static final String DESCRIPTION = PluginsResourceBundle.getDescription(OPTION_NAME);
    private final PluginOption option;
    private boolean enabled;

    public InstalledModuleDescriptorPlugin() {
        this.option = new PluginOption.Builder(OPTION_NAME)
                                      .description(DESCRIPTION)
                                      .build();
        this.enabled = true;
    }

    @Override
    public PluginOption getOption() {
        return option;
    }

    @Override
    public Set<PluginType> getType() {
        return Collections.singleton(CATEGORY.TRANSFORMER);
    }

    @Override
    public String getName() {
        return "installed-modules";
    }

    @Override
    public String getDescription() {
        return DESCRIPTION;
    }

    @Override
    public Set<STATE> getState() {
        return enabled ? EnumSet.of(STATE.AUTO_ENABLED, STATE.FUNCTIONAL)
                       : EnumSet.of(STATE.DISABLED);
    }

    @Override
    public void configure(Map<PluginOption, String> config) {
        if (config.containsKey(option)) {
            enabled = false;
        }
    }

    @Override
    public void visit(Pool in, Pool out) {
        if (!enabled) {
            throw new PluginException(OPTION_NAME + " was set");
        }

        Set<String> moduleNames = new HashSet<>();
        int numPackages = 0;
        for (Pool.Module module : in.getModules()) {
            moduleNames.add(module.getName());
            numPackages += module.getAllPackages().size();
        }

        Builder builder = new Builder(moduleNames, numPackages);

        // generate the byte code to create ModuleDescriptors
        // skip parsing module-info.class and skip name check
        for (Pool.Module module : in.getModules()) {
            ModuleDescriptor md = module.getDescriptor();
            validateNames(md);
            builder.module(md, module.getAllPackages());

            /*
             * TODO: The following doesn't work.  Pool::Module and Pool::ModuleData API
             * should also be reworked.
             *
            Pool.ModuleData data = module.get(module.getName() + "/module-info.class");
            assert module.getName().equals(data.getModule());
            try {
                ModuleDescriptor md = ModuleDescriptor.read(data.stream());
                Builder.ModuleDescriptorBuilder mbuilder = builder.module(md, module.getAllPackages());
                if (md.conceals().isEmpty() &&
                        (md.exports().size() + md.conceals().size()) != module.getAllPackages().size()) {
                    // add ConcealedPackages attribute if not exist
                    ModuleInfoRewriter minfoWriter = new ModuleInfoRewriter(data.stream(), mbuilder.conceals());
                    // replace with the overridden version
                    data = new Pool.ModuleData(data.getModule(), data.getPath(), data.getType(),
                            minfoWriter.stream(), minfoWriter.size());
                }
                out.add(data);
            } catch (IOException e) {
                throw new PluginException(e);
            }
            */
        }

        // Generate the new class
        ClassWriter cwriter = builder.build();
        for (Pool.ModuleData data : in.getContent()) {
            if (builder.isOverriddenClass(data.getPath())) {
                byte[] bytes = cwriter.toByteArray();
                Pool.ModuleData ndata = new Pool.ModuleData(data.getModule(), data.getPath(), data.getType(),
                                                            new ByteArrayInputStream(bytes), bytes.length);
                out.add(ndata);
            } else {
                out.add(data);
            }
        }
    }

    /*
     * Add ConcealedPackages attribute
     */
    class ModuleInfoRewriter extends ByteArrayOutputStream {
        final ModuleInfoExtender extender;
        ModuleInfoRewriter(InputStream in, Set<String> conceals) throws IOException {
            this.extender = ModuleInfoExtender.newExtender(in);
            // Add ConcealedPackages attribute
            this.extender.conceals(conceals);
            this.extender.write(this);
        }

        InputStream stream() {
            return new ByteArrayInputStream(buf);
        }
    }

    void validateNames(ModuleDescriptor md) {
        Checks.requireModuleName(md.name());
        for (Requires req : md.requires()) {
            Checks.requireModuleName(req.name());
        }
        for (Exports exp : md.exports()) {
            Checks.requirePackageName(exp.source());
            exp.targets()
               .ifPresent(targets -> targets.forEach(Checks::requireModuleName));
        }
        for (Map.Entry<String, Provides> e : md.provides().entrySet()) {
            String service = e.getKey();
            Provides provides = e.getValue();
            Checks.requireServiceTypeName(service);
            Checks.requireServiceTypeName(provides.service());
            provides.providers().forEach(Checks::requireServiceProviderName);
        }
        for (String service : md.uses()) {
            Checks.requireServiceTypeName(service);
        }
        for (String pn : md.conceals()) {
            Checks.requirePackageName(pn);
        }
    }

    /*
     * Returns the initial capacity for a new Set or Map of the given size
     * to avoid resizing.
     */
    static final int initialCapacity(int size) {
        if (size == 0) {
            return 0;
        } else {
            // Adjust to try and get size/capacity as close to the
            // HashSet/HashMap default load factor without going over.
            return (int)(Math.ceil((double)size / 0.75));
        }
    }

    /**
     * Builder of a new jdk.internal.module.InstalledModules class
     * to reconstitute ModuleDescriptor of the installed modules.
     */
    static class Builder {
        private static final String CLASSNAME =
            "jdk/internal/module/InstalledModules";
        private static final String MODULE_DESCRIPTOR_BUILDER =
            "jdk/internal/module/Builder";
        private static final String MODULES_MAP_SIGNATURE =
            "Ljava/util/Map<Ljava/lang/String;Ljava/lang/module/ModuleDescriptor;>;";

        // static variables in InstalledModules class
        private static final String MODULE_NAMES = "MODULE_NAMES";
        private static final String PACKAGE_COUNT = "PACKAGES_IN_BOOT_LAYER";
        private static final String DESCRIPTOR_MAP = "MAP";
        private static final String MAP_TYPE = "Ljava/util/Map;";

        private static final int BUILDER_VAR    = 0;
        private static final int MODS_VAR       = 1;
        private static final int STRING_SET_VAR = 2;
        private static final int MAX_LOCAL_VARS = 256;

        private final ClassWriter cw;
        private final MethodVisitor mv;
        private int nextLocalVar = 3;

        // list of all ModuleDescriptorBuilders, invoked in turn when building.
        private final List<ModuleDescriptorBuilder> builders = new ArrayList<>();

        // map Set<String> to a specialized builder to allow them to be
        // deduplicated as they are requested
        private final Map<Set<String>, StringSetBuilder> stringSets = new HashMap<>();

        public Builder(Set<String> moduleNames, int numPackages) {
            this.cw = new ClassWriter(ClassWriter.COMPUTE_MAXS+ClassWriter.COMPUTE_FRAMES);
            this.clinit(moduleNames, numPackages);
            this.mv = cw.visitMethod(ACC_PUBLIC+ACC_STATIC,
                    "modules", "()Ljava/util/Map;",
                    "()" + MODULES_MAP_SIGNATURE, null);
            mv.visitCode();
        }

        /*
         * static initializer initializing the static fields
         *
         * static Map<String, ModuleDescriptor> map = new HashMap<>();
         */
        private void clinit(Set<String> moduleNames, int numPackages) {
            cw.visit(Opcodes.V1_8, ACC_PUBLIC+ACC_FINAL+ACC_SUPER, CLASSNAME,
                    null, "java/lang/Object", null);

            // public static String[] MODULE_NAMES = new String[] {....};
            cw.visitField(ACC_PUBLIC+ACC_FINAL+ACC_STATIC, MODULE_NAMES,
                    "[Ljava/lang/String;", null, null)
                    .visitEnd();

            // public static int PACKAGES_IN_BOOT_LAYER;
            cw.visitField(ACC_PUBLIC+ACC_FINAL+ACC_STATIC, PACKAGE_COUNT,
                    "I", null, numPackages)
                    .visitEnd();

            // static Map<String, ModuleDescriptor> map = new HashMap<>();
            cw.visitField(ACC_FINAL+ACC_STATIC, DESCRIPTOR_MAP, MAP_TYPE,
                    MODULES_MAP_SIGNATURE, null)
                    .visitEnd();

            MethodVisitor mv = cw.visitMethod(ACC_STATIC, "<clinit>", "()V",
                    null, null);
            mv.visitCode();

            // create the MODULE_NAMES array
            int numModules = moduleNames.size();
            newArray(cw, mv, moduleNames, numModules);
            mv.visitFieldInsn(PUTSTATIC, CLASSNAME, MODULE_NAMES,
                    "[Ljava/lang/String;");
            mv.visitIntInsn(numModules < Byte.MAX_VALUE ? BIPUSH : SIPUSH, numModules);
            mv.visitTypeInsn(ANEWARRAY, "[Ljava/lang/String;");


            mv.visitTypeInsn(NEW, "java/util/HashMap");
            mv.visitInsn(DUP);
            mv.visitMethodInsn(INVOKESPECIAL, "java/util/HashMap",
                    "<init>", "()V", false);
            mv.visitFieldInsn(PUTSTATIC, CLASSNAME, DESCRIPTOR_MAP, MAP_TYPE);
            mv.visitInsn(RETURN);
            mv.visitMaxs(0, 0);
            mv.visitEnd();
        }

        /*
         * Adds the given ModuleDescriptor to the installed module list, and
         * prepares mapping from Set<String> to StringSetBuilders to emit an
         * optimized number of string sets during build.
         */
        public ModuleDescriptorBuilder module(ModuleDescriptor md, Set<String> packages) {
            ModuleDescriptorBuilder builder = new ModuleDescriptorBuilder(md, packages, mv);
            builders.add(builder);

            // exports
            for (ModuleDescriptor.Exports exp : md.exports()) {
                if (exp.targets().isPresent()) {
                    stringSets.computeIfAbsent(exp.targets().get(), s -> new StringSetBuilder(s))
                              .increment();
                }
            }

            // provides
            for (ModuleDescriptor.Provides p : md.provides().values()) {
                stringSets.computeIfAbsent(p.providers(), s -> new StringSetBuilder(s))
                          .increment();
            }

            // uses
            stringSets.computeIfAbsent(md.uses(), s -> new StringSetBuilder(s))
                      .increment();
            return builder;
        }

        /*
         * Finish up to generate bytecode for the return value of the modules method
         */
        public ClassWriter build() {
            for (ModuleDescriptorBuilder builder : builders) {
                builder.build();
            }
            mv.visitFieldInsn(GETSTATIC, CLASSNAME, DESCRIPTOR_MAP, MAP_TYPE);
            mv.visitInsn(ARETURN);
            mv.visitMaxs(0, 0);
            mv.visitEnd();
            return cw;
        }

        public boolean isOverriddenClass(String path) {
            return path.equals("/java.base/" + CLASSNAME + ".class");
        }

        private static void newArray(ClassWriter cw, MethodVisitor mv,
                                     Set<String> names, int size) {
            mv.visitIntInsn(size < Byte.MAX_VALUE ? BIPUSH : SIPUSH, size);
            mv.visitTypeInsn(ANEWARRAY, "java/lang/String");
            int index=0;
            for (String n : names) {
                addElement(cw, mv, index++);
                mv.visitLdcInsn(n);      // value
                mv.visitInsn(AASTORE);
            }
        }

        private static void addElement(ClassWriter cw, MethodVisitor mv, int index) {
            mv.visitInsn(DUP);           // arrayref
            if (index <= 5) {            // index
                mv.visitInsn(ICONST_0 + index);
            } else if (index < Byte.MAX_VALUE) {
                mv.visitIntInsn(BIPUSH, index);
            } else if (index < Short.MAX_VALUE) {
                mv.visitIntInsn(SIPUSH, index);
            } else {
                throw new IllegalArgumentException("exceed limit: " + index);
            }
        }

        void pushInt(int num) {
            if (num <= 5) {
                mv.visitInsn(ICONST_0 + num);
            } else if (num < Byte.MAX_VALUE) {
                mv.visitIntInsn(BIPUSH, num);
            } else if (num < Short.MAX_VALUE) {
                mv.visitIntInsn(SIPUSH, num);
            } else {
                throw new IllegalArgumentException("exceed limit: " + num);
            }
        }

        class ModuleDescriptorBuilder {
            static final String REQUIRES_MODIFIER_CLASSNAME =
                    "java/lang/module/ModuleDescriptor$Requires$Modifier";
            static final String REQUIRES_MODIFIER_TYPE =
                "Ljava/lang/module/ModuleDescriptor$Requires$Modifier;";
            static final String BUILDER_TYPE = "Ljdk/internal/module/Builder;";
            static final String REQUIRES_MODIFIER_STRING_SIG =
                "(" + REQUIRES_MODIFIER_TYPE + "Ljava/lang/String;)" + BUILDER_TYPE;
            static final String STRING_SET_SIG =
                "(Ljava/lang/String;Ljava/util/Set;)" + BUILDER_TYPE;
            static final String SET_STRING_SIG =
                "(Ljava/util/Set;Ljava/lang/String;)" + BUILDER_TYPE;
            static final String SET_SIG =
                "(Ljava/util/Set;)" + BUILDER_TYPE;
            static final String STRING_SIG = "(Ljava/lang/String;)" + BUILDER_TYPE;
            static final String STRING_STRING_SIG =
                "(Ljava/lang/String;Ljava/lang/String;)" + BUILDER_TYPE;
            final MethodVisitor mv;
            final ModuleDescriptor md;
            final Set<String> packages;

            ModuleDescriptorBuilder(ModuleDescriptor md, Set<String> packages,
                                    MethodVisitor mv) {
                this.md = md;
                this.packages = packages;
                this.mv = mv;
            }

            void newBuilder(String name, int reqs, int exports, int provides,
                            int conceals, int packages) {
                mv.visitTypeInsn(NEW, MODULE_DESCRIPTOR_BUILDER);
                mv.visitInsn(DUP);
                mv.visitLdcInsn(name);
                pushInt(initialCapacity(reqs));
                pushInt(initialCapacity(exports));
                pushInt(initialCapacity(provides));
                pushInt(initialCapacity(conceals));
                pushInt(initialCapacity(packages));
                mv.visitMethodInsn(INVOKESPECIAL, MODULE_DESCRIPTOR_BUILDER,
                                   "<init>", "(Ljava/lang/String;IIIII)V", false);
                mv.visitVarInsn(ASTORE, BUILDER_VAR);
                mv.visitVarInsn(ALOAD, BUILDER_VAR);
            }

            /*
             * Returns the set of concealed packages from ModuleDescriptor, if present
             * or compute it if the module oes not have ConcealedPackages attribute
             */
            Set<String> conceals() {
                Set<String> conceals = md.conceals();
                if (md.conceals().isEmpty() &&
                        (md.exports().size() + md.conceals().size()) != packages.size()) {
                    Set<String> exports = md.exports().stream()
                                            .map(Exports::source)
                                            .collect(Collectors.toSet());
                    conceals = packages.stream()
                                       .filter(pn -> !exports.contains(pn))
                                       .collect(Collectors.toSet());
                }

                if (conceals.size() + md.exports().size() != packages.size()) {
                    throw new AssertionError(md.name() + ": conceals=" + conceals.size() +
                            ", exports=" + md.exports().size() + ", packages=" + packages.size());
                }
                return conceals;
            }

            void build() {
                newBuilder(md.name(), md.requires().size(),
                           md.exports().size(),
                           md.provides().size(),
                           conceals().size(),
                           conceals().size() + md.exports().size());

                // requires
                for (ModuleDescriptor.Requires req : md.requires()) {
                    switch (req.modifiers().size()) {
                        case 0:
                            requires(req.name());
                            break;
                        case 1:
                            ModuleDescriptor.Requires.Modifier mod =
                                req.modifiers().iterator().next();
                            requires(mod, req.name());
                            break;
                        default:
                            requires(req.modifiers(), req.name());
                    }
                }

                // exports
                for (ModuleDescriptor.Exports exp : md.exports()) {
                    if (exp.targets().isPresent()) {
                        exports(exp.source(), exp.targets().get());
                    } else {
                        exports(exp.source());
                    }
                }

                // uses
                uses(md.uses());

                // provides
                for (ModuleDescriptor.Provides p : md.provides().values()) {
                    provides(p.service(), p.providers());
                }

                // concealed packages
                for (String pn : conceals()) {
                    conceals(pn);
                }

                if (md.version().isPresent()) {
                    version(md.version().get());
                }

                if (md.mainClass().isPresent()) {
                    mainClass(md.mainClass().get());
                }

                // map.put(mn. builder.build());
                putModuleDescriptor(md.name());

                mv.visitFieldInsn(GETSTATIC, CLASSNAME, DESCRIPTOR_MAP, MAP_TYPE);
                mv.visitLdcInsn(md.name());
                mv.visitVarInsn(ALOAD, BUILDER_VAR);
                mv.visitMethodInsn(INVOKEVIRTUAL, MODULE_DESCRIPTOR_BUILDER,
                    "build", "()Ljava/lang/module/ModuleDescriptor;", false);
                mv.visitMethodInsn(INVOKEINTERFACE, "java/util/Map", "put",
                    "(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;", true);
                mv.visitInsn(POP);
            }

            /*
             * Invoke Map::put to put the ModuleDescriptor to InstalledModules.map
             */
            void putModuleDescriptor(String mn) {
                // map.put(mn, builder.build());
                mv.visitFieldInsn(GETSTATIC, CLASSNAME, DESCRIPTOR_MAP, MAP_TYPE);
                mv.visitLdcInsn(mn);
                mv.visitVarInsn(ALOAD, BUILDER_VAR);
                mv.visitMethodInsn(INVOKEVIRTUAL, MODULE_DESCRIPTOR_BUILDER,
                    "build", "()Ljava/lang/module/ModuleDescriptor;", false);
                mv.visitMethodInsn(INVOKEINTERFACE, "java/util/Map",
                    "put", "(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;", true);
                mv.visitInsn(POP);
            }

            /*
             * Invoke Builder.requires(String mn)
             */
            void requires(String name) {
                mv.visitVarInsn(ALOAD, BUILDER_VAR);
                mv.visitLdcInsn(name);
                mv.visitMethodInsn(INVOKEVIRTUAL, MODULE_DESCRIPTOR_BUILDER,
                                   "requires", STRING_SIG, false);
                mv.visitInsn(POP);
            }

            /*
             * Invoke Builder.requires(Modifier mod, String mn)
             */
            void requires(ModuleDescriptor.Requires.Modifier mod, String name) {
                mv.visitVarInsn(ALOAD, BUILDER_VAR);
                mv.visitFieldInsn(GETSTATIC, REQUIRES_MODIFIER_CLASSNAME, mod.name(),
                                  REQUIRES_MODIFIER_TYPE);
                mv.visitLdcInsn(name);
                mv.visitMethodInsn(INVOKEVIRTUAL, MODULE_DESCRIPTOR_BUILDER,
                                   "requires", REQUIRES_MODIFIER_STRING_SIG, false);
                mv.visitInsn(POP);
            }

            /*
             * Invoke Builder.requires(Set<Modifier> mods, String mn)
             *
             * EnumSet<Modifier> mods = EnumSet.of(mod,....);
             * Buidler.requires(mods, mn);
             */
            void requires(Set<ModuleDescriptor.Requires.Modifier> mods, String name) {
                mv.visitVarInsn(ALOAD, MODS_VAR);
                String signature = "(";
                for (ModuleDescriptor.Requires.Modifier m : mods) {
                    mv.visitFieldInsn(GETSTATIC, REQUIRES_MODIFIER_CLASSNAME, m.name(),
                                      REQUIRES_MODIFIER_TYPE);
                    signature += "Ljava/util/Enum;";
                }
                signature += ")Ljava/util/EnumSet;";
                mv.visitMethodInsn(INVOKESTATIC, "java/util/EnumSet", "of",
                                   signature, false);
                mv.visitVarInsn(ASTORE, MODS_VAR);
                mv.visitVarInsn(ALOAD, BUILDER_VAR);
                mv.visitVarInsn(ALOAD, MODS_VAR);
                mv.visitLdcInsn(name);
                mv.visitMethodInsn(INVOKEVIRTUAL, MODULE_DESCRIPTOR_BUILDER,
                                   "requires", SET_STRING_SIG, false);
                mv.visitInsn(POP);
            }

            /*
             * Invoke Builder.exports(String pn)
             */
            void exports(String pn) {
                mv.visitVarInsn(ALOAD, BUILDER_VAR);

                mv.visitLdcInsn(pn);
                mv.visitMethodInsn(INVOKEVIRTUAL, MODULE_DESCRIPTOR_BUILDER,
                        "exports", STRING_SIG, false);
                mv.visitInsn(POP);
            }

            /*
             * Invoke Builder.exports(String pn, Set<String> targets)
             *
             * Set<String> targets = new HashSet<>();
             * targets.add(t);
             * :
             * :
             * Builder.exports(pn, targets);
             */
            void exports(String pn, Set<String> targets) {
                int varIndex = stringSets.get(targets).build();
                mv.visitVarInsn(ALOAD, BUILDER_VAR);
                mv.visitLdcInsn(pn);
                mv.visitVarInsn(ALOAD, varIndex);
                mv.visitMethodInsn(INVOKEVIRTUAL, MODULE_DESCRIPTOR_BUILDER,
                                   "exports", STRING_SET_SIG, false);
                mv.visitInsn(POP);
            }

            /*
             * Invokes Builder.uses(Set<String> uses)
             */
            void uses(Set<String> uses) {
                int varIndex = stringSets.get(uses).build();
                mv.visitVarInsn(ALOAD, BUILDER_VAR);
                mv.visitVarInsn(ALOAD, varIndex);
                mv.visitMethodInsn(INVOKEVIRTUAL, MODULE_DESCRIPTOR_BUILDER,
                        "uses", SET_SIG, false);
                mv.visitInsn(POP);
            }

            /*
             * Invoke Builder.provides(String service, Set<String> providers)
             *
             * Set<String> providers = new HashSet<>();
             * providers.add(impl);
             * :
             * :
             * Builder.exports(service, providers);
             */
            void provides(String service, Set<String> providers) {
                int varIndex = stringSets.get(providers).build();
                mv.visitVarInsn(ALOAD, BUILDER_VAR);
                mv.visitLdcInsn(service);
                mv.visitVarInsn(ALOAD, varIndex);
                mv.visitMethodInsn(INVOKEVIRTUAL, MODULE_DESCRIPTOR_BUILDER,
                                   "provides", STRING_SET_SIG, false);
                mv.visitInsn(POP);
            }

            /*
             * Invoke Builder.conceals(String pn)
             */
            void conceals(String pn) {
                mv.visitVarInsn(ALOAD, BUILDER_VAR);
                mv.visitLdcInsn(pn);
                mv.visitMethodInsn(INVOKEVIRTUAL, MODULE_DESCRIPTOR_BUILDER,
                                   "conceals", STRING_SIG, false);
                mv.visitInsn(POP);
            }

            /*
             * Invoke Builder.mainClass(String cn)
             */
            void mainClass(String cn) {
                mv.visitVarInsn(ALOAD, BUILDER_VAR);
                mv.visitLdcInsn(cn);
                mv.visitMethodInsn(INVOKEVIRTUAL, MODULE_DESCRIPTOR_BUILDER,
                                   "mainClass", STRING_SIG, false);
                mv.visitInsn(POP);
            }

            /*
             * Invoke Builder.version(Version v);
             */
            void version(ModuleDescriptor.Version v) {
                mv.visitVarInsn(ALOAD, BUILDER_VAR);
                mv.visitLdcInsn(v.toString());
                mv.visitMethodInsn(INVOKEVIRTUAL, MODULE_DESCRIPTOR_BUILDER,
                                   "version", STRING_SIG, false);
                mv.visitInsn(POP);
            }

        }

        /*
         * StringSetBuilder generates bytecode to create one single instance
         * of HashSet for a given set of names and assign to a local variable
         * slot.  When there is only one single reference to a Set<String>,
         * it will reuse STRING_SET_VAR for reference.  For Set<String> with
         * multiple references, it will use a new local variable.
         */
        class StringSetBuilder {
            final Set<String> names;
            int refCount;
            int localVarIndex;
            StringSetBuilder(Set<String> names) {
                this.names = names;
            }

            void increment() {
                refCount++;
            }

            /*
             * Build bytecode for the Set<String> represented by this builder,
             * or get the local variable index of a previously generated set
             * (in the local scope).
             *
             * @return local variable index of the generated set.
             */
            int build() {
                int index = localVarIndex;
                if (localVarIndex == 0) {
                    // if more than one set reference this builder, emit to a
                    // unique local
                    index = refCount == 1 ? STRING_SET_VAR : nextLocalVar++;
                    if (index < MAX_LOCAL_VARS) {
                        localVarIndex = index;
                    } else {
                        // overflow: disable optimization and keep localVarIndex = 0
                        index = STRING_SET_VAR;
                    }

                    if (names.size() == 1) {
                        mv.visitLdcInsn(names.iterator().next());
                        mv.visitMethodInsn(INVOKESTATIC, "java/util/Collections",
                                "singleton", "(Ljava/lang/Object;)Ljava/util/Set;", false);
                        mv.visitVarInsn(ASTORE, index);
                    } else {
                        mv.visitTypeInsn(NEW, "java/util/HashSet");
                        mv.visitInsn(DUP);
                        pushInt(initialCapacity(names.size()));
                        mv.visitMethodInsn(INVOKESPECIAL, "java/util/HashSet",
                                "<init>", "(I)V", false);

                        mv.visitVarInsn(ASTORE, index);
                        for (String t : names) {
                            mv.visitVarInsn(ALOAD, index);
                            mv.visitLdcInsn(t);
                            mv.visitMethodInsn(INVOKEINTERFACE, "java/util/Set",
                                    "add", "(Ljava/lang/Object;)Z", true);
                            mv.visitInsn(POP);
                        }
                    }
                }
                return index;
            }
        }
    }
}
