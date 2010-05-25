/*
 * Copyright (c) 2003, 2006, Oracle and/or its affiliates. All rights reserved.
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

package sun.misc;

import sun.reflect.ConstantPool;
import sun.reflect.annotation.AnnotationType;
import sun.nio.ch.Interruptible;

public interface JavaLangAccess {
    /** Return the constant pool for a class. */
    ConstantPool getConstantPool(Class klass);

    /**
     * Set the AnnotationType instance corresponding to this class.
     * (This method only applies to annotation types.)
     */
    void setAnnotationType(Class klass, AnnotationType annotationType);

    /**
     * Get the AnnotationType instance corresponding to this class.
     * (This method only applies to annotation types.)
     */
    AnnotationType getAnnotationType(Class klass);

    /**
     * Returns the elements of an enum class or null if the
     * Class object does not represent an enum type;
     * the result is uncloned, cached, and shared by all callers.
     */
    <E extends Enum<E>> E[] getEnumConstantsShared(Class<E> klass);

    /** Set thread's blocker field. */
    void blockedOn(Thread t, Interruptible b);
}
