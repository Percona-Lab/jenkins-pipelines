#!/usr/bin/env python3
"""Generate CSV report of commits between two refs.

Usage:
    mysql_commit_report.py [options] <COMMIT1> <COMMIT2>

Positional arguments:
    COMMIT1            older ref (excluded)
    COMMIT2            newer ref (included)

Options (order does not matter):
    -i, --input-dir PATH     repository path where git commands will be executed (default: .)
    -o, --output-dir PATH    output directory for CSV files (default: .)
    -g, --generate-per-component-csv          generate per-component CSV files
    -a, --with-ai-analysis       generate AI analysis for each commit (uses cursor-agent CLI)
    -m, --model MODEL           LLM model name to pass to cursor-agent (default: gemini-3-pro)
    -t, --truncate-diff         Truncate the diff size to 10000 characters before
                                analysis (default: disabled)

Examples:
    mysql_commit_report.py -i /path/to/repo -o /home/report -g COMMIT1 COMMIT2

Note:
COMMIT1 and COMMIT2 are positional and their order is significant.
Cursor agent must be installed and the env var CURSOR_API_KEY must be set if -a is used.
"""

import sys
import argparse
import re
import subprocess
from pathlib import Path
import shutil
import os

parser = argparse.ArgumentParser(
    description="Generate CSV report of commits between two refs."
)
parser.add_argument("commit1", help="older ref (excluded)")
parser.add_argument("commit2", help="newer ref (included)")
parser.add_argument(
    "-i",
    "--input-dir",
    dest="input_dir",
    default=".",
    help="repository path where git commands will be executed (default: current dir)",
)
parser.add_argument(
    "-o",
    "--output-dir",
    dest="outdir",
    default=".",
    help="output directory for CSV files (default: current dir)",
)
parser.add_argument(
    "-g",
    "--generate-per-component-csv",
    action="store_true",
    dest="generate_per_component_csv",
    help="generate per-component CSV files",
)
parser.add_argument(
    "-a",
    "--with-ai-analysis",
    action="store_true",
    dest="with_ai_analysis",
    help="generate AI analysis for each commit (uses cursor-agent CLI)",
)
parser.add_argument(
    "-m",
    "--model",
    dest="model",
    default="gemini-3-pro",
    help="LLM model name to pass to cursor-agent (default: gemini-3-pro)",
)
parser.add_argument(
    "-t",
    "--truncate-diff",
    action="store_true",
    dest="truncate_diff",
    help="Truncate the diff size to 10000 characters before analysis (default: disabled)",
)

arguments = parser.parse_args()

generate_per_component_csv = arguments.generate_per_component_csv
with_ai_analysis = arguments.with_ai_analysis
model = arguments.model
truncate_diff = arguments.truncate_diff
COMMIT1 = arguments.commit1
COMMIT2 = arguments.commit2
OUTDIR = Path(arguments.outdir)
OUTDIR.mkdir(parents=True, exist_ok=True)
OUTCSV = OUTDIR / f"{Path(COMMIT2).name}_{model}.csv"
REPO_DIR = arguments.input_dir

# Validate input dir is a git repository
repo_path = Path(REPO_DIR)
if not repo_path.exists() or not repo_path.is_dir():
    print(
        f"Error: input dir '{REPO_DIR}' does not exist or is not a directory",
        file=sys.stderr,
    )
    parser.print_help(sys.stderr)
    sys.exit(1)
chk = subprocess.run(
    ("git", "rev-parse", "--is-inside-work-tree"),
    cwd=repo_path,
    stdout=subprocess.PIPE,
    stderr=subprocess.PIPE,
    text=True,
    check=False,
)
if chk.returncode != 0:
    print(f"Error:  input dir '{REPO_DIR}' is not a git repository", file=sys.stderr)
    parser.print_help(sys.stderr)
    sys.exit(1)


def run_git(*args, cwd):
    """Run a git command and return its stdout as text.

    Args:
        *args: Arguments passed to the git command.
        cwd: Optional working directory for the command.

    Returns:
        stdout text on success, or an empty string on failure.
    """
    p = subprocess.run(
        ("git",) + args,
        cwd=cwd,
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
        text=True,
        check=False,
    )
    if p.returncode != 0:
        return ""
    return p.stdout


