#!/usr/bin/env python3
# Seal: print the surefire results of every lang/java module after a `mvn test`
# run, so the CI log states how many tests passed and why each skipped test
# was skipped (surefire's console output only prints per-class counts).
import glob
import sys
import xml.etree.ElementTree as ET

import re

# Why each upstream skip happens (read from the test sources at this tag).
KNOWN_SKIP_REASONS = {
    "org.apache.avro.reflect.TestReflectLogicalTypes.testReadUUIDMissingLogicalTypeReflect":
        "upstream Assume: needs the reflection-based FieldAccess, but only one FieldAccess can be "
        "set per JVM and the shared surefire JVM already selected the Unsafe one",
    "org.apache.avro.ipc.netty.TestNettyServerWithCallbacks.performanceTest":
        "upstream @Ignore: 10-second multi-threaded throughput benchmark, not a correctness test",
    "org.apache.avro.specific.TestSpecificRecordBuilder.testBuilderPerformance":
        "upstream @Ignore: timing benchmark of the record builder, not a correctness test",
    "org.apache.avro.specific.TestSpecificRecordBuilder.testBuilderPerformanceWithDefaultValues":
        "upstream @Ignore: timing benchmark of the record builder, not a correctness test",
    "org.apache.avro.specific.TestSpecificRecordBuilder.testManualBuildPerformance":
        "upstream @Ignore: timing benchmark of manual record construction, not a correctness test",
}


def source_reason(classname, method):
    """Return the @Ignore / Assume evidence for a skipped test from its source."""
    rel = classname.split("$")[0].replace(".", "/") + ".java"
    for path in glob.glob("**/src/test/java/" + rel, recursive=True):
        lines = open(path, encoding="utf-8", errors="replace").read().splitlines()
        for i, line in enumerate(lines):
            if re.search(r"\bvoid\s+%s\s*\(" % re.escape(method), line):
                for j in range(i - 1, max(i - 6, -1), -1):
                    if "@Ignore" in lines[j] or "@Disabled" in lines[j]:
                        return "%s at %s:%d" % (lines[j].strip(), path, j + 1)
                for j in range(i, min(i + 15, len(lines))):
                    if "Assume." in lines[j]:
                        return "%s at %s:%d" % (lines[j].strip(), path, j + 1)
    return None


label = sys.argv[1] if len(sys.argv) > 1 else "surefire"
totals = {"tests": 0, "failures": 0, "errors": 0, "skipped": 0}
modules = {}
skipped = []
for path in sorted(glob.glob("**/target/surefire-reports/TEST-*.xml", recursive=True)):
    module = path.split("/target/")[0]
    try:
        root = ET.parse(path).getroot()
    except ET.ParseError as exc:
        print("could not parse %s: %s" % (path, exc))
        continue
    suites = [root] if root.tag == "testsuite" else root.findall("testsuite")
    for suite in suites:
        mod = modules.setdefault(module, {"tests": 0, "failures": 0, "errors": 0, "skipped": 0})
        for key in totals:
            value = int(suite.get(key, "0") or 0)
            totals[key] += value
            mod[key] += value
        for case in suite.iter("testcase"):
            node = case.find("skipped")
            if node is not None:
                cls, name = case.get("classname"), case.get("name")
                message = (node.get("message") or (node.text or "").strip()).splitlines()
                parts = []
                known = KNOWN_SKIP_REASONS.get("%s.%s" % (cls, name))
                if known:
                    parts.append(known)
                evidence = source_reason(cls, name)
                if evidence:
                    parts.append("source: " + evidence)
                if message and message[0]:
                    parts.append("surefire message: " + message[0][:160])
                skipped.append("%s.%s: %s" % (cls, name, "; ".join(parts) or "no reason recorded"))

print("=" * 72)
print("SEAL surefire report -- %s" % label)
for module, counts in sorted(modules.items()):
    passed = counts["tests"] - counts["failures"] - counts["errors"] - counts["skipped"]
    print("  %-40s run=%d passed=%d failures=%d errors=%d skipped=%d" % (
        module, counts["tests"], passed, counts["failures"], counts["errors"], counts["skipped"]))
if skipped:
    print("Skipped tests and reasons:")
    for line in skipped:
        print("  SKIPPED %s" % line)
passed = totals["tests"] - totals["failures"] - totals["errors"] - totals["skipped"]
print("%s: TOTAL tests run=%d, passed=%d, failures=%d, errors=%d, skipped=%d" % (
    label, totals["tests"], passed, totals["failures"], totals["errors"], totals["skipped"]))
print("=" * 72)
