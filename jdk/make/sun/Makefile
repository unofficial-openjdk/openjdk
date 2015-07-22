#
# Copyright (c) 1995, 2007, Oracle and/or its affiliates. All rights reserved.
# DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
#
# This code is free software; you can redistribute it and/or modify it
# under the terms of the GNU General Public License version 2 only, as
# published by the Free Software Foundation.  Oracle designates this
# particular file as subject to the "Classpath" exception as provided
# by Oracle in the LICENSE file that accompanied this code.
#
# This code is distributed in the hope that it will be useful, but WITHOUT
# ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
# FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
# version 2 for more details (a copy is included in the LICENSE file that
# accompanied this code).
#
# You should have received a copy of the GNU General Public License version
# 2 along with this work; if not, write to the Free Software Foundation,
# Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
#
# Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA
# or visit www.oracle.com if you need additional information or have any
# questions.
#

#
# Makefile for building all of sun tools
#

BUILDDIR = ..
PRODUCT  = sun
include $(BUILDDIR)/common/Defs.gmk

# Rhino/Mozilla java sources
ORG_EXISTS := $(call DirExists,$(CLOSED_SRC)/share/classes/sun/org,,)
ifneq ("$(ORG_EXISTS)", "")
  ORG_SUBDIR = org
endif

# Non windows subdirs
ifneq ($(PLATFORM), windows)
  ifndef OPENJDK
    ifeq ($(PLATFORM), solaris)
      ifneq ($(ARCH), amd64)
        DGA_SUBDIR = jdga
      endif
    endif
  endif
  ifeq ($(MOTIF_REQUIRED), true)
    MOTIF_SUBDIRS   = motif12 motif21
  endif
  HEADLESS_SUBDIR = headless
  XAWT_SUBDIR     = xawt
endif

ifndef OPENJDK
  JDBC_SUBDIR = jdbc
endif
ifdef OPENJDK
  RENDER_SUBDIR = pisces
else
  RENDER_SUBDIR = dcpr
endif
SUBDIRS = jar security javazic misc net audio $(RENDER_SUBDIR) image \
	  awt splashscreen $(XAWT_SUBDIR) $(MOTIF_SUBDIRS) \
          $(HEADLESS_SUBDIR) $(DGA_SUBDIR) \
	  font jpeg cmm applet rmi $(JDBC_SUBDIR) \
	  jawt text nio launcher management $(ORG_SUBDIR) \
          native2ascii serialver tools jconsole

all build clean clobber::
	$(SUBDIRS-loop)

