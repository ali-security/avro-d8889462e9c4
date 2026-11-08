#!/bin/bash

# Licensed to the Apache Software Foundation (ASF) under one or more
# contributor license agreements.  See the NOTICE file distributed with
# this work for additional information regarding copyright ownership.
# The ASF licenses this file to You under the Apache License, Version 2.0
# (the "License"); you may not use this file except in compliance with
# the License.  You may obtain a copy of the License at
#
#     https://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.

set -e

case "$TRAVIS_OS_NAME" in
"linux")
    # Seal: run the Java part of `./build.sh test` (lang/java/build.sh test) on the VM
    # instead of the Yetus/Docker multi-language image, which no longer builds.
    # Spotless/checkstyle are lint gates, not tests.
    export JAVA_HOME="/usr/lib/jvm/java-${JAVA}-openjdk-amd64"
    export PATH="/tmp/apache-maven-3.9.4/bin:$JAVA_HOME/bin:$PATH"
    export MAVEN_OPTS="-Xmx2g"
    java -version
    mvn -version
    MVN_FLAGS="-B -ntp -Dspotless.check.skip=true -Dcheckstyle.skip=true"
    cd lang/java
    mvn $MVN_FLAGS test
    python3 ../../.travis/test-report.py "Java ${JAVA} default profile"
    # Test the modules that depend on hadoop using Hadoop 2
    mvn $MVN_FLAGS test -Phadoop2
    python3 ../../.travis/test-report.py "Java ${JAVA} hadoop2 profile"
    ;;
"windows")
    ./lang/csharp/build.sh test
    ;;
*)
    echo "Invalid PLATFORM"
    exit 1
    ;;
esac
