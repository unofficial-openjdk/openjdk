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

import java.lang.module.ModuleDescriptor.*;
import java.io.IOException;
import java.lang.module.ModuleDescriptor;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import jdk.internal.module.Checks;
import jdk.internal.org.objectweb.asm.ClassWriter;
import jdk.internal.org.objectweb.asm.MethodVisitor;
import jdk.internal.org.objectweb.asm.Opcodes;
import jdk.tools.jlink.internal.plugins.asm.AsmPools;
import jdk.tools.jlink.plugins.StringTable;
import jdk.tools.jlink.internal.plugins.asm.AsmPlugin;
import jdk.tools.jlink.internal.plugins.asm.AsmModulePool;

import static jdk.internal.org.objectweb.asm.Opcodes.*;

/**
 * Jlink plugin to reconstitute module descriptors for installed modules.
 * It also determines the number of packages of the boot layer at link time.
 *
 * This plugin will override jdk.internal.module.InstalledModules class
 *
 * This plugin is enabled by default.  This can be disabled via
 * jlink --gen-installed-modules off option.
 *
 * TODO: module-info.class may not have the ConcealedPackages attribute.
 * This plugin or a new plugin should add to module-info.class, if not present.
 *
 * @see java.lang.module.InstalledModuleFinder
 * @see jdk.internal.module.InstalledModules
 */
final class InstalledModuleDescriptorPlugin extends AsmPlugin {
    InstalledModuleDescriptorPlugin() {
    }

    @Override
    public String getName() {
        return InstalledModuleDescriptorProvider.NAME;
    }

