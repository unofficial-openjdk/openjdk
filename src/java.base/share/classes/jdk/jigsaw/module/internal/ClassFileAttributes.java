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
package jdk.jigsaw.module.internal;

import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import jdk.internal.org.objectweb.asm.Attribute;
import jdk.internal.org.objectweb.asm.ByteVector;
import jdk.internal.org.objectweb.asm.ClassReader;
import jdk.internal.org.objectweb.asm.ClassWriter;
import jdk.internal.org.objectweb.asm.Label;
import java.lang.module.ModuleDescriptor.Requires;
import java.lang.module.ModuleDescriptor.Requires.Modifier;
import java.lang.module.ModuleDescriptor.Exports;
import java.lang.module.Version;
import jdk.jigsaw.module.internal.Hasher.DependencyHashes;

/**
 * Provides ASM implementations of {@code Attribute} to read and write the
 * class file attributes in a module-info class file.
 */

class ClassFileAttributes {
    private ClassFileAttributes() { }

    /**
     * Module_attribute {
     *   // See jcp/spec/raw-file/tip/web/lang-vm.html for details.
     * }
     */
    static class ModuleAttribute extends Attribute {
        private String name;
        private Set<Requires> moduleDependences = new HashSet<>();

        // optional and created lazily
        private Set<String> serviceDependences;
        private Set<Exports> exports;
        private Map<String, Set<String>> services;

        protected ModuleAttribute() {
            super(ModuleInfo.MODULE);
        }

        @Override
        protected Attribute read(ClassReader cr,
                                 int off,
                                 int len,
                                 char[] buf,
                                 int codeOff,
                                 Label[] labels)
        {
            ModuleAttribute attr = new ModuleAttribute();

            // requires_count and requires[requires_count]
            int requires_count = cr.readUnsignedShort(off);
            off += 2;
            for (int i=0; i<requires_count; i++) {
                String dn = cr.readUTF8(off, buf);
                int flags = cr.readUnsignedShort(off + 2);
                Set<Modifier> mods;
                if (flags == 0) {
                    mods = Collections.emptySet();
                } else {
                    mods = new HashSet<>();
                    if ((flags & ModuleInfo.ACC_PUBLIC) != 0)
                        mods.add(Modifier.PUBLIC);
                    if ((flags & ModuleInfo.ACC_SYNTHETIC) != 0)
                        mods.add(Modifier.SYNTHETIC);
                    if ((flags & ModuleInfo.ACC_MANDATED) != 0)
                        mods.add(Modifier.MANDATED);
                }
                attr.moduleDependences.add(new Requires(mods, dn));
                off += 4;
            }

            // exports_count and exports[exports_count]
            int exports_count = cr.readUnsignedShort(off);
            off += 2;
            if (exports_count > 0) {
                attr.exports = new HashSet<>();
                for (int i=0; i<exports_count; i++) {
                    String pkg = cr.readUTF8(off, buf).replace('/', '.');
                    int exports_to_count = cr.readUnsignedShort(off+2);
                    off += 4;

                    if (exports_to_count > 0) {
                        for (int j=0; j<exports_to_count; j++) {
                            String who = cr.readUTF8(off, buf);
                            off += 2;
                            attr.exports.add(new Exports(pkg, who));
                        }
                    } else {
                        attr.exports.add(new Exports(pkg));
                    }
                }
            }

            // uses_count and uses_index[uses_count]
            int uses_count = cr.readUnsignedShort(off);
            off += 2;
            if (uses_count > 0) {
                attr.serviceDependences = new HashSet<>();
                for (int i=0; i<uses_count; i++) {
                    String sn = cr.readClass(off, buf).replace('/', '.');
                    attr.serviceDependences.add(sn);
                    off += 2;
                }
            }

            // provides_count and provides[provides_count]
            int provides_count = cr.readUnsignedShort(off);
            off += 2;
            if (provides_count > 0) {
                attr.services = new HashMap<>();
                for (int i=0; i<provides_count; i++) {
                    String sn = cr.readClass(off, buf).replace('/', '.');
                    String cn = cr.readClass(off + 2, buf).replace('/', '.');
                    attr.services.computeIfAbsent(sn, k -> new HashSet<>()).add(cn);
                    off += 4;
                }
            }

            return attr;
        }

