#!/bin/bash

JDK=$1
OPTIONS=${@:2}

JAVA_HOME=${JDK}/jdk mvn clean package || exit 1

iter=20
forks=2
file=jmh.out

params=(-p stackDepth=5 -p paramCount=3)

run_benchmark() {
	local benchmark=$1
	local perflevels=${@:2}

	local last=0

	for perf in ${perflevels[@]}; do
		echo
		echo "======================================================"
		echo "benchmark=$benchmark OPTIONS=$OPTIONS perf=$perf "
		echo "------------------------------------------------------"
		echo

		$JDK/bin/java --add-opens java.base/java.io=ALL-UNNAMED -XX:+UnlockDiagnosticVMOptions $OPTIONS -XX:ContPerfTest=$perf -jar target/benchmarks.jar $benchmark -foe true -f $forks -i $iter -v SILENT -rf text -rff $file ${params[@]} && cat $file
		
		res=$(cat $file| tail -1 | awk '{ print $6 }')
		echo
		delta=$(echo "$res - $last" | bc)
		last=$res
		echo "Delta: $delta"
	done
	rm $file
	echo
	echo
}

run_benchmark justYield    5 10 15 20 25 30
run_benchmark justContinue 105 110 120 130
