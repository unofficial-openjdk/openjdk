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

#include "jni.h"
#include "jvm.h"

#include "jdk_internal_jimage_ImageNativeSubstrate.h"

JNIEXPORT jlong JNICALL
Java_jdk_internal_jimage_ImageNativeSubstrate_openImage(JNIEnv *env,
                                        jclass cls, jstring path, jboolean big_endian) {
    return JVM_ImageOpen(env, path, big_endian);
}

JNIEXPORT void JNICALL
Java_jdk_internal_jimage_ImageNativeSubstrate_closeImage(JNIEnv *env,
                                        jclass cls, jlong id) {
    JVM_ImageClose(env, id);
}

JNIEXPORT jlong JNICALL
Java_jdk_internal_jimage_ImageNativeSubstrate_getIndexAddress(JNIEnv *env,
                jclass cls, jlong id) {
 return JVM_ImageGetIndexAddress(env, id);
}

JNIEXPORT jlong JNICALL
Java_jdk_internal_jimage_ImageNativeSubstrate_getDataAddress(JNIEnv *env,
                jclass cls, jlong id) {
 return JVM_ImageGetDataAddress(env, id);
}

JNIEXPORT jboolean JNICALL
Java_jdk_internal_jimage_ImageNativeSubstrate_read(JNIEnv *env,
                                        jclass cls, jlong id, jlong offset,
                                        jobject uncompressedBuffer, jlong uncompressed_size) {
    return JVM_ImageRead(env, id, offset, uncompressedBuffer, uncompressed_size);
}

JNIEXPORT jboolean JNICALL
Java_jdk_internal_jimage_ImageNativeSubstrate_readCompressed(JNIEnv *env,
                                        jclass cls, jlong id, jlong offset,
                                        jobject compressedBuffer, jlong compressed_size,
                                        jobject uncompressedBuffer, jlong uncompressed_size) {
    return JVM_ImageReadCompressed(env, id, offset, compressedBuffer, compressed_size,
                                                    uncompressedBuffer, uncompressed_size);
}

JNIEXPORT jbyteArray JNICALL
Java_jdk_internal_jimage_ImageNativeSubstrate_getStringBytes(JNIEnv *env,
                                        jclass cls, jlong id, jint offset) {
    return JVM_ImageGetStringBytes(env, id, offset);
}

JNIEXPORT jlongArray JNICALL
Java_jdk_internal_jimage_ImageNativeSubstrate_getAttributes(JNIEnv *env,
                                        jclass cls, jlong id, jint offset) {
    return JVM_ImageGetAttributes(env, id, offset);
}

JNIEXPORT jlongArray JNICALL
Java_jdk_internal_jimage_ImageNativeSubstrate_findAttributes(JNIEnv *env,
                                        jclass cls, jlong id, jbyteArray utf8) {
    return JVM_ImageFindAttributes(env, id, utf8);
}

JNIEXPORT jintArray JNICALL
Java_jdk_internal_jimage_ImageNativeSubstrate_attributeOffsets(JNIEnv *env,
                                        jclass cls, jlong id) {
    return JVM_ImageAttributeOffsets(env, id);
}