def git_changed_files(commit_hash):
    """Return a list of files changed by the given commit_hash.

    Uses `git diff --name-only <commit_hash>~.. <commit_hash>` to list changed files.
    """
    out = run_git(
        "diff", "--name-only", f"{commit_hash}~..{commit_hash}", cwd=repo_path
    )
    return out.splitlines()


def git_is_merge_commit(commit_hash):
    """Return True if the commit is a merge commit.

    Detects merges by checking whether the commit has two parents.
    """
    out = run_git(
        "rev-list", "--parents", "-n", "1", commit_hash, cwd=repo_path
    ).strip()
    return len(out.split()) == 3


def git_show_field(commit_hash, fmt, date_short=False):
    """Return a formatted git-show field for the commit_hash.

    Args:
        commit_hash: Commit hash.
        fmt: Format string for --pretty=format:...
        date_short: If True, request short date format (--date=short).

    Returns:
        The requested formatted field as a stripped string.
    """
    args = ["show", "-s", f"--pretty=format:{fmt}", commit_hash]
    if date_short:
        args.insert(1, "--date=short")
    return run_git(*args, cwd=repo_path).strip()


def git_show_stat_last_line(commit_hash):
    """Return the last non-empty line of 'git show --stat' output.

    This typically contains the "N files changed, X insertions(+), ..."
    summary line. Returns empty string if there is no output.
    """
    out = run_git("show", "--stat", commit_hash, cwd=repo_path)
    if not out:
        return ""
    lines = [line for line in out.splitlines() if line.strip()]
    return lines[-1] if lines else ""


def preflight_cursor_agent():
    """Run prechecks before invoking cursor-agent."""
    # 1) cursor-agent installed
    if shutil.which("cursor-agent") is None:
        print(
            "Error: 'cursor-agent' not found in PATH. Please install it.",
            file=sys.stderr,
        )
        parser.print_help(sys.stderr)
        sys.exit(1)

    # 2) CURSOR_API_KEY set
    if not os.environ.get("CURSOR_API_KEY"):
        print("Error: CURSOR_API_KEY environment variable is not set.", file=sys.stderr)
        parser.print_help(sys.stderr)
        sys.exit(1)

    # 3) cursor-agent status (ensure logged in / ready)
    try:
        p = subprocess.run(
            ("cursor-agent", "status"),
            cwd=REPO_DIR,
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
            text=True,
            check=False,
        )
    except FileNotFoundError:
        print("Error: failed to execute 'cursor-agent'.", file=sys.stderr)
        parser.print_help(sys.stderr)
        sys.exit(1)

    if p.returncode != 0:
        print(
            "Error: 'cursor-agent status' failed. Please ensure you're logged in.",
            file=sys.stderr,
        )
        if p.stderr:
            print("Details:", p.stderr.strip(), file=sys.stderr)
        parser.print_help(sys.stderr)
        sys.exit(1)


COMMIT_ANALYSIS_PROMPT = """
You are a Senior software engineer with over a decade of experience in MySQL database internals performing commit review.

Analyze the following commit using:
- commit message
- commit description
- patch diff

Context:
- You are inside the git repository to which the commit belongs to.
- Look around other files within the repository ONLY if necessary to make the best decision possible.

Guidelines:
- All your actions are read only.
- Be concise.
- Do NOT repeat the commit message verbatim.
- Base conclusions on the diff, not guesses.
- If the diff is mostly renames or formatting, classify as "refactor".

Return whether the commit is a security fix or not along with accurate reasoning.
Your response must be two part and must include:
- A clear yes/no decision on whether the commit is a security fix.
- Accurate, technical reasoning explaining why it is or is not a security fix.

Consider indicators such as index corruption, vulnerability mitigation, input validation, authentication/authorization changes, cryptographic fixes, dependency updates addressing CVEs, or other security-related improvements.
Avoid speculation and base your conclusion strictly on the evidence present in the commit.
""".strip()


