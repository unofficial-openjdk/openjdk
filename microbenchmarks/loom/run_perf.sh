#!/bin/bash

declare -a arr=("5" "10" "15" "20" "25" "30")

PATH=$1
OPTIONS=${@:2}

iter=20

for perf in "${arr[@]}"; do
	COUNTER=0
	while [  $COUNTER -lt 2 ]; do
		echo
		echo "======================================================"
		echo "perf=$perf OPTIONS=$OPTIONS"
		echo "------------------------------------------------------"
		echo

		$PATH/jdk/bin/java -XX:+UseParallelGC -XX:+UnlockDiagnosticVMOptions $OPTIONS -XX:ContPerfTest=$perf -jar target/benchmarks.jar 'yield$' -foe true -i $iter -p stackDepth=5 -p paramCount=3

		echo
		echo "perf=$perf"
		echo "------------------------------------------------------"
		echo

		let COUNTER=COUNTER+1 
	done
done
