/*
 * Copyright (c) 2018, Oracle and/or its affiliates. All rights reserved.
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

#include <string.h>
#include "jvmti.h"

#ifdef __cplusplus
extern "C" {
#endif

static void
processFiberEvent(jvmtiEnv *jvmti, JNIEnv* jni, jthread thread, jobject fiber, char* event_name) {
  jboolean is_fiber = JNI_FALSE;
  jvmtiThreadInfo thr_info;
  jvmtiError err;

  err = (*jvmti)->GetThreadInfo(jvmti, thread, &thr_info);

  if (err != JVMTI_ERROR_NONE) {
    (*jni)->FatalError(jni, "event handler failed during the JVMTI GetThreadInfo call");
  }
  char* thr_name = (thr_info.name == NULL) ? "<Unnamed thread>" : thr_info.name;
  printf("%s event: carrier-thread: %s, fiber: %p\n", event_name, thr_name, fiber);

  err = (*jvmti)->IsFiber(jvmti, thread, &is_fiber);
  if (err != JVMTI_ERROR_NONE) {
    (*jni)->FatalError(jni, "event handler: failed during the JVMTI IsFiber call");
  }
  if (is_fiber != JNI_FALSE) {
    (*jni)->FatalError(jni, "event handler: JVMTI IsFiber failed to return FALSE for thread object");
  }
  printf("%s event: JVMTI IsFiber returned JNI_FALSE for a career thread as expected\n", event_name);

  err = (*jvmti)->IsFiber(jvmti, fiber, &is_fiber);
  if (err != JVMTI_ERROR_NONE) {
    (*jni)->FatalError(jni, "event handler: failed during the JVMTI IsFiber call");
  }
  if (is_fiber != JNI_TRUE) {
    (*jni)->FatalError(jni, "event handler: JVMTI IsFiber failed to return TRUE for fiber object");
  }
  printf("%s event: JVMTI IsFiber returned JNI_TRUE for a fiber as expected\n\n", event_name);
}

static void JNICALL
FiberStart(jvmtiEnv *jvmti, JNIEnv* jni, jthread thread, jobject fiber) {
  jobject mounted_fiber = NULL;
  jvmtiError err;

  processFiberEvent(jvmti, jni, thread, fiber, "FiberStart");
  err = (*jvmti)->GetThreadFiber(jvmti, thread, &mounted_fiber);

  if (err != JVMTI_ERROR_NONE) {
    (*jni)->FatalError(jni, "FiberStart event handler: failed during the JVMTI GetThreadFiber call");
  }
  if (mounted_fiber != NULL) {
    (*jni)->FatalError(jni, "FiberStart event handler: JVMTI GetThreadFiber failed to return NULL for mounted fiber");
  }
}

static void JNICALL
FiberEnd(jvmtiEnv *jvmti, JNIEnv* jni, jthread thread, jobject fiber) {
  processFiberEvent(jvmti, jni, thread, fiber, "FiberEnd");
}

static void JNICALL
FiberMount(jvmtiEnv *jvmti, JNIEnv* jni, jthread thread, jobject fiber) {
  processFiberEvent(jvmti, jni, thread, fiber, "FiberMount");
}

static void JNICALL
FiberUnmount(jvmtiEnv *jvmti, JNIEnv* jni, jthread thread, jobject fiber) {
  processFiberEvent(jvmti, jni, thread, fiber, "FiberUnmount");
}

extern JNIEXPORT jint JNICALL Agent_OnLoad(JavaVM *jvm, char *options,
                                           void *reserved) {
  jvmtiEnv *jvmti;
  jvmtiEventCallbacks callbacks;
  jvmtiCapabilities caps;
  jvmtiError err;

  printf("Agent_OnLoad started\n");
  if ((*jvm)->GetEnv(jvm, (void **) (&jvmti), JVMTI_VERSION) != JNI_OK) {
    return JNI_ERR;
  }

  memset(&callbacks, 0, sizeof(callbacks));
  callbacks.FiberStart   = &FiberStart;
  callbacks.FiberEnd     = &FiberEnd;
  callbacks.FiberMount   = &FiberMount;
  callbacks.FiberUnmount = &FiberUnmount;

  memset(&caps, 0, sizeof(caps));
  caps.can_support_fibers = 1;
  err = (*jvmti)->AddCapabilities(jvmti, &caps);
  if (err != JVMTI_ERROR_NONE) {
    printf("error in JVMTI AddCapabilities: %d\n", err);
  }

  err = (*jvmti)->SetEventCallbacks(jvmti, &callbacks, sizeof(jvmtiEventCallbacks));
  if (err != JVMTI_ERROR_NONE) {
    printf("error in JVMTI SetEventCallbacks: %d\n", err);
  }

  err = (*jvmti)->SetEventNotificationMode(jvmti, JVMTI_ENABLE, JVMTI_EVENT_FIBER_START, NULL);
  if (err != JVMTI_ERROR_NONE) {
    printf("error in JVMTI SetEventNotificationMode: %d\n", err);
  }

  err = (*jvmti)->SetEventNotificationMode(jvmti, JVMTI_ENABLE, JVMTI_EVENT_FIBER_END, NULL);
  if (err != JVMTI_ERROR_NONE) {
    printf("error in JVMTI SetEventNotificationMode: %d\n", err);
  }

  err = (*jvmti)->SetEventNotificationMode(jvmti, JVMTI_ENABLE, JVMTI_EVENT_FIBER_MOUNT, NULL);
  if (err != JVMTI_ERROR_NONE) {
    printf("error in JVMTI SetEventNotificationMode: %d\n", err);
  }

  err = (*jvmti)->SetEventNotificationMode(jvmti, JVMTI_ENABLE, JVMTI_EVENT_FIBER_UNMOUNT, NULL);
  if (err != JVMTI_ERROR_NONE) {
    printf("error in JVMTI SetEventNotificationMode: %d\n", err);
  }

  printf("Agent_OnLoad finished\n");
  return 0;
}

#ifdef __cplusplus
}
#endif
