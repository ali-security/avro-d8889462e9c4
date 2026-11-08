#!/bin/bash

# Seal: keep the job open until Travis has archived this job's own log up to
# the test summary, so the log harvested right after the build finishes is
# complete. after_script cannot change the build result.

# Travis appends this line to the log once the script phase has ended.
DONE_RE='travis/script\.sh +; fi" (failed and )?exited with'

URL="https://api.travis-ci.com/v3/job/${TRAVIS_JOB_ID}/log.txt"
waited=0
cap=1200
while [ "$waited" -lt "$cap" ]; do
  if curl -fsSL "$URL" 2>/dev/null | grep -Eq "$DONE_RE"; then
    echo "log archived after ${waited}s"
    exit 0
  fi
  echo "waiting for log archive... (${waited}s)"
  sleep 20
  waited=$((waited + 20))
done
echo "log archive wait capped at ${cap}s"
exit 0
