#!/usr/bin/env python3
import os
import sys
import json
import urllib.request
import urllib.error

def main():
    print("Starting PR Review with OpenRouter in CodeRabbit Style...")

    # Load environment variables
    api_key = os.environ.get("OPENROUTER_API_KEY")
    diff_file_path = os.environ.get("PR_DIFF_PATH", "pr_diff.diff")
    test_results_path = os.environ.get("TEST_RESULTS_PATH", "")
    output_path = os.environ.get("REVIEW_OUTPUT_PATH", "review_feedback.md")

    if not api_key:
        print("Error: OPENROUTER_API_KEY environment variable is not set.", file=sys.stderr)
        # Write a fallback message to the output file so the action doesn't fail catastrophically
        with open(output_path, "w", encoding="utf-8") as f:
            f.write("### PR Review Error\n\nCould not perform review because `OPENROUTER_API_KEY` is missing.")
        sys.exit(0)

    # Read the PR diff
    diff_content = ""
    if os.path.exists(diff_file_path):
        try:
            with open(diff_file_path, "r", encoding="utf-8", errors="replace") as f:
                diff_content = f.read()
        except Exception as e:
            print(f"Warning: Could not read diff file at {diff_file_path}: {e}", file=sys.stderr)
    else:
        print(f"Warning: Diff file not found at {diff_file_path}", file=sys.stderr)

    # Read the unit test results or execution log
    test_summary = "No unit test reports provided."
    if test_results_path and os.path.exists(test_results_path):
        try:
            with open(test_results_path, "r", encoding="utf-8", errors="replace") as f:
                test_summary = f.read()
        except Exception as e:
            print(f"Warning: Could not read test results/logs at {test_results_path}: {e}", file=sys.stderr)

    # If the diff is empty, we don't have anything to review, but let's notify the user
    if not diff_content.strip():
        print("No diff content found to review.")
        with open(output_path, "w", encoding="utf-8") as f:
            f.write("### PR Review\n\nNo code changes found in this PR to review.")
        sys.exit(0)

    # Truncate diff if it's too large for standard limits (though cohere/north-mini-code has 256k context, we should be safe)
    if len(diff_content) > 150000:
        diff_content = diff_content[:150000] + "\n\n... [Diff truncated due to size limits] ..."

    # Construct system prompt in CodeRabbit style
    system_prompt = (
        "You are CodeRabbit, an AI code reviewer that provides extremely polished, structured, and friendly feedback on Pull Requests.\n"
        "Generate your review in the exact style of CodeRabbit, which includes:\n"
        "1. **🐰 CodeRabbit PR Review Summary**: A friendly greeting and high-level description of what the PR accomplishes, using emojis.\n"
        "2. **🔍 Walkthrough**: A structured, bulleted list detailing the changes categorized by module/component.\n"
        "3. **🎯 Key Recommendations**: A bulleted list highlighting major code quality, security, or testing enhancements.\n"
        "4. **🛠️ File-by-File Suggestions**: Detailed file reviews with suggested code refactorings, side-by-side diff blocks, or security warnings. Use standard Markdown tables or collapsible sections where appropriate.\n"
        "5. **📋 CodeRabbit Review Checklist**: A clear table of review checklist items with statuses (e.g. 🟢 Pass, 🟡 Warning, or 🔴 Needs Attention) on security, unit testing, performance, and maintainability.\n\n"
        "Focus on TunnelGuard's domain: security-focused Android TV app, fail-closed VPN robustness, and leak prevention. Keep the tone encouraging, technical, and precise."
    )

    user_prompt = f"""Please review the following Pull Request.

### Pull Request Diff:
```diff
{diff_content}
```

### Unit Test Execution Summary:
```
{test_summary}
```

Provide your detailed CodeRabbit-style review below:"""

    # Prepare OpenRouter request payload
    payload = {
        "model": "cohere/north-mini-code:free",
        "messages": [
            {"role": "system", "content": system_prompt},
            {"role": "user", "content": user_prompt}
        ],
        "temperature": 0.2
    }

    # API Request configuration
    url = "https://openrouter.ai/api/v1/chat/completions"
    headers = {
        "Content-Type": "application/json",
        "Authorization": f"Bearer {api_key}",
        "HTTP-Referer": "https://github.com/TunnelGuard/TunnelGuard",
        "X-Title": "TunnelGuard CodeRabbit Review Bot"
    }

    print("Sending request to OpenRouter API for CodeRabbit-style review...")
    req = urllib.request.Request(url, data=json.dumps(payload).encode("utf-8"), headers=headers, method="POST")

    try:
        with urllib.request.urlopen(req, timeout=120) as response:
            res_data = response.read().decode("utf-8")
            parsed = json.loads(res_data)

            # Extract generated content
            choices = parsed.get("choices", [])
            if choices:
                review_text = choices[0].get("message", {}).get("content", "")
                if review_text:
                    with open(output_path, "w", encoding="utf-8") as f:
                        f.write(review_text)
                    print(f"Successfully wrote CodeRabbit-style PR review feedback to {output_path}")
                    sys.exit(0)

            # If we reached here, parsing or output was empty
            print("Error: Received empty response structure from OpenRouter.", file=sys.stderr)
            print(f"Raw Response: {res_data}", file=sys.stderr)
            fallback_msg = "### 🐰 CodeRabbit Review Summary\n\nReceived empty response from the review model. Please check the logs."
            with open(output_path, "w", encoding="utf-8") as f:
                f.write(fallback_msg)

    except urllib.error.HTTPError as e:
        err_msg = e.read().decode("utf-8", errors="replace")
        print(f"HTTP Error {e.code} contacting OpenRouter: {err_msg}", file=sys.stderr)
        with open(output_path, "w", encoding="utf-8") as f:
            f.write(f"### 🐰 CodeRabbit Review Error\n\nFailed to contact OpenRouter API: HTTP {e.code}.\n```\n{err_msg}\n```")
    except Exception as e:
        print(f"Error executing PR review script: {e}", file=sys.stderr)
        with open(output_path, "w", encoding="utf-8") as f:
            f.write(f"### 🐰 CodeRabbit Review Error\n\nAn unexpected error occurred: {e}")

if __name__ == "__main__":
    main()
