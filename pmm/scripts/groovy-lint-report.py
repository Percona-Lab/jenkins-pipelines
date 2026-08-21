#!/usr/bin/env python3
"""Render an npm-groovy-lint JSON report and decide the gate outcome.

Reads the JSON produced by `npm-groovy-lint --output json`, prints a per-file
listing, annotates every `error` on the PR diff, writes a job summary, and
exits 1 if any error was found. `--advisory` reports without failing (used by
the pmm/ backlog sweep).

Local use, same output as CI:

    npx npm-groovy-lint@18.0.0 --noserver --failon none \
      --config pmm --output json <pmm/... files> > report.json
    python3 pmm/scripts/groovy-lint-report.py report.json
"""

import json
import os
import sys
from collections import Counter

SEVERITIES = ("error", "warning", "info")
# GitHub renders only the first handful of annotations per step; past that they
# are noise. Errors gate the PR, so they get the budget -- the rest is in the log.
MAX_ANNOTATIONS = 50
# Per file, how many hits of one advisory rule to print before collapsing.
MAX_PER_RULE_PER_FILE = 5


def escape(text):
    return text.replace("%", "%25").replace("\r", "%0D").replace("\n", "%0A")


def relative(path):
    """npm-groovy-lint keys files by absolute path; annotations need repo-relative."""
    root = os.environ.get("GITHUB_WORKSPACE") or os.getcwd()
    try:
        return os.path.relpath(path, root)
    except ValueError:
        return path


def main():
    args = [a for a in sys.argv[1:] if not a.startswith("-")]
    advisory = "--advisory" in sys.argv[1:]
    if len(args) != 1:
        print("usage: groovy-lint-report.py <report.json> [--advisory]", file=sys.stderr)
        return 2

    with open(args[0], encoding="utf-8") as handle:
        report = json.load(handle)

    files = report.get("files") or {}
    by_severity = Counter()
    by_rule = Counter()
    annotated = 0
    summary_rows = []

    for abs_path in sorted(files):
        errors = sorted(files[abs_path].get("errors") or [], key=lambda e: (e.get("line") or 0, e.get("rule") or ""))
        if not errors:
            continue
        path = relative(abs_path)
        print(f"\n{path}")
        # One advisory rule repeating down a whole environment block would bury
        # the finding that actually blocks, so repeats collapse. Errors never do.
        shown = Counter()
        elided = Counter()
        for err in errors:
            severity = err.get("severity", "info")
            rule = err.get("rule", "?")
            line = err.get("line") or 1
            msg = err.get("msg", "")
            by_severity[severity] += 1
            by_rule[rule] += 1
            if severity == "error" or shown[rule] < MAX_PER_RULE_PER_FILE:
                shown[rule] += 1
                print(f"  {line:>5}  {severity:<7}  {rule:<40}  {msg}")
            else:
                elided[rule] += 1
            if severity == "error" and annotated < MAX_ANNOTATIONS:
                print(f"::error file={path},line={line},title={rule}::{escape(msg)}")
                annotated += 1
            if severity == "error":
                summary_rows.append((path, line, rule, msg))
        for rule, count in sorted(elided.items()):
            print(f"  {'':>5}  {'':<7}  {rule:<40}  … and {count} more in this file")

    total = sum(by_severity.values())
    counts = ", ".join(f"{by_severity[s]} {s}" for s in SEVERITIES if by_severity[s])
    print(f"\n{len(files)} file(s) linted, {total} violation(s){': ' + counts if counts else ''}")

    write_summary(files, by_severity, by_rule, summary_rows, advisory)

    if by_severity["error"] and not advisory:
        print(f"\nFAIL: {by_severity['error']} error-severity violation(s). "
              "Fix them, or change pmm/.groovylintrc.json in its own PR if the rule does not fit Jenkins DSL.")
        return 1
    return 0


def write_summary(files, by_severity, by_rule, error_rows, advisory):
    path = os.environ.get("GITHUB_STEP_SUMMARY")
    if not path:
        return
    out = [f"## Groovy lint — {len(files)} file(s)", ""]
    if not sum(by_severity.values()):
        out.append("No violations. :tada:")
    else:
        out.append("| severity | count |")
        out.append("|---|---:|")
        for severity in SEVERITIES:
            if by_severity[severity]:
                out.append(f"| {severity} | {by_severity[severity]} |")
        out.append("")
        if error_rows:
            out.append("### Errors" + (" (advisory run, not gating)" if advisory else " — these fail the check"))
            out.append("")
            out.append("| file | line | rule | message |")
            out.append("|---|---:|---|---|")
            for file_path, line, rule, msg in error_rows[:MAX_ANNOTATIONS]:
                cell = msg.replace("|", r"\|")
                out.append(f"| `{file_path}` | {line} | `{rule}` | {cell} |")
            if len(error_rows) > MAX_ANNOTATIONS:
                out.append("")
                out.append(f"_…and {len(error_rows) - MAX_ANNOTATIONS} more; see the step log._")
            out.append("")
        out.append("### Top rules")
        out.append("")
        out.append("| rule | count |")
        out.append("|---|---:|")
        for rule, count in by_rule.most_common(15):
            out.append(f"| `{rule}` | {count} |")
    with open(path, "a", encoding="utf-8") as handle:
        handle.write("\n".join(out) + "\n")


if __name__ == "__main__":
    sys.exit(main())
