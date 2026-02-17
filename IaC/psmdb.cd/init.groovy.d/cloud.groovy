import com.amazonaws.services.ec2.model.InstanceType
import hudson.model.*
import hudson.plugins.ec2.EC2Cloud
import hudson.plugins.ec2.EC2Tag
import hudson.plugins.ec2.SlaveTemplate
import hudson.plugins.ec2.SpotConfiguration
import hudson.plugins.ec2.ConnectionStrategy
import hudson.plugins.ec2.HostKeyVerificationStrategyEnum
import hudson.plugins.ec2.UnixData
import java.util.logging.Logger
import jenkins.model.Jenkins

def logger = Logger.getLogger("")
logger.info("Cloud init started")

// get Jenkins instance
Jenkins jenkins = Jenkins.getInstance()

netMap = [:]
netMap['us-west-2a'] = 'subnet-086a759af174241ac'
netMap['us-west-2b'] = 'subnet-03136d8c244f56036'
netMap['us-west-2c'] = 'subnet-09103aa8678a054f7'

imageMap = [:]
imageMap['micro-amazon']     = 'ami-06a974f9b8a97ecf2'
imageMap['min-al2023-x64']   = 'ami-06a974f9b8a97ecf2'
imageMap['min-centos-7-x64'] = 'ami-04f798ca92cc13f74'
imageMap['min-centos-8-x64'] = 'ami-0155c31ea13d4abd2'
imageMap['min-ol-8-x64']     = 'ami-0f47366880b6cce9f'
imageMap['min-ol-9-x64']     = 'ami-02cae4fd317586d2f'
imageMap['min-rhel-10-x64']  = 'ami-0598edb0ace40eb9a'
imageMap['min-stretch-x64']  = 'ami-040a022e1b0c8b7f4'
imageMap['min-buster-x64']   = 'ami-0164ab05efc075cbc'
imageMap['min-bullseye-x64'] = 'ami-0c1b4dff690b5d229'
imageMap['min-bookworm-x64'] = 'ami-07b2d881c67e4c30e'
imageMap['min-trixie-x64']   = 'ami-088afd31387a0ee3a'
imageMap['min-xenial-x64']   = 'ami-079e7a3f57cc8e0d0'
imageMap['min-bionic-x64']   = 'ami-03342f495768cdf2d'
imageMap['min-focal-x64']    = 'ami-0db245b76e5c21ca1'
imageMap['min-jammy-x64']    = 'ami-005f7acd8475ac91c'
imageMap['min-noble-x64']    = 'ami-0cf2b4e024cdb6960'
imageMap['psmdb']            = imageMap['min-xenial-x64']
imageMap['psmdb-bionic']     = imageMap['min-bionic-x64']
imageMap['docker']           = imageMap['micro-amazon']
imageMap['docker-32gb']      = imageMap['micro-amazon']
imageMap['docker-64gb']      = imageMap['micro-amazon']

imageMap['docker-64gb-aarch64'] = 'ami-0c5777a14602ab4b9'
imageMap['min-al2023-aarch64']  = 'ami-0c5777a14602ab4b9'
imageMap['min-jammy-aarch64']   = 'ami-039aad0ef6d1c0b87'
imageMap['min-noble-aarch64']   = 'ami-0c29a2c5cf69b5a9c'
imageMap['min-bullseye-aarch64'] = 'ami-0572bc6dc45296d87'
imageMap['min-bookworm-aarch64'] = 'ami-0fea30efa5b77a3a4'
imageMap['min-trixie-aarch64']  = 'ami-021bb099085248f4c'

priceMap = [:]
priceMap['m5d.large']   = '0.13' // type=m5d.large, vCPU=2, memory=4GiB, saving=29%, interruption='<5%', price=0.071400
priceMap['c5a.2xlarge']  = '0.25'  //type=c5a.2xlarge, vCPU=8, memory=16GiB, saving=58%, interruption='<5%', price=0.182000
priceMap['g4ad.2xlarge'] = '0.33' //type=g4ad.2xlarge, vCPU=8, memory=32GiB, saving=63%, interruption='<5%', price=0.20
priceMap['i3en.3xlarge'] = '0.72' // type=i3en.3xlarge, vCPU=16, memory=64GiB, saving=70%, interruption='<5%'
priceMap['i4g.4xlarge'] = '0.57' // aarch64 type=i4g.4xlarge, vCPU=16, memory=64GiB, saving=38%, interruption='<5%', price=0.488500

