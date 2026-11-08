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
    # Seal: the upstream Yetus/Docker image (share/docker/Dockerfile) no longer builds
    # (stretch/bullseye apt archives 404), so the Java suite runs directly on the VM.
    sudo apt-get -q update
    # the minimized Travis image lacks man1, which breaks the JDK postinst alternatives
    sudo mkdir -p /usr/share/man/man1
    sudo apt-get -q install --no-install-recommends -y curl git locales python3 "openjdk-${JAVA}-jdk-headless"
    sudo apt-get -q clean
    sudo rm -rf /var/lib/apt/lists/*

    curl -sSL https://archive.apache.org/dist/maven/maven-3/3.9.4/binaries/apache-maven-3.9.4-bin.tar.gz | tar xz -C /tmp/
    ;;
"windows")
    choco install dotnetcore-sdk --version 3.1.101
    # Seal: register nuget.org explicitly; the SDK's first-run user NuGet.Config
    # is sometimes not created before restore, leaving only the VS offline feed.
    nuget_dir="$(cygpath -u "$APPDATA")/NuGet"
    mkdir -p "$nuget_dir"
    cat > "$nuget_dir/NuGet.Config" <<'NUGET'
<?xml version="1.0" encoding="utf-8"?>
<configuration>
  <packageSources>
    <add key="nuget.org" value="https://api.nuget.org/v3/index.json" protocolVersion="3" />
  </packageSources>
</configuration>
NUGET
    ;;
*)
    echo "Invalid PLATFORM"
    exit 1
    ;;
esac
