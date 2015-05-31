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
# @summary Test that a service provider packaged as a JAR file on the module
#          path is treated as an automatic module


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

rm -rf classes mods
mkdir classes mods

# Create JAR file with a service provider and services configuration file
$JAVAC -d classes `find $TESTSRC/src/bananascript -name "*.java"`
$JAR cf mods/bananascript-0.9.jar -C $TESTSRC/src/bananascript META-INF -C classes .

# Create test module
mkdir -p mods/sptest
$JAVAC -d mods/sptest `find $TESTSRC/src/sptest -name "*.java"`

# Run the test
$JAVA -mp mods -m sptest/test.Main

exit 0
