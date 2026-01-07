import cloud.dnation.jenkins.plugins.hetzner.*
import cloud.dnation.jenkins.plugins.hetzner.launcher.*
import hudson.model.*
import jenkins.model.Jenkins
import java.util.logging.Logger

def cloudName = "cloud-htz"

imageMap = [:]                          // ID          TYPE     NAME                 DESCRIPTION          ARCHITECTURE   IMAGE SIZE   DISK SIZE   CREATED                         DEPRECATED
imageMap['fedora42-x64']     = '232895138' // 232895138   system   fedora-42            Fedora 42            x86            -            5 GB        Thu Apr 24 10:00:32 EEST 2025   -
imageMap['fedora42-aarch64'] = '232895264' // 232895264   system   fedora-42            Fedora 42            arm            -            5 GB        Thu Apr 24 10:01:01 EEST 2025   -
imageMap['launcher-x64']  = imageMap['fedora42-x64']

execMap = [:]
execMap['fedora']                = 1
execMap['fedora42-x64-nbg1']     = execMap['fedora']
execMap['fedora42-x64-hel1']     = execMap['fedora']
execMap['fedora42-x64-fsn1']     = execMap['fedora']
execMap['fedora42-aarch64-nbg1'] = execMap['fedora']
execMap['fedora42-aarch64-hel1'] = execMap['fedora']
execMap['fedora42-aarch64-fsn1'] = execMap['fedora']
execMap['fedora42-x64-nbg1-min']     = execMap['fedora']
execMap['fedora42-x64-hel1-min']     = execMap['fedora']
execMap['fedora42-x64-fsn1-min']     = execMap['fedora']
execMap['fedora42-aarch64-nbg1-min'] = execMap['fedora']
execMap['fedora42-aarch64-hel1-min'] = execMap['fedora']
execMap['fedora42-aarch64-fsn1-min'] = execMap['fedora']
execMap['launcher-x64-nbg1']  = 30
execMap['launcher-x64-hel1']  = 30
execMap['launcher-x64-fsn1']  = 30

bootDeadlineMap =[:]
bootDeadlineMap['default']            = 7
bootDeadlineMap['fedora42-x64-nbg1']     = bootDeadlineMap['default']
bootDeadlineMap['fedora42-x64-hel1']     = bootDeadlineMap['default']
bootDeadlineMap['fedora42-x64-fsn1']     = bootDeadlineMap['default']
bootDeadlineMap['fedora42-aarch64-nbg1'] = bootDeadlineMap['default']
bootDeadlineMap['fedora42-aarch64-hel1'] = bootDeadlineMap['default']
bootDeadlineMap['fedora42-aarch64-fsn1'] = bootDeadlineMap['default']
bootDeadlineMap['fedora42-x64-nbg1-min']     = bootDeadlineMap['default']
bootDeadlineMap['fedora42-x64-hel1-min']     = bootDeadlineMap['default']
bootDeadlineMap['fedora42-x64-fsn1-min']     = bootDeadlineMap['default']
bootDeadlineMap['fedora42-aarch64-nbg1-min'] = bootDeadlineMap['default']
bootDeadlineMap['fedora42-aarch64-hel1-min'] = bootDeadlineMap['default']
bootDeadlineMap['fedora42-aarch64-fsn1-min'] = bootDeadlineMap['default']
bootDeadlineMap['launcher-x64-nbg1']  = bootDeadlineMap['default']
bootDeadlineMap['launcher-x64-hel1']  = bootDeadlineMap['default']
bootDeadlineMap['launcher-x64-fsn1']  = bootDeadlineMap['default']

