#!/usr/bin/env python3
import argparse
import concurrent.futures
import getpass
import ipaddress
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
Config = dict[str, Any]
LOGGER = logging.getLogger("create_rancher")


# ── Helpers ───────────────────────────────────────────────────────────────────


def bool_arg(value):
    if isinstance(value, bool):
        return value
    if value.lower() in ("1", "true", "yes", "y", "on"):
        return True
    if value.lower() in ("0", "false", "no", "n", "off"):
        return False
    raise argparse.ArgumentTypeError("expected true or false")


def password(length=24):
    return "".join(
        secrets.choice(string.ascii_letters + string.digits) for _ in range(length)
    )


def slug(value):
    chars = [c if c.isalnum() else "-" for c in value.lower()]
    return "-".join(p for p in "".join(chars).split("-") if p)


def current_user():
    return slug(getpass.getuser()) or "user"


def default_ssh_user():
    user = current_user()
    return "jenkins" if user == "root" else user


def default_project():
    proc = subprocess.run(
        ["gcloud", "config", "get-value", "project"], text=True, capture_output=True
    )
    return proc.stdout.strip() if proc.returncode == 0 else ""


def normalize_helm_version(value):
    v = (value or "latest").strip()
    return "latest" if v.lower() == "latest" else v


def shell_args(*args):
    return " ".join(shlex.quote(str(a)) for a in args if a is not None)


def template(name, **values):
    return Template((FILES / name).read_text()).safe_substitute(values)


def region_from_zone(zone):
    return "-".join(zone.split("-")[:-1])


def install_nfs_client(cfg, node):
    run_remote_script(
        cfg,
        node,
        "install-nfs-client.sh",
        f"Install NFS client: {node}",
    )


def install_nfs_clients(cfg):
    nodes = [cfg["master"], *cfg["workers"]]

    with concurrent.futures.ThreadPoolExecutor(max_workers=len(nodes)) as pool:
        futures = {pool.submit(install_nfs_client, cfg, node): node for node in nodes}

        for future in concurrent.futures.as_completed(futures):
            future.result()
            LOGGER.success("OK: NFS client: %s", futures[future])


# ── Args & config ─────────────────────────────────────────────────────────────


