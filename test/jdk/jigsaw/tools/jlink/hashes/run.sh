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
# @summary Test the recording and checking of dependency hashes

set -e

if [ -z "$TESTJAVA" ]; then
  if [ $# -lt 1 ]; then exit 1; fi
  TESTJAVA="$1"; shift
  COMPILEJAVA="${TESTJAVA}"
  TESTSRC="`pwd`"
  TESTCLASSES="`pwd`"
fi

if [ ! -d "$TESTJAVA/../jmods" ]; then
   echo "Not images build: jmods not found"
   exit 0;
fi

OS=`uname -s`
case "$OS" in
  Windows*)
    PS=";"
    ;;
  CYGWIN* )
    PS=";"
    ;;
  * )
    PS=":"
    ;;
esac

JAVAC="$COMPILEJAVA/bin/javac"
JAVA="$TESTJAVA/bin/java ${TESTVMOPTS}"
JLINK="$TESTJAVA/bin/jlink ${TESTTOOLVMOPTS}"
JMOD="$TESTJAVA/bin/jmod ${TESTTOOLVMOPTS}"

rm -rf mods mlib

# compile and create jmods for m1 and m2
mkdir -p mods/m2
$JAVAC -d mods/m2 `find $TESTSRC/src/m2 -name "*.java"`
mkdir -p mlib
$JMOD create --class-path mods/m2 mlib/m2.jmod

mkdir -p mods/m1
$JAVAC -d mods/m1 -mp mods `find $TESTSRC/src/m1 -name "*.java"`
mkdir -p mlib
$JMOD create --class-path mods/m1 --modulepath mlib --hash-dependences m\.* \
    mlib/m1.jmod

# check that m1 runs
$JAVA -mp mlib -m m1/org.m1.Main

# compile and create jmod for a new version of m2
rm -rf mods/m2/* mlib/m2.jmod
$JAVAC -d mods/m2 `find $TESTSRC/newsrc/m2 -name "*.java"`
$JMOD create --class-path mods/m2 mlib/m2.jmod

# java -m m1 should fail
set +e
$JAVA -mp mlib -m m1/org.m1.Main
if [ $? = 0 ]; then exit 1; fi

# jlink jimage creation should fail
rm -rf myimage
$JLINK --modulepath $TESTJAVA/../jmods${PS}mlib --addmods m1 \
  --output myimage
if [ $? = 0 ]; then exit 1; fi

exit 0