jvmOptsMap = [:]
jvmOptsMap['fedora42']         = '-Xms4g -Xmx16g -Xss4m -XX:+UseG1GC -XX:+ParallelRefProcEnabled -XX:+AlwaysPreTouch --add-opens=java.base/java.lang=ALL-UNNAMED --add-opens=java.base/java.lang.reflect=ALL-UNNAMED'
jvmOptsMap['fedora42-x64-nbg1']     = jvmOptsMap['fedora42']
jvmOptsMap['fedora42-x64-hel1']     = jvmOptsMap['fedora42']
jvmOptsMap['fedora42-x64-fsn1']     = jvmOptsMap['fedora42']
jvmOptsMap['fedora42-aarch64-nbg1'] = jvmOptsMap['fedora42']
jvmOptsMap['fedora42-aarch64-hel1'] = jvmOptsMap['fedora42']
jvmOptsMap['fedora42-aarch64-fsn1'] = jvmOptsMap['fedora42']
jvmOptsMap['fedora42-x64-nbg1-min']     = jvmOptsMap['fedora42']
jvmOptsMap['fedora42-x64-hel1-min']     = jvmOptsMap['fedora42']
jvmOptsMap['fedora42-x64-fsn1-min']     = jvmOptsMap['fedora42']
jvmOptsMap['fedora42-aarch64-nbg1-min'] = jvmOptsMap['fedora42']
jvmOptsMap['fedora42-aarch64-hel1-min'] = jvmOptsMap['fedora42']
jvmOptsMap['fedora42-aarch64-fsn1-min'] = jvmOptsMap['fedora42']
jvmOptsMap['launcher-x64-nbg1']  = jvmOptsMap['fedora42']
jvmOptsMap['launcher-x64-hel1']  = jvmOptsMap['fedora42']
jvmOptsMap['launcher-x64-fsn1']  = jvmOptsMap['fedora42']

labelMap = [:]
labelMap['fedora42-x64-min']     = 'docker-x64-min docker-fedora42-x64-min fedora42-x64-min'
labelMap['fedora42-aarch64-min'] = 'docker-aarch64-min docker-fedora42-aarch64-min fedora42-aarch64-min'
labelMap['fedora42-x64']         = 'docker-x64 docker-fedora42-x64 fedora42-x64'
labelMap['fedora42-aarch64']     = 'docker-aarch64 docker-fedora42-aarch64 fedora42-aarch64'
labelMap['launcher-x64']      = 'launcher-x64'

networkMap = [:]
networkMap['cloud.cd.percona.com'] = '11334955' // cloud.cd.percona.com

initMap = [:]
initMap['fedora-docker'] = '''#!/bin/bash -x
    set -o xtrace
    ( sudo systemctl stop sshd; sleep 300; sudo systemctl start sshd ) &
    sudo fallocate -l 32G /swapfile
    sudo chmod 600 /swapfile
    sudo mkswap /swapfile
    sudo swapon /swapfile
    until sudo dnf update -y; do
        sleep 1
        echo "try again"
    done
    until sudo dnf install -y java-21-openjdk-headless ca-certificates curl gnupg unzip git dnf-plugins-core cronie bc npm make; do
        sleep 1
        echo "try again"
    done
    sudo dnf config-manager addrepo --from-repofile=https://download.docker.com/linux/fedora/docker-ce.repo
    until sudo dnf install -y docker-ce docker-ce-cli containerd.io docker-buildx-plugin docker-compose-plugin; do
        sleep 1
        echo "try again"
    done
    if ! $(aws --version | grep -q 'aws-cli/2'); then
        find /tmp -maxdepth 1 -name "*aws*" | xargs sudo rm -rf
        until curl "https://awscli.amazonaws.com/awscli-exe-linux-$(uname -m).zip" -o "/tmp/awscliv2.zip"; do
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
    sudo sed -i.bak -e 's/nofile=1024:4096/nofile=900000:900000/; s/DAEMON_MAXFILES=.*/DAEMON_MAXFILES=990000/' /etc/sysconfig/docker
    echo 'DOCKER_STORAGE_OPTIONS="--data-root=/mnt/docker"' | sudo tee -a /etc/sysconfig/docker-storage
    sudo sed -i.bak -e 's|^ExecStart=.*|ExecStart=/usr/bin/dockerd --data-root=/mnt/docker --default-ulimit nofile=900000:900000|' /usr/lib/systemd/system/docker.service
    sudo systemctl daemon-reload
    sudo install -o root -g root -d /mnt/docker
    sudo usermod -aG docker $(id -u -n)
    sudo mkdir -p /etc/docker
    echo '{"experimental": true, "ipv6": true, "fixed-cidr-v6": "fd3c:a8b0:18eb:5c06::/64"}' | sudo tee /etc/docker/daemon.json
    sudo systemctl status docker || sudo systemctl start docker
    echo "* * * * * root /usr/sbin/route add default gw 10.30.236.1 eth0" | sudo tee /etc/cron.d/fix-default-route
    sudo systemctl start sshd
'''
initMap['fedora42-x64-nbg1']     = initMap['fedora-docker']
initMap['fedora42-x64-hel1']     = initMap['fedora-docker']
initMap['fedora42-x64-fsn1']     = initMap['fedora-docker']
initMap['fedora42-aarch64-nbg1'] = initMap['fedora-docker']
initMap['fedora42-aarch64-hel1'] = initMap['fedora-docker']
initMap['fedora42-aarch64-fsn1'] = initMap['fedora-docker']
initMap['fedora42-x64-nbg1-min']     = initMap['fedora-docker']
initMap['fedora42-x64-hel1-min']     = initMap['fedora-docker']
initMap['fedora42-x64-fsn1-min']     = initMap['fedora-docker']
initMap['fedora42-aarch64-nbg1-min'] = initMap['fedora-docker']
initMap['fedora42-aarch64-hel1-min'] = initMap['fedora-docker']
initMap['fedora42-aarch64-fsn1-min'] = initMap['fedora-docker']
initMap['launcher-x64-nbg1']  = initMap['fedora-docker']
initMap['launcher-x64-hel1']  = initMap['fedora-docker']
initMap['launcher-x64-fsn1']  = initMap['fedora-docker']

