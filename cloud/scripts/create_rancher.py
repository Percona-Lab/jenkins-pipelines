#!/usr/bin/env python3
import argparse
import concurrent.futures
import getpass
import json
import logging
import os
import secrets
import shlex
import shutil
import stat
import string
import subprocess
import sys
import tempfile
import time
from pathlib import Path
from string import Template
from typing import Any

from utils.logging import log_lines, setup_stdout_logging


ROOT = Path(__file__).resolve().parent
FILES = ROOT / "files"
REMOTE_KUBECONFIG = "/etc/rancher/rke2/rke2.yaml"
REMOTE_USER_KUBECONFIG = "/tmp/rke2-user.yaml"
Config = dict[str, Any]
LOGGER = logging.getLogger("create_rancher")


# Arguments and config


def bool_arg(value):
    if isinstance(value, bool):
        return value
    if value.lower() in ("1", "true", "yes", "y", "on"):
        return True
    if value.lower() in ("0", "false", "no", "n", "off"):
        return False
    raise argparse.ArgumentTypeError("expected true or false")


def password(length=24):
    alphabet = string.ascii_letters + string.digits
    return "".join(secrets.choice(alphabet) for _ in range(length))


def slug(value):
    chars = [c if c.isalnum() else "-" for c in value.lower()]
    return "-".join(part for part in "".join(chars).split("-") if part)


def current_user():
    return slug(getpass.getuser()) or "user"


def default_ssh_user():
    user = current_user()
    return "jenkins" if user == "root" else user


def default_prefix():
    return f"rke2-rancher-{current_user()[:24]}-{secrets.token_hex(3)}"


def default_project():
    proc = subprocess.run(
        ["gcloud", "config", "get-value", "project"],
        text=True,
        capture_output=True,
    )
    return proc.stdout.strip() if proc.returncode == 0 else ""


def parse_args():
    p = argparse.ArgumentParser(
        description="Create an RKE2 Rancher cluster on Google Compute Engine."
    )
    # fmt: off
    p.add_argument("prefix", help="Cluster prefix used during creation")
    p.add_argument("--project-id", type=str, default=os.environ.get("PROJECT_ID") or default_project())
    p.add_argument("--zone", type=str, default=os.environ.get("ZONE", "us-central1-a"))

    p.add_argument("--worker-count", type=int, default=int(os.environ.get("WORKER_COUNT", "3")))
    p.add_argument("--machine-type", type=str, default=os.environ.get("MACHINE_TYPE", "e2-standard-4"))
    p.add_argument("--boot-disk-size", type=str, default=os.environ.get("BOOT_DISK_SIZE", "200GB"))
    p.add_argument("--image-family", type=str, default=os.environ.get("IMAGE_FAMILY", "rocky-linux-9-optimized-gcp"))
    p.add_argument("--image", type=str, default=os.environ.get("IMAGE"))
    p.add_argument("--image-project", type=str, default=os.environ.get("IMAGE_PROJECT", "rocky-linux-cloud"))
    p.add_argument("--source-ranges", type=str, default=os.environ.get("SOURCE_RANGES", "0.0.0.0/0"))

    p.add_argument("--owner", type=str, default=os.environ.get("OWNER") or current_user())
    p.add_argument("--product", type=str, default=os.environ.get("PRODUCT", "psmdb"))
    p.add_argument("--delete-after-hours", type=int, default=int(os.environ.get("DELETE_AFTER_HOURS", "3")))

    p.add_argument("--local-kubeconfig", type=Path, default=Path(os.environ.get("KUBECONFIG", "~/.kube/config").split(os.pathsep)[0]))
    p.add_argument("--save-kubeconfig", type=bool_arg, default=True)
    p.add_argument("--ssh-user", type=str, default=os.environ.get("SSH_USER") or default_ssh_user())
    p.add_argument("--admin-password", type=str, default=os.environ.get("RANCHER_ADMIN_PASSWORD") or password())

    p.add_argument("--rancher-version", type=str, default=os.environ.get("RANCHER_VERSION"))
    p.add_argument("--cert-manager-version", type=str, default=os.environ.get("CERT_MANAGER_VERSION"))
    p.add_argument("--longhorn-version", type=str, default=os.environ.get("LONGHORN_VERSION"))
    p.add_argument("--rke2-channel", type=str, default=os.environ.get("INSTALL_RKE2_CHANNEL", "stable"))
    p.add_argument("--rke2-version", type=str, default=os.environ.get("INSTALL_RKE2_VERSION"))
    p.add_argument("--log-level", type=str, default=os.environ.get("LOG_LEVEL", "info"), choices=("debug", "info", "warning", "error"))
    # fmt: on
    return p.parse_args()