userMap = [:]
userMap['docker']           = 'ec2-user'
userMap['docker-32gb']      = userMap['docker']
userMap['docker-64gb']      = userMap['docker']
userMap['micro-amazon']     = userMap['docker']
userMap['min-al2023-x64']   = 'ec2-user'
userMap['min-centos-7-x64'] = 'centos'
userMap['min-centos-8-x64'] = 'centos'
userMap['min-ol-8-x64']     = 'ec2-user'
userMap['min-ol-9-x64']     = 'ec2-user'
userMap['min-rhel-10-x64']  = 'ec2-user'
userMap['min-stretch-x64']  = 'admin'
userMap['min-buster-x64']   = 'admin'
userMap['min-bullseye-x64'] = 'admin'
userMap['min-bookworm-x64'] = 'admin'
userMap['min-trixie-x64']   = 'admin'
userMap['min-xenial-x64']   = 'ubuntu'
userMap['min-bionic-x64']   = 'ubuntu'
userMap['min-focal-x64']    = 'ubuntu'
userMap['min-jammy-x64']    = 'ubuntu'
userMap['min-noble-x64']    = 'ubuntu'
userMap['psmdb']            = userMap['min-xenial-x64']
userMap['psmdb-bionic']     = userMap['min-xenial-x64']

userMap['docker-64gb-aarch64']  = userMap['docker']
userMap['min-al2023-aarch64']   = 'ec2-user'
userMap['min-jammy-aarch64']    = 'ubuntu'
userMap['min-noble-aarch64']    = 'ubuntu'
userMap['min-bullseye-aarch64'] = 'admin'
userMap['min-bookworm-aarch64'] = 'admin'
userMap['min-trixie-aarch64']   = 'admin'

initMap = [:]
initMap['docker'] = '''
    set -o xtrace

    sudo ethtool -K eth0 sg off
    until sudo yum makecache; do
        sleep 1
        echo try again
    done

    if ! mountpoint -q /mnt; then
        for DEVICE_NAME in $(lsblk -ndpbo NAME,SIZE | sort -n -r | awk '{print $1}'); do
            if ! grep -qs "${DEVICE_NAME}" /proc/mounts; then
                DEVICE="${DEVICE_NAME}"
                break
            fi
        done
        if [ -n "${DEVICE}" ]; then
            sudo yum -y install xfsprogs
            sudo mkfs.xfs ${DEVICE}
            sudo mount -o noatime ${DEVICE} /mnt
        fi
    fi

    echo '10.30.6.9 repo.ci.percona.com' | sudo tee -a /etc/hosts

    sudo yum -y install java-17-amazon-corretto git docker cronie unzip
    sudo yum -y remove awscli
    sudo systemctl enable crond
    sudo systemctl start crond

    # AWS CLI v2 from official installer
    if ! $(aws --version 2>/dev/null | grep -q 'aws-cli/2'); then
        find /tmp -maxdepth 1 -name "*aws*" | xargs sudo rm -rf
        until curl "https://awscli.amazonaws.com/awscli-exe-linux-$(uname -m).zip" -o "/tmp/awscliv2.zip"; do
            sleep 1
            echo try again
        done
        cd /tmp && unzip -q awscliv2.zip
        sudo /tmp/aws/install
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
    sudo sed -i.bak -e 's^ExecStart=.*^ExecStart=/usr/bin/dockerd --data-root=/mnt/docker --default-ulimit nofile=900000:900000^' /usr/lib/systemd/system/docker.service
    sudo systemctl daemon-reload
    sudo install -o root -g root -d /mnt/docker
    sudo usermod -aG docker $(id -u -n)
    sudo mkdir -p /etc/docker
    echo '{"experimental": true}' | sudo tee /etc/docker/daemon.json
    sudo systemctl status docker || sudo systemctl start docker
    sudo service docker status || sudo service docker start
    echo "* * * * * root /usr/sbin/route add default gw 10.188.1.1 eth0" | sudo tee /etc/cron.d/fix-default-route
'''
initMap['docker-32gb'] = '''
    set -o xtrace

    sudo ethtool -K eth0 sg off
    until sudo yum makecache; do
        sleep 1
        echo try again
    done

    if ! mountpoint -q /mnt; then
        for DEVICE_NAME in $(lsblk -ndpbo NAME,SIZE | sort -n -r | awk '{print $1}'); do
            if ! grep -qs "${DEVICE_NAME}" /proc/mounts; then
                DEVICE="${DEVICE_NAME}"
                break
            fi
        done
        if [ -n "${DEVICE}" ]; then
            sudo yum -y install xfsprogs
            sudo mkfs.xfs ${DEVICE}
            sudo mount -o noatime ${DEVICE} /mnt
        fi
    fi

    echo '10.30.6.9 repo.ci.percona.com' | sudo tee -a /etc/hosts

    sudo yum -y install java-17-amazon-corretto git docker cronie unzip
    sudo yum -y remove awscli
    sudo systemctl enable crond
    sudo systemctl start crond

    # AWS CLI v2 from official installer
    if ! $(aws --version 2>/dev/null | grep -q 'aws-cli/2'); then
        find /tmp -maxdepth 1 -name "*aws*" | xargs sudo rm -rf
        until curl "https://awscli.amazonaws.com/awscli-exe-linux-$(uname -m).zip" -o "/tmp/awscliv2.zip"; do
            sleep 1
            echo try again
        done
        cd /tmp && unzip -q awscliv2.zip
        sudo /tmp/aws/install
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
    sudo sed -i.bak -e 's^ExecStart=.*^ExecStart=/usr/bin/dockerd --data-root=/mnt/docker --default-ulimit nofile=900000:900000^' /usr/lib/systemd/system/docker.service
    sudo systemctl daemon-reload
    sudo install -o root -g root -d /mnt/docker
    sudo usermod -aG docker $(id -u -n)
    sudo mkdir -p /etc/docker
    echo '{"experimental": true}' | sudo tee /etc/docker/daemon.json
    sudo systemctl status docker || sudo systemctl start docker
    sudo service docker status || sudo service docker start
    echo "* * * * * root /usr/sbin/route add default gw 10.188.1.1 eth0" | sudo tee /etc/cron.d/fix-default-route
'''

