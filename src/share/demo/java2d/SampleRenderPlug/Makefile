#
# Copyright 2007 Sun Microsystems, Inc.  All Rights Reserved.
# DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
#
# This code is free software; you can redistribute it and/or modify it
# under the terms of the GNU General Public License version 2 only, as
# published by the Free Software Foundation.  Sun designates this
# particular file as subject to the "Classpath" exception as provided
# by Sun in the LICENSE file that accompanied this code.
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
# Please contact Sun Microsystems, Inc., 4150 Network Circle, Santa Clara,
# CA 95054 USA or visit www.sun.com if you need additional information or
# have any questions.
#

TOP_DIR=../../../../..

include $(TOP_DIR)/make/common/shared/Platform.gmk

SRC_DIR=./src
CLASS_DIR=./classes
BUILD_DIR=$(TOP_DIR)/build/$(PLATFORM)-$(ARCH)

DEMO_SRC_DIR=$(SRC_DIR)/sun/java2d/demo
DEMO_CLASS_DIR=$(CLASS_DIR)/sun/java2d/demo
SERVICE_DIR=$(CLASS_DIR)/META-INF/services

JAVAC=$(BUILD_DIR)/bin/javac
JAR=$(BUILD_DIR)/bin/jar

JAR_FILE=RegionRE.jar

CLASSES = \
	$(DEMO_CLASS_DIR)/RegionRenderingEngine.class \
	$(DEMO_CLASS_DIR)/RegionTileGenerator.class \

SERVICE = $(SERVICE_DIR)/sun.java2d.pipe.RenderingEngine

$(JAR_FILE): $(CLASSES) $(SERVICE)
	rm -rf $@
	$(JAR) cvf $@ -C $(CLASS_DIR) .

$(CLASS_DIR):
	mkdir -p $(CLASS_DIR)

$(DEMO_CLASS_DIR)/%.class: $(DEMO_SRC_DIR)/%.java $(CLASS_DIR)
	$(JAVAC) -cp $(SRC_DIR) -d $(CLASS_DIR) $<

$(SERVICE):
	mkdir -p $(SERVICE_DIR)
	rm -rf $@
	echo '# Example Region-based Rendering Engine module' > $@
	echo 'sun.java2d.demo.RegionRenderingEngine' >> $@

clean:
	rm -rf $(CLASS_DIR)
	rm -rf $(JAR_FILE)
