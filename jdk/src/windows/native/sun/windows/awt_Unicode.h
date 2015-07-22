/*
 * Copyright (c) 1996, 2003, Oracle and/or its affiliates. All rights reserved.
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

/*
 * Unicode to ANSI string conversion macros, based on a slide from a
 * presentation by Asmus Freytag.  These must be macros, since the
 * alloca() has to be in the caller's stack space.
 */

#ifndef AWT_UNICODE_H
#define AWT_UNICODE_H

#include <malloc.h>

// Get a Unicode string copy of a Java String object (Java String aren't
// null-terminated).
extern LPWSTR J2WHelper(LPWSTR lpw, LPWSTR lpj, int nChars);
extern LPWSTR J2WHelper1(LPWSTR lpw, LPWSTR lpj, int offset, int nChars);

extern LPWSTR JNI_J2WHelper1(JNIEnv *env, LPWSTR lpw, jstring jstr);

#define TO_WSTRING(jstr) \
   ((jstr == NULL) ? NULL : \
     (JNI_J2WHelper1(env, (LPWSTR) alloca((env->GetStringLength(jstr)+1)*2), \
                     jstr) \
    ))

#endif // AWT_UNICODE_H