def run_ai_analysis(commit_hash, llm_model, cwd):
    """Produce AI analysis for a commit.
    Keep return value safe for CSV (no semicolons/newlines).
    """
    # Fetch the full commit details using `git show`
    commit_details = run_git("show", commit_hash, cwd=cwd)
    max_diff_size = 10000  # Limit in characters
    if truncate_diff and len(commit_details) > max_diff_size:
        print(
            f"Debug: Commit {commit_hash} changes will be truncated before analysis.",
            file=sys.stdout,
        )
        commit_details = commit_details[:max_diff_size] + "\n[TRUNCATED]"

    prompt_content = COMMIT_ANALYSIS_PROMPT
    prompt = f"{prompt_content}\n\nCommit data:\n{commit_details}\n"
    p = subprocess.run(
        ["cursor-agent", "--print", "--model", llm_model, prompt],
        cwd=cwd,
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
        text=True,
        check=False,
    )
    analysis = p.stdout.strip() if p.returncode == 0 else ""
    # sanitize for CSV (remove semicolons/newlines)
    return analysis.replace(";", " ").replace("\n", " ").strip()


def logout_cursor_agent():
    """Logout from cursor-agent at the end of the script."""
    try:
        p = subprocess.run(
            ("cursor-agent", "logout"),
            cwd=REPO_DIR,
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
            text=True,
            check=False,
        )
        if p.returncode != 0:
            print("Warning: 'cursor-agent logout' failed.", file=sys.stderr)
            if p.stderr:
                print("Details:", p.stderr.strip(), file=sys.stderr)
    except FileNotFoundError:
        print("Warning: 'cursor-agent' not found during logout.", file=sys.stderr)


PATTERN_TAGS = [
    (r"\.cmake", "cmake"),
    (r"\.gitignore", "gitignore"),
    (r"\.pem", "cert"),
    (r"binlog", "binlog"),
    (r"clang", "clang"),
    (r"client/", "client"),
    (r"CMakeLists\.txt", "cmakelists"),
    (r"components/", "components"),
    (r"Docs/", "doc"),
    (r"extra/", "third-party-libraries"),
    (r"include/", "include"),
    (r"innobase", "innodb"),
    (r"jemalloc", "jemalloc"),
    (r"libmysql/", "libmysql"),
    (r"LICENSE", "license"),
    (r"man/", "man"),
    (r"mysqld_safe\.sh", "mysqld_safe"),
    (r"mysql-test-run\.pl", "mysql-test-run"),
    (r"mysql-test/", "mtr"),
    (r"mysys/", "mysys"),
    (r"mysql-test/suite/clone/", "clone"),
    (r"mysql-test/suite/privacy/", "privacy"),
    (r"mysql-test/suite/thread_pool/", "thread_pool"),
    (r".*VERSION", "version"),
    (r"ndb", "ndb"),
    (r"packaging/", "packaging"),
    (r"percona-xtradb-cluster-tests", "pxc-tests"),
    (r"plugin/", "plugin"),
    (r"plugin/clone", "plugin_clone"),
    (r"plugin/group_replication", "group_replication"),
    (r"policy/", "policy"),
    (r"router/", "router"),
    (r"rpl_", "replication"),
    (r"scripts/", "scripts"),
    (r"sql/.*_thd_.*\.(cc|h)", "runtime"),
    (r"sql/range_optimizer", "range optimizer"),
    (r"sql/join_optimizer", "join optimizer"),
    ([r"sql/sys_vars\.(cc|h)", r"sql/system_variables\.(cc|h)"], "sys vars"),
    ([r"sql/iterators", r"sql/sql_executor\.(cc|h)"], "executor"),
    (
        [
            r"sql/sql_.*\.(cc|h)",
            r"sql/table_.*\.(cc|h)",
            r"item.*\.(cc|h)",
            r"field\.(cc|h)",
        ],
        "query compiler",
    ),
    (
        [
            r"sql_yacc\.yy",
            r".*lex.*\.(cc|h)",
            r"parse_.*\.(cc|h)",
            r"sql_parse\.(cc|h)",
        ],
        "parser",
    ),
    ([r"sql_select\.(cc|h)$", r"key.*\.(cc|h)$"], "optimizer"),
    (r"sql-common/", "sql-common"),
    (r"storage/archive", "archive"),
    (r"storage/blackhole", "blackhole"),
    (r"storage/federated", "federated"),
    (r"storage/heap", "heap"),
    (r"storage/myisam", "myisam"),
    (r"storage/perfschema", "perfschema"),
    (r"storage/temptable", "temptable"),
    (r"strings/", "strings"),
    (r"support-files/", "support-files"),
    (r"unittest/", "unittest"),
    (r"valgrind", "valgrind"),
    (r"vio/", "vio"),
]

COLUMN_HEADERS = [
    "Date",
    "Author",
    "Commit",
    "Commit title",
    "Components",
    "Modifications",
    "AI Analysis",
    "Investigation Owner",
    "Was it in upstream 5.7?",
    "Fix Owner",
    "Additional Info",
    "Status",
]

