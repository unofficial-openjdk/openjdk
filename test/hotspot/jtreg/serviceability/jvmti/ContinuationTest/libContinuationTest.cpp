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

extern "C" {

static jvmtiEnv *jvmti = NULL;
static jthread exp_thread = NULL;
static jrawMonitorID event_mon = NULL;
static int frame_pop_count = 0;

static void
lock_events() {
  jvmti->RawMonitorEnter(event_mon);
}

static void
unlock_events() {
  jvmti->RawMonitorExit(event_mon);
}

static void
check_jvmti_status(JNIEnv* jni, jvmtiError err, const char* msg) {
  if (err != JVMTI_ERROR_NONE) {
    printf("check_jvmti_status: JVMTI function returned error: %d\n", err);
    jni->FatalError(msg);
  }
}

#define MAX_FRAME_COUNT 20

static void
print_stack_trace(jvmtiEnv *jvmti, JNIEnv* jni) { 
  jvmtiFrameInfo frames[MAX_FRAME_COUNT];
  jint count = 0;
  jvmtiError err;

  err = jvmti->GetStackTrace(NULL, 0, MAX_FRAME_COUNT, frames, &count);
  check_jvmti_status(jni, err, "print_stack_trace: error in JVMTI GetStackTrace");

  printf("JVMTI Stack Trace: frame count: %d\n", count);
  for (int depth = 0; depth < count; depth++) {
    char *mname = NULL;
    char *msign = NULL;

    err = jvmti->GetMethodName(frames[depth].method, &mname, &msign, NULL);
    check_jvmti_status(jni, err, "print_stack_trace: error in JVMTI GetMethodName");
    printf("depth #%d: %s%s\n", depth, mname, msign);
  }
  printf("\n");
}

static void
print_frame_event_info(jvmtiEnv *jvmti, JNIEnv* jni, jthread thread, jmethodID method, const char* event_name) {
  char* mname = NULL;
  char* msign = NULL;
  jvmtiThreadInfo thr_info;
  jvmtiError err;

  memset(&thr_info, 0, sizeof(thr_info));
  err = jvmti->GetThreadInfo(thread, &thr_info);
  check_jvmti_status(jni, err, "event handler failed during JVMTI GetThreadInfo call");
  const char* thr_name = (thr_info.name == NULL) ? "<Unnamed thread>" : thr_info.name;

  err = jvmti->GetMethodName(method, &mname, &msign, NULL);
  check_jvmti_status(jni, err, "event handler failed during JVMTI GetThreadInfo call");

  printf("%s event: thread: %s, method: %s%s\n", event_name, thr_name, mname, msign);
  fflush(0);
}

static void
print_cont_event_info(jvmtiEnv *jvmti, JNIEnv* jni, jthread thread, jint frames_cnt, const char* event_name) {
  jvmtiThreadInfo thr_info;
  jvmtiError err;

  memset(&thr_info, 0, sizeof(thr_info));
  err = jvmti->GetThreadInfo(thread, &thr_info);
  check_jvmti_status(jni, err, "event handler failed during JVMTI GetThreadInfo call");

  const char* thr_name = (thr_info.name == NULL) ? "<Unnamed thread>" : thr_info.name;
  printf("\n%s event: thread: %s, frames: %d\n\n", event_name, thr_name, frames_cnt);

  print_stack_trace(jvmti, jni);
  fflush(0);
}

static void JNICALL
FramePop(jvmtiEnv *jvmti, JNIEnv* jni, jthread thread, jmethodID method,
         jboolean was_popped_by_exception) {
  lock_events();
  frame_pop_count++;
  print_frame_event_info(jvmti, jni, thread, method, "FramePop");
  unlock_events();
}

static void JNICALL
ContinuationRun(jvmtiEnv *jvmti, JNIEnv* jni, jthread thread, jint frames_count) {
  lock_events();
  print_cont_event_info(jvmti, jni, thread, frames_count, "ContinuationRun");
  unlock_events();
}

static void JNICALL
ContinuationYield(jvmtiEnv *jvmti, JNIEnv* jni, jthread thread, jint frames_count) {
  lock_events();
  print_cont_event_info(jvmti, jni, thread, frames_count, "ContinuationYield");

  // Request FramePop notifications for all continuation frames.
  // They all are expected to be cleared as a part of yield protocol.
  for (jint depth = 0; depth < frames_count; depth++) {
    jvmtiError err = jvmti->NotifyFramePop(thread, depth);
    check_jvmti_status(jni, err, "ContinuationYield: error in JVMTI NotifyFramePop");
  }
  unlock_events();
}

JNIEXPORT jint JNICALL
Agent_OnLoad(JavaVM *jvm, char *options, void *reserved) {
  jvmtiEventCallbacks callbacks;
  jvmtiCapabilities caps;
  jvmtiError err;

  printf("Agent_OnLoad started\n");
  if (jvm->GetEnv((void **) (&jvmti), JVMTI_VERSION) != JNI_OK) {
    return JNI_ERR;
  }

  memset(&callbacks, 0, sizeof(callbacks));
  callbacks.FramePop          = &FramePop;
  callbacks.ContinuationRun   = &ContinuationRun;
  callbacks.ContinuationYield = &ContinuationYield;

  memset(&caps, 0, sizeof(caps));
  caps.can_generate_method_exit_events = 1;
  caps.can_generate_frame_pop_events = 1;
  caps.can_support_continuations = 1;

  err = jvmti->AddCapabilities(&caps);
  if (err != JVMTI_ERROR_NONE) {
    printf("Agent_OnLoad: Error in JVMTI AddCapabilities: %d\n", err);
  }

  err = jvmti->SetEventCallbacks(&callbacks, sizeof(jvmtiEventCallbacks));
  if (err != JVMTI_ERROR_NONE) {
    printf("Agent_OnLoad: Error in JVMTI SetEventCallbacks: %d\n", err);
  }

  err = jvmti->CreateRawMonitor("Events Monitor", &event_mon);
  if (err != JVMTI_ERROR_NONE) {
    printf("Agent_OnLoad: Error in JVMTI CreateRawMonitor: %d\n", err);
  }

  printf("Agent_OnLoad finished\n");
  fflush(0);

  return JNI_OK;
}

JNIEXPORT void JNICALL
Java_MyPackage_ContinuationTest_enableEvents(JNIEnv *jni, jclass cls, jthread thread) {
  jvmtiError err;

  printf("enableEvents: started\n");
  exp_thread = (jthread)jni->NewGlobalRef(thread);

  err = jvmti->SetEventNotificationMode(JVMTI_ENABLE, JVMTI_EVENT_FRAME_POP, thread);
  check_jvmti_status(jni, err, "enableEvents: error in JVMTI SetEventNotificationMode: enable FRAME_POP");

  err = jvmti->SetEventNotificationMode(JVMTI_ENABLE, JVMTI_EVENT_CONTINUATION_RUN, thread);
  check_jvmti_status(jni, err, "enableEvents: error in JVMTI SetEventNotificationMode: enable CONTINUATION_RUN");

  err = jvmti->SetEventNotificationMode(JVMTI_ENABLE, JVMTI_EVENT_CONTINUATION_YIELD, thread);
  check_jvmti_status(jni, err, "enableEvents: error in JVMTI SetEventNotificationMode: enable CONTINUATION_YIELD");

  printf("enableEvents: finished\n");
  fflush(0);
}

JNIEXPORT jboolean JNICALL
Java_MyPackage_ContinuationTest_check(JNIEnv *jni, jclass cls) {
  jvmtiError err;

  printf("\n");
  printf("check: started\n");

  err = jvmti->SetEventNotificationMode(JVMTI_DISABLE, JVMTI_EVENT_FRAME_POP, exp_thread);
  check_jvmti_status(jni, err, "error in JVMTI SetEventNotificationMode: disable FRAME_POP");

  err = jvmti->SetEventNotificationMode(JVMTI_DISABLE, JVMTI_EVENT_CONTINUATION_RUN, exp_thread);
  check_jvmti_status(jni, err, "error in JVMTI SetEventNotificationMode: disable CONTINUATION_RUN");

  err = jvmti->SetEventNotificationMode(JVMTI_DISABLE, JVMTI_EVENT_CONTINUATION_YIELD, exp_thread);
  check_jvmti_status(jni, err, "error in JVMTI SetEventNotificationMode: disable CONTINUATION_YIELD");

  printf("check: finished\n");
  printf("\n");
  fflush(0);

  return frame_pop_count == 0;
}
} // extern "C"