def make_config(args):
    prefix = args.prefix
    workdir = Path(tempfile.gettempdir()) / "rancher" / prefix
    cfg = vars(args)
    cfg.update(
        workdir=workdir,
        master=f"{prefix}-server",
        workers=[f"{prefix}-worker-{i}" for i in range(1, args.worker_count + 1)],
        ssh_key=workdir / f"{prefix}_ci_ssh_key",
        output=workdir / f"{prefix}-output.env",
        kubeconfig=workdir / f"{prefix}-kubeconfig.yaml",
        local_kubeconfig=args.local_kubeconfig.expanduser(),
        master_external_ip="",
        master_internal_ip="",
        node_ips={},
        hostname="",
        token="",
    )
    return cfg


def validate(cfg):
    if not cfg["project_id"] or cfg["project_id"] == "gcloud":
        raise ValueError(
            "invalid project id. Use --project-id or set gcloud config project"
        )
    if cfg["worker_count"] < 0:
        raise ValueError("--worker-count must be >= 0")
    if not cfg["image"] and not cfg["image_family"]:
        raise ValueError("set --image-family or --image")


# Logging and command execution


def setup_logging(cfg):
    setup_stdout_logging(LOGGER, level=cfg["log_level"])


def run_command(
    cfg,
    step,
    cmd,
    check=True,
    env=None,
    timeout=None,
    log_output=True,
):
    LOGGER.debug("START: %s", step)
    LOGGER.debug("COMMAND: %s", " ".join(shlex.quote(str(x)) for x in cmd))

    try:
        proc = subprocess.run(
            cmd,
            text=True,
            capture_output=True,
            env=env,
            timeout=timeout,
        )
    except subprocess.TimeoutExpired as exc:
        LOGGER.warning("Timeout after %ss: %s", timeout, step)
        if log_output and exc.stdout:
            log_lines(LOGGER, exc.stdout)
        if log_output and exc.stderr:
            log_lines(LOGGER, exc.stderr)
        proc = subprocess.CompletedProcess(cmd, 124, exc.stdout, exc.stderr)

        if check:
            raise subprocess.CalledProcessError(
                proc.returncode, cmd, proc.stdout, proc.stderr
            ) from exc

        return proc

    if log_output and proc.stdout:
        log_lines(LOGGER, proc.stdout)
    if log_output and proc.stderr:
        log_lines(LOGGER, proc.stderr)

    if check and proc.returncode:
        LOGGER.error("FAILED: %s", step)
        LOGGER.error("Exit code: %s", proc.returncode)
        raise subprocess.CalledProcessError(
            proc.returncode, cmd, proc.stdout, proc.stderr
        )

    if proc.returncode == 0:
        LOGGER.success("OK: %s", step)

    return proc


def gcloud(cfg, *args):
    return ["gcloud", "--project", cfg["project_id"], *args]


def ssh(cfg, host, command, step=None, check=True):
    opts = ["-o", "StrictHostKeyChecking=no", "-o", "UserKnownHostsFile=/dev/null"]
    target = f"{cfg['ssh_user']}@{host}"
    return run_command(
        cfg,
        step or f"SSH {host}",
        gcloud(
            cfg,
            "compute",
            "ssh",
            target,
            "--zone",
            cfg["zone"],
            "--ssh-key-file",
            str(cfg["ssh_key"]),
            "--quiet",
            "--command",
            command,
            "--",
            *opts,
        ),
        check=check,
    )