initMap['rpmMap'] = '''
    set -o xtrace
    RHVER=$(rpm --eval %rhel)
    ARCH=$(uname -m)

    if ! mountpoint -q /mnt; then
        for DEVICE_NAME in $(lsblk -ndbo NAME,SIZE | sort -n -r | awk '{print $1}'); do
            if ! grep -qs "${DEVICE_NAME}" /proc/mounts; then
                DEVICE="/dev/${DEVICE_NAME}"
                break
            fi
        done
        if [ -n "${DEVICE}" ]; then
            sudo yum -y install xfsprogs
            sudo mkfs.xfs ${DEVICE}
            sudo mount ${DEVICE} /mnt
        fi
    fi

    echo '10.30.6.9 repo.ci.percona.com' | sudo tee -a /etc/hosts

    echo "*  soft  nofile  65000" | sudo tee -a /etc/security/limits.conf
    echo "*  hard  nofile  65000" | sudo tee -a /etc/security/limits.conf
    echo "*  soft  nproc  65000"  | sudo tee -a /etc/security/limits.conf
    echo "*  hard  nproc  65000"  | sudo tee -a /etc/security/limits.conf

    if [[ ${RHVER} -eq 8 ]] || [[ ${RHVER} -eq 7 ]]; then
        sudo sed -i 's/mirrorlist/#mirrorlist/g' /etc/yum.repos.d/CentOS-*
        sudo sed -i 's|#baseurl=http://mirror.centos.org|baseurl=http://vault.centos.org|g' /etc/yum.repos.d/CentOS-*
    fi

    until sudo yum makecache; do
        sleep 1
        echo try again
    done

    # Java version detection: RHEL 10 -> Java 21, all others -> Java 17
    if [[ ${RHVER} -eq 10 ]]; then
        sudo yum -y install java-21-openjdk-headless || :
    else
        sudo yum -y install java-17-amazon-corretto-headless || :
        sudo yum -y install java-17-openjdk-headless || :
    fi

    sudo yum -y install git tzdata-java unzip || :
    sudo yum -y remove awscli

    # AWS CLI v2 from official installer for consistency across all systems
    if ! $(aws --version 2>/dev/null | grep -q 'aws-cli/2'); then
        find /tmp -maxdepth 1 -name "*aws*" | xargs sudo rm -rf
        until curl "https://awscli.amazonaws.com/awscli-exe-linux-$(uname -m).zip" -o "/tmp/awscliv2.zip"; do
            sleep 1
            echo try again
        done
        cd /tmp && unzip -q awscliv2.zip
        sudo /tmp/aws/install
    fi

    sudo install -o $(id -u -n) -g $(id -g -n) -d /mnt/jenkins
'''

