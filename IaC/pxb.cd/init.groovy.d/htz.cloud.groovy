import cloud.dnation.jenkins.plugins.hetzner.*
import cloud.dnation.jenkins.plugins.hetzner.launcher.*
import hudson.model.*
import jenkins.model.Jenkins
import java.util.logging.Logger

def cloudName = "pxb-htz"

imageMap = [:]                          // ID          TYPE     NAME                 DESCRIPTION          ARCHITECTURE   IMAGE SIZE   DISK SIZE   CREATED                         DEPRECATED
imageMap['deb12-x64']     = '114690387' // 114690387   system   debian-12            Debian 12            x86            -            5 GB        Tue Jun 13 09:00:02 EEST 2023   -
imageMap['deb12-aarch64'] = '114690389' // 114690389   system   debian-12            Debian 12            arm            -            5 GB        Tue Jun 13 09:00:03 EEST 2023   -
imageMap['launcher-x64']  = imageMap['deb12-x64']

execMap = [:]
execMap['deb']                = 1
execMap['deb12-x64-nbg1']     = execMap['deb']
execMap['deb12-x64-hel1']     = execMap['deb']
execMap['deb12-x64-fsn1']     = execMap['deb']
execMap['deb12-aarch64-nbg1'] = execMap['deb']
execMap['deb12-aarch64-hel1'] = execMap['deb']
execMap['deb12-aarch64-fsn1'] = execMap['deb']
execMap['deb12-x64-nbg1-min']     = execMap['deb']
execMap['deb12-x64-hel1-min']     = execMap['deb']
execMap['deb12-x64-fsn1-min']     = execMap['deb']
execMap['deb12-aarch64-nbg1-min'] = execMap['deb']
execMap['deb12-aarch64-hel1-min'] = execMap['deb']
execMap['deb12-aarch64-fsn1-min'] = execMap['deb']
execMap['launcher-x64-nbg1']  = 30
execMap['launcher-x64-hel1']  = 30
execMap['launcher-x64-fsn1']  = 30

bootDeadlineMap =[:]
bootDeadlineMap['default']            = 3
bootDeadlineMap['deb12-x64-nbg1']     = bootDeadlineMap['default']
bootDeadlineMap['deb12-x64-hel1']     = bootDeadlineMap['default']
bootDeadlineMap['deb12-x64-fsn1']     = bootDeadlineMap['default']
bootDeadlineMap['deb12-aarch64-nbg1'] = bootDeadlineMap['default']
bootDeadlineMap['deb12-aarch64-hel1'] = bootDeadlineMap['default']
bootDeadlineMap['deb12-aarch64-fsn1'] = bootDeadlineMap['default']
bootDeadlineMap['deb12-x64-nbg1-min']     = bootDeadlineMap['default']
bootDeadlineMap['deb12-x64-hel1-min']     = bootDeadlineMap['default']
bootDeadlineMap['deb12-x64-fsn1-min']     = bootDeadlineMap['default']
bootDeadlineMap['deb12-aarch64-nbg1-min'] = bootDeadlineMap['default']
bootDeadlineMap['deb12-aarch64-hel1-min'] = bootDeadlineMap['default']
bootDeadlineMap['deb12-aarch64-fsn1-min'] = bootDeadlineMap['default']
bootDeadlineMap['launcher-x64-nbg1']  = bootDeadlineMap['default']
bootDeadlineMap['launcher-x64-hel1']  = bootDeadlineMap['default']
bootDeadlineMap['launcher-x64-fsn1']  = bootDeadlineMap['default']

jvmOptsMap = [:]
jvmOptsMap['deb12']         = '-Xms4g -Xmx16g -Xss4m -XX:+UseG1GC -XX:+ParallelRefProcEnabled -XX:+AlwaysPreTouch --add-opens=java.base/java.lang=ALL-UNNAMED --add-opens=java.base/java.lang.reflect=ALL-UNNAMED'
jvmOptsMap['deb12-x64-nbg1']     = jvmOptsMap['deb12']
jvmOptsMap['deb12-x64-hel1']     = jvmOptsMap['deb12']
jvmOptsMap['deb12-x64-fsn1']     = jvmOptsMap['deb12']
jvmOptsMap['deb12-aarch64-nbg1'] = jvmOptsMap['deb12']
jvmOptsMap['deb12-aarch64-hel1'] = jvmOptsMap['deb12']
jvmOptsMap['deb12-aarch64-fsn1'] = jvmOptsMap['deb12']
jvmOptsMap['deb12-x64-nbg1-min']     = jvmOptsMap['deb12']
jvmOptsMap['deb12-x64-hel1-min']     = jvmOptsMap['deb12']
jvmOptsMap['deb12-x64-fsn1-min']     = jvmOptsMap['deb12']
jvmOptsMap['deb12-aarch64-nbg1-min'] = jvmOptsMap['deb12']
jvmOptsMap['deb12-aarch64-hel1-min'] = jvmOptsMap['deb12']
jvmOptsMap['deb12-aarch64-fsn1-min'] = jvmOptsMap['deb12']
jvmOptsMap['launcher-x64-nbg1']  = jvmOptsMap['deb12']
jvmOptsMap['launcher-x64-hel1']  = jvmOptsMap['deb12']
jvmOptsMap['launcher-x64-fsn1']  = jvmOptsMap['deb12']

