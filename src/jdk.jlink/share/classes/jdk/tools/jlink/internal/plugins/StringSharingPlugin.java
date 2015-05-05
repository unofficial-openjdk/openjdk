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
package jdk.tools.jlink.internal.plugins;

import com.sun.tools.classfile.Annotation;
import com.sun.tools.classfile.Attribute;
import com.sun.tools.classfile.Attributes;
import com.sun.tools.classfile.ClassFile;
import com.sun.tools.classfile.ConstantPool;
import com.sun.tools.classfile.ConstantPoolException;
import com.sun.tools.classfile.Field;
import com.sun.tools.classfile.LocalVariableTable_attribute;
import com.sun.tools.classfile.LocalVariableTypeTable_attribute;
import com.sun.tools.classfile.Method;
import com.sun.tools.classfile.RuntimeInvisibleAnnotations_attribute;
import com.sun.tools.classfile.RuntimeParameterAnnotations_attribute;
import com.sun.tools.classfile.RuntimeVisibleAnnotations_attribute;
import com.sun.tools.classfile.Signature_attribute;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import jdk.internal.jimage.decompressor.CompressIndexes;
import jdk.internal.jimage.decompressor.SignatureParser;
import jdk.internal.jimage.decompressor.StringSharingDecompressor;
import jdk.tools.jlink.plugins.ResourcePlugin;
import jdk.tools.jlink.plugins.ResourcePool;
import jdk.tools.jlink.plugins.ResourcePool.Resource;
import jdk.tools.jlink.plugins.ResourcePrevisitor;
import jdk.tools.jlink.plugins.StringTable;

/**
 *
 * A Plugin that stores the image classes constant pool UTF_8 entries into the
 * Image StringsTable.
 */
public class StringSharingPlugin implements ResourcePlugin, ResourcePrevisitor {

    private static class CompactCPHelper {

        private class DescriptorsScanner {

            private final ClassFile cf;

            private DescriptorsScanner(ClassFile cf) {
                this.cf = cf;
            }

            private Set<Integer> scan() throws Exception {
                Set<Integer> utf8Descriptors = new HashSet<>();
                scanConstantPool(utf8Descriptors);

                scanFields(utf8Descriptors);

                scanMethods(utf8Descriptors);

                scanAttributes(cf.attributes, utf8Descriptors);

                return utf8Descriptors;
            }

            private void scanAttributes(Attributes attributes,
                    Set<Integer> utf8Descriptors) throws Exception {
                for (Attribute a : attributes) {
                    if (a instanceof Signature_attribute) {
                        Signature_attribute sig = (Signature_attribute) a;
                        utf8Descriptors.add(sig.signature_index);
                    } else if (a instanceof RuntimeVisibleAnnotations_attribute) {
                        RuntimeVisibleAnnotations_attribute an
                                = (RuntimeVisibleAnnotations_attribute) a;
                        for (Annotation annotation : an.annotations) {
                            scanAnnotation(annotation, utf8Descriptors);
                        }
                    } else if (a instanceof RuntimeInvisibleAnnotations_attribute) {
                        RuntimeInvisibleAnnotations_attribute an
                                = (RuntimeInvisibleAnnotations_attribute) a;
                        for (Annotation annotation : an.annotations) {
                            scanAnnotation(annotation, utf8Descriptors);
                        }
                    } else if (a instanceof RuntimeParameterAnnotations_attribute) {
                        RuntimeParameterAnnotations_attribute rap
                                = (RuntimeParameterAnnotations_attribute) a;
                        for (Annotation[] arr : rap.parameter_annotations) {
                            for (Annotation an : arr) {
                                scanAnnotation(an, utf8Descriptors);
                            }
                        }
                    } else if (a instanceof LocalVariableTable_attribute) {
                        LocalVariableTable_attribute lvt
                                = (LocalVariableTable_attribute) a;
                        for (LocalVariableTable_attribute.Entry entry
                                : lvt.local_variable_table) {
                            utf8Descriptors.add(entry.descriptor_index);
                        }
                    } else if (a instanceof LocalVariableTypeTable_attribute) {
                        LocalVariableTypeTable_attribute lvt
                                = (LocalVariableTypeTable_attribute) a;
                        for (LocalVariableTypeTable_attribute.Entry entry
                                : lvt.local_variable_table) {
                            utf8Descriptors.add(entry.signature_index);
                        }
                    }
                }
            }

