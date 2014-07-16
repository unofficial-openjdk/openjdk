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
# @summary Exercise dynamic configuration by launching a container that in
#   turn starts a number of applications

set -e

if [ -z "$TESTJAVA" ]; then
  if [ $# -lt 1 ]; then exit 1; fi
  TESTJAVA="$1"; shift
  COMPILEJAVA="${TESTJAVA}"
  TESTSRC="`pwd`"
  TESTCLASSES="`pwd`"
fi

JAVAC="$TESTJAVA/bin/javac"
JAVA="$TESTJAVA/bin/java"
JAR="$TESTJAVA/bin/jar"
JLINK="$TESTJAVA/bin/jlink"

rm -rf mlib
mkdir -p mlib

mkdir -p mods/container
$JAVAC -d mods/container `find $TESTSRC/src/container -name "*.java"`
$JLINK --class-path mods/container --format jmod \
   --mid container@1.0 --main-class container.Main --output mlib/wls@1.0.jmod

# container ships with its own version of JAX-WS
mkdir -p mods/java.xml.ws
$JAVAC -d mods/java.xml.ws `find $TESTSRC/src/java.xml.ws -name "*.java"`
$JAR cf mlib/jaxws.jar -C mods/java.xml.ws .

rm -rf applib
mkdir -p applib

# app1 uses JAX-WS
mkdir -p mods/app1
$JAVAC -cp mlib/jaxws.jar -d mods/app1 `find $TESTSRC/src/app1 -name "*.java"`
$JAR cf applib/app1.jar -C mods/app1 .

# app2 ships with its own copy of JAX-RS
mkdir -p mods/java.ws.rs
$JAVAC -d mods/java.ws.rs `find $TESTSRC/src/java.ws.rs -name "*.java"`
$JAR cf applib/jaxrs.jar -C mods/java.ws.rs .

mkdir -p mods/app2
$JAVAC -cp applib/jaxrs.jar -d mods/app2 `find $TESTSRC/src/app2 -name "*.java"`
$JAR cf applib/app2.jar -C mods/app2 .

# launch the container
$JAVA -mp mlib -m container