labelMap = [:]
labelMap['deb12-x64-min']     = 'docker-x64-min docker-deb12-x64-min deb12-x64-min'
labelMap['deb12-aarch64-min'] = 'docker-aarch64-min docker-deb12-aarch64-min deb12-aarch64-min'
labelMap['deb12-x64']         = 'docker-x64 docker-deb12-x64 deb12-x64'
labelMap['deb12-aarch64']     = 'docker-aarch64 docker-deb12-aarch64 deb12-aarch64'
labelMap['launcher-x64']      = 'launcher-x64'

networkMap = [:]
networkMap['percona-vpc-eu'] = '10442325' // percona-vpc-eu

initMap = [:]
initMap['deb-docker'] = '''#!/bin/bash -x
    set -o xtrace
    sudo fallocate -l 32G /swapfile
    sudo chmod 600 /swapfile
    sudo mkswap /swapfile
    sudo swapon /swapfile

    export DEBIAN_FRONTEND=noninteractive
    until sudo apt-get update; do
        sleep 1
        echo try again
    done
    until sudo apt-get -y install openjdk-17-jre-headless apt-transport-https ca-certificates curl gnupg lsb-release unzip git; do
        sleep 1
        echo try again
    done
    curl -fsSL https://download.docker.com/linux/debian/gpg | sudo gpg --dearmor -o /usr/share/keyrings/docker-archive-keyring.gpg
    echo "deb [arch=$(dpkg --print-architecture) signed-by=/usr/share/keyrings/docker-archive-keyring.gpg] https://download.docker.com/linux/debian $(lsb_release -cs) stable" | sudo tee /etc/apt/sources.list.d/docker.list > /dev/null
    until sudo apt-get update; do
        sleep 1
        echo try again
    done
    until sudo apt-get -y install docker-ce docker-ce-cli containerd.io; do
        sleep 1
        echo try again
    done
    if ! $(aws --version | grep -q 'aws-cli/2'); then
        find /tmp -maxdepth 1 -name "*aws*" | xargs sudo rm -rf
        until curl "https://awscli.amazonaws.com/awscli-exe-linux-$(uname -m)-2.22.35.zip" -o "/tmp/awscliv2.zip"; do
            sleep 1
            echo try again
        done
        unzip -o /tmp/awscliv2.zip -d /tmp
        cd /tmp/aws && sudo ./install
    fi
    sudo install -o $(id -u -n) -g $(id -g -n) -d /mnt/jenkins
    sudo sysctl net.ipv4.tcp_fin_timeout=15
    sudo sysctl net.ipv4.tcp_tw_reuse=1
    sudo sysctl net.ipv6.conf.all.disable_ipv6=1
    sudo sysctl net.ipv6.conf.default.disable_ipv6=1
    sudo sysctl -w fs.inotify.max_user_watches=10000000 || true
    sudo sysctl -w fs.aio-max-nr=1048576 || true
    sudo sysctl -w fs.file-max=6815744 || true
    echo "*  soft  core  unlimited" | sudo tee -a /etc/security/limits.conf
    sudo sed -i.bak -e 's^ExecStart=.*^ExecStart=/usr/bin/dockerd --data-root=/mnt/docker --default-ulimit nofile=900000:900000^' /lib/systemd/system/docker.service
    sudo systemctl daemon-reload
    sudo install -o root -g root -d /mnt/docker
    sudo usermod -aG docker $(id -u -n)
    sudo mkdir -p /etc/docker
    echo '{"experimental": true, "ipv6": true, "fixed-cidr-v6": "fd3c:a8b0:18eb:5c06::/64"}' | sudo tee /etc/docker/daemon.json
    sudo systemctl restart docker
    echo "* * * * * root /usr/sbin/route add default gw 10.177.1.1 eth0" | sudo tee /etc/cron.d/fix-default-route
'''
initMap['deb12-x64-nbg1']     = initMap['deb-docker']
initMap['deb12-x64-hel1']     = initMap['deb-docker']
initMap['deb12-x64-fsn1']     = initMap['deb-docker']
initMap['deb12-aarch64-nbg1'] = initMap['deb-docker']
initMap['deb12-aarch64-hel1'] = initMap['deb-docker']
initMap['deb12-aarch64-fsn1'] = initMap['deb-docker']
initMap['deb12-x64-nbg1-min']     = initMap['deb-docker']
initMap['deb12-x64-hel1-min']     = initMap['deb-docker']
initMap['deb12-x64-fsn1-min']     = initMap['deb-docker']
initMap['deb12-aarch64-nbg1-min'] = initMap['deb-docker']
initMap['deb12-aarch64-hel1-min'] = initMap['deb-docker']
initMap['deb12-aarch64-fsn1-min'] = initMap['deb-docker']
initMap['launcher-x64-nbg1']  = initMap['deb-docker']
initMap['launcher-x64-hel1']  = initMap['deb-docker']
initMap['launcher-x64-fsn1']  = initMap['deb-docker']

