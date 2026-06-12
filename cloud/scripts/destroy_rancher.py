#!/usr/bin/env python3
import argparse
import concurrent.futures
import logging
import os
import shlex
import shutil
import subprocess
import sys
import tempfile
from pathlib import Path

from utils.logging import setup_stdout_logging


LOGGER = logging.getLogger("destroy_rancher")


def default_project():
    proc = subprocess.run(
        ["gcloud", "config", "get-value", "project"],
        text=True,
        capture_output=True,
    )
    return proc.stdout.strip() if proc.returncode == 0 else ""


def parse_args():
    parser = argparse.ArgumentParser(description="Destroy a Rancher RKE2 cluster created on Google Compute Engine.")
    parser.add_argument("prefix", help="Cluster prefix used during creation")
    parser.add_argument("--project-id", default=os.environ.get("PROJECT_ID") or default_project())
    parser.add_argument("--zone", default=os.environ.get("ZONE", "us-central1-a"))
    parser.add_argument("--log-level", default=os.environ.get("LOG_LEVEL", "info"), choices=("debug", "info", "warning", "error"))
    return parser.parse_args()


def make_config(args):
    return {
        "prefix": args.prefix,
        "project_id": args.project_id,
        "zone": args.zone,
        "log_level": args.log_level,
    }


def setup_logging(config):
    setup_stdout_logging(LOGGER, level=config["log_level"])


def gcloud(config, *args):
    return ["gcloud", "--project", config["project_id"], *args]


def run_command(config, step, cmd, check=True, log_output=True, timeout=None):
    LOGGER.info("START: %s", step)
    LOGGER.debug("COMMAND: %s", " ".join(shlex.quote(str(x)) for x in cmd))

    try:
        proc = subprocess.run(cmd, text=True, capture_output=True, timeout=timeout)
    except subprocess.TimeoutExpired as exc:
        LOGGER.warning("WARNING: Timeout after %ss: %s", timeout, step)

        if log_output and exc.stdout:
            LOGGER.debug("%s", exc.stdout)
        if log_output and exc.stderr:
            LOGGER.debug("%s", exc.stderr)

        proc = subprocess.CompletedProcess(cmd, 124, exc.stdout, exc.stderr)

        if check:
            raise subprocess.CalledProcessError(proc.returncode, cmd, proc.stdout, proc.stderr)

        return proc

    if log_output and proc.stdout:
        LOGGER.debug("%s", proc.stdout)
    if log_output and proc.stderr:
        LOGGER.warning("%s", proc.stderr)

    if check and proc.returncode:
        LOGGER.error("FAILED: %s", step)
        LOGGER.error("Exit code: %s", proc.returncode)
        raise subprocess.CalledProcessError(proc.returncode, cmd, proc.stdout, proc.stderr)

    if proc.returncode == 0:
        LOGGER.success("OK: %s", step)
    elif not check:
        LOGGER.warning("SKIP: %s", step)

    return proc


def validate(config):
    if not config["prefix"]:
        raise ValueError("prefix cannot be empty")

    if not config["project_id"] or config["project_id"] == "gcloud":
        raise ValueError("invalid project id. Use --project-id or set gcloud config project")


def resource_exists(config, step, cmd):
    return run_command(config, step, cmd, check=False, log_output=False).returncode == 0


def list_instances(config):
    prefix = config["prefix"]

    proc = run_command(
        config,
        "List cluster instances",
        gcloud(
            config,
            "compute",
            "instances",
            "list",
            "--zones",
            config["zone"],
            "--filter",
            f"name~'^{prefix}-(server|worker-[0-9]+)$'",
            "--format=value(name)",
        ),
    )

    return [line.strip() for line in proc.stdout.splitlines() if line.strip()]


def instance_exists(config, name):
    return resource_exists(
        config,
        f"Check instance: {name}",
        gcloud(config, "compute", "instances", "describe", name, "--zone", config["zone"]),
    )


def delete_instance(config, instance):
    if not instance_exists(config, instance):
        LOGGER.warning("SKIP: %s", instance)
        return

    run_command(
        config,
        instance,
        gcloud(
            config,
            "compute",
            "instances",
            "delete",
            instance,
            "--zone",
            config["zone"],
            "--quiet",
        ),
        check=False,
    )


def delete_instances(config, instances):
    if not instances:
        LOGGER.warning("SKIP: No instances found for cluster: %s", config["prefix"])
        return

    with concurrent.futures.ThreadPoolExecutor(max_workers=min(len(instances), 8)) as executor:
        futures = {
            executor.submit(delete_instance, config, instance): instance
            for instance in instances
        }

        for future in concurrent.futures.as_completed(futures):
            instance = futures[future]
            try:
                future.result()
            except Exception as exc:
                LOGGER.error("ERROR: %s: %s", instance, exc)


def firewall_exists(config, name):
    return resource_exists(
        config,
        f"Check firewall rule: {name}",
        gcloud(config, "compute", "firewall-rules", "describe", name),
    )


def delete_firewall(config, firewall):
    if not firewall_exists(config, firewall):
        LOGGER.warning("SKIP: %s", firewall)
        return

    run_command(
        config,
        firewall,
        gcloud(
            config,
            "compute",
            "firewall-rules",
            "delete",
            firewall,
            "--quiet",
        ),
        check=False,
    )


def delete_firewalls(config):
    prefix = config["prefix"]

    firewalls = [
        f"{prefix}-allow-ssh",
        f"{prefix}-allow-rancher",
        f"{prefix}-allow-internal",
    ]

    with concurrent.futures.ThreadPoolExecutor(max_workers=min(len(firewalls), 4)) as executor:
        futures = {
            executor.submit(delete_firewall, config, firewall): firewall
            for firewall in firewalls
        }

        for future in concurrent.futures.as_completed(futures):
            firewall = futures[future]
            try:
                future.result()
            except Exception as exc:
                LOGGER.error("ERROR: %s: %s", firewall, exc)


def delete_local_files(config):
    prefix = config["prefix"]
    workdir = Path(tempfile.gettempdir()) / "rancher" / prefix

    if workdir.exists():
        shutil.rmtree(workdir)
        LOGGER.success("OK: Removed %s", workdir)
    else:
        LOGGER.warning("SKIP: Local workdir not found: %s", workdir)

    for path in [
        Path(f"./{prefix}-output.env"),
        Path(f"./{prefix}-kubeconfig.yaml"),
    ]:
        if path.exists():
            path.unlink()
            LOGGER.success("OK: Removed %s", path)
        else:
            LOGGER.warning("SKIP: Local file not found: %s", path)


def summary(config):
    LOGGER.info(
        "Cleanup complete.\n\n"
        "Project: %s\n"
        "Zone: %s\n"
        "Cluster prefix: %s",
        config["project_id"],
        config["zone"],
        config["prefix"],
    )


def main():
    config = make_config(parse_args())
    setup_logging(config)

    try:
        validate(config)

        LOGGER.info("Deleting Rancher cluster with prefix %s", config["prefix"])

        instances = list_instances(config)
        delete_instances(config, instances)
        delete_firewalls(config)
        delete_local_files(config)
        summary(config)

        return 0

    except KeyboardInterrupt:
        LOGGER.warning("Interrupted by user")
        return 130

    except subprocess.CalledProcessError:
        return 1

    except Exception as exc:
        LOGGER.error("ERROR: %s", exc)
        return 1


if __name__ == "__main__":
    sys.exit(main())
