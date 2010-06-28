#
# Copyright (c) 1998, 2006, Oracle and/or its affiliates. All rights reserved.
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

BUILDDIR  = ../..
PACKAGE   = javax.swing
SUN_PACKAGE = com.sun.java.swing.plaf
PRODUCT   = com
SWING_SRC = $(SHARE_SRC)/classes/javax/swing
SUN_SRC = $(SHARE_SRC)/classes/com/sun/java/swing

NIMBUS_GENSRC_DIR = $(GENSRCDIR)/com/sun/java/swing/plaf/nimbus
NIMBUS_SKIN_FILE = $(SUN_SRC)/plaf/nimbus/skin.laf
NIMBUS_GENERATOR_JAR = $(BUILDTOOLJARDIR)/generatenimbus.jar

include $(BUILDDIR)/common/Defs.gmk
LANGUAGE_VERSION = -source 6
CLASS_VERSION = -target 6

#
# Files
#
include FILES.gmk
AUTO_FILES_JAVA_DIRS = javax/swing sun/swing
AUTO_JAVA_PRUNE = plaf

SUBDIRS = html32dtd plaf

#
# Include
#
ifndef DISABLE_NIMBUS
    CLASSES_INIT = $(NIMBUS_GENSRC_DIR)
endif

include $(BUILDDIR)/common/Classes.gmk

build: other_files
	$(SUBDIRS-loop)

clean clobber::
	$(RM) -r $(NIMBUS_GENSRC_DIR)
	$(SUBDIRS-loop)

other_files: $(MISC_FILES)

$(CLASSBINDIR)/%.wav: $(SHARE_SRC)/classes/%.wav
	$(install-file)

$(CLASSBINDIR)/%.gif: $(SHARE_SRC)/classes/%.gif
	$(install-file)

$(CLASSBINDIR)/%.css: $(SHARE_SRC)/classes/%.css
	$(install-file)

$(CLASSBINDIR)/%.bdtd: $(SHARE_SRC)/classes/%.bdtd
	$(install-file)

$(CLASSBINDIR)/%.txt: $(SHARE_SRC)/classes/%.txt
	$(install-file)

$(NIMBUS_GENSRC_DIR): $(NIMBUS_SKIN_FILE) $(NIMBUS_GENERATOR_JAR)
	@$(ECHO) "Generating Nimbus source files:"
	$(RM) -r $(NIMBUS_GENSRC_DIR)
	$(BOOT_JAVA_CMD) -jar $(NIMBUS_GENERATOR_JAR) \
	    -skinFile $(NIMBUS_SKIN_FILE) -buildDir $(GENSRCDIR) \
	    -packagePrefix $(SUN_PACKAGE).nimbus -lafName Nimbus
	@$(ECHO) "Finished generating Nimbus source files"
