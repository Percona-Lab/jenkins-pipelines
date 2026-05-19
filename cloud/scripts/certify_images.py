import argparse
import subprocess
import json
import os
import sys
import shutil
from datetime import datetime, timezone
from xml.etree import ElementTree as ET


def log(msg):
    now = datetime.now(timezone.utc).strftime("%H:%M:%S")
    print(f"[{now}] {msg}", flush=True)


def hide_secrets(cmd):
    return [
        "--pyxis-api-token=****" if arg.startswith("--pyxis-api-token=") else arg
        for arg in cmd
    ]


def run_cmd(
    cmd,
    cwd=None,
    env=None,
    quiet=False,
    input=None,
    check=True,
    capture_output=False,
    return_result=False,
):
    if not quiet:
        log(f"$ {' '.join(hide_secrets(cmd))}")

    stdout = (
        subprocess.PIPE if capture_output else subprocess.DEVNULL if quiet else None
    )
    stderr = subprocess.PIPE if quiet or capture_output else None

    result = subprocess.run(
        cmd,
        cwd=cwd,
        env=env,
        input=input,
        stdout=stdout,
        stderr=stderr,
        text=True,
    )

    if result.returncode == 0 or not check:
        if return_result:
            return result
        return result.stdout if capture_output else None

    if quiet and result.stderr:
        log(result.stderr.strip())

    log(f"failed ({result.returncode})")
    sys.exit(result.returncode)


def ensure_go():
    tools = ".tools"
    go_binary = os.path.abspath(os.path.join(tools, "go", "bin", "go"))
    if os.path.exists(go_binary):
        return go_binary

    log("installing latest go")
    goos, goarch = detect_platform()
    version = json.loads(
        subprocess.check_output(
            ["curl", "-sL", "https://go.dev/dl/?mode=json"],
            text=True,
        )
    )[0]["version"]
    archive = f"{version}.{goos}-{goarch}.tar.gz"
    archive_path = os.path.join(tools, archive)

    os.makedirs(tools, exist_ok=True)
    run_cmd(
        ["curl", "-sL", "-o", archive_path, f"https://go.dev/dl/{archive}"],
        quiet=True,
    )
    run_cmd(["tar", "-C", tools, "-xzf", archive_path], quiet=True)

    log(f"go ready: {go_binary}")
    return go_binary


def docker_login(user, key):
    run_cmd(
        ["docker", "login", "-u", user, "--password-stdin", "quay.io"],
        input=key,
        quiet=False,
    )


def cleanup_docker_image(image):
    run_cmd(["docker", "rmi", "-f", image], quiet=True, check=False)
    run_cmd(["docker", "image", "prune", "-f"], quiet=True, check=False)


def prepare_single_platform_image(image, dest, platform):
    run_cmd(["docker", "pull", "--platform", f"linux/{platform}", image])
    run_cmd(["docker", "tag", image, dest])
    run_cmd(["docker", "push", dest])


def prepare_multiplatform_image(image, dest):
    for platform in ["amd64", "arm64"]:
        run_cmd(["docker", "pull", "--platform", f"linux/{platform}", image])

    run_cmd(["docker", "buildx", "imagetools", "create", "-t", dest, image])


def prepare_image(image, dest, platform):
    log("prepare image")

    cleanup_docker_image(image)
    cleanup_docker_image(dest)

    if platform == "multiplatform":
        prepare_multiplatform_image(image, dest)
        return

    prepare_single_platform_image(image, dest, platform)


def detect_platform():
    goos = "darwin" if sys.platform == "darwin" else "linux"
    arch = os.uname().machine
    goarch = "arm64" if arch in ["arm64", "aarch64"] else "amd64"
    return goos, goarch


def download_preflight_source():
    repo = "openshift-preflight"
    shutil.rmtree(repo, ignore_errors=True)
    run_cmd(
        [
            "git",
            "clone",
            "-q",
            "https://github.com/redhat-openshift-ecosystem/openshift-preflight",
        ]
    )
    return repo


def checkout_latest_preflight_version(repo):
    tags = run_cmd(
        ["git", "tag", "--sort=-creatordate"],
        cwd=repo,
        capture_output=True,
    ).splitlines()
    if not tags:
        sys.exit("no tags found in repo")

    tag = tags[0]
    run_cmd(["git", "checkout", "-q", tag], cwd=repo)
    return tag


def current_git_commit(repo):
    return run_cmd(
        ["git", "rev-parse", "HEAD"],
        cwd=repo,
        capture_output=True,
    ).strip()


def build_preflight(repo, tag):
    goos, goarch = detect_platform()
    env = {
        **os.environ,
        "GOOS": goos,
        "GOARCH": goarch,
        "CGO_ENABLED": "0",
    }

    log("building preflight")
    run_cmd(
        [
            ensure_go(),
            "build",
            "-o",
            "../preflight",
            "-ldflags",
            f"-X github.com/redhat-openshift-ecosystem/openshift-preflight/version.commit={current_git_commit(repo)} "
            f"-X github.com/redhat-openshift-ecosystem/openshift-preflight/version.version={tag}",
            "cmd/preflight/main.go",
        ],
        cwd=repo,
        env=env,
        quiet=True,
    )


