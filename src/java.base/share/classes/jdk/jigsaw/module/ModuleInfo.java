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

package jdk.jigsaw.module;

import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Collections;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import jdk.jigsaw.module.ModuleDependence.Modifier;

/**
 * Represents module information as read from a {@code module-info} class file.
 */
class ModuleInfo {

    // The name of the Module attribute
    private static final String MODULE = "Module";

    // access and requires flags
    private static final int ACC_MODULE      = 0x8000;
    private static final int ACC_PUBLIC      = 0x0020;

    private final String name;
    private final Set<ModuleDependence> moduleDependences = new HashSet<>();

    // optional and created lazily
    private Set<ServiceDependence> serviceDependences;
    private Set<ModuleExport> exports;
    private Map<String, Set<String>> services;

    /**
     * Returns the module name. This is the binary name rather than the
     * internal name in the {@code this_class} item.
     */
    public String name() {
        return name;
    }

    /**
     * Returns the module dependences as read from the {@code Module} attribute.
     */
    public Set<ModuleDependence> moduleDependences() {
        return moduleDependences;
    }

    /**
     * Returns the service dependences (<em>uses</em>) as read from the {@code Module}
     * attribute.
     */
    public Set<ServiceDependence> serviceDependences() {
        if (serviceDependences == null) {
            return Collections.emptySet();
        } else {
            return serviceDependences;
        }
    }

    /**
     * Returns the exports as read from the {@code Module} attribute.
     */
    public Set<ModuleExport> exports() {
        if (exports == null) {
            return Collections.emptySet();
        } else {
            return exports;
        }
    }

    /**
     * Returns the map of service implementation provided, as read from the
     * {@code Module} attribute.
     */
    public Map<String, Set<String>> services() {
        if (services == null) {
            return Collections.emptyMap();
        } else {
            return services;
        }
    }

    /**
     * Reads a {@code module-info.class} from the given input stream.
     *
     * @throws ClassFormatError if the class file is malformed
     * @throws IOException if an I/O errors occurs
     */
    public static ModuleInfo read(InputStream in) throws IOException {
        return new ModuleInfo(new DataInputStream(in));
    }

    /**
     * Reads the input as a module-info class file.
     */
    private ModuleInfo(DataInputStream in) throws IOException {
        int magic = in.readInt();
        if (magic != 0xCAFEBABE)
            throw new ClassFormatError("Bad magic number");

        int minor_version = in.readUnsignedShort();
        int major_version = in.readUnsignedShort();
        if (major_version < 53) {
            // throw new ClassFormatError("Must be >= 53.0");
        }

        ConstantPool cpool = new ConstantPool(in);

        int access_flags = in.readUnsignedShort();
        if (access_flags != ACC_MODULE) {
            // throw new ClassFormatError("access_flags should be ACC_MODULE");
        }

        int this_class = in.readUnsignedShort();
        String mn = cpool.getClassName(this_class);
        int suffix = mn.indexOf("/module-info");
        if (suffix < 1)
            throw new ClassFormatError("this_class not of form name/module-info");
        this.name = mn.substring(0, suffix).replace('/', '.');

        int super_class = in.readUnsignedShort();
        if (super_class > 0)
            throw new ClassFormatError("bad #super_class");

        int interfaces_count = in.readUnsignedShort();
        if (interfaces_count > 0)
            throw new ClassFormatError("Bad #interfaces");

        int fields_count = in.readUnsignedShort();
        if (fields_count > 0)
            throw new ClassFormatError("Bad #fields");

        int methods_count = in.readUnsignedShort();
        if (methods_count > 0)
            throw new ClassFormatError("Bad #fields");

        int attributes_count = in.readUnsignedShort();
        boolean found = false;
        for (int i = 0; i < attributes_count ; i++) {
            int name_index = in.readUnsignedShort();
            String name = cpool.getUtf8(name_index);
            int length = in.readInt();
            switch (name) {
                case MODULE :
                    if (found)
                        throw new ClassFormatError("More than one Module attribute");
                    readModuleAttribute(in, cpool);
                    found = true;
                    break;
                default:
                    // Should check that it's one of: Synthetic, SourceFile,
                    // SourceDebugExtension, Deprecated.
                    in.skip(length);
            }
        }
        if (!found)
            throw new ClassFormatError("Missing Module attribute");
    }

