#!/bin/bash

perflevels=(5 10 15 20 25 30)

benchmarks='yield$'
params=(-p stackDepth=5 -p paramCount=3)

iter=20
forks=2
file=jmh.out

JDK=$1
OPTIONS=${@:2}

last=0

for perf in "${perflevels[@]}"; do
	echo
	echo "======================================================"
	echo "perf=$perf OPTIONS=$OPTIONS"
	echo "------------------------------------------------------"
	echo

	$JDK/jdk/bin/java --add-opens java.base/java.io=ALL-UNNAMED -XX:+UseParallelGC -XX:+UnlockDiagnosticVMOptions $OPTIONS -XX:ContPerfTest=$perf -jar target/benchmarks.jar $benchmarks -foe true -f $forks -i $iter -v SILENT -rf text -rff $file ${params[@]} && cat $file
	res=$(cat $file| tail -1 | awk '{ print $6 }')
	echo
	delta=$(echo "$res - $last" | bc)
	last=$res
	echo "Delta: $delta"
done
