#
# Copyright (c) 2014, 2015, Oracle and/or its affiliates. All rights reserved.
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
# @summary Basic test of -XaddExports to export JDK-internal APIs

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

rm -rf mods
mkdir -p mods/test
$JAVAC -XaddExports:java.base/sun.misc -d mods/test `find $TESTSRC/src/test -name "*.java"`

# unnamed module using sun.misc.Unsafe
$JAVA -XaddExports:java.base/sun.misc -cp mods/test jdk.test.UsesUnsafe

# named module using sun.misc.Unsafe
$JAVA -XaddExports:java.base/sun.misc -mp mods -m test/jdk.test.UsesUnsafe


# Negative tests

set +e
failures=0

go() {
  sh -xc "$JAVA $*" 2>&1
  if [ $? != 0 ]; then failures=`expr $failures + 1`; fi
}

# unknown module
go -XaddExports:java.base/sun.misc,java.monkey/sun.monkey -cp mods/test jdk.test.UsesUnsafe

# unknown package
go -XaddExports:java.base/sun.misc,java.base/sun.monkey -cp mods/test jdk.test.UsesUnsafe

# missing package
go -XaddExports:java.base/sun.misc,java.base -cp mods/test jdk.test.UsesUnsafe

echo ''
if [ $failures -ne 3 ]; then
  echo "$failures cases failed, expected 3"
  exit 1
else
  echo "Failures as expected"
  exit 0
fi 