def scp_from(cfg, host, remote, local, step):
    target = f"{cfg['ssh_user']}@{host}:{remote}"
    return run_command(
        cfg,
        step,
        gcloud(
            cfg,
            "compute",
            "scp",
            target,
            str(local),
            "--zone",
            cfg["zone"],
            "--ssh-key-file",
            str(cfg["ssh_key"]),
            "--strict-host-key-checking=no",
            "--quiet",
        ),
    )


def scp_to(cfg, local, host, remote, step):
    target = f"{cfg['ssh_user']}@{host}:{remote}"
    return run_command(
        cfg,
        step,
        gcloud(
            cfg,
            "compute",
            "scp",
            str(local),
            target,
            "--zone",
            cfg["zone"],
            "--ssh-key-file",
            str(cfg["ssh_key"]),
            "--strict-host-key-checking=no",
            "--quiet",
        ),
    )


def remote_path(cfg, name):
    return f"/tmp/{cfg['prefix']}-{Path(name).name}"


def upload_remote_asset(cfg, node, filename, remote=None):
    remote = remote or remote_path(cfg, filename)
    scp_to(
        cfg,
        FILES / filename,
        node,
        remote,
        f"Upload {filename}: {node}",
    )
    return remote


def upload_remote_content(cfg, node, filename, content, remote=None):
    cfg["workdir"].mkdir(parents=True, exist_ok=True)
    local = cfg["workdir"] / filename
    local.write_text(content.rstrip() + "\n")
    local.chmod(stat.S_IRUSR | stat.S_IWUSR)
    remote = remote or remote_path(cfg, filename)
    scp_to(cfg, local, node, remote, f"Upload {filename}: {node}")
    return remote


def shell_args(*args):
    return " ".join(shlex.quote(str(arg)) for arg in args if arg is not None)


def run_remote_script(cfg, node, filename, step, *args, check=True):
    remote = upload_remote_asset(cfg, node, filename)
    return ssh(
        cfg,
        node,
        f"bash {shlex.quote(remote)} {shell_args(*args)}",
        step,
        check=check,
    )


def configure_node_firewalld_base(cfg, node):
    return run_remote_script(
        cfg,
        node,
        "firewalld-rke2-base.sh",
        f"Configure firewalld base rules: {node}",
    )


def configure_node_firewalld_cni(cfg, node):
    return run_remote_script(
        cfg,
        node,
        "firewalld-rke2-cni.sh",
        f"Configure firewalld CNI interfaces: {node}",
        check=False,
    )


def configure_cluster_firewalld_cni(cfg):
    nodes = [cfg["master"], *cfg["workers"]]
    LOGGER.info("Configuring firewalld CNI interfaces: %s", ", ".join(nodes))
    with concurrent.futures.ThreadPoolExecutor(max_workers=len(nodes)) as pool:
        futures = {
            pool.submit(configure_node_firewalld_cni, cfg, node): node for node in nodes
        }
        for future in concurrent.futures.as_completed(futures):
            future.result()
            LOGGER.success(
                "OK: Configure firewalld CNI interfaces: %s", futures[future]
            )


def out(cfg, step, cmd):
    return run_command(cfg, step, cmd).stdout.strip()


# Local setup and GCP infrastructure


def ensure_ssh_key(cfg):
    cfg["workdir"].mkdir(parents=True, exist_ok=True)
    if not cfg["ssh_key"].exists():
        run_command(
            cfg,
            "Generate CI SSH key",
            [
                "ssh-keygen",
                "-t",
                "ed25519",
                "-N",
                "",
                "-f",
                str(cfg["ssh_key"]),
                "-C",
                f"{cfg['prefix']}-ci",
            ],
        )
    cfg["ssh_key"].chmod(stat.S_IRUSR | stat.S_IWUSR)


def exists(cfg, step, cmd):
    return run_command(cfg, step, cmd, check=False, log_output=False).returncode == 0