initMap['debMap'] = '''
    set -o xtrace
    if ! mountpoint -q /mnt; then
        for DEVICE_NAME in $(lsblk -ndpbo NAME,SIZE | sort -n -r | awk '{print $1}'); do
            if ! grep -qs "${DEVICE_NAME}" /proc/mounts; then
                DEVICE="${DEVICE_NAME}"
                break
            fi
        done
        if [ -n "${DEVICE}" ]; then
            sudo DEBIAN_FRONTEND=noninteractive apt-get -y install xfsprogs
            sudo mkfs.xfs ${DEVICE}
            sudo mount ${DEVICE} /mnt
        fi
    fi

    sudo sed -i 's|http://cdn-aws.deb.debian.org/debian stretch|http://archive.debian.org/debian stretch|g' /etc/apt/sources.list
    sudo sed -i 's|http://security.debian.org/debian-security stretch|http://archive.debian.org/debian-security stretch|g' /etc/apt/sources.list
    sudo sed -i '/stretch-updates/d' /etc/apt/sources.list
    sudo sed -i '/buster-backports/ s/cdn-aws.deb.debian.org/archive.debian.org/' /etc/apt/sources.list
    sudo sed -i '/bullseye-backports/ s/cdn-aws.deb.debian.org/archive.debian.org/' /etc/apt/sources.list

    until sudo DEBIAN_FRONTEND=noninteractive apt-get update; do
        sleep 1
        echo try again
    done
    until sudo DEBIAN_FRONTEND=noninteractive apt-get install -y lsb-release; do
        sleep 1
        echo try again
    done
    DEB_VER=$(lsb_release -sc)

    if [[ ${DEB_VER} == "trixie" ]]; then
        JAVA_VER="openjdk-21-jre-headless"
    elif [[ ${DEB_VER} == "bookworm" ]] || [[ ${DEB_VER} == "bullseye" ]] || [[ ${DEB_VER} == "jammy" ]] || [[ ${DEB_VER} == "noble" ]] || [[ ${DEB_VER} == "focal" ]] || [[ ${DEB_VER} == "bionic" ]] || [[ ${DEB_VER} == "xenial" ]]; then
        JAVA_VER="openjdk-17-jre-headless"
    else
        JAVA_VER="openjdk-11-jre-headless"
    fi
    if [[ ${DEB_VER} == "trixie" ]] || [[ ${DEB_VER} == "bookworm" ]] || [[ ${DEB_VER} == "buster" ]]; then
        sudo DEBIAN_FRONTEND=noninteractive sudo apt-get -y install ${JAVA_VER} git
        sudo mv /etc/ssl /etc/ssl_old
        sudo DEBIAN_FRONTEND=noninteractive sudo apt-get -y install ${JAVA_VER}
        sudo cp -r /etc/ssl_old /etc/ssl
        sudo DEBIAN_FRONTEND=noninteractive sudo apt-get -y install ${JAVA_VER}
    else
        sudo DEBIAN_FRONTEND=noninteractive sudo apt-get -y install ${JAVA_VER} git
    fi
    sudo install -o $(id -u -n) -g $(id -g -n) -d /mnt/jenkins

    echo '10.30.6.9 repo.ci.percona.com' | sudo tee -a /etc/hosts
'''


initMap['docker-64gb']       = initMap['docker-32gb']
initMap['micro-amazon']      = initMap['rpmMap']
initMap['min-al2023-x64']    = initMap['rpmMap']
initMap['min-centos-7-x64']  = initMap['rpmMap']
initMap['min-centos-8-x64']  = initMap['rpmMap']
initMap['min-ol-8-x64']      = initMap['rpmMap']
initMap['min-ol-9-x64']      = initMap['rpmMap']
initMap['min-rhel-10-x64']   = initMap['rpmMap']

initMap['min-stretch-x64']  = initMap['debMap']
initMap['min-buster-x64']   = initMap['debMap']
initMap['min-bullseye-x64'] = initMap['debMap']
initMap['min-bookworm-x64'] = initMap['debMap']
initMap['min-trixie-x64']   = initMap['debMap']
initMap['min-xenial-x64']   = initMap['debMap']
initMap['min-bionic-x64']   = initMap['debMap']
initMap['min-focal-x64']    = initMap['debMap']
initMap['min-jammy-x64']    = initMap['debMap']
initMap['min-noble-x64']    = initMap['debMap']
initMap['psmdb']            = initMap['debMap']
initMap['psmdb-bionic']     = initMap['debMap']

