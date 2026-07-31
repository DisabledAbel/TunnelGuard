#!/bin/bash
set -e

# Generate automatic release description using GitHub API
echo "Generating automatic release description using GitHub Releases API..."

# Default tag name to a fallback if CLEAN_VERSION_NAME is somehow missing
TAG_NAME="v${CLEAN_VERSION_NAME:-1.0.0}"
TARGET_COMMITISH="${GITHUB_REF_NAME:-main}"

# Call GitHub API to generate release notes
API_RESPONSE=$(gh api repos/{owner}/{repo}/releases/generate-notes \
  -f tag_name="$TAG_NAME" \
  -f target_commitish="$TARGET_COMMITISH" \
  --jq .body 2>/dev/null || echo "")

if [ -n "$API_RESPONSE" ]; then
  echo "Successfully generated release description from GitHub API."
  if [ -n "$RELEASE_DESCRIPTION" ]; then
    printf "%s\n\n%s\n" "$RELEASE_DESCRIPTION" "$API_RESPONSE" > generated_description.txt
  else
    printf "%s\n" "$API_RESPONSE" > generated_description.txt
  fi
else
  echo "WARNING: Failed to generate description or empty response. Falling back to manual description."
  printf "%s\n" "$RELEASE_DESCRIPTION" > generated_description.txt
fi
