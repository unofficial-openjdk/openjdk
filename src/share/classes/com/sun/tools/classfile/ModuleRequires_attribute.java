/*
 * Copyright (c) 2008, 2014, Oracle and/or its affiliates. All rights reserved.
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

package com.sun.tools.classfile;

import java.io.IOException;

/**
 * See Jigsaw.
 *
 *  <p><b>This is NOT part of any supported API.
 *  If you write code that depends on this, you do so at your own risk.
 *  This code and its internal interfaces are subject to change or
 *  deletion without notice.</b>
 */
public class ModuleRequires_attribute extends Attribute {
    public static final int ACC_PUBLIC   = 0x1;
    public static final int ACC_MANDATED = 1<<15;

    ModuleRequires_attribute(ClassReader cr, int name_index, int length) throws IOException {
        super(name_index, length);
        module_length = cr.readUnsignedShort();
        module_table = new Entry[module_length];
        for (int i = 0; i < module_length; i++)
            module_table[i] = new Entry(cr);
        service_length = cr.readUnsignedShort();
        service_table = new Entry[service_length];
        for (int i = 0; i < service_length; i++)
            service_table[i] = new Entry(cr);
    }

    public ModuleRequires_attribute(ConstantPool constant_pool, Entry[] module_table, Entry[] service_table)
            throws ConstantPoolException {
        this(constant_pool.getUTF8Index(Attribute.ModuleRequires), module_table, service_table);
    }

    public ModuleRequires_attribute(int name_index, Entry[] module_table, Entry[] service_table) {
        super(name_index, 2 + length(module_table) + 2 + length(service_table));
        this.module_length = module_table.length;
        this.module_table = module_table;
        this.service_length = service_table.length;
        this.service_table = service_table;
    }

    public <R, D> R accept(Visitor<R, D> visitor, D data) {
        return visitor.visitModuleRequires(this, data);
    }

    public final int module_length;
    public final Entry[] module_table;
    public final int service_length;
    public final Entry[] service_table;

    private static int length(Entry[] table) {
        return table.length * Entry.length;
    }

    public static class Entry {
        Entry(ClassReader cr) throws IOException {
            index = cr.readUnsignedShort();
            flags = cr.readInt();
        }

        public Entry(int index, int flags) {
            this.index = index;
            this.flags = flags;
        }

        public static final int length = 4;

        public final int index;
        public final int flags;
    }
}