initMap['docker-64gb-aarch64']  = initMap['docker-32gb']
initMap['min-al2023-aarch64']   = initMap['min-al2023-x64']
initMap['min-jammy-aarch64']    = initMap['debMap']
initMap['min-noble-aarch64']    = initMap['debMap']
initMap['min-bullseye-aarch64'] = initMap['debMap']
initMap['min-bookworm-aarch64'] = initMap['debMap']
initMap['min-trixie-aarch64']   = initMap['debMap']

capMap = [:]
capMap['c5a.2xlarge'] = '60'
capMap['g4ad.2xlarge'] = '80'
capMap['i3en.3xlarge'] = '30'
capMap['i4g.4xlarge'] = '20'

typeMap = [:]
typeMap['micro-amazon']      = 'm5d.large'
typeMap['docker']            = 'c5a.2xlarge'
typeMap['docker-32gb']       = 'g4ad.2xlarge'
typeMap['docker-64gb']       = 'i3en.3xlarge'
typeMap['min-al2023-x64']    = typeMap['docker-32gb']
typeMap['min-centos-7-x64']  = typeMap['docker-32gb']
typeMap['min-centos-8-x64']  = typeMap['docker-32gb']
typeMap['min-ol-8-x64']      = typeMap['docker-32gb']
typeMap['min-ol-9-x64']      = typeMap['docker-32gb']
typeMap['min-rhel-10-x64']   = typeMap['docker-32gb']
typeMap['min-stretch-x64']   = typeMap['docker-32gb']
typeMap['min-buster-x64']    = typeMap['docker-32gb']
typeMap['min-bullseye-x64']  = typeMap['docker-32gb']
typeMap['min-bookworm-x64']  = typeMap['docker-32gb']
typeMap['min-trixie-x64']    = typeMap['docker-32gb']
typeMap['min-xenial-x64']    = typeMap['docker-32gb']
typeMap['min-bionic-x64']    = typeMap['docker-32gb']
typeMap['min-focal-x64']     = typeMap['docker-32gb']
typeMap['min-jammy-x64']     = typeMap['docker-32gb']
typeMap['min-noble-x64']     = typeMap['docker-32gb']
typeMap['psmdb']             = typeMap['docker-32gb']
typeMap['psmdb-bionic']      = typeMap['docker-32gb']

typeMap['docker-64gb-aarch64']  = 'i4g.4xlarge'
typeMap['min-al2023-aarch64']   = 'i4g.4xlarge'
typeMap['min-jammy-aarch64']    = 'i4g.4xlarge'
typeMap['min-noble-aarch64']    = 'i4g.4xlarge'
typeMap['min-bullseye-aarch64'] = 'i4g.4xlarge'
typeMap['min-bookworm-aarch64'] = 'i4g.4xlarge'
typeMap['min-trixie-aarch64']   = 'i4g.4xlarge'

execMap = [:]
execMap['docker']            = '1'
execMap['docker-32gb']       = execMap['docker']
execMap['docker-64gb']       = execMap['docker']
execMap['micro-amazon']      = '30'
execMap['min-al2023-x64']    = '1'
execMap['min-centos-7-x64']  = '1'
execMap['min-centos-8-x64']  = '1'
execMap['min-ol-8-x64']      = '1'
execMap['min-ol-9-x64']      = '1'
execMap['min-rhel-10-x64']   = '1'
execMap['min-stretch-x64']   = '1'
execMap['min-buster-x64']    = '1'
execMap['min-bullseye-x64']  = '1'
execMap['min-bookworm-x64']  = '1'
execMap['min-trixie-x64']    = '1'
execMap['min-xenial-x64']    = '1'
execMap['min-bionic-x64']    = '1'
execMap['min-focal-x64']     = '1'
execMap['min-jammy-x64']     = '1'
execMap['min-noble-x64']     = '1'
execMap['psmdb']             = '1'
execMap['psmdb-bionic']      = '1'

execMap['docker-64gb-aarch64']  = execMap['docker']
execMap['min-al2023-aarch64']   = '1'
execMap['min-jammy-aarch64']    = '1'
execMap['min-noble-aarch64']    = '1'
execMap['min-bullseye-aarch64'] = '1'
execMap['min-bookworm-aarch64'] = '1'
execMap['min-trixie-aarch64']   = '1'

