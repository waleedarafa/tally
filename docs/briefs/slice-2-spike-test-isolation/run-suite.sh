#!/usr/bin/env bash
# Runs the integration and acceptance tests (failsafe, every *IT) N times and records, per run:
# wall time, failed tests, and how many Spring test contexts were started.
# Usage: run-suite.sh <label> <runs> [checkout], checkout defaulting to this repository (a worktree per option). Results go to results/<label>/: one log per run (gzipped),
# runs.csv (run, seconds, failures or build-failed, contexts, configurations, instances) and failures.txt (run: test).
# Extra Maven arguments come from EXTRA, for example EXTRA="-Dtally.spike.patches=off -Dit.test=..."
# Needs one full ./mvnw verify of the checkout first: it uses the fake-provider jar from fake-provider/target.
set -u
label=$1; runs=$2
here=$(cd "$(dirname "$0")" && pwd)
root=$(cd "${3:-$here/../../..}" && pwd)
out="$here/results/$label"
mkdir -p "$out"
[ -f "$out/runs.csv" ] || echo "run,seconds,failures,contexts,configurations,instances" > "$out/runs.csv"
export JAVA_HOME=/usr/lib/jvm/java-25-openjdk-amd64
start_at=$(($(wc -l < "$out/runs.csv")))
for i in $(seq "$start_at" $((start_at + runs - 1))); do
  log="$out/run-$i.log"
  # Reports of an earlier run would otherwise be read as this run's.
  rm -rf "$root/tally/target/failsafe-reports"
  t0=$(date +%s)
  (cd "$root" && ./mvnw -o -B -pl tally verify -Dtest=none -Dsurefire.failIfNoSpecifiedTests=false \
      -Dlogging.level.org.springframework.test.context.cache=DEBUG \
      -Dlogging.level.org.springframework.test.context.cache.DefaultCacheAwareContextLoaderDelegate=TRACE \
      ${EXTRA:-}) > "$log" 2>&1
  t1=$(date +%s)
  # Contexts the test framework created: the cache's last missCount. Distinct configurations: the cache
  # keys stored, without the test class and object identities, which the key's equality ignores.
  # Instances a test starts by hand (TallyInstances) log their start as ForkedBooter or TallyApplication.
  contexts=$(grep "cache statistics" "$log" | tail -1 | sed -E 's/.*missCount = ([0-9]+).*/\1/')
  grep "Storing ApplicationContext" "$log" | sed -E 's/.*under key \[?//; s/testClass = [^,]*, //; s/@[0-9a-f]+//g' \
      | sort -u > "$out/keys-$i.txt"
  configurations=$(wc -l < "$out/keys-$i.txt")
  instances=$(grep -cE "Started (ForkedBooter|TallyApplication) in" "$log")
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
  # No failsafe summary: the build broke before or during the tests. Recorded, never read as a pass.
  grep -q "Tests run: [0-9]*, Failures: [0-9]*, Errors: [0-9]*, Skipped: [0-9]*$" "$log" || failures="build-failed"
  echo "$i,$((t1 - t0)),$failures,$contexts,$configurations,$instances" >> "$out/runs.csv"
  gzip -f "$log"
done
