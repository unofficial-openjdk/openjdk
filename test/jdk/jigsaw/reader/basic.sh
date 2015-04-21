#
# Copyright (c) 2015, Oracle and/or its affiliates. All rights reserved.
# DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
#
# This code is free software; you can redistribute it and/or modify it
# under the terms of the GNU General Public License version 2 only, as
# published by the Free Software Foundation.
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

# @test
# @summary Basic test for ModuleReader with the built-in artifact types

set -e

if [ -z "$TESTJAVA" ]; then
  if [ $# -lt 1 ]; then exit 1; fi
  TESTJAVA="$1"; shift
  COMPILEJAVA="${TESTJAVA}"
  TESTSRC="`pwd`"
  TESTCLASSES="`pwd`"
fi

JAVAC="$COMPILEJAVA/bin/javac"
JAVA="$TESTJAVA/bin/java ${TESTVMOPTS}"
JAR="$TESTJAVA/bin/jar ${TESTTOOLVMOPTS}"
JMOD="$TESTJAVA/bin/jmod ${TESTTOOLVMOPTS}"

# Compile test
$JAVAC -d . $TESTSRC/Basic.java

# Compile module used by the test
rm -rf mods
mkdir -p mods/m
$JAVAC -d mods/m `find $TESTSRC/src/m -name "*.java"`

# Test exploded module
$JAVA Basic m mods p/Foo.class mods/m/p/Foo.class

# Test modular JAR
rm -rf mlib
mkdir -p mlib
$JAR cf mlib/m.jar -C mods/m .
$JAVA Basic m mlib p/Foo.class mods/m/p/Foo.class

# Test jmod
rm -rf mlib
mkdir -p mlib
$JMOD create --class-path mods/m mlib/m.jmod
$JAVA Basic m mlib p/Foo.class mods/m/p/Foo.class

exit 0
