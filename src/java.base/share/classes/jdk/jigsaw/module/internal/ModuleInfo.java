/*
 * Copyright (c) 2014, 2015, Oracle and/or its affiliates. All rights reserved.
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

import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import jdk.internal.org.objectweb.asm.Attribute;
import jdk.internal.org.objectweb.asm.ClassReader;
import jdk.internal.org.objectweb.asm.ClassVisitor;
import jdk.internal.org.objectweb.asm.ClassWriter;
import jdk.internal.org.objectweb.asm.Opcodes;

import java.lang.module.ModuleDescriptor.Requires;
import java.lang.module.ModuleDescriptor.Requires.Modifier;
import java.lang.module.ModuleDescriptor.Exports;
import java.lang.module.Version;
import jdk.jigsaw.module.internal.Hasher.DependencyHashes;

/**
 * Represents module information as read from a {@code module-info} class file.
 *
 * @implNote The rational for the hand coded reader is performance and fine
 * control on the throwing of ClassFormatError.
 */
public final class ModuleInfo {

    // Attribute names
    static final String MODULE        = "Module";
    static final String MAIN_CLASS    = "MainClass";
    static final String VERSION       = "Version";
    static final String HASHES        = "Hashes";

    // access and requires flags
    static final int ACC_MODULE       = 0x8000;
    static final int ACC_PUBLIC       = 0x0020;
    static final int ACC_SYNTHETIC    = 0x1000;
    static final int ACC_MANDATED     = 0x8000;

    // module name read from the Module attribute
    private final String name;

    // module dependences read from the Module attribute
    private final Set<Requires> requires = new HashSet<>();

    // module exports read from the Module attribute (created lazily)
    private Set<Exports> exports;

    // service dependences (uses) read from the Module attribute
    private Set<String> serviceDependences;

    // service providers (provides) read from the Module attribute
    private Map<String, Set<String>> services;

    // extended meta data read from other attributes
    private Version version;
    private String mainClass;
    private DependencyHashes hashes;

    /**
     * Returns the module name in the {@code Module} attribute. This is the
     * binary name rather than the internal name in the {@code this_class} item.
     */
    public String name() {
        return name;
    }

    /**
     * Returns the module dependences as read from the {@code Module} attribute.
     */
    public Set<Requires> requires() {
        return requires;
    }

    /**
     * Returns the exports as read from the {@code Module} attribute.
     */
    public Set<Exports> exports() {
        if (exports == null) {
            return Collections.emptySet();
        } else {
            return exports;
        }
    }