def create_firewall(cfg, name, allow, *extra):
    if exists(
        cfg,
        f"Check firewall rule: {name}",
        gcloud(cfg, "compute", "firewall-rules", "describe", name),
    ):
        LOGGER.warning("SKIP: Firewall rule already exists: %s", name)
        return
    run_command(
        cfg,
        f"Create firewall rule: {name}",
        gcloud(
            cfg,
            "compute",
            "firewall-rules",
            "create",
            name,
            "--allow",
            allow,
            "--target-tags",
            cfg["prefix"],
            *extra,
            "--quiet",
        ),
    )


def create_firewalls(cfg):
    rules = [
        (
            f"{cfg['prefix']}-allow-ssh",
            "tcp:22",
            ("--source-ranges", cfg["source_ranges"]),
        ),
        (
            f"{cfg['prefix']}-allow-rancher",
            "tcp:80,tcp:443,tcp:6443,tcp:9345,tcp:10250,udp:8472",
            ("--source-ranges", cfg["source_ranges"]),
        ),
        (
            f"{cfg['prefix']}-allow-internal",
            "tcp,udp,icmp",
            ("--source-tags", cfg["prefix"]),
        ),
    ]

    LOGGER.info(f"Creating firewall rules: {', '.join(rule[0] for rule in rules)}")
    with concurrent.futures.ThreadPoolExecutor(max_workers=len(rules)) as pool:
        futures = {
            pool.submit(create_firewall, cfg, name, allow, *extra): name
            for name, allow, extra in rules
        }
        for done, future in enumerate(concurrent.futures.as_completed(futures), 1):
            future.result()
            LOGGER.success("OK: %s", futures[future])


def create_instance(cfg, name):
    if exists(
        cfg,
        f"Check VM: {name}",
        gcloud(cfg, "compute", "instances", "describe", name, "--zone", cfg["zone"]),
    ):
        LOGGER.debug("SKIP: VM already exists: %s", name)
        return

    image = (
        ["--image", cfg["image"]]
        if cfg["image"]
        else ["--image-family", cfg["image_family"]]
    )

    labels = (
        f"delete-after-hours={cfg['delete_after_hours']},"
        f"product={cfg['product']},"
        f"owner={cfg['owner']}"
    )

    run_command(
        cfg,
        f"Create VM: {name}",
        gcloud(
            cfg,
            "compute",
            "instances",
            "create",
            name,
            "--zone",
            cfg["zone"],
            "--machine-type",
            cfg["machine_type"],
            *image,
            "--image-project",
            cfg["image_project"],
            "--boot-disk-size",
            cfg["boot_disk_size"],
            "--tags",
            cfg["prefix"],
            "--metadata",
            "enable-oslogin=FALSE,vmDnsSetting=ZonalOnly",
            "--labels",
            labels,
            "--quiet",
        ),
    )


def create_instances(cfg):
    names = [cfg["master"], *cfg["workers"]]
    LOGGER.info(f"Creating instances: {', '.join(names)}")
    with concurrent.futures.ThreadPoolExecutor(
        max_workers=1 + len(cfg["workers"])
    ) as pool:
        futures = {pool.submit(create_instance, cfg, name): name for name in names}
        for done, future in enumerate(concurrent.futures.as_completed(futures), 1):
            future.result()
            LOGGER.success("OK: %s", futures[future])
    LOGGER.success("OK: Create all VMs in parallel")


def wait_for_ssh(cfg, name):
    LOGGER.debug("START: Wait for SSH on %s", name)
    for _ in range(60):
        if (
            ssh(cfg, name, "echo ok", f"Check SSH on {name}", check=False).returncode
            == 0
        ):
            LOGGER.success("OK: SSH ready on %s", name)
            return
        time.sleep(10)
    raise TimeoutError(f"SSH not ready on {name}")


def get_instance_ip(cfg, instance, ip_format):
    return out(
        cfg,
        f"Get {instance} {ip_format}",
        gcloud(
            cfg,
            "compute",
            "instances",
            "describe",
            instance,
            "--zone",
            cfg["zone"],
            f"--format=value({ip_format})",
        ),
    )


