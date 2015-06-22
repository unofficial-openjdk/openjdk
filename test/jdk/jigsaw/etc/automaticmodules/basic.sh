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
# @summary Basic test for automatic modules


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

rm -rf mods

# Compile logging and http server libraries as modules as javac doesn't
# support automatic modules yet

mkdir -p mods/logging
$JAVAC -d mods/logging `find $TESTSRC/src/logging -name "*.java"`

mkdir -p mods/httpserver
$JAVAC -d mods/httpserver -mp mods `find $TESTSRC/src/httpserver -name "*.java"`

mkdir -p mods/basictest
$JAVAC -d mods/basictest -mp mods `find $TESTSRC/src/basictest -name "*.java"`


# Create regular (non-modular JAR files)

$JAR cf mods/logging-1.0.jar -C mods/logging logging
rm -rf mods/logging

$JAR cf mods/http-server-9.0.0.jar -C mods/httpserver http
rm -rf mods/httpserver


# Run the test
$JAVA -mp mods -addmods logging -m basictest/test.Main

exit 0
