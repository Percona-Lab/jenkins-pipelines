import argparse
import subprocess
import json
import os
import sys
import shutil
from datetime import datetime


def log(msg):
    now = datetime.utcnow().strftime("%H:%M:%S")
    print(f"[{now}] {msg}", flush=True)


def run(cmd, cwd=None, env=None, quiet=False):
    if not quiet:
        log(f"$ {' '.join(cmd)}")

    result = subprocess.run(
        cmd,
        cwd=cwd,
        env=env,
        stdout=subprocess.DEVNULL if quiet else None,
        stderr=subprocess.PIPE if quiet else None,
        text=True
    )

    if result.returncode != 0:
        if quiet and result.stderr:
            log(result.stderr.strip())
        log(f"failed ({result.returncode})")
        sys.exit(result.returncode)


def ensure_go():
    go_path = shutil.which("go")
    if go_path:
        return go_path

    log("go not found, installing")

    version = "1.22.5"
    goos = "darwin" if sys.platform == "darwin" else "linux"

    arch = os.uname().machine
    goarch = "arm64" if arch in ["arm64", "aarch64"] else "amd64"

    archive = f"go{version}.{goos}-{goarch}.tar.gz"
    url = f"https://go.dev/dl/{archive}"

    tools = ".tools"
    os.makedirs(tools, exist_ok=True)

    archive_path = os.path.join(tools, archive)

    run(["curl", "-sL", "-o", archive_path, url], quiet=True)
    run(["tar", "-C", tools, "-xzf", archive_path], quiet=True)

    go_bin_dir = os.path.abspath(os.path.join(tools, "go", "bin"))
    go_binary = os.path.join(go_bin_dir, "go")
    os.environ["PATH"] = f"{go_bin_dir}:{os.environ.get('PATH', '')}"

    log(f"go ready: {go_binary}")

    return go_binary


def docker_login(user, key):
    log("docker login quay.io")

    p = subprocess.Popen(
        ["docker", "login", "-u", user, "--password-stdin", "quay.io"],
        stdin=subprocess.PIPE,
        stdout=subprocess.DEVNULL,
        stderr=subprocess.PIPE,
        text=True,
    )

    _, err = p.communicate(input=key)

    if p.returncode != 0:
        log("login failed")
        log(err.strip())
        sys.exit(p.returncode)


def cleanup_image(image):
    subprocess.run(["docker", "rmi", "-f", image],
                   stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    subprocess.run(["docker", "image", "prune", "-f"],
                   stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)


def prepare_image(image, dest, platform):
    log("prepare image")

    cleanup_image(image)
    cleanup_image(dest)

    run(["docker", "pull", "--platform", f"linux/{platform}", image])
    run(["docker", "tag", image, dest])
    run(["docker", "push", dest])


def detect_platform():
    goos = "darwin" if sys.platform == "darwin" else "linux"
    arch = os.uname().machine
    goarch = "arm64" if arch in ["arm64", "aarch64"] else "amd64"
    return goos, goarch


def install_preflight():
    if shutil.which("preflight"):
        log("preflight already installed")
        return

    ensure_go()

    log("installing preflight")

    repo = "openshift-preflight"
    shutil.rmtree(repo, ignore_errors=True)

    run(["git", "clone", "-q", "https://github.com/redhat-openshift-ecosystem/openshift-preflight"])

    goos, goarch = detect_platform()

    tags = subprocess.check_output(
        ["git", "tag", "--sort=-creatordate"],
        cwd=repo,
        text=True
    ).splitlines()

    if not tags:
        log("no tags found in repo")
        sys.exit(1)

    tag = tags[0]

    run(["git", "checkout", "-q", tag], cwd=repo)

    version = subprocess.check_output(
        ["git", "rev-parse", "HEAD"],
        cwd=repo,
        text=True
    ).strip()

    env = os.environ.copy()
    env.update({
        "GOOS": goos,
        "GOARCH": goarch,
        "CGO_ENABLED": "0",
    })

    log("building preflight")

    run([
        "go", "build",
        "-o", "../preflight",
        "-ldflags",
        f"-X github.com/redhat-openshift-ecosystem/openshift-preflight/version.commit={version} "
        f"-X github.com/redhat-openshift-ecosystem/openshift-preflight/version.version={tag}",
        "cmd/preflight/main.go"
    ], cwd=repo, env=env, quiet=True)

    os.chmod("preflight", 0o755)
    shutil.rmtree(repo)

    log("preflight ready")


def is_already_published(output: str) -> bool:
    return "published image can't be updated" in output.lower()


def run_preflight(dest, platform, docker_config, token, component, skip_published):
    install_preflight()

    log("running preflight")

    cmd = [
        "./preflight",
        "check", "container", dest,
        f"--platform={platform}",
        f"--docker-config={docker_config}",
        f"--pyxis-api-token={token}",
        f"--certification-component-id={component}",
        "--loglevel", "debug",
        "--submit",
    ]

    result = subprocess.run(cmd)

    output = (result.stdout or "") + (result.stderr or "")
    if skip_published and is_already_published(output):
        log("image already published, skipping")
        return

    if result.returncode != 0:
        log(f"preflight failed ({result.returncode})")
        sys.exit(result.returncode)


def print_summary(data):
    fails = [r for r in data["results"] if r["result"] != "PASS"]

    log(f"results: {len(data['results'])} total / {len(fails)} failed")

    for f in fails:
        log(f"{f['name']} -> {f['result']}")

    if fails:
        sys.exit(1)


def get_secret(cli_value, env_name, required=True):
    value = cli_value or os.getenv(env_name)
    if required and not value:
        raise ValueError(f"{env_name} not set (via arg or env)")
    return value


def main():
    parser = argparse.ArgumentParser()

    parser.add_argument("--image", required=True)
    parser.add_argument("--dest_image", required=True)
    parser.add_argument("--component", required=True)
    parser.add_argument("--platform", required=True)
    parser.add_argument("--skip_published", default=False)

    # Can be set as environment variables
    parser.add_argument("--token")
    parser.add_argument("--registry-user")
    parser.add_argument("--registry-key")

    # By default gets from user home
    parser.add_argument("--docker-config", default=os.path.expanduser("~/.docker/config.json"))

    args = parser.parse_args()

    token = get_secret(args.token, "PYXIS_TOKEN")
    registry_user = get_secret(args.registry_user, "REGISTRY_USER")
    registry_key = get_secret(args.registry_key, "REGISTRY_KEY")

    if not registry_user or not registry_key or not token:
        raise ValueError("Registry credentials or PYXIS token not provided")

    log(f"Certifying {args.image} -> {args.dest_image}")

    log("start")

    docker_login(registry_user, registry_key)

    prepare_image(args.image, args.dest_image, args.platform)

    run_preflight(
        args.dest_image,
        args.platform,
        args.docker_config,
        token,
        args.component,
        args.skip_published,
    )

    log("done")


if __name__ == "__main__":
    main()
