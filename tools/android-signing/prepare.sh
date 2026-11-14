#!/usr/bin/env bash
set -euo pipefail
script_dir=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
work_dir=${POLARIS_SIGNING_WORKDIR:-${RUNNER_TEMP:-/tmp}/polaris-signing-tools}
mkdir -p "$work_dir/lib" "$work_dir/classes"
while read -r name expected; do
  target="$work_dir/lib/$name-1.87.jar"
  if [[ ! -f "$target" ]]; then
    curl --fail --silent --show-error --location --retry 2 \
      "https://repo.maven.apache.org/maven2/org/bouncycastle/$name/1.87/$name-1.87.jar" -o "$target"
  fi
  printf '%s  %s\n' "$expected" "$target" | sha256sum --check --status
done <<'CHECKSUMS'
bcprov-jdk18on 12d2d3dd82a9ec15e92ec7c7943ee64f6097c6dc12c07e984f1693b77565b9aa
bcpkix-jdk18on a44be2660d72ed88673372b8cc2d53a25a5aaf27122fb803ed93f07147536aa1
bcutil-jdk18on af46dd381553fdc466cd0c18f8bf2144c7dbcb7337eee3e16cab15e96613ddf0
CHECKSUMS
javac --release 17 -cp "$work_dir/lib/*" -d "$work_dir/classes" "$script_dir/PersonalApkSigner.java"
java -cp "$work_dir/classes:$work_dir/lib/*" PersonalApkSigner self-test