devMap = [:]
devMap['docker']            = '/dev/xvda=:20:true:gp2,/dev/xvdd=:500:true:gp2'
devMap['docker-32gb']       = devMap['docker']
devMap['docker-64gb']       = devMap['docker']
devMap['micro-amazon']      = '/dev/xvda=:20:true:gp2,/dev/xvdd=:160:true:gp2'
devMap['min-al2023-x64']    = '/dev/xvda=:20:true:gp2,/dev/xvdd=:500:true:gp2'
devMap['min-centos-7-x64']  = '/dev/xvda=:20:true:gp2,/dev/xvdd=:500:true:gp2'
devMap['min-centos-8-x64']  = '/dev/xvda=:20:true:gp2,/dev/xvdd=:500:true:gp2'
devMap['min-ol-8-x64']      = '/dev/sda1=:20:true:gp2,/dev/xvdd=:500:true:gp2'
devMap['min-ol-9-x64']      = '/dev/sda1=:20:true:gp2,/dev/xvdd=:500:true:gp2'
devMap['min-rhel-10-x64']   = '/dev/xvda=:20:true:gp2,/dev/xvdd=:500:true:gp2'
devMap['min-stretch-x64']   = 'xvda=:20:true:gp2,xvdd=:500:true:gp2'
devMap['min-buster-x64']    = '/dev/xvda=:20:true:gp2,/dev/xvdd=:500:true:gp2'
devMap['min-bullseye-x64']  = '/dev/xvda=:20:true:gp2,/dev/xvdd=:500:true:gp2'
devMap['min-bookworm-x64']  = '/dev/xvda=:20:true:gp2,/dev/xvdd=:500:true:gp2'
devMap['min-trixie-x64']    = '/dev/xvda=:20:true:gp2,/dev/xvdd=:500:true:gp2'
devMap['min-xenial-x64']    = '/dev/sda1=:20:true:gp2,/dev/sdd=:500:true:gp2'
devMap['min-bionic-x64']    = '/dev/sda1=:20:true:gp2,/dev/sdd=:500:true:gp2'
devMap['min-focal-x64']     = '/dev/sda1=:20:true:gp2,/dev/sdd=:500:true:gp2'
devMap['min-jammy-x64']     = '/dev/sda1=:20:true:gp2,/dev/sdd=:500:true:gp2'
devMap['min-noble-x64']     = '/dev/sda1=:20:true:gp2,/dev/sdd=:500:true:gp2'
devMap['psmdb']             = '/dev/sda1=:20:true:gp2,/dev/sdd=:500:true:gp2'
devMap['psmdb-bionic']      = '/dev/sda1=:20:true:gp2,/dev/sdd=:500:true:gp2'

devMap['docker-64gb-aarch64']  = devMap['docker']
devMap['min-al2023-aarch64']   = '/dev/xvda=:20:true:gp2,/dev/xvdd=:500:true:gp2'
devMap['min-jammy-aarch64']    = '/dev/sda1=:20:true:gp2,/dev/sdd=:500:true:gp2'
devMap['min-noble-aarch64']    = '/dev/sda1=:20:true:gp2,/dev/sdd=:500:true:gp2'
devMap['min-bullseye-aarch64'] = '/dev/xvda=:20:true:gp2,/dev/xvdd=:500:true:gp2'
devMap['min-bookworm-aarch64'] = '/dev/xvda=:20:true:gp2,/dev/xvdd=:500:true:gp2'
devMap['min-trixie-aarch64']   = '/dev/xvda=:20:true:gp2,/dev/xvdd=:500:true:gp2'

labelMap = [:]
labelMap['docker']            = ''
labelMap['docker-32gb']       = ''
labelMap['docker-64gb']       = ''
labelMap['micro-amazon']      = 'master'
labelMap['min-al2023-x64']    = ''
labelMap['min-centos-7-x64']  = ''
labelMap['min-centos-8-x64']  = ''
labelMap['min-ol-8-x64']      = ''
labelMap['min-ol-9-x64']      = ''
labelMap['min-rhel-10-x64']   = ''
labelMap['min-stretch-x64']   = ''
labelMap['min-buster-x64']    = ''
labelMap['min-bullseye-x64']  = ''
labelMap['min-bookworm-x64']  = ''
labelMap['min-trixie-x64']    = ''
labelMap['min-xenial-x64']    = ''
labelMap['min-bionic-x64']    = ''
labelMap['min-focal-x64']     = ''
labelMap['min-jammy-x64']     = ''
labelMap['min-noble-x64']     = ''
labelMap['psmdb']             = ''
labelMap['psmdb-bionic']      = ''