def parse_args():
    p = argparse.ArgumentParser(description="Create an RKE2 Rancher cluster on GCE.")

    p.add_argument(
        "prefix",
        help="Prefix used to name all created resources, such as VMs, firewall rules, kubeconfig context, and temporary files.",
    )

    p.add_argument(
        "--project-id",
        default=os.environ.get("PROJECT_ID") or default_project(),
        help="GCP project ID where the cluster infrastructure will be created. Defaults to PROJECT_ID or the current gcloud project.",
    )

    p.add_argument(
        "--zone",
        default=os.environ.get("ZONE", "us-central1-a"),
        help="GCP zone where the VM instances will be created. Defaults to ZONE or us-central1-a.",
    )

    p.add_argument(
        "--worker-count",
        type=int,
        default=int(os.environ.get("WORKER_COUNT", "3")),
        help="Number of RKE2 worker nodes to create. Defaults to WORKER_COUNT or 3.",
    )

    p.add_argument(
        "--machine-type",
        default=os.environ.get("MACHINE_TYPE", "e2-standard-4"),
        help="GCE machine type for the server and worker nodes. Defaults to MACHINE_TYPE or e2-standard-4.",
    )

    p.add_argument(
        "--boot-disk-size",
        default=os.environ.get("BOOT_DISK_SIZE", "200GB"),
        help="Boot disk size for each VM instance. Defaults to BOOT_DISK_SIZE or 200GB.",
    )

    p.add_argument(
        "--image-family",
        default=os.environ.get("IMAGE_FAMILY", "rocky-linux-9-optimized-gcp"),
        help="GCE image family used for node creation when --image is not provided. Defaults to IMAGE_FAMILY or rocky-linux-9-optimized-gcp.",
    )

    p.add_argument(
        "--image",
        default=os.environ.get("IMAGE"),
        help="Specific GCE image to use instead of an image family. Defaults to IMAGE if set.",
    )

    p.add_argument(
        "--image-project",
        default=os.environ.get("IMAGE_PROJECT", "rocky-linux-cloud"),
        help="GCP image project containing the selected image or image family. Defaults to IMAGE_PROJECT or rocky-linux-cloud.",
    )

    p.add_argument(
        "--source-ranges",
        default=os.environ.get("SOURCE_RANGES", "0.0.0.0/0"),
        help="CIDR source ranges allowed by firewall rules for SSH, Rancher, and Kubernetes access. Defaults to SOURCE_RANGES or 0.0.0.0/0.",
    )

    p.add_argument(
        "--owner",
        default=os.environ.get("OWNER") or current_user(),
        help="Owner label applied to created resources. Defaults to OWNER or the current local user.",
    )

    p.add_argument(
        "--product",
        default=os.environ.get("PRODUCT", "psmdb"),
        help="Product label applied to created resources. Defaults to PRODUCT or psmdb.",
    )

    p.add_argument(
        "--delete-after-hours",
        type=int,
        default=int(os.environ.get("DELETE_AFTER_HOURS", "3")),
        help="TTL label value, in hours, used by cleanup automation. Defaults to DELETE_AFTER_HOURS or 3.",
    )

    p.add_argument(
        "--local-kubeconfig",
        type=Path,
        default=Path(
            os.environ.get("KUBECONFIG", "~/.kube/config").split(os.pathsep)[0]
        ),
        help="Local kubeconfig file where the generated cluster context will be merged. Defaults to the first KUBECONFIG entry or ~/.kube/config.",
    )

    p.add_argument(
        "--save-kubeconfig",
        type=bool_arg,
        default=True,
        help="Whether to merge the generated kubeconfig into the local kubeconfig. Defaults to true.",
    )

    p.add_argument(
        "--ssh-user",
        default=os.environ.get("SSH_USER") or default_ssh_user(),
        help="SSH username used for gcloud compute ssh/scp. Defaults to SSH_USER, or jenkins when running as root, otherwise the current user.",
    )

    p.add_argument(
        "--admin-password",
        default=os.environ.get("RANCHER_ADMIN_PASSWORD") or password(),
        help="Initial Rancher admin password. Defaults to RANCHER_ADMIN_PASSWORD or a randomly generated password.",
    )

    p.add_argument(
        "--rancher-version",
        default=os.environ.get("RANCHER_VERSION"),
        help="Rancher Helm chart version to install. Defaults to RANCHER_VERSION or latest.",
    )

    p.add_argument(
        "--cert-manager-version",
        default=os.environ.get("CERT_MANAGER_VERSION"),
        help="cert-manager Helm chart version to install. Defaults to CERT_MANAGER_VERSION or latest.",
    )

    p.add_argument(
        "--longhorn-version",
        default=os.environ.get("LONGHORN_VERSION"),
        help="Longhorn Helm chart version to install. Defaults to LONGHORN_VERSION or latest.",
    )

    p.add_argument(
        "--metallb-version",
        default=os.environ.get("METALLB_VERSION"),
        help="MetalLB Helm chart version to install. Defaults to METALLB_VERSION or latest.",
    )

    p.add_argument(
        "--metallb-range",
        default=os.environ.get("METALLB_RANGE"),
        help="IP address range used by MetalLB. Defaults to METALLB_RANGE or an automatically discovered range from the subnet.",
    )

    p.add_argument(
        "--rke2-channel",
        default=os.environ.get("INSTALL_RKE2_CHANNEL", "stable"),
        help="RKE2 release channel used by the installer. Defaults to INSTALL_RKE2_CHANNEL or stable.",
    )

    p.add_argument(
        "--rke2-version",
        default=os.environ.get("INSTALL_RKE2_VERSION"),
        help="Specific RKE2 version to install. Defaults to INSTALL_RKE2_VERSION, or the latest version from the selected channel.",
    )

    p.add_argument(
        "--log-level",
        default=os.environ.get("LOG_LEVEL", "info"),
        choices=("debug", "info", "warning", "error"),
        help="Logging verbosity. Defaults to LOG_LEVEL or info.",
    )
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


