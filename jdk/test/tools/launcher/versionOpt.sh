#!/bin/sh

#
# Copyright (c) 2007, Oracle and/or its affiliates. All rights reserved.
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
# @bug 6545058
# @summary test -version and -fullversion and compare with -J option 
# @run shell versionOpt.sh

if [ "${TESTJAVA}" = "" ]; then
  echo "TESTJAVA not set.  Test cannot execute.  Failed."
  exit 1
fi
echo "TESTJAVA=${TESTJAVA}"

# set platform-dependent variables
OS=`uname -s`
case "$OS" in
  SunOS | Linux )
    NULL=/dev/null
    PS=":"
    FS="/"
    ;;
  Windows*|CYGWIN* )
    NULL=NUL
    PS=";"
    FS="\\"
    ;;
  * )
    echo "Unrecognized system!"
    exit 1;
    ;;
esac

# create reference files based on java values
${TESTJAVA}${FS}bin${FS}java -version 2>&1 | \
  grep -v HotSpot > version.ref.out

${TESTJAVA}${FS}bin${FS}java  -fullversion > fullversion.ref.out 2>&1


# A list of all the known (common to Unix and Windows) programs in the bin 
# directory, which accept -J argument and outputs the version strings to stderr.
PLIST="\
appletviewer \
apt \
extcheck \
idlj \
jar \
jarsigner \
javac \
javadoc \
javah \
javap \
jconsole \
jdb \
jhat \
jinfo \
jmap \
jps \
jstack \
jstat \
jstatd \
keytool \
native2ascii \
orbd \
pack200 \
policytool \
rmic \
rmid \
rmiregistry \
schemagen \
serialver \
servertool \
tnameserv \
wsgen \
wsimport \
xjc"


for prog in $PLIST; do
  versionOut="$prog.version.out"
  fversionOut="$prog.full.version.out"

  ${TESTJAVA}${FS}bin${FS}${prog}  -J-version 2>&1 | \
    grep -v HotSpot > $versionOut

  cat $versionOut
  diff -c version.ref.out $versionOut
  version_result=$?

  ${TESTJAVA}${FS}bin${FS}${prog}  -J-fullversion > $fversionOut 2>&1

  cat $fversionOut
  diff -c fullversion.ref.out $fversionOut
  fullversion_result=$?

  if [ $version_result -eq 0 -a $fullversion_result -eq 0 ]; then
    printf "%s:Pass\n" $prog
  else
    printf "%s:Fail\n" $prog
    exit 1
  fi
done
exit 0
