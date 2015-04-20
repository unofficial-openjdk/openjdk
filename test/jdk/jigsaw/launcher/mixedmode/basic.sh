#
# Copyright (c) 2014, Oracle and/or its affiliates. All rights reserved.
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
# @summary Test application on class path making use of library on module path

set -e

if [ -z "$TESTJAVA" ]; then
  if [ $# -lt 1 ]; then exit 1; fi
  TESTJAVA="$1"; shift
  COMPILEJAVA="${TESTJAVA}"
  TESTSRC="`pwd`"
  TESTCLASSES="`pwd`"
fi

JAVAC="$COMPILEJAVA/bin/javac ${TESTTOOLVMOPTS}"
JAVA="$TESTJAVA/bin/java ${TESTVMOPTS}"
JLINK="$TESTJAVA/bin/jlink ${TESTTOOLVMOPTS}"

rm -rf mods
mkdir -p mods/lib
$JAVAC -d mods/lib `find $TESTSRC/src/lib -name "*.java"`

rm -rf classes
mkdir -p classes
$JAVAC -d classes -mp mods `find $TESTSRC/src/app -name "*.java"`

# Run with both a module and class path
$JAVA -mp mods -cp classes app.Main

exit 0
