/*
 * Copyright (c) 2012, Oracle and/or its affiliates. All rights reserved.
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
 *
 */
#ifndef SHARE_VM_TRACE_TRACEBACKEND_HPP
#define SHARE_VM_TRACE_TRACEBACKEND_HPP

#if INCLUDE_TRACE

#include "trace/traceTime.hpp"
#include "tracefiles/traceEventIds.hpp"
#include "runtime/globals.hpp"
#include "runtime/os.hpp"

class TraceBackend {
 public:
  static bool enabled(TraceEventId id) {
    return EnableTracing;
  }

  static TracingTime time() {
    return os::elapsed_counter();
  }

  static TracingTime time_adjustment(jlong time) {
    return time;
  }
};

typedef TraceBackend Tracing;

#endif /* INCLUDE_TRACE */
#endif /* SHARE_VM_TRACE_TRACEBACKEND_HPP */