        @Override
        protected ByteVector write(ClassWriter cw,
                                   byte[] code,
                                   int len,
                                   int maxStack,
                                   int maxLocals)
        {
            ByteVector attr = new ByteVector();

            // requires_count
            attr.putShort(moduleDependences.size());

            // requires[requires_count]
            for (Requires md : moduleDependences) {
                String dn = md.name();
                int flags = 0;
                if (md.modifiers().contains(Modifier.PUBLIC))
                    flags |= ModuleInfo.ACC_PUBLIC;
                if (md.modifiers().contains(Modifier.SYNTHETIC))
                    flags |= ModuleInfo.ACC_SYNTHETIC;
                if (md.modifiers().contains(Modifier.MANDATED))
                    flags |= ModuleInfo.ACC_MANDATED;
                int index = cw.newUTF8(dn);
                attr.putShort(index);
                attr.putShort(flags);
            }

            // exports_count and exports[exports_count];
            if (exports == null) {
                attr.putShort(0);
            } else {
                // group by exported package
                Map<String, Set<String>> map = new HashMap<>();
                for (Exports export : exports) {
                    String pkg = export.pkg();
                    String permit = export.permit();
                    if (permit == null) {
                        map.computeIfAbsent(pkg, k -> new HashSet<>());
                    } else {
                        map.computeIfAbsent(pkg, k -> new HashSet<>()).add(permit);
                    }
                }
                attr.putShort(map.size());
                for (Map.Entry<String, Set<String>> entry : map.entrySet()) {
                    String pkg = entry.getKey().replace('.', '/');
                    int index = cw.newUTF8(pkg);
                    attr.putShort(index);

                    Set<String> permits = entry.getValue();
                    attr.putShort(permits.size());
                    for (String permit : permits) {
                        index = cw.newUTF8(permit);
                        attr.putShort(index);
                    }
                }
            }

            // uses_count and uses_index[uses_count]
            if (serviceDependences == null) {
                attr.putShort(0);
            } else {
                attr.putShort(serviceDependences.size());
                for (String s : serviceDependences) {
                    String service = s.replace('.', '/');
                    int index = cw.newClass(service);
                    attr.putShort(index);
                }
            }

            // provides_count and provides[provides_count]
            if (services == null) {
                attr.putShort(0);
            } else {
                int count = services.values().stream().mapToInt(c -> c.size()).sum();
                attr.putShort(count);
                for (Map.Entry<String, Set<String>> entry : services.entrySet()) {
                    String service = entry.getKey().replace('.', '/');
                    int index = cw.newClass(service);
                    for (String provider : entry.getValue()) {
                        attr.putShort(index);
                        attr.putShort(cw.newClass(provider.replace('.', '/')));
                    }
                }
            }

            return attr;
        }
    }

    /**
     * Version_attribute {
     *   // index to CONSTANT_utf8_info structure in constant pool representing
     *   // the string "Version"
     *   u2 attribute_name_index;
     *   u4 attribute_length;
     *
     *   // index to CONSTANT_CONSTANT_utf8_info structure with the version
     *   u2 version_index;
     * }
     */
    static class VersionAttribute extends Attribute {
        private final Version version;

        VersionAttribute(Version version) {
            super(ModuleInfo.VERSION);
            this.version = version;
        }

        VersionAttribute() {
            this(null);
        }

