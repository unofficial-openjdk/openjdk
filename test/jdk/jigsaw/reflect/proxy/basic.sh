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
# @summary Basic test of dynamic proxies with modules

set -e

if [ -z "$TESTJAVA" ]; then
  if [ $# -lt 1 ]; then exit 1; fi
  TESTJAVA="$1"; shift
  COMPILEJAVA="${TESTJAVA}"
  TESTSRC=`pwd`
  TESTCLASSES=`pwd`
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
JAVA="$TESTJAVA/bin/java"

mkdir -p mods/m1
$JAVAC -d mods/m1 `find $TESTSRC/src/m1 -name "*.java"`

mkdir -p mods/m2
$JAVAC -d mods/m2 `find $TESTSRC/src/m2 -name "*.java"`

mkdir -p mods/m3
$JAVAC -d mods/m3 `find $TESTSRC/src/m3 -name "*.java"`

mkdir -p classes
$JAVAC -d classes `find $TESTSRC/src/q -name "*.java"`

$JAVAC -d classes -cp "mods/m1${PS}mods/m2${PS}mods/m3${PS}classes" \
          $TESTSRC/src/ProxyAccess.java

mkdir -p mods/test

## FIXME: javac -modulepath support
$JAVAC -d mods/test -cp "mods/m1${PS}mods/m2${PS}mods/m3${PS}classes" \
       `find $TESTSRC/src/test -name "*.java"`

$JAVA -cp classes -mp mods -m test/jdk.test.Main
$JAVA -cp classes -mp mods -addmods m1,m2,m3 ProxyAccess

exit 0