    @Override
    public void visit(AsmPools pools, StringTable strings) throws IOException {
        Set<String> moduleNames = new HashSet<>();
        int numPackages = 0;
        for (AsmModulePool module : pools.getModulePools()) {
            // validate names
            validateNames(module.getDescriptor());
            moduleNames.add(module.getModuleName());
            numPackages += module.getAllPackages().size();
        }

        Builder builder = new Builder(moduleNames, numPackages);

        // generate the byte code to create ModuleDescriptors
        for (AsmModulePool module : pools.getModulePools()) {
            // ModuleDescriptor created at runtime skips the name checks.
            builder.module(module.getDescriptor(), module.getAllPackages());
        }
        // Generate the new class
        ClassWriter cwriter = builder.build();

        // Retrieve java.base module
        AsmModulePool javaBase = pools.getModulePool("java.base");
        // Add the new class in the pool of transformed class
        // will override the existing one
        javaBase.getTransformedClasses().addClass(cwriter);
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

        private final ClassWriter cw;
        private final MethodVisitor mv;

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
         * Add the given ModuleDescriptor to the installed module list.
         */
        public Builder module(ModuleDescriptor md, Set<String> packages) {
            ModuleDescriptorBuilder builder = new ModuleDescriptorBuilder(mv);
            builder.run(md);
            return this;
        }

        /*
         * Finish up to generate bytecode for the return value of the modules method
         */
        public ClassWriter build() {
            mv.visitFieldInsn(GETSTATIC, CLASSNAME, DESCRIPTOR_MAP, MAP_TYPE);
            mv.visitInsn(ARETURN);
            mv.visitMaxs(0, 0);
            mv.visitEnd();
            return cw;
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

        class ModuleDescriptorBuilder {
            static final String REQUIRES_MODIFIER_TYPE =
                "Ljava/lang/module/ModuleDescriptor$Requires$Modifier;";
            static final String BUILDER_TYPE = "Ljdk/internal/module/Builder;";
            static final String REQUIRES_MODIFIER_STRING_SIG =
                "(" + REQUIRES_MODIFIER_TYPE + "Ljava/lang/String;)" + BUILDER_TYPE;
            static final String STRING_SET_SIG =
                "(Ljava/lang/String;Ljava/util/Set;)" + BUILDER_TYPE;
            static final String SET_STRING_SIG =
                "(Ljava/util/Set;Ljava/lang/String;)" + BUILDER_TYPE;
            static final String STRING_SIG = "(Ljava/lang/String;)" + BUILDER_TYPE;
            static final String STRING_STRING_SIG =
                "(Ljava/lang/String;Ljava/lang/String;)" + BUILDER_TYPE;
            final MethodVisitor mv;
            final int BUILDER_VAR = 0;
            final int MODS_VAR = 1;
            final int TARGETS_VAR = 2;
            final int PROVIDERS_VAR = 3;
            ModuleDescriptorBuilder(MethodVisitor mv) {
                this.mv = mv;
            }

            void newBuilder(String name, int reqs, int exports, int uses,
                            int provides, int conceals, int packages) {
                mv.visitTypeInsn(NEW, MODULE_DESCRIPTOR_BUILDER);
                mv.visitInsn(DUP);
                mv.visitLdcInsn(name);
                pushInt(reqs);
                pushInt(exports);
                pushInt(uses);
                pushInt(provides);
                pushInt(conceals);
                pushInt(packages);
                mv.visitMethodInsn(INVOKESPECIAL, MODULE_DESCRIPTOR_BUILDER,
                                   "<init>", "(Ljava/lang/String;IIIIII)V", false);
                mv.visitVarInsn(ASTORE, BUILDER_VAR);
                mv.visitVarInsn(ALOAD, BUILDER_VAR);
            }

            void run(ModuleDescriptor md) {
                // ## handle if module-info.class doesn't have concealed attribute
                newBuilder(md.name(), md.requires().size(),
                           md.exports().size(),
                           md.uses().size(),
                           md.provides().size(),
                           md.conceals().size(),
                           md.conceals().size() + md.exports().size());

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
                for (String s : md.uses()) {
                    uses(s);
                }

                // provides
                for (ModuleDescriptor.Provides p : md.provides().values()) {
                    if (p.providers().size() == 1) {
                        provides(p.service(), p.providers().iterator().next());
                    } else {
                        provides(p.service(), p.providers());
                    }
                }

                // concealed packages
                for (String pn : md.conceals()) {
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
                mv.visitFieldInsn(GETSTATIC, REQUIRES_MODIFIER_TYPE, mod.name(),
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
                    mv.visitFieldInsn(GETSTATIC, REQUIRES_MODIFIER_TYPE, m.name(),
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
                mv.visitTypeInsn(NEW, "java/util/HashSet");
                mv.visitInsn(DUP);
                pushInt(targets.size());
                mv.visitMethodInsn(INVOKESPECIAL, "java/util/HashSet",
                                   "<init>", "(I)V", false);
                mv.visitVarInsn(ASTORE, TARGETS_VAR);
                for (String t : targets) {
                    mv.visitVarInsn(ALOAD, TARGETS_VAR);
                    mv.visitLdcInsn(t);
                    mv.visitMethodInsn(INVOKEINTERFACE, "java/util/Set",
                        "add", "(Ljava/lang/Object;)Z", true);
                    mv.visitInsn(POP);
                }
                mv.visitVarInsn(ALOAD, BUILDER_VAR);
                mv.visitLdcInsn(pn);
                mv.visitVarInsn(ALOAD, TARGETS_VAR);
                mv.visitMethodInsn(INVOKEVIRTUAL, MODULE_DESCRIPTOR_BUILDER,
                                   "exports", STRING_SET_SIG, false);
                mv.visitInsn(POP);

            }

            /*
             * Invokes Builder.use(String cn)
             */
            void uses(String cn) {
                mv.visitVarInsn(ALOAD, BUILDER_VAR);
                mv.visitLdcInsn(cn);
                mv.visitMethodInsn(INVOKEVIRTUAL, MODULE_DESCRIPTOR_BUILDER,
                        "uses", STRING_SIG, false);
                mv.visitInsn(POP);
            }

            /*
             * Invoke Builder.provides(service, provider);
             */
            void provides(String service, String provider) {
                mv.visitVarInsn(ALOAD, BUILDER_VAR);
                mv.visitLdcInsn(service);
                mv.visitLdcInsn(provider);
                mv.visitMethodInsn(INVOKEVIRTUAL, MODULE_DESCRIPTOR_BUILDER,
                                   "provides", STRING_STRING_SIG, false);
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
                mv.visitTypeInsn(NEW, "java/util/HashSet");
                mv.visitInsn(DUP);
                pushInt(providers.size());
                mv.visitMethodInsn(INVOKESPECIAL, "java/util/HashSet",
                                   "<init>", "(I)V", false);
                mv.visitVarInsn(ASTORE, PROVIDERS_VAR);
                for (String impl : providers) {
                    mv.visitVarInsn(ALOAD, PROVIDERS_VAR);
                    mv.visitLdcInsn(impl);
                    mv.visitMethodInsn(INVOKEINTERFACE, "java/util/Set",
                        "add", "(Ljava/lang/Object;)Z", true);
                    mv.visitInsn(POP);
                }
                mv.visitVarInsn(ALOAD, BUILDER_VAR);
                mv.visitLdcInsn(service);
                mv.visitVarInsn(ALOAD, PROVIDERS_VAR);
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
        }
    }
}
