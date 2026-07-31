#!/usr/bin/env bash
set -euo pipefail

usage() {
  echo "Usage: $0 v1.0.0"
}

version="${1:-}"
if [[ -z "$version" ]]; then
  usage
  exit 1
fi

if [[ ! "$version" =~ ^v[0-9]+(\.[0-9]+){1,3}(-[A-Za-z0-9._-]+)?$ ]]; then
  echo "Version must look like v1.0.0"
  exit 1
fi

repo_root="$(git rev-parse --show-toplevel)"
cd "$repo_root"

if [[ -n "$(git status --porcelain)" ]]; then
  echo "Worktree is not clean. Commit or stash changes before releasing."
  exit 1
fi

branch="$(git branch --show-current)"
if [[ -z "$branch" ]]; then
  echo "Detached HEAD is not supported for release."
  exit 1
fi

if git rev-parse -q --verify "refs/tags/$version" >/dev/null; then
  echo "Tag already exists: $version"
  exit 1
fi

remote_url="$(git remote get-url origin)"
owner=""
repo=""

if [[ "$remote_url" =~ ^https://github.com/([^/]+)/([^/]+)(\.git)?$ ]]; then
  owner="${BASH_REMATCH[1]}"
  repo="${BASH_REMATCH[2]}"
elif [[ "$remote_url" =~ ^git@github.com:([^/]+)/([^/]+)(\.git)?$ ]]; then
  owner="${BASH_REMATCH[1]}"
  repo="${BASH_REMATCH[2]}"
else
  echo "Unsupported origin URL for GitHub/JitPack automation: $remote_url"
  exit 1
fi

repo="${repo%.git}"
group_id="com.github.${owner}.${repo}"
group_path="com/github/${owner}/${repo}"

echo "Building release artifacts for $group_id:$version"
./gradlew \
  :ad-sdk:assembleRelease \
  :ad-sdk-modern:assembleRelease \
  :ad-sdk:publishReleasePublicationToMavenLocal \
  :ad-sdk-modern:publishReleasePublicationToMavenLocal \
  -PPUBLISH_GROUP_ID="$group_id" \
  -PPUBLISH_VERSION="$version" \
  --console=plain

echo "Pushing branch $branch to origin"
git push origin "$branch"

echo "Creating and pushing tag $version"
git tag -a "$version" -m "Release $version"
git push origin "$version"

jitpack_url="https://jitpack.io/${group_path}/${version}/build.log"
log_file="/tmp/jitpack-${repo}-${version}.log"

echo "Triggering JitPack build: $jitpack_url"
curl --fail --location --retry 2 --connect-timeout 20 "$jitpack_url" | tee "$log_file"

echo
echo "Release dependencies:"
echo "  implementation '${group_id}:ad-sdk:${version}'"
echo "  implementation '${group_id}:ad-sdk-modern:${version}'"
echo
echo "JitPack log saved to: $log_file"