# if AI analysis requested, run preflight checks once
if with_ai_analysis:
    COLUMN_HEADERS[6] = f"AI Analysis with {model}"
    preflight_cursor_agent()

HEADER = ";".join(COLUMN_HEADERS)

with OUTCSV.open("w", encoding="utf-8") as f:
    f.write(HEADER + "\n")

rev_list_args = ["rev-list", "--topo-order", "--reverse", COMMIT2, f"^{COMMIT1}"]
commits_out = run_git(*rev_list_args, cwd=repo_path)
commits = [c for c in commits_out.splitlines() if c.strip()]

for commit in commits:
    date = git_show_field(commit, "%cd", date_short=True)
    author = git_show_field(commit, "%an")
    commit_short = git_show_field(commit, "%h")
    link = f"https://github.com/mysql/mysql-server/commit/{commit_short}"  # pylint: disable=invalid-name
    hyperlink = f'=HYPERLINK("{link}","{commit_short}")'  # pylint: disable=invalid-name

    title = git_show_field(commit, "%s").replace(";", "")
    statline = git_show_stat_last_line(commit)
    ai_analysis = ""  # pylint: disable=invalid-name

    tags = []
    changed = git_changed_files(commit)

    for pattern_group, tag in PATTERN_TAGS:
        # Ensure pattern_group is always iterable
        if isinstance(pattern_group, str):
            pattern_group = [pattern_group]
        # Check if any pattern in the group matches a file
        matched = any(
            re.search(pattern, fn) for pattern in pattern_group for fn in changed
        )
        if matched:
            tags.append(tag)

    if git_is_merge_commit(commit):
        tags.append("merge_commit")

    # Check if title is empty or only out of scope tags are present
    if not title or (
        len(tags) == 1
        and tags[0]
        in (
            "ndb",
            "cmake",
            "doc",
            "license",
            "version",
            "third-party-libraries",
        )
    ):
        tags.append("to be omitted")

    # optionally generate AI analysis (boilerplate)
    if with_ai_analysis and "to be omitted" not in tags and "merge_commit" not in tags:
        ai_analysis = run_ai_analysis(commit, model, cwd=repo_path)  # pylint: disable=invalid-name

    tags_str = ", ".join(tags) if tags else ""  # pylint: disable=invalid-name

    # parse stats: "<N> files changed, X insertions(+), Y deletions(-)"
    files_changed = 0  # pylint: disable=invalid-name
    insertions = 0  # pylint: disable=invalid-name
    deletions = 0  # pylint: disable=invalid-name
    if statline:
        m_files = re.search(r"(\d+)\s+files?\s+changed", statline)
        if m_files:
            files_changed = int(m_files.group(1))
        m_ins = re.search(r"(\d+)\s+insertions?\(\+\)", statline)
        if m_ins:
            insertions = int(m_ins.group(1))
        m_del = re.search(r"(\d+)\s+deletions?\(-\)", statline)
        if m_del:
            deletions = int(m_del.group(1))
        # Sometimes git shows "X insertions(+), Y deletions(-)" without "files changed"
        if files_changed == 0:
            m_files2 = re.search(r"(\d+)\s+file\s+changed", statline)
            if m_files2:
                files_changed = int(m_files2.group(1))

    modifications = f"{files_changed} files {insertions}+ {deletions}-"  # pylint: disable=invalid-name

    # Build CSV line (semicolon-separated)
    line = f"{date};{author};{hyperlink};{title};{tags_str};{modifications};{ai_analysis};\n"

    # append to overall CSV
    with OUTCSV.open("a", encoding="utf-8") as f:
        f.write(line)

    # component-wise CSV files
    if generate_per_component_csv:  # Only run this block if `-g` is specified
        for t in tags:
            t_clean = t.strip()
            if not t_clean:
                continue
            tagfile = OUTDIR / f"{t_clean}.csv"
            # ensure tag file has header if newly created
            if not tagfile.exists():
                with tagfile.open("w", encoding="utf-8") as tf:
                    tf.write(HEADER + "\n")
            with tagfile.open("a", encoding="utf-8") as tf:
                tf.write(line)

# Ensure logout at the end if AI analysis was used
if with_ai_analysis:
    logout_cursor_agent()