            private void scanAnnotation(Annotation annotation,
                    Set<Integer> utf8Descriptors) throws Exception {
                utf8Descriptors.add(annotation.type_index);
                for (Annotation.element_value_pair evp : annotation.element_value_pairs) {
                    utf8Descriptors.add(evp.element_name_index);
                    scanElementValue(evp.value, utf8Descriptors);
                }
            }

            private void scanElementValue(Annotation.element_value value,
                    Set<Integer> utf8Descriptors) throws Exception {
                if (value instanceof Annotation.Enum_element_value) {
                    Annotation.Enum_element_value eev
                            = (Annotation.Enum_element_value) value;
                    utf8Descriptors.add(eev.type_name_index);
                }
                if (value instanceof Annotation.Class_element_value) {
                    Annotation.Class_element_value eev
                            = (Annotation.Class_element_value) value;
                    utf8Descriptors.add(eev.class_info_index);
                }
                if (value instanceof Annotation.Annotation_element_value) {
                    Annotation.Annotation_element_value aev
                            = (Annotation.Annotation_element_value) value;
                    scanAnnotation(aev.annotation_value, utf8Descriptors);
                }
                if (value instanceof Annotation.Array_element_value) {
                    Annotation.Array_element_value aev
                            = (Annotation.Array_element_value) value;
                    for (Annotation.element_value v : aev.values) {
                        scanElementValue(v, utf8Descriptors);
                    }
                }
            }

            private void scanFields(Set<Integer> utf8Descriptors)
                    throws Exception {
                for (Field field : cf.fields) {
                    int descriptorIndex = field.descriptor.index;
                    utf8Descriptors.add(descriptorIndex);
                    scanAttributes(field.attributes, utf8Descriptors);
                }

            }

            private void scanMethods(Set<Integer> utf8Descriptors)
                    throws Exception {
                for (Method m : cf.methods) {
                    int descriptorIndex = m.descriptor.index;
                    utf8Descriptors.add(descriptorIndex);
                    scanAttributes(m.attributes, utf8Descriptors);
                }
            }

            private void scanConstantPool(Set<Integer> utf8Descriptors)
                    throws Exception {
                for (int i = 1; i < cf.constant_pool.size(); i++) {
                    try {
                        ConstantPool.CPInfo info = cf.constant_pool.get(i);
                        if (info instanceof ConstantPool.CONSTANT_NameAndType_info) {
                            ConstantPool.CONSTANT_NameAndType_info nameAndType
                                    = (ConstantPool.CONSTANT_NameAndType_info) info;
                            utf8Descriptors.add(nameAndType.type_index);
                        }
                        if (info instanceof ConstantPool.CONSTANT_MethodType_info) {
                            ConstantPool.CONSTANT_MethodType_info mt
                                    = (ConstantPool.CONSTANT_MethodType_info) info;
                            utf8Descriptors.add(mt.descriptor_index);
                        }

                        if (info instanceof ConstantPool.CONSTANT_Double_info
                                || info instanceof ConstantPool.CONSTANT_Long_info) {
                            i++;
                        }
                    } catch (ConstantPool.InvalidIndex ex) {
                        throw new IOException(ex);
                    }
                }
            }
        }
        private final boolean preVisit;

        public CompactCPHelper(boolean preVisit) {
            this.preVisit = preVisit;
        }

        public byte[] transform(Resource resource, ResourcePool out,
                StringTable strings) throws IOException {
            byte[] content = resource.getByteArray();
            try {
                ClassFile cf;
                try (InputStream stream = new ByteArrayInputStream(content)) {
                    cf = ClassFile.read(stream);
                } catch (ConstantPoolException ex) {
                    System.err.println("Compressor EX " + ex + " for "
                            + resource.getPath() + " content.length " + content.length);
                    throw new IOException(ex);
                }
                DescriptorsScanner scanner = new DescriptorsScanner(cf);
                return optimize(resource, out, strings, scanner.scan(), content);
            } catch (Exception ex) {
                throw new IOException(ex);
            }
        }