    /**
     * Returns the service dependences (<em>uses</em>) as read from the
     * {@code Module} attribute.
     */
    public Set<String> serviceDependences() {
        if (serviceDependences == null) {
            return Collections.emptySet();
        } else {
            return serviceDependences;
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
     * Returns the value of version string in the {@code Version} attribute
     * or {@code null} if the attribute does not exist.
     */
    public Version version() {
        return version;
    }

    /**
     * Returns the value of main class string in the {@code MainClass}
     * attribute or {@code null} if the attribute does not exist.
     */
    public String mainClass() {
        return mainClass;
    }

    /**
     * Returns a {@code DependencyHashes} object encapsulating the result of
     * hashing the contents of some or all of the module dependences.
     */
    public DependencyHashes hashes() {
        return hashes;
    }

    /**
     * Reads a {@code module-info.class} from the given input stream.
     *
     * @throws ClassFormatError if the class file is malformed
     * @throws IOException if an I/O errors occurs
     */
    public static ModuleInfo read(InputStream in) throws IOException {
        return new ModuleInfo(new DataInputStream(in), true);
    }

    /**
     * Reads a {@code module-info.class} from the given input stream
     * but ignore the {@code Hashes} attribute.
     *
     * @throws ClassFormatError if the class file is malformed
     * @throws IOException if an I/O errors occurs
     */
    public static ModuleInfo readIgnoringHashes(InputStream in) throws IOException {
        return new ModuleInfo(new DataInputStream(in), false);
    }

    /**
     * An object that may be used to add additional attributes to a module-info
     * class file.
     */
    public static class Appender {

        // the input stream to read the original module-info.class
        private final InputStream in;

        // the value of the Version attribute
        private Version version;

        // the value of the MainClass attribute
        private String mainClass;

        // the hashes for the Hashes attribute
        private DependencyHashes hashes;

        Appender(InputStream in) {
            this.in = in;
        }

        /**
         * Sets the value of the Version attribute.
         */
        public Appender version(Version version) {
            this.version = version;
            return this;
        }

        /**
         * Sets the value of the MainClass attribute.
         */
        public Appender mainClass(String mainClass) {
            this.mainClass = mainClass;
            return this;
        }

        /**
         * The Hashes attribute will be emitted to the module-info with
         * the hashes encapsulated in the given {@code DependencyHashes}
         * object.
         */
        public Appender hashes(DependencyHashes hashes) {
            this.hashes = hashes;
            return this;
        }

        /**
         * Outputs the modified module-info.class to the given output stream.
         * Once this method has been called then the Appender object should
         * be discarded.
         */
        public void write(OutputStream out) throws IOException {
            ClassWriter cw =
                new ClassWriter(ClassWriter.COMPUTE_MAXS + ClassWriter.COMPUTE_FRAMES);
            ClassVisitor cv = new ClassVisitor(Opcodes.ASM5, cw) { };

            ClassReader cr = new ClassReader(in);

            List<Attribute> attrs = new ArrayList<>();
            attrs.add(new ClassFileAttributes.ModuleAttribute());

            // pass through existing attributes if we aren't changing them
            if (version == null)
                attrs.add(new ClassFileAttributes.VersionAttribute());
            if (mainClass == null)
                attrs.add(new ClassFileAttributes.MainClassAttribute());
            if (hashes == null)
                attrs.add(new ClassFileAttributes.HashesAttribute());

            cr.accept(cv, attrs.toArray(new Attribute[0]), 0);

            // add new attributes
            if (version != null)
                cv.visitAttribute(new ClassFileAttributes.VersionAttribute(version));
            if (mainClass != null)
                cv.visitAttribute(new ClassFileAttributes.MainClassAttribute(mainClass));
            if (hashes != null)
                cv.visitAttribute(new ClassFileAttributes.HashesAttribute(hashes));

            // emit to the output stream
            out.write(cw.toByteArray());
        }
    }

    /**
     * Returns an {@code Appender} that may be used to add additional
     * attributes to the module-info.class read from the given input
     * stream.
     */
    public static Appender newAppender(InputStream in) {
        return new Appender(in);
    }

    /**
     * Reads the input as a module-info class file.
     */
    @SuppressWarnings("fallthrough")
    private ModuleInfo(DataInputStream in, boolean parseHashes) throws IOException {
        int magic = in.readInt();
        if (magic != 0xCAFEBABE)
            classFormatError("Bad magic number");

        int minor_version = in.readUnsignedShort();
        int major_version = in.readUnsignedShort();
        if (major_version < 53) {
            // classFormatError("Must be >= 53.0");
        }

        ConstantPool cpool = new ConstantPool(in);

        int access_flags = in.readUnsignedShort();
        if (access_flags != ACC_MODULE)
            classFormatError("access_flags should be ACC_MODULE");

        int this_class = in.readUnsignedShort();
        String mn = cpool.getClassName(this_class);
        int suffix = mn.indexOf("/module-info");
        if (suffix < 1)
            classFormatError("this_class not of form name/module-info");
        this.name = mn.substring(0, suffix).replace('/', '.');

        int super_class = in.readUnsignedShort();
        if (super_class > 0)
            classFormatError("bad #super_class");

        int interfaces_count = in.readUnsignedShort();
        if (interfaces_count > 0)
            classFormatError("Bad #interfaces");

        int fields_count = in.readUnsignedShort();
        if (fields_count > 0)
            classFormatError("Bad #fields");

        int methods_count = in.readUnsignedShort();
        if (methods_count > 0)
            classFormatError("Bad #fields");

        int attributes_count = in.readUnsignedShort();
        boolean foundModule = false;
        boolean foundVersion = false;
        boolean foundMainClass = false;
        boolean foundHashes = false;
        for (int i = 0; i < attributes_count ; i++) {
            int name_index = in.readUnsignedShort();
            String name = cpool.getUtf8(name_index);
            int length = in.readInt();
            switch (name) {
                case MODULE :
                    if (foundModule)
                        classFormatError("More than one " + MODULE + " attribute");
                    readModuleAttribute(in, cpool);
                    foundModule = true;
                    break;
                case VERSION :
                    if (foundVersion)
                        classFormatError("More than one " + VERSION + " attribute");
                    foundVersion = true;
                    readVersionAttribute(in, cpool);
                    break;
                case MAIN_CLASS :
                    if (foundMainClass)
                        classFormatError("More than one " + MAIN_CLASS + " attribute");
                    foundMainClass = true;
                    readMainClassAttribute(in, cpool);
                    break;
                case HASHES :
                    if (parseHashes) {
                        if (foundHashes)
                            classFormatError("More than one " + HASHES + " attribute");
                        foundHashes = true;
                        readHashesAttribute(in, cpool);
                        break;
                    }
                    // fallthrough
                default:
                    // Should check that it's one of: Synthetic, SourceFile,
                    // SourceDebugExtension, Deprecated.
                    in.skip(length);
            }
        }
        if (!foundModule)
            classFormatError("Missing Module attribute");
    }

    /**
     * Reads the Module attribute.
     */
    private void readModuleAttribute(DataInputStream in, ConstantPool cpool)
        throws IOException
    {
        int requires_count = in.readUnsignedShort();
        if (requires_count == 0 && !name.equals("java.base"))
            classFormatError("The requires table must have at least one entry");
        for (int i=0; i<requires_count; i++) {
            int index = in.readUnsignedShort();
            int flags = in.readUnsignedShort();
            String dn = cpool.getUtf8(index);
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
            requires.add(new Requires(mods, dn));
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
                        exports.add(new Exports(pkg, permit));
                    }
                } else {
                    exports.add(new Exports(pkg));
                }
            }
        }