def get_node_ips(cfg):
    for node in [cfg["master"], *cfg["workers"]]:
        cfg["node_ips"][node] = {
            "internal": get_instance_ip(cfg, node, "networkInterfaces[0].networkIP"),
            "external": get_instance_ip(
                cfg, node, "networkInterfaces[0].accessConfigs[0].natIP"
            ),
        }

    cfg["master_internal_ip"] = cfg["node_ips"][cfg["master"]]["internal"]
    cfg["master_external_ip"] = cfg["node_ips"][cfg["master"]]["external"]
    cfg["hostname"] = f"{cfg['master_external_ip']}.sslip.io"
    LOGGER.debug("Rancher hostname: %s", cfg["hostname"])


# RKE2 install helpers


def template(name, **values):
    return Template((FILES / name).read_text()).safe_substitute(values)


def install_env(cfg, kind=None):
    env = []
    if cfg["rke2_channel"]:
        env.append(f"INSTALL_RKE2_CHANNEL={shlex.quote(cfg['rke2_channel'])}")
    if cfg["rke2_version"]:
        env.append(f"INSTALL_RKE2_VERSION={shlex.quote(cfg['rke2_version'])}")
    if kind:
        env.append(f"INSTALL_RKE2_TYPE={kind}")
    return " ".join(env)


def install_server(cfg):
    LOGGER.info("Installing RKE2 server: %s", cfg["master"])
    rke2 = template(
        "rke2-server-config.yaml.tmpl",
        master_external_ip=cfg["master_external_ip"],
        master_internal_ip=cfg["master_internal_ip"],
        hostname=cfg["hostname"],
    )
    config_file = upload_remote_content(
        cfg,
        cfg["master"],
        "rke2-server-config.yaml",
        rke2,
    )
    firewalld_script = upload_remote_asset(
        cfg,
        cfg["master"],
        "firewalld-rke2-base.sh",
    )
    run_remote_script(
        cfg,
        cfg["master"],
        "install-rke2-server.sh",
        "Install RKE2 server",
        "--config-file",
        config_file,
        "--firewalld-script",
        firewalld_script,
        "--install-env",
        install_env(cfg),
        "--remote-kubeconfig",
        REMOTE_KUBECONFIG,
    )
    LOGGER.success("OK: RKE2 server installed on %s", cfg["master"])


def ensure_server_installed(cfg):
    proc = ssh(
        cfg,
        cfg["master"],
        "sudo systemctl cat rke2-server >/dev/null 2>&1",
        "Check RKE2 server unit",
        check=False,
    )
    if proc.returncode == 0:
        return

    LOGGER.warning("RKE2 server unit is missing; reinstalling server")
    install_server(cfg)


def read_token(cfg):
    cfg["token"] = run_remote_script(
        cfg,
        cfg["master"],
        "read-rke2-token.sh",
        "Read RKE2 token",
    ).stdout.strip()
    if not cfg["token"]:
        raise RuntimeError("Empty RKE2 token")


def join_worker(cfg, worker):
    rke2 = template(
        "rke2-agent-config.yaml.tmpl",
        master_internal_ip=cfg["master_internal_ip"],
        token=cfg["token"],
    )
    config_file = upload_remote_content(
        cfg,
        worker,
        f"{worker}-rke2-agent-config.yaml",
        rke2,
    )
    firewalld_script = upload_remote_asset(
        cfg,
        worker,
        "firewalld-rke2-base.sh",
    )
    run_remote_script(
        cfg,
        worker,
        "install-rke2-agent.sh",
        f"Join worker: {worker}",
        "--config-file",
        config_file,
        "--firewalld-script",
        firewalld_script,
        "--install-env",
        install_env(cfg, "agent"),
    )


def join_workers(cfg):
    if not cfg["workers"]:
        return
    LOGGER.info(f"Adding worker nodes: {', '.join(cfg['workers'])}")
    LOGGER.debug("START: Join workers in parallel")
    with concurrent.futures.ThreadPoolExecutor(max_workers=len(cfg["workers"])) as pool:
        futures = {
            pool.submit(join_worker, cfg, worker): worker for worker in cfg["workers"]
        }
        for done, future in enumerate(concurrent.futures.as_completed(futures), 1):
            future.result()
            LOGGER.success("OK: %s", futures[future])
    LOGGER.success("OK: Join workers in parallel")


