#!/bin/bash
set -e

# Generate automatic release description using Openrouter
if [ -n "$OPENROUTER_API_KEY" ]; then
  echo "Generating automatic release description using Openrouter..."

  # Fetch PR number for the current branch/ref using GITHUB_REF_NAME to prevent template injection
  PR_NUMBER=$(gh pr list --head "$GITHUB_REF_NAME" --state all --json number -q '.[0].number' || echo "")

  if [ -n "$PR_NUMBER" ]; then
    # Fetch PR title, body, and comments
    PR_DATA=$(gh pr view "$PR_NUMBER" --json title,body,comments -q '"Title: " + .title + "\n\nBody: " + .body + "\n\nComments:\n" + (.comments | map(.body) | join("\n"))' || echo "")
  else
    PR_DATA="No open or closed PR found for branch $GITHUB_REF_NAME."
  fi

  # Safely build the prompt payload using jq
  PAYLOAD=$(jq -n \
    --arg model "nvidia/nemotron-3-ultra-550b-a55b:free" \
    --arg prompt "You are an AI assistant helping write release notes for TunnelGuard. Write a concise, professional release description using the following PR comments and context:\n\n$PR_DATA\n\nEnsure it is well-structured with clear sections like 'What's New'." \
    '{model: $model, messages: [{role: "user", content: $prompt}]}')

  # Call Openrouter API with bounded connection and request timeout
  RESPONSE=$(curl -s --connect-timeout 10 --max-time 30 -X POST "https://openrouter.ai/api/v1/chat/completions" \
    -H "Content-Type: application/json" \
    -H "Authorization: Bearer $OPENROUTER_API_KEY" \
    -d "$PAYLOAD" || echo "")

  # Extract generated content
  GENERATED_DESC=$(echo "$RESPONSE" | jq -r '.choices[0].message.content' 2>/dev/null || echo "")

  if [ -n "$GENERATED_DESC" ] && [ "$GENERATED_DESC" != "null" ]; then
    echo "Successfully generated release description."
    echo "$GENERATED_DESC" > generated_description.txt
  else
    echo "WARNING: Failed to generate description or empty response. Falling back to manual description."
    echo "$RELEASE_DESCRIPTION" > generated_description.txt
  fi
else
  echo "OPENROUTER_API_KEY is not defined. Using manual release description."
  echo "$RELEASE_DESCRIPTION" > generated_description.txt
fi
