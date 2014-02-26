/*
 * Copyright (c) 2004, 2014, Oracle and/or its affiliates. All rights reserved.
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

#include <string.h>

#include "jni.h"
#include "jni_util.h"
#include "jlong.h"
#include "jvm.h"
#include "jdk_util.h"

#include "sun_misc_VM.h"

JNIEXPORT jobject JNICALL
Java_sun_misc_VM_latestUserDefinedLoader(JNIEnv *env, jclass cls) {
    return JVM_LatestUserDefinedLoader(env);
}

JNIEXPORT jlong JNICALL
Java_sun_misc_VM_defineModule(JNIEnv *env, jclass cls, jstring name) {
    void* handle = JVM_DefineModule(env, name);
    return ptr_to_jlong(handle);
}

JNIEXPORT void JNICALL
Java_sun_misc_VM_bindToModule(JNIEnv *env, jclass cls, jobject loader, jstring pkg, jlong handle) {
    JVM_BindToModule(env, loader, pkg, jlong_to_ptr(handle));
}

JNIEXPORT void JNICALL
Java_sun_misc_VM_addRequires(JNIEnv *env, jclass cls, jlong handle1, jlong handle2) {
    JVM_AddRequires(env, jlong_to_ptr(handle1), jlong_to_ptr(handle2));
}

JNIEXPORT void JNICALL
Java_sun_misc_VM_addExports(JNIEnv *env, jclass cls, jlong handle, jstring pkg) {
    JVM_AddExports(env, jlong_to_ptr(handle), pkg);
}

JNIEXPORT void JNICALL
Java_sun_misc_VM_addExportsWithPermits(JNIEnv *env, jclass cls, jlong handle1, jstring pkg, jlong handle2) {
    JVM_AddExportsWithPermits(env, jlong_to_ptr(handle1), pkg, jlong_to_ptr(handle2));
}

JNIEXPORT void JNICALL
Java_sun_misc_VM_addBackdoorAccess(JNIEnv *env, jclass cls, jobject loader, jstring pkg,
                                   jobject toLoader, jstring toPackage)
{
    JVM_AddBackdoorAccess(env, loader, pkg, toLoader, toPackage);
}

typedef void (JNICALL *GetJvmVersionInfo_fp)(JNIEnv*, jvm_version_info*, size_t);

JNIEXPORT void JNICALL
Java_sun_misc_VM_initialize(JNIEnv *env, jclass cls) {
    GetJvmVersionInfo_fp func_p;

    if (!JDK_InitJvmHandle()) {
        JNU_ThrowInternalError(env, "Handle for JVM not found for symbol lookup");
        return;
    }

    func_p = (GetJvmVersionInfo_fp) JDK_FindJvmEntry("JVM_GetVersionInfo");
     if (func_p != NULL) {
        jvm_version_info info;

        memset(&info, 0, sizeof(info));

        /* obtain the JVM version info */
        (*func_p)(env, &info, sizeof(info));
    }
}