# Kubeconfig and Kubernetes readiness


def kube_env(*paths):
    env = os.environ.copy()
    env["KUBECONFIG"] = os.pathsep.join(str(path) for path in paths if path)
    return env


def fetch_kubeconfig(cfg):
    run_remote_script(
        cfg,
        cfg["master"],
        "prepare-kubeconfig.sh",
        "Prepare local kubeconfig",
        "--master-external-ip",
        cfg["master_external_ip"],
        "--remote-kubeconfig",
        REMOTE_KUBECONFIG,
        "--output-file",
        "/tmp/rke2.yaml",
    )
    scp_from(
        cfg, cfg["master"], "/tmp/rke2.yaml", cfg["kubeconfig"], "Download kubeconfig"
    )
    cfg["kubeconfig"].chmod(stat.S_IRUSR | stat.S_IWUSR)


def normalize_kubeconfig(cfg):
    context = cfg["prefix"]
    data = json.loads(
        run_command(
            cfg,
            "Read downloaded kubeconfig",
            ["kubectl", "config", "view", "--raw", "-o", "json"],
            env=kube_env(cfg["kubeconfig"]),
            log_output=False,
        ).stdout
    )

    data["current-context"] = context
    for cluster in data.get("clusters", []):
        cluster["name"] = context
        cluster["cluster"]["server"] = f"https://{cfg['master_external_ip']}:6443"
    for user in data.get("users", []):
        user["name"] = context
    for ctx in data.get("contexts", []):
        ctx["name"] = context
        ctx["context"]["cluster"] = context
        ctx["context"]["user"] = context

    cfg["kubeconfig"].write_text(json.dumps(data, indent=2) + "\n")
    cfg["kubeconfig"].chmod(stat.S_IRUSR | stat.S_IWUSR)


def merge_kubeconfig(cfg):
    context = cfg["prefix"]
    local = cfg["local_kubeconfig"]
    fetched = cfg["kubeconfig"]
    normalize_kubeconfig(cfg)
    local.parent.mkdir(parents=True, exist_ok=True)
    if local.exists():
        merged = run_command(
            cfg,
            "Flatten merged kubeconfig",
            ["kubectl", "config", "view", "--flatten"],
            env=kube_env(fetched, local),
        ).stdout
        tmp = local.with_suffix(local.suffix + ".tmp")
        tmp.write_text(merged)
        tmp.replace(local)
    else:
        shutil.copyfile(fetched, local)
    local.chmod(stat.S_IRUSR | stat.S_IWUSR)
    run_command(
        cfg,
        f"Use kubeconfig context: {context}",
        ["kubectl", "config", "use-context", context],
        env=kube_env(local),
    )


def node_ready(node):
    return any(
        condition.get("type") == "Ready" and condition.get("status") == "True"
        for condition in node.get("status", {}).get("conditions", [])
    )


def list_nodes(cfg):
    proc = run_command(
        cfg,
        "List Kubernetes nodes",
        ["kubectl", "get", "nodes", "-o", "json"],
        env=kube_env(cfg["kubeconfig"]),
        check=False,
        log_output=False,
    )
    if proc.returncode:
        LOGGER.warning("Kubernetes API is not ready yet")
        if proc.stderr:
            log_lines(LOGGER, proc.stderr)
        return []

    return json.loads(proc.stdout).get("items", [])


def wait_nodes(cfg):
    fetch_kubeconfig(cfg)
    if cfg["save_kubeconfig"]:
        merge_kubeconfig(cfg)
    expected = 1 + len(cfg["workers"])
    for _ in range(60):
        nodes = list_nodes(cfg)
        ready_count = sum(1 for node in nodes if node_ready(node))
        LOGGER.info("Nodes Ready: %s/%s", ready_count, expected)
        if ready_count == expected:
            return
        time.sleep(10)
    raise TimeoutError(f"Timed out waiting for {expected} Ready nodes")


# Rancher install and outputs


def helm_step_label(step):
    return f"{step['chart']}@{step['version']}"