        int uses_count = in.readUnsignedShort();
        if (uses_count > 0) {
            serviceDependences = new HashSet<>();
            for (int i=0; i<uses_count; i++) {
                int index = in.readUnsignedShort();
                String sn = cpool.getClassName(index).replace('/', '.');
                serviceDependences.add(sn);
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
     * Reads the Version attribute
     *
     * @throws IllegalAccessException if the version attribute cannot be parsed
     */
    private void readVersionAttribute(DataInputStream in, ConstantPool cpool)
        throws IOException
    {
        int index = in.readUnsignedShort();
        this.version = Version.parse(cpool.getUtf8(index));
    }

    /**
     * Reads the MainClass attribute
     */
    private void readMainClassAttribute(DataInputStream in, ConstantPool cpool)
        throws IOException
    {
        int index = in.readUnsignedShort();
        this.mainClass = cpool.getClassName(index).replace('/', '.');
    }

    /**
     * Reads the Hashes attribute
     *
     * @apiNote For now the hash is stored in base64 as a UTF-8 string, this
     * should be changed to be an array of u1.
     */
    private void readHashesAttribute(DataInputStream in, ConstantPool cpool)
        throws IOException
    {
        int index = in.readUnsignedShort();
        String algorithm = cpool.getUtf8(index);

        int hash_count = in.readUnsignedShort();

        Map<String, String> map = new HashMap<>();
        for (int i=0; i<hash_count; i++) {
            index = in.readUnsignedShort();
            String dn = cpool.getUtf8(index);
            index = in.readUnsignedShort();
            String hash = cpool.getUtf8(index);
            map.put(dn, hash);
        }

        this.hashes = new DependencyHashes(algorithm, map);
    }

    /**
     * Throws {@code ClassFormatError} with the given message.
     */
    private static void classFormatError(String msg) {
        throw new ClassFormatError(msg);
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
                        classFormatError("Bad constant pool entry" + i);
                }
            }
        }

        String getClassName(int index) {
            checkIndex(index);
            Entry e = pool[index];
            assert e.tag == CONSTANT_Class;
            return getUtf8(((IndexEntry) e).index);
        }

        String getUtf8(int index) {
            checkIndex(index);
            Entry e = pool[index];
            assert e.tag == CONSTANT_Utf8;
            return (String) (((ValueEntry) e).value);
        }

        void checkIndex(int index) {
            if (index >= pool.length)
                classFormatError("Index into constant pool out of range");
        }
    }
}
