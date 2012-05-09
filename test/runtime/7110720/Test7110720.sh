#
#  Copyright (c) 2012, Oracle and/or its affiliates. All rights reserved.
#  DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
#
#  This code is free software; you can redistribute it and/or modify it
#  under the terms of the GNU General Public License version 2 only, as
#  published by the Free Software Foundation.
#
#  This code is distributed in the hope that it will be useful, but WITHOUT
#  ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
#  FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
#  version 2 for more details (a copy is included in the LICENSE file that
#  accompanied this code).
#
#  You should have received a copy of the GNU General Public License version
#  2 along with this work; if not, write to the Free Software Foundation,
#  Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
#
#  Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA
#  or visit www.oracle.com if you need additional information or have any
#  questions.
#


#
# @test Test7110720.sh
# @bug 7110720
# @summary improve VM configuration file loading
# @run shell Test7110720.sh
#

if [ "${TESTSRC}" = "" ]
  then TESTSRC=.
fi

if [ "${TESTJAVA}" = "" ]
then
  PARENT=`dirname \`which java\``
  TESTJAVA=`dirname ${PARENT}`
  echo "TESTJAVA not set, selecting " ${TESTJAVA}
  echo "If this is incorrect, try setting the variable manually."
fi

if [ "${TESTCLASSES}" = "" ]
then
  echo "TESTCLASSES not set.  Test cannot execute.  Failed."
  exit 1
fi

# set platform-dependent variables
OS=`uname -s`
case "$OS" in
  SunOS | Linux )
    FS="/"
    RM=/bin/rm
    CP=/bin/cp
    MV=/bin/mv
    ;;
  Windows_* )
    FS="\\"
    RM=rm
    CP=cp
    MV=mv
    ;;
  * )
    echo "Unrecognized system!"
    exit 1;
    ;;
esac


JAVA=${TESTJAVA}${FS}bin${FS}java

# Don't test debug builds, they do read the config files:
${JAVA} ${TESTVMOPTS} -version 2>&1 | grep "debug" >/dev/null
if [ "$?" = "0" ]; then
  echo Skipping test for debug build.
  exit 0
fi

ok=yes

$RM -f .hotspot_compiler .hotspotrc

${JAVA} ${TESTVMOPTS} -version 2>&1 | grep "garbage in" >/dev/null
if [ "$?" = "0" ]; then
  echo "FAILED: base case failure"
  exit 1
fi


echo "garbage in, garbage out" > .hotspot_compiler
${JAVA} ${TESTVMOPTS} -version 2>&1 | grep "garbage in" >/dev/null
if [ "$?" = "0" ]; then
  echo "FAILED: .hotspot_compiler was read"
  ok=no
fi

$MV .hotspot_compiler hs_comp.txt
${JAVA} ${TESTVMOPTS} -XX:CompileCommandFile=hs_comp.txt -version 2>&1 | grep "garbage in" >/dev/null
if [ "$?" = "1" ]; then
  echo "FAILED: explicit compiler command file not read"
  ok=no
fi

$RM -f .hotspot_compiler hs_comp.txt

echo "garbage" > .hotspotrc
${JAVA} ${TESTVMOPTS} -version 2>&1 | grep "garbage" >/dev/null
if [ "$?" = "0" ]; then
  echo "FAILED: .hotspotrc was read"
  ok=no
fi

$MV .hotspotrc hs_flags.txt
${JAVA} ${TESTVMOPTS} -XX:Flags=hs_flags.txt -version 2>&1 | grep "garbage" >/dev/null
if [ "$?" = "1" ]; then
  echo "FAILED: explicit flags file not read"
  ok=no
fi

if [ "${ok}" = "no" ]; then 
  echo "Some tests failed."
  exit 1
else 
  echo "Passed"
  exit 0
fi

