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

package jdk.internal.module;

import java.lang.module.ModuleDescriptor;
import java.lang.module.ModuleDescriptor.Requires;
import java.lang.module.ModuleDescriptor.Requires.Modifier;
import java.lang.module.ModuleDescriptor.Exports;
import java.lang.module.ModuleDescriptor.Provides;
import java.lang.module.Version;
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
import jdk.internal.module.Hasher.DependencyHashes;
import static jdk.internal.module.ClassFileConstants.*;


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

        private ModuleDescriptor descriptor;

        protected ModuleAttribute() {
            super(MODULE);
        }

        @Override
        protected Attribute read(ClassReader cr,
                                 int off,
                                 int len,
                                 char[] buf,
                                 int codeOff,
                                 Label[] labels)
        {
            ModuleDescriptor.Builder builder
                = new ModuleDescriptor.Builder("xyzzy"); // Name never used
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
                    if ((flags & ACC_PUBLIC) != 0)
                        mods.add(Modifier.PUBLIC);
                    if ((flags & ACC_SYNTHETIC) != 0)
                        mods.add(Modifier.SYNTHETIC);
                    if ((flags & ACC_MANDATED) != 0)
                        mods.add(Modifier.MANDATED);
                }
                builder.requires(mods, dn);
                off += 4;
            }

            // exports_count and exports[exports_count]
            int exports_count = cr.readUnsignedShort(off);
            off += 2;
            if (exports_count > 0) {
                for (int i=0; i<exports_count; i++) {
                    String pkg = cr.readUTF8(off, buf).replace('/', '.');
                    int exports_to_count = cr.readUnsignedShort(off+2);
                    off += 4;
                    if (exports_to_count > 0) {
                        Set<String> targets = new HashSet<>();
                        for (int j=0; j<exports_to_count; j++) {
                            String t = cr.readUTF8(off, buf);
                            off += 2;
                            targets.add(t);
                        }
                        builder.exports(pkg, targets);
                    } else {
                        builder.exports(pkg);
                    }
                }
            }

            // uses_count and uses_index[uses_count]
            int uses_count = cr.readUnsignedShort(off);
            off += 2;
            if (uses_count > 0) {
                for (int i=0; i<uses_count; i++) {
                    String sn = cr.readClass(off, buf).replace('/', '.');
                    builder.uses(sn);
                    off += 2;
                }
            }

            // provides_count and provides[provides_count]
            int provides_count = cr.readUnsignedShort(off);
            off += 2;
            if (provides_count > 0) {
                Map<String, Set<String>> provides = new HashMap<>();
                for (int i=0; i<provides_count; i++) {
                    String sn = cr.readClass(off, buf).replace('/', '.');
                    String cn = cr.readClass(off + 2, buf).replace('/', '.');
                    provides.computeIfAbsent(sn, k -> new HashSet<>()).add(cn);
                    off += 4;
                }
                provides.entrySet().forEach(e -> builder.provides(e.getKey(),
                                                                  e.getValue()));
            }

            attr.descriptor = builder.build();
            return attr;
        }

        @Override
        protected ByteVector write(ClassWriter cw,
                                   byte[] code,
                                   int len,
                                   int maxStack,
                                   int maxLocals)
        {
            assert descriptor != null;
            ByteVector attr = new ByteVector();

            // requires_count
            attr.putShort(descriptor.requires().size());

            // requires[requires_count]
            for (Requires md : descriptor.requires()) {
                String dn = md.name();
                int flags = 0;
                if (md.modifiers().contains(Modifier.PUBLIC))
                    flags |= ACC_PUBLIC;
                if (md.modifiers().contains(Modifier.SYNTHETIC))
                    flags |= ACC_SYNTHETIC;
                if (md.modifiers().contains(Modifier.MANDATED))
                    flags |= ACC_MANDATED;
                int index = cw.newUTF8(dn);
                attr.putShort(index);
                attr.putShort(flags);
            }

            // exports_count and exports[exports_count];
            if (descriptor.exports().isEmpty()) {
                attr.putShort(0);
            } else {
                attr.putShort(descriptor.exports().size());
                for (Exports e : descriptor.exports()) {
                    String pkg = e.source().replace('.', '/');
                    attr.putShort(cw.newUTF8(pkg));
                    if (e.targets().isPresent()) {
                        Set<String> ts = e.targets().get();
                        attr.putShort(ts.size());
                        ts.forEach(t -> attr.putShort(cw.newUTF8(t)));
                    } else {
                        attr.putShort(0);
                    }
                }
            }

            // uses_count and uses_index[uses_count]
            if (descriptor.uses().isEmpty()) {
                attr.putShort(0);
            } else {
                attr.putShort(descriptor.uses().size());
                for (String s : descriptor.uses()) {
                    String service = s.replace('.', '/');
                    int index = cw.newClass(service);
                    attr.putShort(index);
                }
            }

            // provides_count and provides[provides_count]
            if (descriptor.provides().isEmpty()) {
                attr.putShort(0);
            } else {
                int count = descriptor.provides().values()
                    .stream().mapToInt(ps -> ps.providers().size()).sum();
                attr.putShort(count);
                for (Provides p : descriptor.provides().values()) {
                    String service = p.service().replace('.', '/');
                    int index = cw.newClass(service);
                    for (String provider : p.providers()) {
                        attr.putShort(index);
                        attr.putShort(cw.newClass(provider.replace('.', '/')));
                    }
                }
            }

            return attr;
        }
    }

    static abstract class OptionalAttribute extends Attribute {
        protected boolean isPresent;
        boolean isPresent() { return isPresent; }
        OptionalAttribute(String type) {
            super(type);
        }
    }

    /**
     * ConcealedPackages_attribute {
     *   // index to CONSTANT_utf8_info structure in constant pool representing
     *   // the string "ConcealedPackages"
     *   u2 attribute_name_index;
     *   u4 attribute_length;
     *
     *   // the number of entries in the packages table
     *   u2 package_count;
     *   { // index to CONSTANT_CONSTANT_utf8_info structure with the package name
     *     u2 package_index
     *   } package[package_count];
     */
    static class ConcealedPackagesAttribute extends OptionalAttribute {
        private final Set<String> packages;

        ConcealedPackagesAttribute(Set<String> packages) {
            super(CONCEALED_PACKAGES);
            this.packages = packages;
        }

        ConcealedPackagesAttribute() {
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
            // package count
            int package_count = cr.readUnsignedShort(off);
            off += 2;

            // packages
            Set<String> packages = new HashSet<>();
            for (int i=0; i<package_count; i++) {
                String pkg = cr.readUTF8(off, buf).replace('/', '.');
                packages.add(pkg);
                off += 2;
            }

            isPresent = true;
            if (this.packages != null)
                packages = this.packages;
            return new ConcealedPackagesAttribute(packages);
        }

        @Override
        protected ByteVector write(ClassWriter cw,
                                   byte[] code,
                                   int len,
                                   int maxStack,
                                   int maxLocals)
        {
            assert packages != null;

            ByteVector attr = new ByteVector();

            // package_count
            attr.putShort(packages.size());

            // packages
            packages.stream()
                .map(p -> p.replace('.', '/'))
                .forEach(p -> attr.putShort(cw.newUTF8(p)));

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
    static class VersionAttribute extends OptionalAttribute {
        private final Version version;

        VersionAttribute(Version version) {
            super(VERSION);
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
            Version version = Version.parse(cr.readUTF8(off, buf));
            isPresent = true;
            if (this.version != null)
                version = this.version;
            return new VersionAttribute(version);
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
    static class MainClassAttribute extends OptionalAttribute {
        private final String mainClass;

        MainClassAttribute(String mainClass) {
            super(MAIN_CLASS);
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
            isPresent = true;
            if (this.mainClass != null)
                value = this.mainClass;
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
    static class HashesAttribute extends OptionalAttribute {
        private final DependencyHashes hashes;

        HashesAttribute(DependencyHashes hashes) {
            super(HASHES);
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
                off += 2;
                String hash = cr.readUTF8(off, buf);
                off += 2;
                map.put(dn, hash);
            }

            DependencyHashes hashes = new DependencyHashes(algorithm, map);
            isPresent = true;
            if (this.hashes != null)
                hashes = this.hashes;
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
