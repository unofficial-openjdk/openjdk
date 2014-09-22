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
# @summary Basic test of jlink to create a modular image 

set -e

if [ -z "$TESTJAVA" ]; then
  if [ $# -lt 1 ]; then exit 1; fi
  TESTJAVA="$1"; shift
  COMPILEJAVA="${TESTJAVA}"
  TESTSRC="`pwd`"
  TESTCLASSES="`pwd`"
fi

if [ ! -d "$TESTJAVA/../jmods" ]; then
   echo "Not images build: jmods not exists"
   exit 0;
fi

JAVAC="$COMPILEJAVA/bin/javac"
JAVA="$TESTJAVA/bin/java"
JLINK="$TESTJAVA/bin/jlink"

rm -rf mods
mkdir -p mods/test
$JAVAC -d mods/test `find $TESTSRC/src/test -name "*.java"`

rm -rf mlib myimage myjimage

# create jmod
mkdir mlib
$JLINK --format jmod --class-path mods/test --mid test@1.0 --main-class jdk.test.Test \
    --output mlib/test@1.0.jmod

# legacy image
$JLINK --module-path $TESTJAVA/../jmods:mlib --format image --output myimage --mods test
myimage/bin/test a b c

# jimage
$JLINK --module-path $TESTJAVA/../jmods:mlib --format jimage --output myjimage --mods test
myjimage/bin/test 1 2 3

exit 0
