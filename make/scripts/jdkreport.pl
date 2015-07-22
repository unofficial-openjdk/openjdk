#!/usr/bin/perl -w

#
# Copyright (c) 2009, 2011, Oracle and/or its affiliates. All rights reserved.
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

use warnings;
#use strict;

$| = 1; #set buffer flushing on, so that messages are printed immediately

if ($#ARGV != 2) {
 print "Usage: $0 <repo> <prev-tag> <curr-tag>\n";
 print "\n";
 print "Example: $0 repo jdk7-b40 jdk7-b41\n";
 print "Exmaple: $0 repo jdk7-b50 tip\n";
 print "\n";
 print "Generates a report for changes in repo (and its sup repos).\n";
 print "<prev-tag> is exclusive and <curr-tag> is inclusive. A report\n";
 print "from jdk7-b40 to jdk7-b41 will include all changes after the\n";
 print "jdk7-b40 tag until (and including) the change tagged as jdk7-b41.\n";
 exit 1;
}

chdir ("$ARGV[0]");
@list = `hg ftrees`;
chomp(@list);
my $start_tag = $ARGV[1];
my $end_tag = $ARGV[2];

sub print_bugs() {
 if($first) { #print repo line if it encounters first results in prev line
   @reponame = split '/',$i;
   if ($reponame[$#reponame] eq "jdk6") {
     $reponame[$#reponame] = "";
   }
   print "<br><br><b>http://hg.openjdk.java.net/jdk6/$reponame[$#reponame]</b>\n";
      print '<table border="1" width="100%">';
   print "<tr>";
   print "<td with=\"13%\"><b> Changeset </b></td> <td width = \"7%\"><b>Bug ID</b></td> <td><b>Synopsys</b></td>";
   print "</tr>";
   $first = 0;
 }

 print "<tr>";
 $rowspan = $#bugs + 1; #row span for changeset is number of bugs seen in desc

 if ($reponame[$#reponame] eq "jdk7") {
   $reponame[$#reponame] = "";
   $sla = "";
 } else {
   $sla = "/";
 }
 $chgseturl  = "http://hg.openjdk.java.net/jdk6/jdk6/" . $reponame[$#reponame]. $sla . "rev/$changeset";
 print "<td width=\"10%\" rowspan=$rowspan><a href =\"". $chgseturl.  "\"> $changeset</a> </td>";

 $firstbug = 1;
 foreach(@bugs) {
   ($bugid, $desc) = split ':',$_, 2; # limit the number of splits to 2
   $bugid =~ s/^\s+//;         #remove leading white spaces

   if ($bugid =~ /^OPENJDK6-/) { # use different urls for different bugs
     $url = "http:/java.net/jira/browse/$bugid";
   } else {
     $url = "http://bugs.sun.com/bugdatabase/view_bug.do?bug_id=$bugid";
   }

   if($firstbug) {
     $firstbug = 0;
   }
   else {
     print "<tr>"; #start new table row for 2nd bug on (1st row started above for changeset)
   }
   print "<td width =\"7%\"><a href =\"". $url. "\">$bugid</a></td> <td>$desc</td>\n";
   print "</tr>";
 }

 @bugs = (); #clear list of bugs after printing them
}

foreach $i (@list) {
 chdir("$i");
 my $log_command = "hg log -r \'::tag(\"$end_tag\") - ::tag(\"$start_tag\")\' --no-merges --template 'changeset: {node|short}\n{desc}\n' > /tmp/jdk7report.$$";
 system($log_command) and die "Could not run hg log for $i \n";

 open(FILE, "/tmp/jdk7report.$$") or die "Could not openfile\n";

 $first = 1;
 @bugs = ();

 while (<FILE>) {
   if(/^changeset: /) {  #check for new changeset
     $fileline = $_;

     if($#bugs >= 0) {
       print_bugs(); #print bugs for prev changeset
     }

     ($beg, $changeset) = split ' ',$fileline; # limit the number of splits to 2
     chomp($changeset); #remove end of line
   }

   if(/^(OPENJDK6-)?\d+:/) { #match lines that start with bugids
     push(@bugs, $_);
   }
 }

 if($#bugs >= 0) { #print the bugs for the last changeset in the file
   print_bugs();
 }

 if($first == 0) {
   print "</table>"; #close table only if first is false
 }
}

print "<br>\n";

