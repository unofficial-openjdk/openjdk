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
# @summary Basic test of jlink to create jmods and images

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
JAR="$COMPILEJAVA/bin/jar"
JAVA="$TESTJAVA/bin/java ${TESTVMOPTS}"
JLINK="$TESTJAVA/bin/jlink ${TESTTOOLVMOPTS}"
JMOD="$TESTJAVA/bin/jmod ${TESTTOOLVMOPTS}"

rm -rf mods
mkdir -p mods/test
$JAVAC -d mods/test `find $TESTSRC/src/test -name "*.java"`

rm -rf mlib mlib2 myimage myjimage

# create jmod from exploded classes on the class path
mkdir mlib
$JMOD create --class-path mods/test --module-version 1.0 --main-class jdk.test.Test \
    mlib/test@1.0.jmod

# create jmod from JAR file on the class path
mkdir mlib2
$JAR cf mlib2/test.jar -C mods/test .
$JMOD create --class-path mlib2/test.jar --module-version 1.0 --main-class jdk.test.Test \
    mlib2/test@1.0.jmod

# uncompressed image
$JLINK --modulepath $TESTJAVA/../jmods${PS}mlib --addmods test --output myjimage
myjimage/bin/test 1 2 3

# compressed image
$JLINK --modulepath $TESTJAVA/../jmods${PS}mlib --addmods test \
  --output mysmalljimage --compress-resources on
mysmalljimage/bin/test 1 2 3

exit 0