labelMap['docker-64gb-aarch64']  = 'docker-32gb-aarch64'
labelMap['min-al2023-aarch64']   = ''
labelMap['min-jammy-aarch64']    = ''
labelMap['min-noble-aarch64']    = ''
labelMap['min-bullseye-aarch64'] = ''
labelMap['min-bookworm-aarch64'] = ''
labelMap['min-trixie-aarch64']   = ''

jvmoptsMap = [:]
jvmoptsMap['docker']            = '-Xmx512m -Xms512m'
jvmoptsMap['docker-32gb']       = jvmoptsMap['docker']
jvmoptsMap['docker-64gb']       = jvmoptsMap['docker']
jvmoptsMap['micro-amazon']      = jvmoptsMap['docker']
jvmoptsMap['min-al2023-x64']    = jvmoptsMap['docker']
jvmoptsMap['min-centos-7-x64']  = jvmoptsMap['docker']
jvmoptsMap['min-centos-8-x64']  = jvmoptsMap['docker']
jvmoptsMap['min-ol-8-x64']      = jvmoptsMap['docker']
jvmoptsMap['min-ol-9-x64']      = jvmoptsMap['docker']
jvmoptsMap['min-rhel-10-x64']   = jvmoptsMap['docker']
jvmoptsMap['min-stretch-x64']   = jvmoptsMap['docker']
jvmoptsMap['min-buster-x64']    = jvmoptsMap['docker']
jvmoptsMap['min-bullseye-x64']  = jvmoptsMap['docker']
jvmoptsMap['min-bookworm-x64']  = '-Xmx512m -Xms512m --add-opens=java.base/java.lang=ALL-UNNAMED --add-opens=java.base/java.lang.reflect=ALL-UNNAMED'
jvmoptsMap['min-trixie-x64']    = jvmoptsMap['min-bookworm-x64']
jvmoptsMap['min-xenial-x64']    = jvmoptsMap['docker']
jvmoptsMap['min-bionic-x64']    = jvmoptsMap['docker']
jvmoptsMap['min-focal-x64']     = jvmoptsMap['docker']
jvmoptsMap['min-jammy-x64']     = jvmoptsMap['docker']
jvmoptsMap['min-noble-x64']     = jvmoptsMap['docker']
jvmoptsMap['psmdb']             = jvmoptsMap['docker']
jvmoptsMap['psmdb-bionic']      = jvmoptsMap['docker']

jvmoptsMap['docker-64gb-aarch64']  = jvmoptsMap['docker']
jvmoptsMap['min-al2023-aarch64']   = jvmoptsMap['docker']
jvmoptsMap['min-jammy-aarch64']    = jvmoptsMap['docker']
jvmoptsMap['min-noble-aarch64']    = jvmoptsMap['docker']
jvmoptsMap['min-bullseye-aarch64'] = jvmoptsMap['docker']
jvmoptsMap['min-bookworm-aarch64'] = '-Xmx512m -Xms512m --add-opens=java.base/java.lang=ALL-UNNAMED --add-opens=java.base/java.lang.reflect=ALL-UNNAMED'
jvmoptsMap['min-trixie-aarch64']   = jvmoptsMap['min-bookworm-aarch64']

// https://github.com/jenkinsci/ec2-plugin/blob/ec2-1.39/src/main/java/hudson/plugins/ec2/SlaveTemplate.java
SlaveTemplate getTemplate(String OSType, String AZ) {
    return new SlaveTemplate(
        imageMap[OSType],                           // String ami
        '',                                         // String zone
        new SpotConfiguration(true, priceMap[typeMap[OSType]], false, '0'), // SpotConfiguration spotConfig
        'default',                                  // String securityGroups
        '/mnt/jenkins',                             // String remoteFS
        InstanceType.fromValue(typeMap[OSType]),    // InstanceType type
        ( typeMap[OSType].startsWith("c") || typeMap[OSType].startsWith("m") ), // boolean ebsOptimized
        OSType + ' ' + labelMap[OSType],            // String labelString
        Node.Mode.NORMAL,                           // Node.Mode mode
        OSType,                                     // String description
        initMap[OSType],                            // String initScript
        '',                                         // String tmpDir
        '',                                         // String userData
        execMap[OSType],                            // String numExecutors
        userMap[OSType],                            // String remoteAdmin
        new UnixData('', '', '', '22', ''),         // AMITypeData amiType
        jvmoptsMap[OSType],                         // String jvmopts
        false,                                      // boolean stopOnTerminate
        netMap[AZ],                                 // String subnetId
        [
            new EC2Tag('Name', 'jenkins-psmdb-' + OSType),
            new EC2Tag('iit-billing-tag', 'jenkins-psmdb-slave')
        ],                                          // List<EC2Tag> tags
        '3',                                        // String idleTerminationMinutes
        0,                                          // Init minimumNumberOfInstances
        0,                                          // minimumNumberOfSpareInstances
        capMap[typeMap[OSType]],                    // String instanceCapStr
        'arn:aws:iam::119175775298:instance-profile/jenkins-psmdb-slave', // String iamInstanceProfile
        true,                                       // boolean deleteRootOnTermination
        false,                                      // boolean useEphemeralDevices
        false,                                      // boolean useDedicatedTenancy
        '',                                         // String launchTimeoutStr
        true,                                       // boolean associatePublicIp
        devMap[OSType],                             // String customDeviceMapping
        true,                                       // boolean connectBySSHProcess
        false,                                      // boolean monitoring
        false,                                      // boolean t2Unlimited
        ConnectionStrategy.PUBLIC_DNS,              // connectionStrategy
        -1,                                         // int maxTotalUses
        null,
        HostKeyVerificationStrategyEnum.OFF,
    )
}

