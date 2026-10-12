#!/usr/bin/env bash
# Runs the integration and acceptance tests (failsafe, every *IT) N times and records, per run:
# wall time, failed tests, and how many Spring test contexts were started.
# Usage: run-suite.sh <label> <runs> [checkout], checkout defaulting to this repository (a worktree per option). Results go to results/<label>/: one log per run (gzipped),
# runs.csv (run, seconds, failures, contexts, instances) and failures.txt (run: test).
# Needs one full ./mvnw verify of the checkout first: it uses the fake-provider jar from fake-provider/target.
set -u
label=$1; runs=$2
here=$(cd "$(dirname "$0")" && pwd)
root=$(cd "${3:-$here/../../..}" && pwd)
out="$here/results/$label"
mkdir -p "$out"
[ -f "$out/runs.csv" ] || echo "run,seconds,failures,contexts,instances" > "$out/runs.csv"
export JAVA_HOME=${JAVA_HOME:-/usr/lib/jvm/java-25-openjdk-amd64}
start_at=$(($(wc -l < "$out/runs.csv")))
for i in $(seq "$start_at" $((start_at + runs - 1))); do
  log="$out/run-$i.log"
  t0=$(date +%s)
  (cd "$root" && ./mvnw -o -B -pl tally verify -Dtest=none -Dsurefire.failIfNoSpecifiedTests=false \
      -Dlogging.level.org.springframework.test.context.cache=DEBUG) > "$log" 2>&1
  t1=$(date +%s)
  # Contexts the test framework created (cache misses), and Tally instances started by hand in the JVM.
  contexts=$(grep -c "Storing ApplicationContext" "$log")
  instances=$(grep -c "Started TallyApplication in" "$log")
  python3 - "$root/tally/target/failsafe-reports" "$i" >> "$out/failures.txt" <<'PY'
import glob, sys, xml.etree.ElementTree as ET
for f in sorted(glob.glob(sys.argv[1] + "/TEST-*.xml")):
    for case in ET.parse(f).getroot().iter("testcase"):
        for kind in ("failure", "error"):
            node = case.find(kind)
            if node is not None:
                msg = (node.get("message") or "").splitlines()[0][:160] if node.get("message") else ""
                print(f"{sys.argv[2]}: {case.get('classname')}.{case.get('name')} [{kind}] {msg}")
PY
  failures=$(grep -c "^$i: " "$out/failures.txt")
  echo "$i,$((t1 - t0)),$failures,$contexts,$instances" >> "$out/runs.csv"
  gzip -f "$log"
done
