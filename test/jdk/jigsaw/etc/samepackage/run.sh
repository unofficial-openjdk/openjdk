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
# @summary Basic test to ensure that startup fails if two or more modules
#   in the boot Layer have the same package

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
JLINK="$TESTJAVA/bin/jlink ${TESTTOOLVMOPTS}"

rm -rf mods

compile() {
    m=$1
    mkdir -p mods/$m
    $JAVAC -d mods/$m `find $TESTSRC/src/$m -name "*.java"`
}

compile test
compile misc
compile m1
compile m2

failures=0

try() {
    echo ''
    sh -xc "$JAVA $*" 2>&1
    if [ $? != 0 ]; then failures=`expr $failures + 1`; fi
}

set +e

# no overlapping packages
try -mp mods -m test/test.Main

# module on the application module path with package sun.misc
try -mp mods -addmods misc -m test/test.Main

# two modules on the application module path with package p
try -mp mods -addmods m1,m2 -m test/test.Main

if [ $failures -ne 2 ];
  then echo "$failures test(s) failed"; exit $failiures;
  else echo "Test passed"; exit 0;
fi
exit $failures