String privateKey = ''
jenkins.clouds.each {
    if (it.hasProperty('cloudName') && it['cloudName'] == 'AWS-Dev b') {
        privateKey = it['privateKey']
    }
}

String sshKeysCredentialsId = '87fbc2e7-40f8-45ba-b9a7-b92cdd2a90b3'

String region = 'us-west-2'
('b'..'b').each {
    // https://github.com/jenkinsci/ec2-plugin/blob/ec2-1.39/src/main/java/hudson/plugins/ec2/AmazonEC2Cloud.java
    EC2Cloud ec2Cloud = new EC2Cloud(
        "AWS-Dev ${it}",                        // String cloudName
        true,                                   // boolean useInstanceProfileForCredentials
        '',                                     // String credentialsId
        region,                                 // String region
        privateKey,                             // String privateKey
        sshKeysCredentialsId,                   // String sshKeysCredentialsId
        '240',                                   // String instanceCapStr
        [
            getTemplate('docker',                  "${region}${it}"),
            getTemplate('docker-32gb',             "${region}${it}"),
            getTemplate('docker-64gb',             "${region}${it}"),
            getTemplate('micro-amazon',            "${region}${it}"),
            getTemplate('min-al2023-x64',          "${region}${it}"),
            getTemplate('min-centos-7-x64',        "${region}${it}"),
            getTemplate('min-centos-8-x64',        "${region}${it}"),
            getTemplate('min-ol-8-x64',            "${region}${it}"),
            getTemplate('min-ol-9-x64',            "${region}${it}"),
            getTemplate('min-rhel-10-x64',         "${region}${it}"),
            getTemplate('min-stretch-x64',         "${region}${it}"),
            getTemplate('min-buster-x64',          "${region}${it}"),
            getTemplate('min-bullseye-x64',        "${region}${it}"),
            getTemplate('min-bookworm-x64',        "${region}${it}"),
            getTemplate('min-trixie-x64',          "${region}${it}"),
            getTemplate('min-xenial-x64',          "${region}${it}"),
            getTemplate('min-bionic-x64',          "${region}${it}"),
            getTemplate('min-focal-x64',           "${region}${it}"),
            getTemplate('min-jammy-x64',           "${region}${it}"),
            getTemplate('min-noble-x64',           "${region}${it}"),
            getTemplate('psmdb',                   "${region}${it}"),
            getTemplate('psmdb-bionic',            "${region}${it}"),
            getTemplate('docker-64gb-aarch64',     "${region}${it}"),
            getTemplate('min-al2023-aarch64',      "${region}${it}"),
            getTemplate('min-jammy-aarch64',       "${region}${it}"),
            getTemplate('min-noble-aarch64',       "${region}${it}"),
            getTemplate('min-bullseye-aarch64',    "${region}${it}"),
            getTemplate('min-bookworm-aarch64',    "${region}${it}"),
            getTemplate('min-trixie-aarch64',      "${region}${it}"),
        ],
        '',
        ''                                    // List<? extends SlaveTemplate> templates
    )

    // add cloud configuration to Jenkins
    jenkins.clouds.each {
        if (it.hasProperty('name') && it.name == ec2Cloud.name) {
            jenkins.clouds.remove(it)
        }
    }
    jenkins.clouds.add(ec2Cloud)
}

// save current Jenkins state to disk
jenkins.save()

logger.info("Cloud init finished")
