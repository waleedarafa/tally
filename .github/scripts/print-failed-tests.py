#!/usr/bin/env python3
"""Prints every failed test class's report into the job log, so a cause can be confirmed from the log
without downloading the artifact: the class's .txt summary (with stack traces), then the last lines of
the output it captured (system-out and system-err from its TEST-*.xml report). CI runs this only when
the test step failed."""
import glob
import os
import sys
import xml.etree.ElementTree as ET

TAIL = 300  # lines of captured output per failed class

def failed(txt):
    with open(txt, encoding="utf-8", errors="replace") as f:
        text = f.read()
    return text if ("<<< FAILURE!" in text or "<<< ERROR!" in text) else None

found = 0
for txt in sorted(glob.glob("**/target/*-reports/*.txt", recursive=True)):
    text = failed(txt)
    if text is None:
        continue
    found += 1
    name = os.path.basename(txt)[:-len(".txt")]
    print(f"::group::FAILED {name} ({txt})")
    print(text)
    xml = os.path.join(os.path.dirname(txt), f"TEST-{name}.xml")
    if os.path.exists(xml):
        lines = []
        for element in ET.parse(xml).getroot().iter():
            if element.tag in ("system-out", "system-err") and element.text:
                lines.extend(f"[{element.tag}] {line}" for line in element.text.splitlines())
        print(f"--- last {min(TAIL, len(lines))} of {len(lines)} lines of captured output ---")
        print("\n".join(lines[-TAIL:]))
    print("::endgroup::")
print(f"{found} failed test class(es) printed above." if found else "No failed test report found.")
sys.exit(0)