def run_helm_step(cfg, step):
    return run_remote_script(
        cfg,
        cfg["master"],
        step["script"],
        f"Install Rancher: {step['name']}",
        *step["args"],
        check=False,
    )


def cleanup_helm_step(cfg, step):
    if not step["release"]:
        return

    LOGGER.warning("Retrying %s after uninstall", helm_step_label(step))
    run_remote_script(
        cfg,
        cfg["master"],
        "cleanup-helm-release.sh",
        f"Cleanup failed Helm install: {step['name']}",
        "--release",
        step["release"],
        "--namespace",
        step["namespace"],
        "--remote-kubeconfig",
        REMOTE_KUBECONFIG,
        "--remote-user-kubeconfig",
        REMOTE_USER_KUBECONFIG,
        check=False,
    )
    time.sleep(10)


def raise_helm_step_error(cfg, proc, step):
    LOGGER.error("Install Rancher failed: %s", helm_step_label(step))
    if proc.stdout:
        LOGGER.error("%s", proc.stdout.rstrip())
    if proc.stderr:
        LOGGER.error("%s", proc.stderr.rstrip())
    raise subprocess.CalledProcessError(
        proc.returncode, proc.args, proc.stdout, proc.stderr
    )


def install_helm_step(cfg, step, done, total):
    proc = None
    for attempt in range(1, 3):
        LOGGER.info(
            "Installing Rancher (%s/%s): %s attempt %s/2",
            done,
            total,
            helm_step_label(step),
            attempt,
        )
        proc = run_helm_step(cfg, step)
        if proc.returncode == 0:
            LOGGER.success(
                "OK: Installing Rancher (%s/%s): %s", done, total, helm_step_label(step)
            )
            return
        if attempt == 1:
            cleanup_helm_step(cfg, step)

    raise_helm_step_error(cfg, proc, step)


def normalize_helm_version(value, default="latest"):
    version = (value or default).strip()
    return "latest" if version.lower() == "latest" else version


def install_rancher(cfg):

    cert_manager_version = normalize_helm_version(cfg.get("cert_manager_version"))
    rancher_version = normalize_helm_version(cfg.get("rancher_version"))
    longhorn_version = normalize_helm_version(cfg.get("longhorn_version"))

    # cert-manager is required for Rancher ingress TLS and for RKE2 cluster issuers.
    # Longhorn is required for Rancher to manage local cluster storage.
    set_default = '{"metadata":{"annotations":{"storageclass.kubernetes.io/is-default-class":"true"}}}'
    helm_kubeconfig_args = [
        "--remote-kubeconfig",
        REMOTE_KUBECONFIG,
        "--remote-user-kubeconfig",
        REMOTE_USER_KUBECONFIG,
    ]
    
    steps = [
        {
            "name": "helm repositories",
            "chart": "repositories",
            "version": "latest",
            "release": "",
            "namespace": "",
            "script": "install-helm-repos.sh",
            "args": [],
        },
        {
            "name": "cert-manager",
            "chart": "jetstack/cert-manager",
            "version": cert_manager_version,
            "release": "cert-manager",
            "namespace": "cert-manager",
            "script": "install-helm.sh",
            "args": [
                "--release-name", "cert-manager",
                "--chart", "jetstack/cert-manager",
                "--chart-version", cert_manager_version,
                "--namespace", "cert-manager",
                *helm_kubeconfig_args,
            ],
        },
        {
            "name": "longhorn",
            "chart": "longhorn/longhorn",
            "version": longhorn_version,
            "release": "longhorn",
            "namespace": "longhorn-system",
            "script": "install-helm.sh",
            "args": [
                "--release-name", "longhorn",
                "--chart", "longhorn/longhorn",
                "--chart-version", longhorn_version,
                "--namespace", "longhorn-system",
                "--set-default-storageclass-patch", set_default,
                *helm_kubeconfig_args,
            ],
        },
        {
            "name": "rancher",
            "chart": "rancher-stable/rancher",
            "version": rancher_version,
            "release": "rancher",
            "namespace": "cattle-system",
            "script": "install-helm.sh",
            "args": [
                "--release-name", "rancher",
                "--chart", "rancher-stable/rancher",
                "--chart-version", rancher_version,
                "--namespace", "cattle-system",
                "--hostname", cfg["hostname"],
                "--admin-password", cfg["admin_password"],
                *helm_kubeconfig_args,
            ],
        },
    ]

    charts = ", ".join(
        f"{step['chart']}@{step['version']}"
        for step in steps
        if step["chart"] != "repositories"
    )

    LOGGER.info(f"Installing Rancher charts: {charts}")

    for done, step in enumerate(steps, 1):
        install_helm_step(cfg, step, done, len(steps))

    LOGGER.success("OK: Rancher stack installed")


