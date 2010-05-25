/*
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
 This file is available under and governed by the GNU General Public
 License version 2 only, as published by the Free Software Foundation.
 However, the following notice accompanied the original version of this
 file:

Copyright (C) 1994-2001 The XFree86 Project, Inc. All Rights Reserved.

Permission is hereby granted, free of charge, to any person obtaining a
copy of this software and associated documentation files (the "Soft-
ware"), to deal in the Software without restriction, including without
limitation the rights to use, copy, modify, merge, publish, distribute,
and/or sell copies of the Software, and to permit persons to whom the
Software is furnished to do so, provided that the above copyright
notice(s) and this permission notice appear in all copies of the Soft-
ware and that both the above copyright notice(s) and this permission
notice appear in supporting documentation.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS
OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABIL-
ITY, FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT OF THIRD PARTY
RIGHTS. IN NO EVENT SHALL THE COPYRIGHT HOLDER OR HOLDERS INCLUDED IN
THIS NOTICE BE LIABLE FOR ANY CLAIM, OR ANY SPECIAL INDIRECT OR CONSE-
QUENTIAL DAMAGES, OR ANY DAMAGES WHATSOEVER RESULTING FROM LOSS OF USE,
DATA OR PROFITS, WHETHER IN AN ACTION OF CONTRACT, NEGLIGENCE OR OTHER
TORTIOUS ACTION, ARISING OUT OF OR IN CONNECTION WITH THE USE OR PERFOR-
MANCE OF THIS SOFTWARE.

Except as contained in this notice, the name of a copyright holder shall
not be used in advertising or otherwise to promote the sale, use or
other dealings in this Software without prior written authorization of
the copyright holder.
*/

/* $XFree86: xc/include/extensions/Xinerama.h,v 3.2 2000/03/01 01:04:20 dawes Exp $ */

#ifdef __linux__

#ifndef _Xinerama_h
#define _Xinerama_h

typedef struct {
   int   screen_number;
   short x_org;
   short y_org;
   short width;
   short height;
} XineramaScreenInfo;

Bool XineramaQueryExtension (
   Display *dpy,
   int     *event_base,
   int     *error_base
);

Status XineramaQueryVersion(
   Display *dpy,
   int     *major,
   int     *minor
);

Bool XineramaIsActive(Display *dpy);


/*
   Returns the number of heads and a pointer to an array of
   structures describing the position and size of the individual
   heads.  Returns NULL and number = 0 if Xinerama is not active.

   Returned array should be freed with XFree().
*/

XineramaScreenInfo *
XineramaQueryScreens(
   Display *dpy,
   int     *number
);

#endif /* _Xinerama_h */

#endif /* __linux__ */
