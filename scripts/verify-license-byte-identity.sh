#!/usr/bin/env bash
# verify-license-byte-identity.sh
# The license byte-identity gate for the 3-tier license swap.
#
# Usage:
#   scripts/verify-license-byte-identity.sh [repoLicense] [siteLicenseTxt]
#
# Defaults (standard workspace layout):
#   repoLicense    = <this repo>/LICENSE
#   siteLicenseTxt = <workspace>/ttt-site/public/licenses/LICENSE.PolyForm-Perimeter-1.0.1.txt
#
# Exits 0 when the two files are byte-identical, 1 when they diverge or a
# path is missing. The gate is the enforcement of the standing invariant:
# the site-hosted license text must be a byte twin of the repo LICENSE,
# or the site describes a license the repo no longer ships.
set -u

scriptDir="$(cd "$(dirname "$0")" && pwd)"
repoRoot="$(cd "$scriptDir/.." && pwd)"
repoLicense="${1:-$repoRoot/LICENSE}"
siteLicenseTxt="${2:-$repoRoot/../../ttt-site/public/licenses/LICENSE.PolyForm-Perimeter-1.0.1.txt}"

if [ ! -f "$repoLicense" ]; then
  echo "GATE FAIL: repo license not found: $repoLicense"
  exit 1
fi
if [ ! -f "$siteLicenseTxt" ]; then
  echo "GATE FAIL: site license not found: $siteLicenseTxt"
  exit 1
fi

repoSha="$(sha256sum "$repoLicense" | cut -d' ' -f1)"
siteSha="$(sha256sum "$siteLicenseTxt" | cut -d' ' -f1)"
repoSize="$(wc -c < "$repoLicense" | tr -d ' ')"
siteSize="$(wc -c < "$siteLicenseTxt" | tr -d ' ')"

echo "Gate: license byte-identity"
echo "  repo: $repoLicense ($repoSize bytes, sha256 $repoSha)"
echo "  site: $siteLicenseTxt ($siteSize bytes, sha256 $siteSha)"

if cmp -s "$repoLicense" "$siteLicenseTxt"; then
  echo "RESULT: BYTE-IDENTICAL (gate passes)"
  exit 0
fi

echo "RESULT: DIVERGED (gate fails)"
exit 1
