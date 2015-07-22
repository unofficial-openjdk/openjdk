/*
 * Copyright (c) 1999, 2001, Oracle and/or its affiliates. All rights reserved.
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
#ifndef     _INC_MULTIMON_
#define     _INC_MULTIMON_
#endif
//
// build defines that replace the regular APIs with our versions
//
#undef GetMonitorInfo
#undef GetSystemMetrics
#undef MonitorFromWindow
#undef MonitorFromRect
#undef MonitorFromPoint
#undef EnumDisplayMonitors
#undef EnumDisplayDevices

#include    "awt_MMStub.h"

#define GetSystemMetricsMM      _getSystemMetrics
#define MonitorFromWindow       _monitorFromWindow
#define MonitorFromRect         _monitorFromRect
#define MonitorFromPoint        _monitorFromPoint
#define GetMonitorInfo          _getMonitorInfo
#define EnumDisplayMonitors     _enumDisplayMonitors
#define EnumDisplayDevices      _enumDisplayDevices


#define CountMonitors           _countMonitors
#define CollectMonitors         _collectMonitors
#define MonitorBounds           _monitorBounds
#define MakeDCFromMonitor       _makeDCFromMonitor
#define CreateWindowOnMonitor   _createWindowOM