        @Override
        protected Attribute read(ClassReader cr,
                                 int off,
                                 int len,
                                 char[] buf,
                                 int codeOff,
                                 Label[] labels)
        {
            String value = cr.readUTF8(off, buf);
            return new VersionAttribute(Version.parse(value));
        }

        @Override
        protected ByteVector write(ClassWriter cw,
                                   byte[] code,
                                   int len,
                                   int maxStack,
                                   int maxLocals)
        {
            ByteVector attr = new ByteVector();
            int index = cw.newUTF8(version.toString());
            attr.putShort(index);
            return attr;
        }
    }

    /**
     * MainClass_attribute {
     *   // index to CONSTANT_utf8_info structure in constant pool representing
     *   // the string "MainClass"
     *   u2 attribute_name_index;
     *   u4 attribute_length;
     *
     *   // index to CONSTANT_Class_info structure with the main class name
     *   u2 main_class_index;
     * }
     */
    static class MainClassAttribute extends Attribute {
        private final String mainClass;

        MainClassAttribute(String mainClass) {
            super(ModuleInfo.MAIN_CLASS);
            this.mainClass = mainClass;
        }

        MainClassAttribute() {
            this(null);
        }

        @Override
        protected Attribute read(ClassReader cr,
                                 int off,
                                 int len,
                                 char[] buf,
                                 int codeOff,
                                 Label[] labels)
        {
            String value = cr.readClass(off, buf);
            return new MainClassAttribute(value);
        }

        @Override
        protected ByteVector write(ClassWriter cw,
                                   byte[] code,
                                   int len,
                                   int maxStack,
                                   int maxLocals)
        {
            ByteVector attr = new ByteVector();
            int index = cw.newClass(mainClass);
            attr.putShort(index);
            return attr;
        }
    }

    /**
     * Hashes_attribute {
     *   // index to CONSTANT_utf8_info structure in constant pool representing
     *   // the string "Hashes"
     *   u2 attribute_name_index;
     *   u4 attribute_length;
     *
     *   // index to CONSTANT_CONSTANT_utf8_info structure with algorithm name
     *   u2 algorithm_index;
     *
     *   // the number of entries in the hashes table
     *   u2 hash_count;
     *   {   u2 requires_index
     *       u2 hash_index;
     *   } hashes[hash_count];
     *
     * @apiNote For now the hash is stored in base64 as a UTF-8 string, this
     * should be changed to be an array of u1.
     */
    static class HashesAttribute extends Attribute {
        private final DependencyHashes hashes;

        HashesAttribute(DependencyHashes hashes) {
            super(ModuleInfo.HASHES);
            this.hashes = hashes;
        }

        HashesAttribute() {
            this(null);
        }

        @Override
        protected Attribute read(ClassReader cr,
                                 int off,
                                 int len,
                                 char[] buf,
                                 int codeOff,
                                 Label[] labels)
        {
            String algorithm = cr.readUTF8(off, buf);
            off += 2;

            int hash_count = cr.readUnsignedShort(off);
            off += 2;

            Map<String, String> map = new HashMap<>();
            for (int i=0; i<hash_count; i++) {
                String dn = cr.readUTF8(off, buf);
                String hash = cr.readUTF8(off, buf);
                map.put(dn, hash);
                off += 2;
            }

            DependencyHashes hashes = new DependencyHashes(algorithm, map);

            return new HashesAttribute(hashes);
        }

        @Override
        protected ByteVector write(ClassWriter cw,
                                   byte[] code,
                                   int len,
                                   int maxStack,
                                   int maxLocals)
        {
            ByteVector attr = new ByteVector();

            int index = cw.newUTF8(hashes.algorithm());
            attr.putShort(index);

            Set<String> names = hashes.names();
            attr.putShort(names.size());

            for (String dn : names) {
                String hash = hashes.hashFor(dn);
                assert hash != null;
                attr.putShort(cw.newUTF8(dn));
                attr.putShort(cw.newUTF8(hash));
            }

            return attr;
        }
    }
}
