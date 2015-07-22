/*
 * Copyright (c) 2003, 2007, Oracle and/or its affiliates. All rights reserved.
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
#include "jni_util.h"
#include "jvm.h"

#include "io_util.h"
#include "io_util_md.h"
#include "java_io_FileOutputStream.h"

#include <fcntl.h>

/*******************************************************************/
/*  BEGIN JNI ********* BEGIN JNI *********** BEGIN JNI ************/
/*******************************************************************/

jfieldID fos_fd; /* id for jobject 'fd' in java.io.FileOutputStream */

jfieldID fos_append;

/**************************************************************
 * static methods to store field ID's in initializers
 */

JNIEXPORT void JNICALL
Java_java_io_FileOutputStream_initIDs(JNIEnv *env, jclass fosClass) {
    fos_fd =
        (*env)->GetFieldID(env, fosClass, "fd", "Ljava/io/FileDescriptor;");
    fos_append = (*env)->GetFieldID(env, fosClass, "append", "Z");
}

/**************************************************************
 * Output stream
 */

JNIEXPORT void JNICALL
Java_java_io_FileOutputStream_open(JNIEnv *env, jobject this, jstring path) {
    fileOpen(env, this, path, fos_fd, O_WRONLY | O_CREAT | O_TRUNC);
}

JNIEXPORT void JNICALL
Java_java_io_FileOutputStream_openAppend(JNIEnv *env, jobject this, jstring path) {
    fileOpen(env, this, path, fos_fd, O_WRONLY | O_CREAT | O_APPEND);
}

JNIEXPORT void JNICALL
Java_java_io_FileOutputStream_write(JNIEnv *env, jobject this, jint byte) {
    jboolean append = (*env)->GetBooleanField(env, this, fos_append);
    FD fd = GET_FD(this, fos_fd);
    if (fd == -1) {
        JNU_ThrowIOException(env, "Stream Closed");
        return;
    }
    if (append == JNI_TRUE) {
        if (IO_Lseek(fd, 0L, SEEK_END) == -1) {
            JNU_ThrowIOExceptionWithLastError(env, "Append failed");
        }
    }
    writeSingle(env, this, byte, fos_fd);
}

JNIEXPORT void JNICALL
Java_java_io_FileOutputStream_writeBytes(JNIEnv *env,
    jobject this, jbyteArray bytes, jint off, jint len) {
    jboolean append = (*env)->GetBooleanField(env, this, fos_append);
    FD fd = GET_FD(this, fos_fd);
    if (fd == -1) {
        JNU_ThrowIOException(env, "Stream Closed");
        return;
    }
    if (append == JNI_TRUE) {
        if (IO_Lseek(fd, 0L, SEEK_END) == -1) {
            JNU_ThrowIOExceptionWithLastError(env, "Append failed");
        }
    }
    writeBytes(env, this, bytes, off, len, fos_fd);
}

JNIEXPORT void JNICALL
Java_java_io_FileOutputStream_close0(JNIEnv *env, jobject this) {
        handleClose(env, this, fos_fd);
}