# ── Command execution ─────────────────────────────────────────────────────────


def run_command(cfg, step, cmd, check=True, env=None, timeout=None, log_output=True):
    LOGGER.debug("START: %s", step)
    LOGGER.debug("COMMAND: %s", " ".join(shlex.quote(str(x)) for x in cmd))
    try:
        proc = subprocess.run(
            cmd, text=True, capture_output=True, env=env, timeout=timeout
        )
    except subprocess.TimeoutExpired as exc:
        LOGGER.warning("Timeout after %ss: %s", timeout, step)
        if log_output and exc.stdout:
            log_lines(LOGGER, exc.stdout)
        if log_output and exc.stderr:
            log_lines(
                LOGGER,
                exc.stderr,
                default_level=logging.ERROR,
            )
        proc = subprocess.CompletedProcess(cmd, 124, exc.stdout, exc.stderr)
        if check:
            raise subprocess.CalledProcessError(
                proc.returncode, cmd, proc.stdout, proc.stderr
            ) from exc
        return proc

    if log_output and proc.stdout:
        log_lines(LOGGER, proc.stdout)
    if log_output and proc.stderr:
        log_lines(
            LOGGER,
            proc.stderr,
            default_level=logging.ERROR if proc.returncode else logging.DEBUG,
        )
    if check and proc.returncode:
        LOGGER.error("FAILED: %s (exit %s)", step, proc.returncode)
        raise subprocess.CalledProcessError(
            proc.returncode, cmd, proc.stdout, proc.stderr
        )
    if proc.returncode == 0:
        LOGGER.success("OK: %s", step)
    return proc


def out(cfg, step, cmd):
    return run_command(cfg, step, cmd, log_output=False).stdout.strip()


def exists(cfg, step, cmd):
    return run_command(cfg, step, cmd, check=False, log_output=False).returncode == 0


def kube_env(cfg):
    env = os.environ.copy()
    env["KUBECONFIG"] = str(cfg["kubeconfig"])
    return env


# ── GCloud / SSH shortcuts ────────────────────────────────────────────────────


def gcloud(cfg, *args):
    return ["gcloud", "--project", cfg["project_id"], *args]