def templates = [
       /* new HetznerServerTemplate("ubuntu20-cx21", "java", "name=ubuntu20-docker", "fsn1", "cx21"), */
        //                        tmplName                  tmplLabels                     tmplImage                  region server type
        new HetznerServerTemplate("fedora42-x64-nbg1-min",     labelMap['fedora42-x64-min'],     imageMap['fedora42-x64'],     "nbg1", "cpx42"),
        new HetznerServerTemplate("fedora42-aarch64-nbg1-min", labelMap['fedora42-aarch64-min'], imageMap['fedora42-aarch64'], "nbg1", "cax31"),
        new HetznerServerTemplate("fedora42-x64-hel1-min",     labelMap['fedora42-x64-min'],     imageMap['fedora42-x64'],     "hel1", "cpx42"),
        new HetznerServerTemplate("fedora42-aarch64-hel1-min", labelMap['fedora42-aarch64-min'], imageMap['fedora42-aarch64'], "hel1", "cax31"),
        new HetznerServerTemplate("fedora42-x64-fsn1-min",     labelMap['fedora42-x64-min'],     imageMap['fedora42-x64'],     "fsn1", "cpx42"),
        new HetznerServerTemplate("fedora42-aarch64-fsn1-min", labelMap['fedora42-aarch64-min'], imageMap['fedora42-aarch64'], "fsn1", "cax31"),
        new HetznerServerTemplate("fedora42-x64-nbg1",         labelMap['fedora42-x64'],         imageMap['fedora42-x64'],     "nbg1", "cpx62"),
        new HetznerServerTemplate("fedora42-aarch64-nbg1",     labelMap['fedora42-aarch64'],     imageMap['fedora42-aarch64'], "nbg1", "cax41"),
        new HetznerServerTemplate("fedora42-x64-hel1",         labelMap['fedora42-x64'],         imageMap['fedora42-x64'],     "hel1", "cpx62"),
        new HetznerServerTemplate("fedora42-aarch64-hel1",     labelMap['fedora42-aarch64'],     imageMap['fedora42-aarch64'], "hel1", "cax41"),
        new HetznerServerTemplate("fedora42-x64-fsn1",         labelMap['fedora42-x64'],         imageMap['fedora42-x64'],     "fsn1", "cpx62"),
        new HetznerServerTemplate("fedora42-aarch64-fsn1",     labelMap['fedora42-aarch64'],     imageMap['fedora42-aarch64'], "fsn1", "cax41"),
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
                       it.network = networkMap['cloud.cd.percona.com']
                       it.userData = initMap[tmplName]
               }

// public HetznerCloud(String name, String credentialsId, String instanceCapStr, List<HetznerServerTemplate> serverTemplates)
def cloud = new HetznerCloud(cloudName, "htz.cd.token", "100", templates)

def jenkins = Jenkins.get()

jenkins.clouds.remove(jenkins.clouds.getByName(cloudName))
jenkins.clouds.add(cloud)
jenkins.save()