    /**
     * Reads the Module attribute.
     */
    private void readModuleAttribute(DataInputStream in, ConstantPool cpool)
        throws IOException
    {
        int requires_count = in.readUnsignedShort();
        if (requires_count == 0 && !name.equals("java.base"))
            throw new ClassFormatError("The requires table must have at least one entry");
        for (int i=0; i<requires_count; i++) {
            int index = in.readUnsignedShort();
            int flags = in.readUnsignedShort();
            String dn = cpool.getUtf8(index);
            Set<Modifier> mods = ((flags & ACC_PUBLIC) != 0) ?
                    EnumSet.of(Modifier.PUBLIC) : Collections.emptySet();
            moduleDependences.add(new ModuleDependence(mods, ModuleIdQuery.parse(dn)));
        }

        // ignore permits, they are going away
        int permits_count = in.readUnsignedShort();
        if (permits_count > 0) {
            for (int i=0; i<permits_count; i++) {
                int index = in.readUnsignedShort();
            }
        }

        int exports_count = in.readUnsignedShort();
        if (exports_count > 0) {
            exports = new HashSet<>();
            for (int i=0; i<exports_count; i++) {
                int index = in.readUnsignedShort();
                String pkg = cpool.getUtf8(index).replace('/', '.');
                int exports_to_count = in.readUnsignedShort();
                if (exports_to_count > 0) {
                    for (int j=0; j<exports_to_count; j++) {
                        int exports_to_index = in.readUnsignedShort();
                        String permit = cpool.getUtf8(exports_to_index);
                        exports.add(new ModuleExport(pkg, permit));
                    }
                } else {
                    exports.add(new ModuleExport(pkg));
                }
            }
        }

        int uses_count = in.readUnsignedShort();
        if (uses_count > 0) {
            serviceDependences = new HashSet<>();
            for (int i=0; i<uses_count; i++) {
                int index = in.readUnsignedShort();
                String sn = cpool.getClassName(index).replace('/', '.');
                serviceDependences.add(new ServiceDependence(null, sn));
            }
        }

        int provides_count = in.readUnsignedShort();
        if (provides_count > 0) {
            services = new HashMap<>();
            for (int i=0; i<provides_count; i++) {
                int index = in.readUnsignedShort();
                int with_index = in.readUnsignedShort();
                String sn = cpool.getClassName(index).replace('/', '.');
                String cn = cpool.getClassName(with_index).replace('/', '.');
                services.computeIfAbsent(sn, k -> new HashSet<>()).add(cn);
            }
        }
    }

    /**
     * The constant pool in a class file.
     */
    private static class ConstantPool {
        static final int CONSTANT_Utf8 = 1;
        static final int CONSTANT_Integer = 3;
        static final int CONSTANT_Float = 4;
        static final int CONSTANT_Long = 5;
        static final int CONSTANT_Double = 6;
        static final int CONSTANT_Class = 7;
        static final int CONSTANT_String = 8;
        static final int CONSTANT_Fieldref = 9;
        static final int CONSTANT_Methodref = 10;
        static final int CONSTANT_InterfaceMethodref = 11;
        static final int CONSTANT_NameAndType = 12;
        static final int CONSTANT_MethodHandle = 15;
        static final int CONSTANT_MethodType = 16;
        static final int CONSTANT_InvokeDynamic = 18;

        private static class Entry {
            protected Entry(int tag) {
                this.tag = tag;
            }
            final int tag;
        }

        private static class IndexEntry extends Entry {
            IndexEntry(int tag, int index) {
                super(tag);
                this.index = index;
            }
            final int index;
        }

        private static class Index2Entry extends Entry {
            Index2Entry(int tag, int index1, int index2) {
                super(tag);
                this.index1 = index1;
                this.index2 = index2;
            }
            final int index1,  index2;
        }

        private static class ValueEntry extends Entry {
            ValueEntry(int tag, Object value) {
                super(tag);
                this.value = value;
            }
            final Object value;
        }

        final Entry[] pool;

        ConstantPool(DataInputStream in) throws IOException {
            int count = in.readUnsignedShort();
            pool = new Entry[count];

            for (int i = 1; i < count; i++) {
                int tag = in.readUnsignedByte();
                switch (tag) {
                    case CONSTANT_Class:
                    case CONSTANT_String:
                        int index = in.readUnsignedShort();
                        pool[i] = new IndexEntry(tag, index);
                        break;

                    case CONSTANT_Double:
                        double dvalue = in.readDouble();
                        pool[i] = new ValueEntry(tag, dvalue);
                        i++;
                        break;

                    case CONSTANT_Fieldref:
                    case CONSTANT_InterfaceMethodref:
                    case CONSTANT_Methodref:
                    case CONSTANT_InvokeDynamic:
                    case CONSTANT_NameAndType:
                        int index1 = in.readUnsignedShort();
                        int index2 = in.readUnsignedShort();
                        pool[i] = new Index2Entry(tag, index1, index2);
                        break;

                    case CONSTANT_MethodHandle:
                        int refKind = in.readUnsignedByte();
                        index = in.readUnsignedShort();
                        pool[i] = new Index2Entry(tag, refKind, index);
                        break;

                    case CONSTANT_MethodType:
                        index = in.readUnsignedShort();
                        pool[i] = new IndexEntry(tag, index);
                        break;

                    case CONSTANT_Float:
                        float fvalue = in.readFloat();
                        pool[i] = new ValueEntry(tag, fvalue);
                        break;

                    case CONSTANT_Integer:
                        int ivalue = in.readInt();
                        pool[i] = new ValueEntry(tag, ivalue);
                        break;

                    case CONSTANT_Long:
                        long lvalue = in.readLong();
                        pool[i] = new ValueEntry(tag, lvalue);
                        i++;
                        break;

                    case CONSTANT_Utf8:
                        String svalue = in.readUTF();
                        pool[i] = new ValueEntry(tag, svalue);
                        break;

                    default:
                        throw new ClassFormatError("Bad constant pool entry" + i);
                }
            }
        }

        String getClassName(int index) {
            Entry e = pool[index];
            assert e.tag == CONSTANT_Class;
            return getUtf8(((IndexEntry) e).index);
        }

        String getUtf8(int index) {
            Entry e = pool[index];
            assert e.tag == CONSTANT_Utf8;
            return (String) (((ValueEntry) e).value);
        }

        Object getValue(int index) {
            Entry e = pool[index];
            assert e.tag == CONSTANT_Double ||
                    e.tag == CONSTANT_Float ||
                    e.tag == CONSTANT_Integer ||
                    e.tag == CONSTANT_Long;
            return ((ValueEntry) e).value;
        }
    }
}