def ssh(cfg, host, command, step=None, check=True):
    opts = ["-o", "StrictHostKeyChecking=no", "-o", "UserKnownHostsFile=/dev/null"]
    return run_command(
        cfg,
        step or f"SSH {host}",
        gcloud(
            cfg,
            "compute",
            "ssh",
            f"{cfg['ssh_user']}@{host}",
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


def scp(cfg, src, dst, host, step, direction="to"):
    target = f"{cfg['ssh_user']}@{host}"
    remote_arg = f"{target}:{dst}" if direction == "to" else f"{target}:{src}"
    local_arg = src if direction == "to" else dst
    args = [local_arg, remote_arg] if direction == "to" else [remote_arg, local_arg]
    return run_command(
        cfg,
        step,
        gcloud(
            cfg,
            "compute",
            "scp",
            *[str(a) for a in args],
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


def upload_asset(cfg, node, filename, remote=None):
    remote = remote or remote_path(cfg, filename)
    scp(cfg, FILES / filename, remote, node, f"Upload {filename}: {node}")
    return remote


def upload_content(cfg, node, filename, content, remote=None):
    cfg["workdir"].mkdir(parents=True, exist_ok=True)
    local = cfg["workdir"] / filename
    local.write_text(content.rstrip() + "\n")
    local.chmod(stat.S_IRUSR | stat.S_IWUSR)
    remote = remote or remote_path(cfg, filename)
    scp(cfg, local, remote, node, f"Upload {filename}: {node}")
    return remote


def run_remote_script(cfg, node, filename, step, *args, check=True):
    remote = upload_asset(cfg, node, filename)
    return ssh(
        cfg, node, f"bash {shlex.quote(remote)} {shell_args(*args)}", step, check=check
    )


# ── GCP infrastructure ────────────────────────────────────────────────────────


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


def create_firewall(cfg, name, allow, *extra):
    LOGGER.info("Provisioning firewall rule: %s", name)
    if exists(
        cfg,
        f"Check firewall: {name}",
        gcloud(cfg, "compute", "firewall-rules", "describe", name),
    ):
        LOGGER.warning("SKIP: Firewall already exists: %s", name)
        return
    run_command(
        cfg,
        f"Create firewall: {name}",
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
    with concurrent.futures.ThreadPoolExecutor(max_workers=len(rules)) as pool:
        futures = {
            pool.submit(create_firewall, cfg, name, allow, *extra): name
            for name, allow, extra in rules
        }
        for f in concurrent.futures.as_completed(futures):
            f.result()
            LOGGER.success("OK: %s", futures[f])


def create_instance(cfg, name):
    if exists(
        cfg,
        f"Check VM: {name}",
        gcloud(cfg, "compute", "instances", "describe", name, "--zone", cfg["zone"]),
    ):
        LOGGER.warning("SKIP: VM already exists: %s", name)
        return
    image = (
        ["--image", cfg["image"]]
        if cfg["image"]
        else ["--image-family", cfg["image_family"]]
    )
    labels = f"delete-after-hours={cfg['delete_after_hours']},product={cfg['product']},owner={cfg['owner']}"
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
    LOGGER.info("Provisioning instances: %s", ", ".join(names))
    with concurrent.futures.ThreadPoolExecutor(max_workers=len(names)) as pool:
        futures = {pool.submit(create_instance, cfg, n): n for n in names}
        for f in concurrent.futures.as_completed(futures):
            f.result()
            LOGGER.success("OK: %s", futures[f])


def wait_for_ssh(cfg, name):
    for _ in range(60):
        if ssh(cfg, name, "echo ok", f"Check SSH: {name}", check=False).returncode == 0:
            LOGGER.success("OK: SSH ready: %s", name)
            return
        time.sleep(10)
    raise TimeoutError(f"SSH not ready: {name}")


def get_instance_ip(cfg, instance, fmt):
    return out(
        cfg,
        f"Get {instance} {fmt}",
        gcloud(
            cfg,
            "compute",
            "instances",
            "describe",
            instance,
            "--zone",
            cfg["zone"],
            f"--format=value({fmt})",
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


def discover_metallb_range(cfg):
    subnet_link = out(
        cfg,
        f"Get {cfg['master']} subnet",
        gcloud(
            cfg,
            "compute",
            "instances",
            "describe",
            cfg["master"],
            "--zone",
            cfg["zone"],
            "--format=value(networkInterfaces[0].subnetwork)",
        ),
    )
    subnet_name = subnet_link.rstrip("/").split("/")[-1]
    cidr = out(
        cfg,
        f"Get subnet CIDR: {subnet_name}",
        gcloud(
            cfg,
            "compute",
            "networks",
            "subnets",
            "describe",
            subnet_name,
            "--region",
            region_from_zone(cfg["zone"]),
            "--format=value(ipCidrRange)",
        ),
    )
    network = ipaddress.ip_network(cidr)
    used = {
        ipaddress.ip_address(v["internal"])
        for v in cfg["node_ips"].values()
        if v.get("internal")
    }
    candidates = [ip for ip in network.hosts() if ip not in used]
    if len(candidates) < 30:
        raise RuntimeError(f"Not enough IPs in {cidr} for MetalLB")
    selected = candidates[-30:-10]
    r = f"{selected[0]}-{selected[-1]}"
    LOGGER.info("Discovered MetalLB range: %s", r)
    return r


def ensure_metallb_range(cfg):
    if not cfg.get("metallb_range"):
        cfg["metallb_range"] = discover_metallb_range(cfg)


# ── RKE2 install ──────────────────────────────────────────────────────────────


def install_env(cfg, kind=None):
    parts = []
    if cfg["rke2_channel"]:
        parts.append(f"INSTALL_RKE2_CHANNEL={cfg['rke2_channel']}")
    if cfg["rke2_version"]:
        parts.append(f"INSTALL_RKE2_VERSION={cfg['rke2_version']}")
    if kind:
        parts.append(f"INSTALL_RKE2_TYPE={kind}")
    return " ".join(parts)


def install_server(cfg):
    LOGGER.info("Installing RKE2 server: %s", cfg["master"])
    rke2_cfg = template(
        "rke2-server-config.yaml.tmpl",
        master_external_ip=cfg["master_external_ip"],
        master_internal_ip=cfg["master_internal_ip"],
        hostname=cfg["hostname"],
    )
    config_file = upload_content(
        cfg, cfg["master"], "rke2-server-config.yaml", rke2_cfg
    )
    firewalld = upload_asset(cfg, cfg["master"], "firewalld-rke2-base.sh")
    run_remote_script(
        cfg,
        cfg["master"],
        "install-rke2-server.sh",
        "Install RKE2 server",
        "--config-file",
        config_file,
        "--firewalld-script",
        firewalld,
        "--install-env",
        install_env(cfg),
        "--remote-kubeconfig",
        REMOTE_KUBECONFIG,
    )


def ensure_server_installed(cfg):
    proc = ssh(
        cfg,
        cfg["master"],
        "sudo systemctl cat rke2-server >/dev/null 2>&1",
        "Check RKE2 unit",
        check=False,
    )
    if proc.returncode != 0:
        LOGGER.warning("RKE2 server unit missing; reinstalling")
        install_server(cfg)


def read_token(cfg):
    cfg["token"] = run_remote_script(
        cfg, cfg["master"], "read-rke2-token.sh", "Read RKE2 token"
    ).stdout.strip()
    if not cfg["token"]:
        raise RuntimeError("Empty RKE2 token")


def join_worker(cfg, worker):
    LOGGER.info("Joining worker: %s", worker)
    rke2_cfg = template(
        "rke2-agent-config.yaml.tmpl",
        master_internal_ip=cfg["master_internal_ip"],
        token=cfg["token"],
    )
    config_file = upload_content(
        cfg, worker, f"{worker}-rke2-agent-config.yaml", rke2_cfg
    )
    firewalld = upload_asset(cfg, worker, "firewalld-rke2-base.sh")
    run_remote_script(
        cfg,
        worker,
        "install-rke2-agent.sh",
        f"Join worker: {worker}",
        "--config-file",
        config_file,
        "--firewalld-script",
        firewalld,
        "--install-env",
        install_env(cfg, "agent"),
    )


def join_workers(cfg):
    if not cfg["workers"]:
        return
    with concurrent.futures.ThreadPoolExecutor(max_workers=len(cfg["workers"])) as pool:
        futures = {pool.submit(join_worker, cfg, w): w for w in cfg["workers"]}
        for f in concurrent.futures.as_completed(futures):
            f.result()
            LOGGER.success("OK: %s", futures[f])


def configure_node_firewalld_cni(cfg, node):
    remote = upload_asset(cfg, node, "firewalld-rke2-cni.sh")
    return ssh(
        cfg,
        node,
        f"sudo bash {shlex.quote(remote)}",
        f"Configure CNI: {node}",
        check=True,
    )


def configure_cluster_firewalld_cni(cfg):
    nodes = [cfg["master"], *cfg["workers"]]
    with concurrent.futures.ThreadPoolExecutor(max_workers=len(nodes)) as pool:
        futures = {pool.submit(configure_node_firewalld_cni, cfg, n): n for n in nodes}
        for f in concurrent.futures.as_completed(futures):
            f.result()
            LOGGER.success("OK: Configure CNI: %s", futures[f])


# ── Kubeconfig ────────────────────────────────────────────────────────────────


def fetch_kubeconfig(cfg):
    run_remote_script(
        cfg,
        cfg["master"],
        "prepare-kubeconfig.sh",
        "Prepare kubeconfig",
        "--master-external-ip",
        cfg["master_external_ip"],
        "--remote-kubeconfig",
        REMOTE_KUBECONFIG,
        "--output-file",
        "/tmp/rke2.yaml",
    )
    scp(
        cfg,
        "/tmp/rke2.yaml",
        cfg["kubeconfig"],
        cfg["master"],
        "Download kubeconfig",
        direction="from",
    )
    cfg["kubeconfig"].chmod(stat.S_IRUSR | stat.S_IWUSR)


def normalize_kubeconfig(cfg):
    ctx = cfg["prefix"]
    data = json.loads(
        run_command(
            cfg,
            "Read kubeconfig",
            ["kubectl", "config", "view", "--raw", "-o", "json"],
            env=kube_env(cfg),
            log_output=False,
        ).stdout
    )
    data["current-context"] = ctx
    for cluster in data.get("clusters", []):
        cluster["name"] = ctx
        cluster["cluster"]["server"] = f"https://{cfg['master_external_ip']}:6443"
    for user in data.get("users", []):
        user["name"] = ctx
    for c in data.get("contexts", []):
        c["name"] = ctx
        c["context"]["cluster"] = ctx
        c["context"]["user"] = ctx
    cfg["kubeconfig"].write_text(json.dumps(data, indent=2) + "\n")
    cfg["kubeconfig"].chmod(stat.S_IRUSR | stat.S_IWUSR)


def merge_kubeconfig(cfg):
    normalize_kubeconfig(cfg)
    local = cfg["local_kubeconfig"]
    local.parent.mkdir(parents=True, exist_ok=True)
    env = os.environ.copy()
    env["KUBECONFIG"] = (
        f"{cfg['kubeconfig']}{os.pathsep}{local}"
        if local.exists()
        else str(cfg["kubeconfig"])
    )
    if local.exists():
        merged = run_command(
            cfg,
            "Flatten kubeconfig",
            ["kubectl", "config", "view", "--flatten"],
            env=env,
        ).stdout
        tmp = local.with_suffix(local.suffix + ".tmp")
        tmp.write_text(merged)
        tmp.replace(local)
    else:
        shutil.copyfile(cfg["kubeconfig"], local)
    local.chmod(stat.S_IRUSR | stat.S_IWUSR)
    env["KUBECONFIG"] = str(local)
    run_command(
        cfg,
        f"Use context: {cfg['prefix']}",
        ["kubectl", "config", "use-context", cfg["prefix"]],
        env=env,
    )


def node_ready(node):
    return any(
        c.get("type") == "Ready" and c.get("status") == "True"
        for c in node.get("status", {}).get("conditions", [])
    )


def wait_nodes(cfg):
    fetch_kubeconfig(cfg)
    if cfg["save_kubeconfig"]:
        merge_kubeconfig(cfg)
    expected = 1 + len(cfg["workers"])
    for _ in range(60):
        proc = run_command(
            cfg,
            "List nodes",
            ["kubectl", "get", "nodes", "-o", "json"],
            env=kube_env(cfg),
            check=False,
            log_output=False,
        )
        if proc.returncode == 0:
            nodes = json.loads(proc.stdout).get("items", [])
            ready = sum(1 for n in nodes if node_ready(n))
            LOGGER.info("Nodes Ready: %s/%s", ready, expected)
            if ready == expected:
                return
        time.sleep(10)
    raise TimeoutError(f"Timed out waiting for {expected} Ready nodes")


# ── Helm install (local) ──────────────────────────────────────────────────────


def helm_add_repos(cfg):
    repos = [
        ("jetstack", "https://charts.jetstack.io"),
        ("longhorn", "https://charts.longhorn.io"),
        ("metallb", "https://metallb.github.io/metallb"),
        ("rancher-stable", "https://releases.rancher.com/server-charts/stable"),
    ]
    for name, url in repos:
        run_command(
            cfg,
            f"Helm repo add: {name}",
            ["helm", "repo", "add", "--force-update", name, url],
            env=kube_env(cfg),
        )
    run_command(cfg, "Helm repo update", ["helm", "repo", "update"], env=kube_env(cfg))


def helm_install(cfg, step):
    version_args = ["--version", step["version"]] if step["version"] != "latest" else []
    cmd = [
        "helm",
        "upgrade",
        "--install",
        step["release"],
        step["chart"],
        "--namespace",
        step["namespace"],
        "--create-namespace",
        *version_args,
        *step.get("extra_args", []),
        "--wait",
    ]
    return run_command(
        cfg, f"Helm install: {step['name']}", cmd, env=kube_env(cfg), check=False
    )


def helm_uninstall(cfg, step):
    run_command(
        cfg,
        f"Helm uninstall: {step['name']}",
        [
            "helm",
            "uninstall",
            step["release"],
            "--namespace",
            step["namespace"],
            "--ignore-not-found",
        ],
        env=kube_env(cfg),
        check=False,
    )


def install_helm_step(cfg, step, done, total):
    label = f"{step['chart']}@{step['version']}"
    for attempt in range(1, 3):
        LOGGER.info("Installing (%s/%s): %s attempt %s/2", done, total, label, attempt)
        proc = helm_install(cfg, step)
        if proc.returncode == 0:
            LOGGER.success("OK: (%s/%s): %s", done, total, label)
            return
        if attempt == 1:
            LOGGER.warning("Retrying %s after uninstall", label)
            helm_uninstall(cfg, step)
            time.sleep(10)
    LOGGER.error("Install failed: %s", label)
    raise subprocess.CalledProcessError(
        proc.returncode, proc.args, proc.stdout, proc.stderr
    )


def wait_metallb_crds(cfg):
    crds = ["ipaddresspools.metallb.io", "l2advertisements.metallb.io"]
    for _ in range(30):
        proc = run_command(
            cfg,
            "Check MetalLB CRDs",
            ["kubectl", "get", "crd", *crds],
            env=kube_env(cfg),
            check=False,
            log_output=False,
        )
        if proc.returncode == 0:
            LOGGER.info("MetalLB CRDs ready")
            return
        time.sleep(5)
    raise TimeoutError("MetalLB CRDs not ready after 150s")


def configure_metallb(cfg):
    ensure_metallb_range(cfg)
    local = cfg["workdir"] / "metallb-config.yaml"
    local.write_text(
        template(
            "metallb-config.yaml.tmpl", METALLB_RANGE=cfg["metallb_range"]
        ).rstrip()
        + "\n"
    )
    run_command(
        cfg,
        "Configure MetalLB",
        ["kubectl", "apply", "--server-side", "--force-conflicts", "-f", str(local)],
        env=kube_env(cfg),
    )

def configure_default_storage_class(cfg):
    local = FILES / "default-storage-class.sh"

    run_command(
        cfg,
        "Configure default StorageClass",
        [
            "bash",
            str(local),
            "longhorn",
        ],
        env=kube_env(cfg),
    )

def install_rancher(cfg):
    cert_ver = normalize_helm_version(cfg.get("cert_manager_version"))
    rancher_ver = normalize_helm_version(cfg.get("rancher_version"))
    longhorn_ver = normalize_helm_version(cfg.get("longhorn_version"))
    metallb_ver = normalize_helm_version(cfg.get("metallb_version"))

    steps = [
        {
            "name": "metallb",
            "chart": "metallb/metallb",
            "version": metallb_ver,
            "release": "metallb",
            "namespace": "metallb-system",
            "extra_args": [],
        },
        {
            "name": "cert-manager",
            "chart": "jetstack/cert-manager",
            "version": cert_ver,
            "release": "cert-manager",
            "namespace": "cert-manager",
            "extra_args": ["--set", "crds.enabled=true"],
        },
        {
            "name": "longhorn",
            "chart": "longhorn/longhorn",
            "version": longhorn_ver,
            "release": "longhorn",
            "namespace": "longhorn-system",
            "extra_args": [],
        },
        {
            "name": "rancher",
            "chart": "rancher-stable/rancher",
            "version": rancher_ver,
            "release": "rancher",
            "namespace": "cattle-system",
            "extra_args": [
                "--set",
                f"hostname={cfg['hostname']}",
                "--set",
                f"bootstrapPassword={cfg['admin_password']}",
            ],
        },
    ]

    charts = ", ".join(f"{s['chart']}@{s['version']}" for s in steps)
    LOGGER.info("Installing Rancher charts: %s", charts)

    helm_add_repos(cfg)

    for done, step in enumerate(steps, 1):
        install_helm_step(cfg, step, done, len(steps))
        if step["name"] == "metallb":
            wait_metallb_crds(cfg)
            configure_metallb(cfg)
        if step["name"] == "longhorn":
            configure_default_storage_class(cfg)

    LOGGER.success("OK: Rancher stack installed")


# ── Output ────────────────────────────────────────────────────────────────────


def write_output(cfg):
    kubeconfig = (
        cfg["local_kubeconfig"] if cfg["save_kubeconfig"] else cfg["kubeconfig"]
    )
    lines = {
        "RANCHER_URL": f"https://{cfg['hostname']}",
        "RANCHER_USER": "admin",
        "RANCHER_PASSWORD": cfg["admin_password"],
        "PROJECT_ID": cfg["project_id"],
        "ZONE": cfg["zone"],
        "MASTER": cfg["master"],
        "MASTER_EXTERNAL_IP": cfg["master_external_ip"],
        "MASTER_INTERNAL_IP": cfg["master_internal_ip"],
        "METALLB_RANGE": cfg.get("metallb_range", ""),
        "SSH_KEY_FILE": cfg["ssh_key"],
        "KUBECONFIG": kubeconfig,
    }
    for node, ips in cfg["node_ips"].items():
        prefix = node.upper().replace("-", "_")
        lines[f"{prefix}_INTERNAL_IP"] = ips["internal"]
        lines[f"{prefix}_EXTERNAL_IP"] = ips["external"]
    cfg["workdir"].mkdir(parents=True, exist_ok=True)
    cfg["output"].write_text("\n".join(f"{k}={v}" for k, v in lines.items()) + "\n")
    cfg["output"].chmod(stat.S_IRUSR | stat.S_IWUSR)


def summary(cfg):
    kubeconfig = (
        cfg["local_kubeconfig"] if cfg["save_kubeconfig"] else cfg["kubeconfig"]
    )
    nodes = "\n".join(
        f"  {n}: internal={ips['internal']} external={ips['external']}"
        for n, ips in cfg["node_ips"].items()
    )
    ssh_cmds = "\n".join(
        f"  gcloud compute ssh {cfg['ssh_user']}@{n} --zone {cfg['zone']} --ssh-key-file {cfg['ssh_key']}"
        for n in [cfg["master"], *cfg["workers"]]
    )
    LOGGER.info(
        "Cluster created:\n\n"
        f"Rancher URL: https://{cfg['hostname']}\n\n"
        f"Credentials:\n  RANCHER_USER=admin\n  RANCHER_PASSWORD={cfg['admin_password']}\n\n"
        f"Files:\n  OUTPUT_FILE={cfg['output']}\n  SSH_KEY_FILE={cfg['ssh_key']}\n  KUBECONFIG={kubeconfig}\n\n"
        f"Nodes:\n{nodes}\n\nSSH:\n{ssh_cmds}"
    )


# ── Main ──────────────────────────────────────────────────────────────────────


def main():
    cfg = make_config(parse_args())
    setup_stdout_logging(LOGGER, level=cfg["log_level"])
    try:
        validate(cfg)
        ensure_ssh_key(cfg)

        LOGGER.info("Provisioning firewall rules")
        create_firewalls(cfg)

        LOGGER.info("Provisioning instances")
        create_instances(cfg)

        get_node_ips(cfg)
        ensure_metallb_range(cfg)

        LOGGER.info("Waiting for SSH on master: %s", cfg["master"])
        wait_for_ssh(cfg, cfg["master"])

        install_server(cfg)
        ensure_server_installed(cfg)
        read_token(cfg)

        for worker in cfg["workers"]:
            LOGGER.info("Waiting for SSH on worker: %s", worker)
            wait_for_ssh(cfg, worker)

        LOGGER.info("Joining workers: %s", ", ".join(cfg["workers"]))
        join_workers(cfg)

        LOGGER.info("Waiting for all nodes to be Ready")
        wait_nodes(cfg)

        LOGGER.info("Installing NFS client on all nodes")
        install_nfs_clients(cfg)

        LOGGER.info("Configuring firewalld CNI on all nodes")
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