def install_preflight():
    preflight_path = os.path.abspath("preflight")
    if os.path.exists(preflight_path):
        log("preflight already installed")
        return preflight_path

    log("installing preflight")

    repo = download_preflight_source()
    tag = checkout_latest_preflight_version(repo)
    build_preflight(repo, tag)
    os.chmod(preflight_path, 0o755)
    shutil.rmtree(repo)

    log("preflight ready")
    return preflight_path


def result_dir_for(image):
    image_name = image.split("/")[-1].replace(":", "_")
    return os.path.join("preflight-results", image_name)


def result_name_for(image):
    return image.split("/")[-1].replace(":", "_")


def junit_image_name(image):
    return image.rsplit(":", 1)[-1]


def rename_result_json_files(results_dir, image):
    image_name = junit_image_name(image)

    for root, _, files in os.walk(results_dir):
        for file_name in files:
            if not file_name.endswith(".json") or file_name.startswith(
                f"{image_name}-"
            ):
                continue

            source = os.path.join(root, file_name)
            dest = os.path.join(root, f"{image_name}-{file_name}")
            os.replace(source, dest)


def write_junit_result(image, dest, platform, _component, status):
    os.makedirs("preflight-results", exist_ok=True)
    name = junit_image_name(dest)

    testsuite = ET.Element(
        "testsuite",
        {
            "name": "image-certification",
            "tests": "1",
            "failures": "0" if status == 0 else "1",
        },
    )

    testcase = ET.SubElement(
        testsuite,
        "testcase",
        {
            "classname": "image-certification",
            "name": name,
        },
    )

    if status != 0:
        failure = ET.SubElement(
            testcase, "failure", {"message": "Certification failed"}
        )
        failure.text = (
            f"Image: {image}\n"
            f"Destination: {dest}\n"
            f"Platform: {platform}\n"
            f"Exit code: {status}"
        )

    path = os.path.join("preflight-results", f"{name}.xml")
    ET.ElementTree(testsuite).write(path, encoding="utf-8", xml_declaration=True)

    log(f"junit result saved: {path}")


def run_preflight(dest, platform, docker_config, token, component):
    preflight = install_preflight()

    log("running preflight")

    results_dir = result_dir_for(dest)
    os.makedirs(results_dir, exist_ok=True)

    cmd = [
        preflight,
        "check",
        "container",
        dest,
        "" if platform == "multiplatform" else f"--platform={platform}",
        f"--artifacts={os.path.abspath(results_dir)}",
        f"--docker-config={docker_config}",
        f"--pyxis-api-token={token}",
        f"--certification-component-id={component}",
        "--loglevel",
        "debug",
        "--submit",
    ]
    cmd = [arg for arg in cmd if arg]

    result = run_cmd(
        cmd,
        capture_output=True,
        check=False,
        return_result=True,
    )

    output = (result.stdout or "") + (result.stderr or "")
    for line in output.splitlines():
        log(line)

    rename_result_json_files(results_dir, dest)

    if "Preflight result: FAILED" in output:
        log("preflight failed (output contains FAILED)")
        sys.exit(1)

    if result.returncode != 0:
        log(f"preflight failed ({result.returncode})")
        sys.exit(result.returncode)

    log(f"preflight artifacts saved: {results_dir}")


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
    parser.add_argument(
        "--platform", choices=["amd64", "arm64", "multiplatform"], required=True
    )

    # Can be set as environment variables
    parser.add_argument("--token")
    parser.add_argument("--registry-user")
    parser.add_argument("--registry-key")

    # By default gets from user home
    parser.add_argument(
        "--docker-config",
        default=os.path.expanduser("~/.docker/config.json"),
    )

    args = parser.parse_args()

    token = get_secret(args.token, "PYXIS_TOKEN")
    registry_user = get_secret(args.registry_user, "REGISTRY_USER")
    registry_key = get_secret(args.registry_key, "REGISTRY_KEY")

    if not registry_user or not registry_key or not token:
        raise ValueError("Registry credentials or PYXIS token not provided")

    log(f"Certifying {args.image} -> {args.dest_image}")

    log("start")

    try:
        docker_login(registry_user, registry_key)

        prepare_image(args.image, args.dest_image, args.platform)

        run_preflight(
            args.dest_image,
            args.platform,
            args.docker_config,
            token,
            args.component,
        )
    except SystemExit as e:
        status = e.code if isinstance(e.code, int) else 1
        write_junit_result(
            args.image,
            args.dest_image,
            args.platform,
            args.component,
            status,
        )
        raise

    write_junit_result(args.image, args.dest_image, args.platform, args.component, 0)

    log("done")


if __name__ == "__main__":
    main()