def write_output(cfg):
    lines = {
        "RANCHER_URL": f"https://{cfg['hostname']}",
        "RANCHER_USER": "admin",
        "RANCHER_PASSWORD": cfg["admin_password"],
        "PROJECT_ID": cfg["project_id"],
        "ZONE": cfg["zone"],
        "MASTER": cfg["master"],
        "MASTER_EXTERNAL_IP": cfg["master_external_ip"],
        "MASTER_INTERNAL_IP": cfg["master_internal_ip"],
        "SSH_KEY_FILE": cfg["ssh_key"],
        "KUBECONFIG": cfg["local_kubeconfig"]
        if cfg["save_kubeconfig"]
        else cfg["kubeconfig"],
    }
    for node, ips in cfg["node_ips"].items():
        env_name = node.upper().replace("-", "_")
        lines[f"{env_name}_INTERNAL_IP"] = ips["internal"]
        lines[f"{env_name}_EXTERNAL_IP"] = ips["external"]

    cfg["workdir"].mkdir(parents=True, exist_ok=True)
    cfg["output"].write_text("\n".join(f"{k}={v}" for k, v in lines.items()) + "\n")
    cfg["output"].chmod(stat.S_IRUSR | stat.S_IWUSR)


def summary(cfg):
    kubeconfig = (
        cfg["local_kubeconfig"] if cfg["save_kubeconfig"] else cfg["kubeconfig"]
    )
    files = "\n".join(
        [
            f"  OUTPUT_FILE={cfg['output']}",
            f"  SSH_KEY_FILE={cfg['ssh_key']}",
            f"  KUBECONFIG={kubeconfig}",
        ]
    )
    nodes = "\n".join(
        f"  {node}: internal={ips['internal']} external={ips['external']}"
        for node, ips in cfg["node_ips"].items()
    )
    ssh_commands = "\n".join(
        f"  gcloud compute ssh {cfg['ssh_user']}@{node} --zone {cfg['zone']} --ssh-key-file {cfg['ssh_key']}"
        for node in [cfg["master"], *cfg["workers"]]
    )
    LOGGER.info(
        f"Cluster created with below information:\n\n"
        f"Rancher URL: https://{cfg['hostname']}\n\n"
        f"Credentials:\n  RANCHER_USER=admin\n  RANCHER_PASSWORD={cfg['admin_password']}\n\n"
        f"Files:\n{files}\n\n"
        f"Nodes:\n{nodes}\n\n"
        f"SSH:\n{ssh_commands}"
    )


def main():
    cfg = make_config(parse_args())
    setup_logging(cfg)

    try:
        validate(cfg)
        ensure_ssh_key(cfg)

        create_firewalls(cfg)
        create_instances(cfg)
        get_node_ips(cfg)

        wait_for_ssh(cfg, cfg["master"])
        install_server(cfg)
        ensure_server_installed(cfg)
        read_token(cfg)
        for worker in cfg["workers"]:
            wait_for_ssh(cfg, worker)
        join_workers(cfg)
        wait_nodes(cfg)
        configure_cluster_firewalld_cni(cfg)

        install_rancher(cfg)
        write_output(cfg)
        summary(cfg)

        return 0
    except KeyboardInterrupt:
        LOGGER.warning("Interrupted by user")
        return 130
    except subprocess.CalledProcessError:
        LOGGER.error("Subprocess call failed")
        return 1
    except Exception as exc:
        LOGGER.error("ERROR: %s", exc)
        return 1


if __name__ == "__main__":
    sys.exit(main())
