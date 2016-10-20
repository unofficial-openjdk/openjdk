/*
 * Copyright (c) 2005, 2010, Oracle and/or its affiliates. All rights reserved.
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
 * This file primarily consists of all the error and warning messages, that
 * are used in JLI_ReportErrorMessage. All message must be defined here, in
 * order to help with localizing the messages.
 */

#ifndef _EMESSAGES_H
#define _EMESSAGES_H

#define JNI_ERROR       "Error: A JNI error has occurred, please check your installation and try again"

#define ARG_ERROR1      "Error: %s requires class path specification"
#define ARG_ERROR2      "Error: %s requires jar file specification"

#define JRE_ERROR1      "Error: Could not find Java SE Runtime Environment."
#define JRE_ERROR11     "Error: Path length exceeds maximum length (PATH_MAX)"
#define JRE_ERROR13     "Error: String processing operation failed"

#define DLL_ERROR4      "Error: loading: %s"

#endif /* _EMESSAGES_H */