def templates = [
       /* new HetznerServerTemplate("ubuntu20-cx21", "java", "name=ubuntu20-docker", "fsn1", "cx21"), */
        //                        tmplName                  tmplLabels                     tmplImage                  region server type
        new HetznerServerTemplate("deb12-x64-nbg1-min",     labelMap['deb12-x64-min'],     imageMap['deb12-x64'],     "nbg1", "cpx42"),
        new HetznerServerTemplate("deb12-aarch64-nbg1-min", labelMap['deb12-aarch64-min'], imageMap['deb12-aarch64'], "nbg1", "cax31"),
        new HetznerServerTemplate("deb12-x64-hel1-min",     labelMap['deb12-x64-min'],     imageMap['deb12-x64'],     "hel1", "cpx42"),
        new HetznerServerTemplate("deb12-aarch64-hel1-min", labelMap['deb12-aarch64-min'], imageMap['deb12-aarch64'], "hel1", "cax31"),
        new HetznerServerTemplate("deb12-x64-fsn1-min",     labelMap['deb12-x64-min'],     imageMap['deb12-x64'],     "fsn1", "cpx42"),
        new HetznerServerTemplate("deb12-aarch64-fsn1-min", labelMap['deb12-aarch64-min'], imageMap['deb12-aarch64'], "fsn1", "cax31"),
        new HetznerServerTemplate("deb12-x64-nbg1",         labelMap['deb12-x64'],         imageMap['deb12-x64'],     "nbg1", "cpx62"),
        new HetznerServerTemplate("deb12-aarch64-nbg1",     labelMap['deb12-aarch64'],     imageMap['deb12-aarch64'], "nbg1", "cax41"),
        new HetznerServerTemplate("deb12-x64-hel1",         labelMap['deb12-x64'],         imageMap['deb12-x64'],     "hel1", "cpx62"),
        new HetznerServerTemplate("deb12-aarch64-hel1",     labelMap['deb12-aarch64'],     imageMap['deb12-aarch64'], "hel1", "cax41"),
        new HetznerServerTemplate("deb12-x64-fsn1",         labelMap['deb12-x64'],         imageMap['deb12-x64'],     "fsn1", "cpx62"),
        new HetznerServerTemplate("deb12-aarch64-fsn1",     labelMap['deb12-aarch64'],     imageMap['deb12-aarch64'], "fsn1", "cax41"),
        new HetznerServerTemplate("launcher-x64-nbg1",      labelMap['launcher-x64'],      imageMap['launcher-x64'],  "nbg1", "cpx22"),
        new HetznerServerTemplate("launcher-x64-hel1",      labelMap['launcher-x64'],      imageMap['launcher-x64'],  "hel1", "cpx22"),
        new HetznerServerTemplate("launcher-x64-fsn1",      labelMap['launcher-x64'],      imageMap['launcher-x64'],  "fsn1", "cpx22")
]

templates.each { it ->
                       def sshConnector = new SshConnectorAsRoot("htz.cd.key")
                       sshConnector.setConnectionMethod(new PublicAddressOnly())  // Replace with the desired method
                       it.setConnector(sshConnector)
                       def tmplName = it.name
                       it.setNumExecutors(execMap[tmplName])
                       it.bootDeadline = bootDeadlineMap[tmplName]
                       it.remoteFs = "/mnt/jenkins/"
                       it.jvmOpts = jvmOptsMap[tmplName]
                       it.network = networkMap['percona-vpc-eu']
                       it.userData = initMap[tmplName]
               }

// public HetznerCloud(String name, String credentialsId, String instanceCapStr, List<HetznerServerTemplate> serverTemplates)
def cloud = new HetznerCloud(cloudName, "htz.cd.token", "100", templates)

def jenkins = Jenkins.get()

jenkins.clouds.remove(jenkins.clouds.getByName(cloudName))
jenkins.clouds.add(cloud)
jenkins.save()
