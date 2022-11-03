#!/usr/bin/env python
import argparse
import subprocess
import os

MAX_LIVETIME = 1
PMM_TAG = 'jenkins-pmm'


def parse_args():
    parser = argparse.ArgumentParser()
    parser.add_argument('-v', '--version', help="Expected PMM version")
    parser.add_argument('-p', '--pre_post', help="Pass 'pre' from pre-upgrade stage and pass 'post' for post-upgrade "
                                                 "stage", default='post')
    parser.add_argument('-e', '--env', help="Pass 'ami' if it is AMI env, otherwise leave empty", default='not_ami')

    args = parser.parse_args()

    return args


def verify_command(command):
    with open("/tmp/output.log", "a") as output:
        error_code = subprocess.call(command, shell=True, stdout=output, stderr=output)
        if error_code != 0:
            output = subprocess.getoutput(command.split("|")[0])
            assert error_code == 0, f"expected to run {command} without errors. \nOutput: {output}"


def main():
    args = parse_args()

    if args.env in "ami":
        verify_command('rpm -qa | grep percona-qan-api2-' + args.version)
        verify_command('rpm -qa | grep percona-dashboards-' + args.version)
        if args.version == "2.25.0":
            verify_command('rpm -qa | grep pmm-update-' + args.version)

        verify_command('rpm -qa | grep pmm-managed-' + args.version)
        verify_command('rpm -qa | grep pmm2-client-' + args.version)
        verify_command('sudo supervisorctl status | grep qan-api2 | grep RUNNING')
        verify_command('sudo supervisorctl status | grep alertmanager | grep RUNNING')
        verify_command('sudo supervisorctl status | grep clickhouse | grep RUNNING')
        verify_command('sudo supervisorctl status | grep grafana | grep RUNNING')
        verify_command('sudo supervisorctl status | grep nginx | grep RUNNING')
        verify_command('sudo supervisorctl status | grep pmm-agent | grep RUNNING')
        verify_command('sudo supervisorctl status | grep pmm-managed | grep RUNNING')
        verify_command('sudo supervisorctl status | grep postgresql | grep RUNNING')

        if args.pre_post == "post":
            verify_command('rpm -qa | grep dbaas-controller-' + args.version)
            verify_command('rpm -qa | grep pmm-dump-' + args.version)
            verify_command('sudo supervisorctl status | grep victoriametrics | grep RUNNING')
            verify_command('sudo supervisorctl status | grep vmalert | grep RUNNING')
            verify_command('grafana-cli plugins ls | grep "vertamedia-clickhouse-datasource @ 2.4.4"')
            verify_command('grafana-cli plugins ls | grep alexanderzobnin-zabbix-app')
            verify_command('sudo victoriametrics --version | grep victoria-metrics-20220620-144706-pmm-6401-v1.77.1')
    else:
        pmm_server_docker_container = subprocess.getoutput("docker ps --format \"table {{.ID}}\t{{.Image}}\t{{"
                                                           ".Names}}\" | grep 'pmm-server' | awk '{print $3}'")
        print(f"PMM Server container name is {pmm_server_docker_container}, verification for {args.pre_post}")

        verify_command(f"docker exec {pmm_server_docker_container} rpm -qa | grep percona-qan-api2-{args.version}")
        verify_command(
            f"docker exec {pmm_server_docker_container} rpm -qa | grep percona-dashboards-{args.version}")
        if args.version != "2.25.0":
            verify_command(f"docker exec {pmm_server_docker_container} rpm -qa | grep pmm-update-{args.version}")
        verify_command(f"docker exec {pmm_server_docker_container} rpm -qa | grep pmm-managed-{args.version}")
        verify_command(f"docker exec {pmm_server_docker_container} rpm -qa | grep pmm2-client-{args.version}")
        verify_command(f"docker exec {pmm_server_docker_container} supervisorctl status | grep qan-api2 | grep "
                       f"RUNNING")
        verify_command(f"docker exec {pmm_server_docker_container} supervisorctl status | grep alertmanager | grep "
                       f"RUNNING")
        verify_command(f"docker exec {pmm_server_docker_container} supervisorctl status | grep clickhouse | grep "
                       f"RUNNING")
        verify_command(f"docker exec {pmm_server_docker_container} supervisorctl status | grep grafana | grep "
                       f"RUNNING")
        verify_command(f"docker exec {pmm_server_docker_container} supervisorctl status | grep nginx | grep "
                       f"RUNNING")
        verify_command(f"docker exec {pmm_server_docker_container} supervisorctl status | grep pmm-agent | grep "
                       f"RUNNING")
        verify_command(f"docker exec {pmm_server_docker_container} supervisorctl status | grep pmm-managed | grep "
                       f"RUNNING")
        verify_command(f"docker exec {pmm_server_docker_container} supervisorctl status | grep postgresql | grep "
                       f"RUNNING")
        if args.pre_post == "post":
            verify_command(
                f"docker exec {pmm_server_docker_container} rpm -qa | grep dbaas-controller-{args.version}")
            verify_command(
                f"docker exec {pmm_server_docker_container} rpm -qa | grep pmm-dump-{args.version}")
            verify_command(
                f"docker exec {pmm_server_docker_container} supervisorctl status | grep victoriametrics | grep "
                f"RUNNING")
            verify_command(
                f"docker exec {pmm_server_docker_container} supervisorctl status | grep vmalert | grep "
                f"RUNNING")
            verify_command(
                f"docker exec {pmm_server_docker_container} victoriametrics --version | grep victoria-metrics-20221031-101137-pmm-6401-v1.82.1")

            docker_version = os.getenv("DOCKER_VERSION")
            do_docker_way = os.getenv("PERFORM_DOCKER_WAY_UPGRADE")
            pmm_minor_v = docker_version.split('.')[1]

            if (do_docker_way == "yes" and int(pmm_minor_v) > 22) or (do_docker_way != "yes"):
                verify_command(
                    f"docker exec -e GF_PLUGIN_DIR=/srv/grafana/plugins/ {pmm_server_docker_container} grafana"
                    f"-cli plugins ls | grep alexanderzobnin-zabbix-app")

            verify_command(
                f"docker exec -e GF_PLUGIN_DIR=/srv/grafana/plugins/ {pmm_server_docker_container} grafana"
                f"-cli plugins ls | grep \"vertamedia-clickhouse-datasource @ 2.4.4\"")
            print(f"Post upgrade verification complete! for {args.version}")


if __name__ == "__main__":
    main()
