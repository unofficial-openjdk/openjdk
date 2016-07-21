/*
 * Copyright (c) 2016, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.
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

#include <stdio.h>
#include <string.h>
#include "jvmti.h"

#ifdef __cplusplus
extern "C" {
#endif

#ifndef JNI_ENV_ARG

#ifdef __cplusplus
#define JNI_ENV_ARG(x, y) y
#define JNI_ENV_PTR(x) x
#else
#define JNI_ENV_ARG(x,y) x, y
#define JNI_ENV_PTR(x) (*x)
#endif

#endif

#define TranslateError(err) "JVMTI error"

#define PASSED 0
#define FAILED 2

static const char *EXC_CNAME = "java/lang/Exception";
static const char* MOD_CNAME = "Ljava/lang/reflect/Module;";

static jvmtiEnv *jvmti = NULL;
static jint result = PASSED;

static jint Agent_Initialize(JavaVM *jvm, char *options, void *reserved);

JNIEXPORT
jint JNICALL Agent_OnLoad(JavaVM *jvm, char *options, void *reserved) {
    return Agent_Initialize(jvm, options, reserved);
}

JNIEXPORT
jint JNICALL Agent_OnAttach(JavaVM *jvm, char *options, void *reserved) {
    return Agent_Initialize(jvm, options, reserved);
}

JNIEXPORT
jint JNICALL JNI_OnLoad(JavaVM *jvm, void *reserved) {
    return JNI_VERSION_1_8;
}

static
jint Agent_Initialize(JavaVM *jvm, char *options, void *reserved) {
    jint res = JNI_ENV_PTR(jvm)->GetEnv(JNI_ENV_ARG(jvm, (void **) &jvmti),
                                        JVMTI_VERSION_9);
    if (res != JNI_OK || jvmti == NULL) {
        printf("    Error: wrong result of a valid call to GetEnv!\n");
        return JNI_ERR;
    }

    return JNI_OK;
}

static
jint throw_exc(JNIEnv *env, char *msg) {
    jclass exc_class = JNI_ENV_PTR(env)->FindClass(JNI_ENV_ARG(env, EXC_CNAME));

    if (exc_class == NULL) {
        printf("throw_exc: Error in FindClass(env, %s)\n", EXC_CNAME);
        return -1;
    }
    return JNI_ENV_PTR(env)->ThrowNew(JNI_ENV_ARG(env, exc_class), msg);
}

static
jclass jlrM(JNIEnv *env) {
    jclass cls = NULL;

    cls = JNI_ENV_PTR(env)->FindClass(JNI_ENV_ARG(env, MOD_CNAME));
    if (cls == NULL) {
        printf("    Error in JNI FindClass: %s\n", MOD_CNAME);
    }
    return cls;
}

jmethodID
get_method(JNIEnv *env, jclass clazz, const char * name, const char *sig) {
    jmethodID method = NULL;

    method = JNI_ENV_PTR(env)->GetMethodID(JNI_ENV_ARG(env, clazz), name, sig);
    if (method == NULL) {
        printf("    Error in JNI GetMethodID %s with signature %s", name, sig);
    }
    return method;
}

static
jboolean is_exported(JNIEnv *env, jobject module, const char* pkg) {
    static jmethodID mIsExported = NULL;
    jstring jstr = NULL;
    jboolean res = JNI_FALSE;

    if (mIsExported == NULL) {
        const char* sign = "(Ljava/lang/String;)Z";
        mIsExported = get_method(env, jlrM(env), "isExported", sign);
    }
    jstr = JNI_ENV_PTR(env)->NewStringUTF(JNI_ENV_ARG(env, pkg));
    res = JNI_ENV_PTR(env)->CallBooleanMethod(JNI_ENV_ARG(env, module),
                                              mIsExported, jstr);
    return res;
}

static
jboolean is_exported_to(JNIEnv *env, jobject module, const char* pkg, jobject to_module) {
    static jmethodID mIsExportedTo = NULL;
    jstring jstr = NULL;
    jboolean res = JNI_FALSE;

    if (mIsExportedTo == NULL) {
        const char* sign = "(Ljava/lang/String;Ljava/lang/reflect/Module;)Z";
        mIsExportedTo = get_method(env, jlrM(env), "isExported", sign);
    }
    jstr = JNI_ENV_PTR(env)->NewStringUTF(JNI_ENV_ARG(env, pkg));
    res = JNI_ENV_PTR(env)->CallBooleanMethod(JNI_ENV_ARG(env, module),
                                              mIsExportedTo, jstr, to_module);
    return res;
}

static
jint check_add_module_exports(JNIEnv *env,
                              jclass  cls,
                              jobject baseModule,
                              jobject thisModule) {
    jvmtiError err = JVMTI_ERROR_NONE;
    const char* pkg = "jdk.internal.misc";
    const char* bad_pkg = "my.bad.pkg";
    jboolean exported = JNI_FALSE;

    // Export from NULL module
    printf("Check #N1:\n");
    err = (*jvmti)->AddModuleExports(jvmti, NULL, pkg, thisModule);
    if (err != JVMTI_ERROR_NULL_POINTER) {
        printf("#N1: jvmtiError from AddModuleExports: %d\n", err);
        throw_exc(env, "Check #N1: failed to return JVMTI_ERROR_NULL_POINTER for module==NULL");
        return FAILED;
    }

    // Export NULL package
    printf("Check #N2:\n");
    err = (*jvmti)->AddModuleExports(jvmti, baseModule, NULL, thisModule);
    if (err != JVMTI_ERROR_NULL_POINTER) {
        printf("#N2: jvmtiError from AddModuleExports: %d\n", err);
        throw_exc(env, "Check #N2: failed to return JVMTI_ERROR_NULL_POINTER for pkg==NULL");
        return FAILED;
    }

    // Export to NULL module
    printf("Check #N3:\n");
    err = (*jvmti)->AddModuleExports(jvmti, baseModule, pkg, NULL);
    if (err != JVMTI_ERROR_NULL_POINTER) {
        printf("#N3: jvmtiError from AddModuleExports: %d\n", err);
        throw_exc(env, "Check #N3: failed to return JVMTI_ERROR_NULL_POINTER for to_module==NULL");
        return FAILED;
    }

    // Export a bad package
    printf("Check #I0:\n");
    err = (*jvmti)->AddModuleExports(jvmti, baseModule, bad_pkg, thisModule);
    if (err != JVMTI_ERROR_ILLEGAL_ARGUMENT) {
        printf("#I0: jvmtiError from AddModuleExports: %d\n", err);
        throw_exc(env, "Check #I0: did not get expected JVMTI_ERROR_ILLEGAL_ARGUMENT for invalid package");
        return FAILED;
    }

    // Export from invalid module (cls)
    printf("Check #I1:\n");
    err = (*jvmti)->AddModuleExports(jvmti, (jobject)cls, pkg, thisModule);
    if (err != JVMTI_ERROR_INVALID_MODULE) {
        printf("#I1: jvmtiError from AddModuleExports: %d\n", err);
        throw_exc(env, "Check #I1: did not get expected JVMTI_ERROR_INVALID_MODULE for invalid module");
        return FAILED;
    }

    // Export to invalid module (cls)
    printf("Check #I2:\n");
    err = (*jvmti)->AddModuleExports(jvmti, baseModule, pkg, (jobject)cls);
    if (err != JVMTI_ERROR_INVALID_MODULE) {
        printf("#I2: jvmtiError from AddModuleExports: %d\n", err);
        throw_exc(env, "Check #I2: did not get expected JVMTI_ERROR_INVALID_MODULE for invalid to_module");
        return FAILED;
    }

    // Check the "jdk.internal.misc" is not exported from baseModule to thisModule
    printf("Check #C0:\n");
    exported = is_exported_to(env, baseModule, pkg, thisModule);
    if (exported != JNI_FALSE) {
        throw_exc(env, "Check #C0: unexpected export of jdk.internal.misc from base to this");
        return FAILED;
    }

    // Add export of "jdk.internal.misc" from baseModule to thisModule
    printf("Check #C1:\n");
    err = (*jvmti)->AddModuleExports(jvmti, baseModule, pkg, thisModule);
    if (err != JVMTI_ERROR_NONE) {
        printf("#C1: jvmtiError from AddModuleExports: %d\n", err);
        throw_exc(env, "Check #C1: error in add export of jdk.internal.misc from base to this");
        return FAILED;
    }

    // Check the "jdk.internal.misc" is exported from baseModule to thisModule
    printf("Check #C2:\n");
    exported = is_exported_to(env, baseModule, pkg, thisModule);
    if (exported == JNI_FALSE) {
        throw_exc(env, "Check #C2: failed to export jdk.internal.misc from base to this");
        return FAILED;
    }

    // Check the "jdk.internal.misc" is not exported to all modules
    printf("Check #C3:\n");
    exported = is_exported(env, baseModule, pkg);
    if (exported != JNI_FALSE) {
        throw_exc(env, "Check #C3: unexpected export of jdk.internal.misc from base to all modules");
        return FAILED;
    }
    return PASSED;
}

JNIEXPORT jint JNICALL
Java_MyPackage_AddModuleExportsTest_check(JNIEnv *env,
                                          jclass cls,
                                          jobject baseModule,
                                          jobject thisModule) {
    if (jvmti == NULL) {
        throw_exc(env, "JVMTI client was not properly loaded!\n");
        return FAILED;
    }

    printf("\n*** Checks for JVMTI AddModuleExports ***\n\n");
    result = check_add_module_exports(env, cls, baseModule, thisModule);
    if (result != PASSED) {
        return result;
    }
    return result;
}

#ifdef __cplusplus
}
#endif
