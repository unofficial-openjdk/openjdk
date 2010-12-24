#!/bin/sh

#
# Copyright (c) 2010, Oracle and/or its affiliates. All rights reserved.
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

# Script to update the Copyright YEAR range in Mercurial sources.
#  (Originally from xdono, Thanks!)

# Stop on any error
set -e

# Temp area
tmp=/tmp/`basename $0`.${USER}.$$
rm -f -r ${tmp}
mkdir -p ${tmp}
log=${tmp}/log
files=${tmp}/files
desc=${tmp}/desc
changesets=${tmp}/changesets
total=0

maxlines=20

# This year
year=`date +%Y`

updateFiles() # changeset`
{
  count=0
  hg log --rev $1 -v --template '{files}\n' \
    | awk -F' ' '{ for ( i = 1 ; i <= NF ; i++ ) print $i } ' \
    > ${files}
  if [ -f "${files}" -a -s "${files}" ] ; then
    copyright="Copyright (c)"
    company="Oracle"
    for i in `cat ${files}` ; do
      if [ -f "${i}" ] ; then
        cp ${i} ${i}.orig
        cat ${i}.orig | \
          sed -e "s@\(${copyright} [12][0-9][0-9][0-9],\) [12][0-9][0-9][0-9], ${company}@\1 ${year}, ${company}@" \
              -e "s@\(${copyright} [12][0-9][0-9][0-9],\) ${company}@\1 ${year}, ${company}@" | \
          sed -e "s@${copyright} ${year}, ${year}, ${company}@${copyright} ${year}, ${company}@"  \
	  > ${i}
        if ! diff ${i}.orig ${i} > /dev/null ; then \
          echo "File updated: ${i}" > ${log}
	  count=`expr ${count} '+' 1`
        fi
        rm -f ${i}.orig
      fi
    done
    if [ ${count} -gt 0 ] ; then
      echo "------------------------------------------------"
      hg log --rev $1 --template 'Changeset by {author}\n{desc|firstline}\n'
      fcount=`cat ${files}| wc -l`
      fc=`printf '%d\n' ${fcount}`
      echo "Updated year on ${count} files (${fc} files changed in changeset)."
      total=`expr ${total} '+' ${count}`
    fi
  fi
}

# Get all changesets this year
hg log --no-merges -v -d ">${year}-01-01" --template '{node}\n' > ${changesets}

# Check changeset to see if it is Copyright only changes, filter changesets
if [ -s ${changesets} ] ; then
  echo "Changesets made in ${year}: `cat ${changesets} | wc -l`"
  for c in `cat ${changesets}` ; do
    rm -f ${desc}
    hg log --rev ${c} --template '{desc}\n' > ${desc}
    if cat ${desc} | fgrep -i "Added tag" > /dev/null ; then
      echo "SKIPPING: `cat ${desc}`" > ${log}
      #hg log --rev ${c}
    elif cat ${desc} | fgrep -i rebrand > /dev/null ; then
      echo "SKIPPING: `cat ${desc}`"  > ${log}
      #hg log --rev ${c}
    elif cat ${desc} | fgrep -i copyright > /dev/null ; then
      echo "SKIPPING: `cat ${desc}`"  > ${log}
      #hg log --rev ${c}
    else
      #hg log --rev ${c} -p
      updateFiles ${c}
    fi
  done
fi

if [ ${total} -gt 0 ] ; then
   echo "---------------------------------------------"
   echo "Updated the copyright year on a total of ${total} files."
fi

# Cleanup
rm -f -r ${tmp}
exit 0

