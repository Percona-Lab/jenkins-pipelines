#!/usr/bin/env python3
"""Resolve the newest role=ppg-package-test AMI for every OL/Rocky
os+major+arch combo and rewrite vars/moleculeEnvPPG.groovy's static
export lines to match.

Run weekly by the update-molecule-env job in ppg-ami-factory.yml.
This replaces the dynamic DescribeImages-at-test-time resolution
(which every parallel molecule job used to do independently, and
which could trip the account-wide DescribeImages rate limit under
load) with a single resolution done once here, matching how
RHEL/Ubuntu/Debian are already hand-pinned. It runs even after a
partial bake: each combo resolves to its newest promoted AMI, so a
failed combo keeps last week's image while the others advance.

Fails closed: if any combo fails to resolve, exits non-zero without
writing anything, so a wiped-out factory (or a transient API error)
never corrupts the file: last week's still-valid AMI IDs are left in
place untouched, and this job simply tries again next week.
"""
import re
import sys

REGION = "eu-central-1"
# (tag:os value, moleculeEnvPPG.groovy var prefix, tag:os_major value)
COMBOS = [
    ("oraclelinux", "ol", "8"),
    ("oraclelinux", "ol", "9"),
    ("oraclelinux", "ol", "10"),
    ("rocky", "rocky", "8"),
    ("rocky", "rocky", "9"),
    ("rocky", "rocky", "10"),
]
ARCHES = ["x86_64", "arm64"]

# Matches the whole comment block explaining the old dynamic-resolution
# design, plus the _ppg_ami() helper definition itself — both dead once
# every combo below is a static export. Only matches when the comment
# block is immediately followed by the helper definition, so it can
# never eat an unrelated comment elsewhere in the file. A no-op (no
# match) on every run after the first, once this has already been removed.
_HELPER_BLOCK_RE = re.compile(
    r"(?:^ {8}#[^\n]*\n)+^ {8}_ppg_ami\(\)[^\n]*\n",
    re.MULTILINE,
)


def resolve_all(client):
    """Query AWS once per combo; return {var_name: ami_id}. Raises/exits
    via caller on any missing resolution — see fail-closed note above."""
    resolved = {}
    missing = []
    for os_name, var_prefix, major in COMBOS:
        for arch in ARCHES:
            var = f"ami_{var_prefix}{major}_{arch}"
            images = client.describe_images(
                Owners=["self"],
                Filters=[
                    {"Name": "tag:role", "Values": ["ppg-package-test"]},
                    {"Name": "tag:os", "Values": [os_name]},
                    {"Name": "tag:os_major", "Values": [major]},
                    {"Name": "tag:arch", "Values": [arch]},
                    {"Name": "state", "Values": ["available"]},
                ],
            )["Images"]
            if not images:
                missing.append(var)
                continue
            newest = sorted(images, key=lambda x: x["CreationDate"])[-1]
            resolved[var] = newest["ImageId"]
            print(f"resolved {var} = {newest['ImageId']}")
    if missing:
        raise RuntimeError(f"no available AMI found for: {', '.join(missing)}")
    return resolved


def rewrite_file(content, resolved):
    """Pure text transform: given the current file content and a dict of
    {var_name: ami_id}, return the new content. Raises ValueError if any
    var's line can't be found/replaced exactly once, so a partial rewrite
    is never written. Helper-block removal is a no-op once already gone."""
    content = _HELPER_BLOCK_RE.sub("", content, count=1)

    for var, ami in resolved.items():
        pattern = re.compile(r"^( *)(?:export )?" + re.escape(var) + r"=.*$", re.MULTILINE)
        new_content, n = pattern.subn(r"\1export " + var + "=" + ami, content)
        if n != 1:
            raise ValueError(f"expected exactly 1 line for {var}, found {n}")
        content = new_content

    return content


def main():
    import boto3
    import botocore.config as C

    client = boto3.client(
        "ec2",
        region_name=REGION,
        config=C.Config(retries={"max_attempts": 10, "mode": "adaptive"}),
    )
    try:
        resolved = resolve_all(client)
    except RuntimeError as e:
        print(f"FAIL: {e}", file=sys.stderr)
        sys.exit(1)

    path = "vars/moleculeEnvPPG.groovy"
    with open(path) as f:
        content = f.read()

    try:
        new_content = rewrite_file(content, resolved)
    except ValueError as e:
        print(f"FAIL: {e}", file=sys.stderr)
        sys.exit(1)

    if new_content == content:
        print("no changes (all AMI IDs already up to date)")
        return

    with open(path, "w") as f:
        f.write(new_content)
    print(f"updated {path}")


if __name__ == "__main__":
    main()
