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

`--check` writes nothing and exits 1 when a promoted AMI newer than the
pin has waited unpinned for more than `--grace-days` (the review window of
the weekly refresh PR), or when a pin is missing or deregistered. The
daily ppg-ami-pins-check workflow runs it against master.

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


def parse_aws_time(ts):
    """EC2 timestamps as naive UTC. CreationDate usually carries milliseconds
    (2026-09-07T06:30:00.000Z), DeprecationTime does not, so accept both."""
    from datetime import datetime

    return datetime.fromisoformat(ts.replace("Z", "+00:00")).replace(tzinfo=None)


def resolve_all(client):
    """Query AWS once per combo; return ({var_name: ami_id},
    {var_name: creation_date}, {var_name: [(creation_date, ami_id), ...]
    newest first}). Raises/exits via caller on any missing resolution — see
    fail-closed note above."""
    resolved = {}
    created = {}
    promoted = {}
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
            ordered = sorted(
                ((x["CreationDate"], x["ImageId"]) for x in images),
                key=lambda pair: (parse_aws_time(pair[0]), pair[1]),
                reverse=True,
            )
            promoted[var] = ordered
            created[var], resolved[var] = ordered[0]
            print(f"resolved {var} = {resolved[var]} ({created[var]})")
    if missing:
        raise RuntimeError(f"no available AMI found for: {', '.join(missing)}")
    return resolved, created, promoted


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


def current_pins(content):
    """{var_name: ami_id} for every OL/Rocky export line in the file. A var
    that appears more than once raises: the consumer shell would use the last
    value while a reader would trust the first, so the file is ambiguous."""
    pins = {}
    for os_name, var_prefix, major in COMBOS:
        for arch in ARCHES:
            var = f"ami_{var_prefix}{major}_{arch}"
            matches = re.findall(r"^ *(?:export )?" + re.escape(var) + r"=(\S+)\s*$", content, re.MULTILINE)
            if len(matches) > 1:
                raise ValueError(f"{var} is assigned {len(matches)} times, expected once")
            pins[var] = matches[0] if matches else None
    return pins


def pinned_creation_dates(client, pins):
    """{ami_id: CreationDate} for the pinned ids that still exist. A filter
    query (not ImageIds=) so a deregistered id is simply absent instead of
    failing the whole call."""
    ids = sorted({ami for ami in pins.values() if ami})
    if not ids:
        return {}
    images = client.describe_images(Owners=["self"], Filters=[{"Name": "image-id", "Values": ids}])["Images"]
    return {img["ImageId"]: img["CreationDate"] for img in images}


def stale_pins(pins, pinned_dates, resolved, promoted, grace_days, now):
    """Return [(var, pinned, newest, reason)] for every combo whose pin is
    missing, deregistered, or has left a newer promoted AMI unpinned for
    more than grace_days. The clock starts at the OLDEST promoted AMI newer
    than the pin, so the weekly refresh PR gets grace_days of review after
    each bake and later bakes can never reset the clock on a rotting pin.
    Age is measured from the AMI's CreationDate: an image promoted long after
    it was created counts the time it spent unpromoted."""
    from datetime import timedelta

    stale = []
    for var, newest in resolved.items():
        pinned = pins.get(var)
        if not pinned:
            stale.append((var, pinned, newest, "no pin line in file"))
            continue
        if pinned not in pinned_dates:
            stale.append((var, pinned, newest, "pinned AMI no longer exists"))
            continue
        if pinned not in {ami for _, ami in promoted[var]}:
            stale.append((var, pinned, newest, "pinned AMI is not a promoted image of this combo"))
            continue
        if pinned == newest:
            continue
        pinned_at = parse_aws_time(pinned_dates[pinned])
        newer = [parse_aws_time(ts) for ts, ami in promoted[var] if parse_aws_time(ts) > pinned_at]
        if not newer:
            continue
        waiting = now - min(newer)
        if waiting > timedelta(days=grace_days):
            days = round(waiting.total_seconds() / 86400, 1)
            stale.append((var, pinned, newest, f"a newer promoted AMI has waited {days} days unpinned"))
    return stale


def main():
    import argparse

    import boto3
    import botocore.config as C

    parser = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    parser.add_argument(
        "--check",
        action="store_true",
        help="write nothing; exit 1 when a pin lags the newest promoted AMI by more than --grace-days",
    )
    parser.add_argument(
        "--grace-days",
        type=int,
        default=3,
        help="review window before a lagging pin counts as stale (default 3)",
    )
    args = parser.parse_args()

    client = boto3.client(
        "ec2",
        region_name=REGION,
        config=C.Config(retries={"max_attempts": 10, "mode": "adaptive"}),
    )
    try:
        resolved, created, promoted = resolve_all(client)
    except RuntimeError as e:
        print(f"FAIL: {e}", file=sys.stderr)
        sys.exit(1)

    path = "vars/moleculeEnvPPG.groovy"
    with open(path) as f:
        content = f.read()

    if args.check:
        from datetime import datetime, timezone

        try:
            pins = current_pins(content)
        except ValueError as e:
            print(f"FAIL: {e}", file=sys.stderr)
            sys.exit(1)
        now = datetime.now(timezone.utc).replace(tzinfo=None)
        stale = stale_pins(pins, pinned_creation_dates(client, pins), resolved, promoted, args.grace_days, now)
        for var, pinned, newest, reason in stale:
            print(f"STALE: {var} pins {pinned}, newest promoted is {newest}: {reason}")
        if stale:
            print(
                f"FAIL: {len(stale)} pin(s) lag the newest promoted AMI by more than {args.grace_days} days",
                file=sys.stderr,
            )
            sys.exit(1)
        print(f"pins current (no drift older than {args.grace_days} days)")
        return

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