        @SuppressWarnings("fallthrough")
        private byte[] optimize(Resource resource, ResourcePool resources,
                StringTable strings,
                Set<Integer> descriptorIndexes, byte[] content) throws Exception {
            DataInputStream stream = new DataInputStream(new ByteArrayInputStream(content));
            ByteArrayOutputStream outStream = new ByteArrayOutputStream(content.length);
            DataOutputStream out = new DataOutputStream(outStream);
            byte[] header = new byte[8]; //magic/4, minor/2, major/2
            stream.read(header);
            out.write(header);
            int count = stream.readUnsignedShort();
            out.writeShort(count);
            for (int i = 1; i < count; i++) {
                int tag = stream.readUnsignedByte();
                byte[] arr;
                switch (tag) {
                    case ConstantPool.CONSTANT_Utf8: {
                        String original = stream.readUTF();
                        // 2 cases, a Descriptor or a simple String
                        if (descriptorIndexes.contains(i)) {
                            SignatureParser.ParseResult parseResult
                                    = SignatureParser.parseSignatureDescriptor(original);
                            List<Integer> indexes
                                    = parseResult.types.stream().map((type) -> {
                                        return strings.addString(type);
                                    }).collect(Collectors.toList());
                            if (!indexes.isEmpty()) {
                                out.write(StringSharingDecompressor.
                                        EXTERNALIZED_STRING_DESCRIPTOR);
                                int sigIndex = strings.addString(parseResult.formatted);
                                byte[] compressed
                                        = CompressIndexes.compress(sigIndex);
                                out.write(compressed, 0, compressed.length);

                                writeDescriptorReference(out, indexes);
                                continue;
                            }
                        }
                        // Put all strings in strings table.
                        writeUTF8Reference(out, strings.addString(original));

                        break;
                    }

                    case ConstantPool.CONSTANT_Long:
                    case ConstantPool.CONSTANT_Double: {
                        i++;
                    }
                    default: {
                        out.write(tag);
                        int size = StringSharingDecompressor.SIZES[tag];
                        arr = new byte[size];
                        stream.read(arr);
                        out.write(arr);
                    }
                }
            }
            out.write(content, content.length - stream.available(),
                    stream.available());
            out.flush();

            return outStream.toByteArray();
        }

        private void writeDescriptorReference(DataOutputStream out,
                List<Integer> indexes) throws IOException {
            List<byte[]> buffers = new ArrayList<>();
            int l = 0;
            for (Integer index : indexes) {
                byte[] buffer = CompressIndexes.compress(index);
                l += buffer.length;
                buffers.add(buffer);
            }
            ByteBuffer bb = ByteBuffer.allocate(l);
            buffers.stream().forEach((buf) -> {
                bb.put(buf);
            });
            byte[] compressed_indices = bb.array();
            byte[] compressed_size = CompressIndexes.
                    compress(compressed_indices.length);
            out.write(compressed_size, 0, compressed_size.length);
            out.write(compressed_indices, 0, compressed_indices.length);
        }

        private void writeUTF8Reference(DataOutputStream out, int index)
                throws IOException {
            out.write(StringSharingDecompressor.EXTERNALIZED_STRING);
            byte[] compressed = CompressIndexes.compress(index);
            out.write(compressed, 0, compressed.length);
        }
    }

    private final ResourceFilter filter;

    public StringSharingPlugin(String[] patterns) {
        this.filter = new ResourceFilter(patterns);
    }

    @Override
    public void visit(ResourcePool resources, ResourcePool result,
            StringTable strings) throws Exception {
        CompactCPHelper visit = new CompactCPHelper(false);
        resources.visit((resource, order, str) -> {
            Resource res = resource;
            if (filter.accept(resource.getPath()) && resource.getPath().endsWith(".class")) {
                byte[] compressed = visit.transform(resource, result, strings);
                res = ResourcePool.CompressedResource.newCompressedResource(resource,
                        ByteBuffer.wrap(compressed), getName(), null, str, order);
            }
            return res;
        }, result, strings);
    }

    @Override
    public String getName() {
        return StringSharingProvider.NAME;
    }

    @Override
    public void previsit(ResourcePool resources, StringTable strings)
            throws Exception {
        CompactCPHelper preVisit = new CompactCPHelper(true);
        for (Resource resource : resources.getResources()) {
            if (resource.getPath().endsWith(".class") && filter.accept(resource.getPath())) {
                preVisit.transform(resource, null, strings);
            }
        }
    }
}
