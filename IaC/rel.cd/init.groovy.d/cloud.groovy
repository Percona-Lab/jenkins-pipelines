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
netMap['eu-west-1b'] = 'subnet-02e8446080b5a52ef'
netMap['eu-west-1c'] = 'subnet-0eb939327a262a348'

imageMap = [:]
imageMap['eu-west-1a.docker'] = 'ami-0e8b5d4aece7e1ce8'
imageMap['eu-west-1a.docker-32gb'] = 'ami-0e8b5d4aece7e1ce8'
imageMap['eu-west-1a.docker-64gb'] = 'ami-0e8b5d4aece7e1ce8'
imageMap['eu-west-1a.docker2'] = 'ami-0e8b5d4aece7e1ce8'
imageMap['eu-west-1a.micro-amazon'] = 'ami-0e8b5d4aece7e1ce8'
imageMap['eu-west-1a.min-amazon-2-x64'] = 'ami-0e8b5d4aece7e1ce8'

imageMap['eu-west-1a.min-centos-7-x64']  = 'ami-00d464afa64e1fc69'
imageMap['eu-west-1a.fips-centos-7-x64'] = 'ami-00d464afa64e1fc69'
imageMap['eu-west-1a.min-centos-8-x64']  = 'ami-0a75a5a43b05b4d5f'
imageMap['eu-west-1a.min-ol-8-x64']      = 'ami-0f31520cb83bd0301'
imageMap['eu-west-1a.min-ol-9-x64']      = 'ami-04e2c29c7de5e0f5a'
imageMap['eu-west-1a.min-al2023-x64']    = 'ami-091a906f2e1e40076'
imageMap['eu-west-1a.min-rhel-10-x64']   = 'ami-0b93e28d1386a8580'
imageMap['eu-west-1a.min-bookworm-x64']  = 'ami-09176e8784b68a502'
imageMap['eu-west-1a.min-bullseye-x64']  = 'ami-089f338f3a2e69431'
imageMap['eu-west-1a.min-buster-x64']    = 'ami-00aa3f69e07141166'
imageMap['eu-west-1a.min-trixie-x64']    = 'ami-04034bfd5da1fa2ed'
imageMap['eu-west-1a.min-jammy-x64']     = 'ami-0d2f9b5f04091bdb7'
imageMap['eu-west-1a.min-noble-x64']     = 'ami-0776c814353b4814d'
imageMap['eu-west-1a.min-focal-x64']     = 'ami-051d5c7c7ec9cf96c'
imageMap['eu-west-1a.min-bionic-x64']    = 'ami-027ceacf6a9f484c3'
imageMap['eu-west-1a.min-hirsute-x64-zenfs'] = 'ami-02469e1cc9f95b137'
imageMap['eu-west-1a.min-focal-x64-zenfs']   = 'ami-05a657c9227900694'
imageMap['eu-west-1a.min-bionic-x64-zenfs']  = 'ami-02d7fe93ba8353d4e'

imageMap['eu-west-1b.docker'] = imageMap['eu-west-1a.docker']
imageMap['eu-west-1b.docker-32gb'] = imageMap['eu-west-1a.docker-32gb']
imageMap['eu-west-1b.docker-64gb'] = imageMap['eu-west-1a.docker-64gb']
imageMap['eu-west-1b.docker2'] = imageMap['eu-west-1a.docker2']
imageMap['eu-west-1b.micro-amazon'] = imageMap['eu-west-1a.micro-amazon']
imageMap['eu-west-1b.min-amazon-2-x64'] = imageMap['eu-west-1a.min-amazon-2-x64']
imageMap['eu-west-1b.min-centos-7-x64'] = imageMap['eu-west-1a.min-centos-7-x64']
imageMap['eu-west-1b.fips-centos-7-x64'] = imageMap['eu-west-1a.fips-centos-7-x64']

imageMap['eu-west-1b.min-centos-7-x64'] = imageMap['eu-west-1a.min-centos-7-x64']
imageMap['eu-west-1b.fips-centos-7-x64'] = imageMap['eu-west-1a.fips-centos-7-x64']
imageMap['eu-west-1b.min-centos-8-x64'] = imageMap['eu-west-1a.min-centos-8-x64']
imageMap['eu-west-1b.min-ol-8-x64']     = imageMap['eu-west-1a.min-ol-8-x64']
imageMap['eu-west-1b.min-ol-9-x64']     = imageMap['eu-west-1a.min-ol-9-x64']
imageMap['eu-west-1b.min-al2023-x64']   = imageMap['eu-west-1a.min-al2023-x64']
imageMap['eu-west-1b.min-rhel-10-x64']  = imageMap['eu-west-1a.min-rhel-10-x64']
imageMap['eu-west-1b.min-bookworm-x64'] = imageMap['eu-west-1a.min-bookworm-x64']
imageMap['eu-west-1b.min-bullseye-x64'] = imageMap['eu-west-1a.min-bullseye-x64']
imageMap['eu-west-1b.min-buster-x64']   = imageMap['eu-west-1a.min-buster-x64']
imageMap['eu-west-1b.min-trixie-x64']   = imageMap['eu-west-1a.min-trixie-x64']
imageMap['eu-west-1b.min-jammy-x64']    = imageMap['eu-west-1a.min-jammy-x64']
imageMap['eu-west-1b.min-noble-x64']    = imageMap['eu-west-1a.min-noble-x64']
imageMap['eu-west-1b.min-focal-x64']    = imageMap['eu-west-1a.min-focal-x64']
imageMap['eu-west-1b.min-bionic-x64']   = imageMap['eu-west-1a.min-bionic-x64']
imageMap['eu-west-1b.min-hirsute-x64-zenfs'] = imageMap['eu-west-1a.min-hirsute-x64-zenfs']
imageMap['eu-west-1b.min-focal-x64-zenfs'] = imageMap['eu-west-1a.min-focal-x64-zenfs']
imageMap['eu-west-1b.min-bionic-x64-zenfs']  = imageMap['eu-west-1a.min-bionic-x64-zenfs']

imageMap['eu-west-1c.docker'] = imageMap['eu-west-1a.docker']
imageMap['eu-west-1c.docker-32gb'] = imageMap['eu-west-1a.docker-32gb']
imageMap['eu-west-1c.docker-64gb'] = imageMap['eu-west-1a.docker-64gb']
imageMap['eu-west-1c.docker2'] = imageMap['eu-west-1a.docker2']
imageMap['eu-west-1c.micro-amazon'] = imageMap['eu-west-1a.micro-amazon']
imageMap['eu-west-1c.min-amazon-2-x64'] = imageMap['eu-west-1a.min-amazon-2-x64']
imageMap['eu-west-1c.min-centos-7-x64'] = imageMap['eu-west-1a.min-centos-7-x64']
imageMap['eu-west-1c.fips-centos-7-x64'] = imageMap['eu-west-1a.fips-centos-7-x64']

imageMap['eu-west-1c.min-centos-7-x64'] = imageMap['eu-west-1a.min-centos-7-x64']
imageMap['eu-west-1c.min-centos-8-x64'] = imageMap['eu-west-1a.min-centos-8-x64']
imageMap['eu-west-1c.min-ol-8-x64']     = imageMap['eu-west-1a.min-ol-8-x64']
imageMap['eu-west-1c.min-ol-9-x64']     = imageMap['eu-west-1a.min-ol-9-x64']
imageMap['eu-west-1c.min-al2023-x64']   = imageMap['eu-west-1a.min-al2023-x64']
imageMap['eu-west-1c.min-rhel-10-x64']  = imageMap['eu-west-1a.min-rhel-10-x64']
imageMap['eu-west-1c.min-bookworm-x64'] = imageMap['eu-west-1a.min-bookworm-x64']
imageMap['eu-west-1c.min-bullseye-x64'] = imageMap['eu-west-1a.min-bullseye-x64']
imageMap['eu-west-1c.min-buster-x64']   = imageMap['eu-west-1a.min-buster-x64']
imageMap['eu-west-1c.min-trixie-x64']   = imageMap['eu-west-1a.min-trixie-x64']
imageMap['eu-west-1c.min-jammy-x64']    = imageMap['eu-west-1a.min-jammy-x64']
imageMap['eu-west-1c.min-noble-x64']    = imageMap['eu-west-1a.min-noble-x64']
imageMap['eu-west-1c.min-focal-x64']    = imageMap['eu-west-1a.min-focal-x64']
imageMap['eu-west-1c.min-bionic-x64']   = imageMap['eu-west-1a.min-bionic-x64']
imageMap['eu-west-1c.min-hirsute-x64-zenfs'] = imageMap['eu-west-1a.min-hirsute-x64-zenfs']
imageMap['eu-west-1c.min-focal-x64-zenfs'] = imageMap['eu-west-1a.min-focal-x64-zenfs']
imageMap['eu-west-1c.min-bionic-x64-zenfs']  = imageMap['eu-west-1a.min-bionic-x64-zenfs']

imageMap['eu-west-1a.docker-32gb-aarch64']  = 'ami-0b3f5005d71118f36'
imageMap['eu-west-1a.docker-64gb-aarch64']  = 'ami-0b3f5005d71118f36'
imageMap['eu-west-1a.min-al2023-aarch64']   = 'ami-0b24063151d1c59e7'
imageMap['eu-west-1a.min-jammy-aarch64']    = 'ami-0fd301a23be2fbe30'
imageMap['eu-west-1a.min-noble-aarch64']    = 'ami-0a636034c582e2138'
imageMap['eu-west-1a.min-bullseye-aarch64'] = 'ami-0083086bc808b1d71'
imageMap['eu-west-1a.min-bookworm-aarch64'] = 'ami-02dda204c4af0aecc'
imageMap['eu-west-1a.min-trixie-aarch64']   = 'ami-08aa48334e0370a03'
imageMap['eu-west-1b.docker-32gb-aarch64']  = imageMap['eu-west-1a.docker-32gb-aarch64']
imageMap['eu-west-1b.docker-64gb-aarch64']  = imageMap['eu-west-1a.docker-64gb-aarch64']
imageMap['eu-west-1b.min-al2023-aarch64']   = imageMap['eu-west-1a.min-al2023-aarch64']
imageMap['eu-west-1b.min-jammy-aarch64']    = imageMap['eu-west-1a.min-jammy-aarch64']
imageMap['eu-west-1b.min-noble-aarch64']    = imageMap['eu-west-1a.min-noble-aarch64']
imageMap['eu-west-1b.min-bullseye-aarch64'] = imageMap['eu-west-1a.min-bullseye-aarch64']
imageMap['eu-west-1b.min-bookworm-aarch64'] = imageMap['eu-west-1a.min-bookworm-aarch64']
imageMap['eu-west-1b.min-trixie-aarch64']   = imageMap['eu-west-1a.min-trixie-aarch64']

imageMap['eu-west-1c.docker-32gb-aarch64']  = imageMap['eu-west-1a.docker-32gb-aarch64']
imageMap['eu-west-1c.docker-64gb-aarch64']  = imageMap['eu-west-1a.docker-64gb-aarch64']
imageMap['eu-west-1c.min-al2023-aarch64']   = imageMap['eu-west-1a.min-al2023-aarch64']
imageMap['eu-west-1c.min-jammy-aarch64']    = imageMap['eu-west-1a.min-jammy-aarch64']
imageMap['eu-west-1c.min-noble-aarch64']    = imageMap['eu-west-1a.min-noble-aarch64']
imageMap['eu-west-1c.min-bullseye-aarch64'] = imageMap['eu-west-1a.min-bullseye-aarch64']
imageMap['eu-west-1c.min-bookworm-aarch64'] = imageMap['eu-west-1a.min-bookworm-aarch64']
imageMap['eu-west-1c.min-trixie-aarch64']   = imageMap['eu-west-1a.min-trixie-aarch64']

priceMap = [:]
priceMap['t2.small'] = '0.02'    // type=t2.small, vCPU=1, memory=2GiB, saving=68%, interruption='<5%', price=0.008000
priceMap['c5.xlarge'] = '0.15'   // type=c5.xlarge, vCPU=4, memory=8GiB, saving=58%, interruption='<5%', price=0.086400
priceMap['g4ad.4xlarge'] = '0.53' // type=g4ad.4xlarge, vCPU=16, memory=64GiB, saving=65%, interruption='<5%', price=0.0.46910
priceMap['r6a.4xlarge'] = '0.42' // type=r6a.4xlarge, vCPU=16, memory=128GiB, saving=66%, interruption='<5%', price=0.361600
priceMap['i4i.2xlarge'] = '0.32' // type=i4i.2xlarge, vCPU=8, memory=64GiB, saving=68%, interruption='<5%', price=0.248900

priceMap['m6g.2xlarge'] = '0.24' // aarch64 type=m6g.2xlarge, vCPU=8, memory=32GiB, saving=60%, interruption='<5%', price=0.161800
priceMap['m7g.4xlarge'] = '0.39' // aarch64 type=m7g.4xlarge, vCPU=16, memory=64GiB, saving=58%, interruption='<5%', price=0.348600

userMap = [:]
userMap['docker']            = 'ec2-user'
userMap['docker-32gb']       = userMap['docker']
userMap['docker-64gb']       = userMap['docker']
userMap['docker2']           = userMap['docker']
userMap['micro-amazon']      = userMap['docker']
userMap['min-amazon-2-x64']  = userMap['docker']
userMap['min-jammy-x64']     = 'ubuntu'
userMap['min-noble-x64']     = 'ubuntu'
userMap['min-focal-x64']     = 'ubuntu'
userMap['min-bionic-x64']    = 'ubuntu'
userMap['min-centos-7-x64']  = 'centos'
userMap['min-centos-8-x64']  = 'centos'
userMap['min-ol-8-x64']      = 'ec2-user'
userMap['min-ol-9-x64']      = 'ec2-user'
userMap['min-al2023-x64']    = 'ec2-user'
userMap['min-rhel-10-x64']   = 'ec2-user'
userMap['fips-centos-7-x64'] = 'centos'
userMap['min-buster-x64']    = 'admin'
userMap['min-bullseye-x64']  = 'admin'
userMap['min-bookworm-x64']  = 'admin'
userMap['min-trixie-x64']    = 'admin'
userMap['min-hirsute-x64-zenfs']    = 'ubuntu'
userMap['min-focal-x64-zenfs']    = 'ubuntu'
userMap['min-bionic-x64-zenfs']   = 'ubuntu'

userMap['docker-32gb-aarch64'] = userMap['docker']
userMap['docker-64gb-aarch64'] = userMap['docker']
userMap['min-al2023-aarch64']  = 'ec2-user'
userMap['min-jammy-aarch64']   = 'ubuntu'
userMap['min-noble-aarch64']   = 'ubuntu'
userMap['min-bullseye-aarch64'] = 'admin'
userMap['min-bookworm-aarch64'] = 'admin'
userMap['min-trixie-aarch64']   = 'admin'

initMap = [:]
initMap['docker'] = '''
    set -o xtrace

    if ! mountpoint -q /mnt; then
        for DEVICE_NAME in $(lsblk -ndpbo NAME,SIZE | sort -n -r | awk '{print $1}'); do
            if ! grep -qs "${DEVICE_NAME}" /proc/mounts; then
                DEVICE="${DEVICE_NAME}"
                break
            fi
        done
        if [ -n "${DEVICE}" ]; then
            sudo mkfs.ext4 ${DEVICE}
            sudo mount -o noatime ${DEVICE} /mnt
        fi
    fi
    sudo ethtool -K eth0 sg off

    echo '10.30.6.9 repo.ci.percona.com' | sudo tee -a /etc/hosts

    until sudo yum makecache; do
        sleep 1
        echo try again
    done

    sudo amazon-linux-extras install epel -y
    sudo yum -y install java-17-amazon-corretto-headless tzdata-java || :
    sudo yum -y install git docker p7zip
    sudo yum -y remove awscli

    if ! $(aws --version | grep -q 'aws-cli/2'); then
        find /tmp -maxdepth 1 -name "*aws*" | xargs sudo rm -rf

        until curl "https://awscli.amazonaws.com/awscli-exe-linux-$(uname -m).zip" -o "/tmp/awscliv2.zip"; do
            sleep 1
            echo try again
        done

        7za -o/tmp x /tmp/awscliv2.zip
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
    sudo sed -i.bak -e 's^ExecStart=.*^ExecStart=/usr/bin/dockerd --data-root=/mnt/docker --default-ulimit nofile=900000:900000^' /usr/lib/systemd/system/docker.service
    sudo systemctl daemon-reload
    sudo install -o root -g root -d /mnt/docker
    sudo usermod -aG docker $(id -u -n)
    sudo mkdir -p /etc/docker
    echo '{"experimental": true, "ipv6": true, "fixed-cidr-v6": "fd3c:a8b0:18eb:5c06::/64"}' | sudo tee /etc/docker/daemon.json
    sudo systemctl status docker || sudo systemctl start docker
    sudo service docker status || sudo service docker start
    echo "* * * * * root /usr/sbin/route add default gw 10.199.1.1 eth0" | sudo tee /etc/cron.d/fix-default-route
'''
initMap['docker-32gb'] = initMap['docker']
initMap['docker-64gb'] = initMap['docker']
initMap['docker2'] = initMap['docker']
initMap['micro-amazon'] = '''
    set -o xtrace
    RHVER=$(rpm --eval %rhel)
    if ! mountpoint -q /mnt; then
        for DEVICE_NAME in $(lsblk -ndpbo NAME,SIZE | sort -n -r | awk '{print $1}'); do
            if ! grep -qs "${DEVICE_NAME}" /proc/mounts; then
                DEVICE="${DEVICE_NAME}"
                break
            fi
        done
        if [ -n "${DEVICE}" ]; then
            sudo mkfs.ext2 ${DEVICE}
            sudo mount ${DEVICE} /mnt
        fi
    fi

    echo '10.30.6.9 repo.ci.percona.com' | sudo tee -a /etc/hosts

    if [[ ${RHVER} -eq 8 ]] || [[ ${RHVER} -eq 7 ]]; then
        sudo sed -i 's/mirrorlist/#mirrorlist/g' /etc/yum.repos.d/CentOS-*
        sudo sed -i 's|#baseurl=http://mirror.centos.org|baseurl=http://vault.centos.org|g' /etc/yum.repos.d/CentOS-*
    fi

    until sudo yum makecache; do
        sleep 1
        echo try again
    done
    if [[ -f /etc/os-release ]] && . /etc/os-release && [[ "${ID}" == "amzn" ]]; then
        if command -v amazon-linux-extras >/dev/null 2>&1; then
            sudo amazon-linux-extras install epel -y
        fi
        sudo yum -y install java-17-amazon-corretto-headless tzdata-java || :
    else
        sudo yum -y install java-17-openjdk-headless tzdata-java || :
    fi
    sudo yum -y install git || :
    sudo yum -y install aws-cli || :
    sudo install -o $(id -u -n) -g $(id -g -n) -d /mnt/jenkins
'''
initMap['min-amazon-2-x64']  = initMap['micro-amazon']
initMap['min-al2023-x64']    = initMap['micro-amazon']
initMap['min-centos-7-x64']  = initMap['micro-amazon']
initMap['fips-centos-7-x64'] = initMap['micro-amazon']
initMap['min-centos-8-x64']  = initMap['micro-amazon']
initMap['min-ol-8-x64']      = initMap['micro-amazon']
initMap['min-ol-9-x64']      = initMap['micro-amazon']

// Custom init script for RHEL 10 (Java 21)
initMap['min-rhel-10-x64'] = '''
    set -o xtrace
    RHVER=$(rpm --eval %rhel)
    if ! mountpoint -q /mnt; then
        for DEVICE_NAME in $(lsblk -ndpbo NAME,SIZE | sort -n -r | awk '{print $1}'); do
            if ! grep -qs "${DEVICE_NAME}" /proc/mounts; then
                DEVICE="${DEVICE_NAME}"
                break
            fi
        done
        if [ -n "${DEVICE}" ]; then
            sudo mkfs.ext2 ${DEVICE}
            sudo mount ${DEVICE} /mnt
        fi
    fi

    echo '10.30.6.9 repo.ci.percona.com' | sudo tee -a /etc/hosts

    until sudo yum makecache; do
        sleep 1
        echo try again
    done
    sudo yum -y install java-21-openjdk-headless tzdata-java || :
    sudo yum -y install awscli2 || :
    sudo yum -y install git || :
    sudo install -o $(id -u -n) -g $(id -g -n) -d /mnt/jenkins
'''
initMap['min-bionic-x64'] = '''
    set -o xtrace
    if ! mountpoint -q /mnt; then
        for DEVICE_NAME in $(lsblk -ndpbo NAME,SIZE | sort -n -r | awk '{print $1}'); do
            if ! grep -qs "${DEVICE_NAME}" /proc/mounts; then
                DEVICE="${DEVICE_NAME}"
                break
            fi
        done
        if [ -n "${DEVICE}" ]; then
            sudo mkfs.ext2 ${DEVICE}
            sudo mount ${DEVICE} /mnt
        fi
    fi

    echo '10.30.6.9 repo.ci.percona.com' | sudo tee -a /etc/hosts

    until sudo apt-get update; do
        sleep 1
        echo try again
    done
    until sudo apt-get -y install openjdk-17-jre-headless git; do
        sleep 1
        echo try again
    done
    sudo install -o $(id -u -n) -g $(id -g -n) -d /mnt/jenkins
'''
initMap['min-buster-x64'] = '''
    set -o xtrace
    if ! mountpoint -q /mnt; then
        for DEVICE_NAME in $(lsblk -ndpbo NAME,SIZE | sort -n -r | awk '{print $1}'); do
            if ! grep -qs "${DEVICE_NAME}" /proc/mounts; then
                DEVICE="${DEVICE_NAME}"
                break
            fi
        done
        if [ -n "${DEVICE}" ]; then
            sudo mkfs.ext2 ${DEVICE}
            sudo mount ${DEVICE} /mnt
        fi
    fi

    echo '10.30.6.9 repo.ci.percona.com' | sudo tee -a /etc/hosts

    sudo sed -i '/buster-backports/ s/cdn-aws.deb.debian.org/archive.debian.org/' /etc/apt/sources.list
    sudo sed -i '/bullseye-backports/ s/cdn-aws.deb.debian.org/archive.debian.org/' /etc/apt/sources.list

    until sudo apt-get update; do
        sleep 1
        echo try again
    done

    DEB_VER=$(lsb_release -sc)
    # Java version based on Debian/Ubuntu version
    if [[ ${DEB_VER} == "trixie" ]]; then
        JAVA_VER="openjdk-21-jre-headless"
    else
        JAVA_VER="openjdk-17-jre-headless"
    fi
    if [[ ${DEB_VER} == "trixie" ]] || [[ ${DEB_VER} == "bookworm" ]] || [[ ${DEB_VER} == "bullseye" ]] || [[ ${DEB_VER} == "buster" ]]; then
        sudo DEBIAN_FRONTEND=noninteractive sudo apt-get -y install ${JAVA_VER} git
        sudo mv /etc/ssl /etc/ssl_old
        sudo DEBIAN_FRONTEND=noninteractive sudo apt-get -y install ${JAVA_VER}
        sudo cp -r /etc/ssl_old /etc/ssl
        sudo DEBIAN_FRONTEND=noninteractive sudo apt-get -y install ${JAVA_VER}
    else
        sudo apt-get -y install ${JAVA_VER} git
    fi

    sudo install -o $(id -u -n) -g $(id -g -n) -d /mnt/jenkins
'''
initMap['min-jammy-x64'] = initMap['min-bionic-x64']
initMap['min-noble-x64'] = initMap['min-bionic-x64']
initMap['min-focal-x64'] = initMap['min-bionic-x64']
initMap['min-bookworm-x64'] = initMap['min-buster-x64']
initMap['min-bullseye-x64'] = initMap['min-buster-x64']
initMap['min-trixie-x64']   = initMap['min-buster-x64']
initMap['min-hirsute-x64-zenfs'] = initMap['min-bionic-x64']
initMap['min-focal-x64-zenfs'] = initMap['min-bionic-x64']
initMap['min-bionic-x64-zenfs'] = initMap['min-bionic-x64']

initMap['docker-32gb-aarch64'] = initMap['docker']
initMap['docker-64gb-aarch64'] = initMap['docker']
initMap['min-al2023-aarch64']  = initMap['micro-amazon']
initMap['min-jammy-aarch64']   = initMap['min-buster-x64']
initMap['min-noble-aarch64']   = initMap['min-buster-x64']
initMap['min-bullseye-aarch64'] = initMap['min-buster-x64']
initMap['min-bookworm-aarch64'] = initMap['min-buster-x64']
initMap['min-trixie-aarch64']   = initMap['min-buster-x64']

capMap = [:]
capMap['c5.xlarge']    = '60'
capMap['g4ad.4xlarge'] = '40'
capMap['r6a.4xlarge']   = '40'
capMap['i4i.2xlarge']  = '40'

capMap['m6g.2xlarge'] = '20'
capMap['m7g.4xlarge'] = '20'

typeMap = [:]
typeMap['micro-amazon']      = 't2.small'
typeMap['docker']            = 'c5.xlarge'
typeMap['docker-32gb']       = 'g4ad.4xlarge'
typeMap['docker-64gb']       = 'g4ad.4xlarge'
typeMap['docker2']           = 'r6a.4xlarge'
typeMap['min-centos-7-x64']  = typeMap['docker-32gb']
typeMap['min-centos-8-x64']  = typeMap['min-centos-7-x64']
typeMap['min-ol-8-x64']      = typeMap['min-centos-7-x64']
typeMap['min-ol-9-x64']      = 'i4i.2xlarge'
typeMap['min-rhel-10-x64']   = typeMap['docker']
typeMap['min-al2023-x64']    = typeMap['docker']
typeMap['min-amazon-2-x64']  = typeMap['docker']
typeMap['fips-centos-7-x64'] = typeMap['min-centos-7-x64']
typeMap['min-jammy-x64']     = typeMap['min-centos-7-x64']
typeMap['min-noble-x64']     = typeMap['min-centos-7-x64']
typeMap['min-focal-x64']     = typeMap['min-centos-7-x64']
typeMap['min-bionic-x64']    = typeMap['min-centos-7-x64']
typeMap['min-bookworm-x64']  = typeMap['min-centos-7-x64']
typeMap['min-bullseye-x64']  = typeMap['min-centos-7-x64']
typeMap['min-buster-x64']    = typeMap['min-centos-7-x64']
typeMap['min-trixie-x64']    = typeMap['docker']
typeMap['min-hirsute-x64-zenfs'] = typeMap['min-centos-7-x64']
typeMap['min-focal-x64-zenfs'] = typeMap['min-centos-7-x64']
typeMap['min-bionic-x64-zenfs'] = typeMap['min-centos-7-x64']

typeMap['docker-32gb-aarch64'] = 'm6g.2xlarge'
typeMap['docker-64gb-aarch64'] = 'm7g.4xlarge'
typeMap['min-al2023-aarch64']  = typeMap['docker-32gb-aarch64']
typeMap['min-jammy-aarch64']   = typeMap['docker-32gb-aarch64']
typeMap['min-noble-aarch64']   = typeMap['docker-32gb-aarch64']
typeMap['min-bullseye-aarch64'] = typeMap['docker-32gb-aarch64']
typeMap['min-bookworm-aarch64'] = typeMap['docker-32gb-aarch64']
typeMap['min-trixie-aarch64']   = typeMap['docker-32gb-aarch64']

execMap = [:]
execMap['docker']            = '1'
execMap['docker-32gb']       = execMap['docker']
execMap['docker-64gb']       = execMap['docker']
execMap['docker2']           = execMap['docker']
execMap['micro-amazon']      = '30'
execMap['min-amazon-2-x64']  = '1'
execMap['min-bionic-x64']    = '1'
execMap['min-focal-x64']     = '1'
execMap['min-jammy-x64']     = '1'
execMap['min-noble-x64']     = '1'
execMap['min-centos-7-x64']  = '1'
execMap['fips-centos-7-x64'] = '1'
execMap['min-centos-8-x64']  = '1'
execMap['min-ol-8-x64']      = '1'
execMap['min-ol-9-x64']      = '1'
execMap['min-rhel-10-x64']   = '1'
execMap['min-al2023-x64']    = '1'
execMap['min-buster-x64']    = '1'
execMap['min-bullseye-x64']  = '1'
execMap['min-bookworm-x64']  = '1'
execMap['min-trixie-x64']    = '1'
execMap['min-hirsute-x64-zenfs'] = '1'
execMap['min-focal-x64-zenfs'] = '1'
execMap['min-bionic-x64-zenfs'] = '1'

execMap['docker-32gb-aarch64'] = execMap['docker']
execMap['docker-64gb-aarch64'] = execMap['docker']
execMap['min-al2023-aarch64']  = '1'
execMap['min-jammy-aarch64']   = '1'
execMap['min-noble-aarch64']   = '1'
execMap['min-bullseye-aarch64'] = '1'
execMap['min-bookworm-aarch64'] = '1'
execMap['min-trixie-aarch64']   = '1'

devMap = [:]
devMap['docker']                = '/dev/xvda=:8:true:gp2,/dev/xvdd=:320:true:gp2'
devMap['docker2']               = '/dev/xvda=:8:true:gp2,/dev/xvdd=:220:true:gp2'
devMap['docker-32gb']           = devMap['docker']
devMap['docker-64gb']           = devMap['docker']
devMap['micro-amazon']          = devMap['docker']
devMap['min-amazon-2-x64']      = '/dev/xvda=:30:true:gp2,/dev/xvdd=:220:true:gp2'
devMap['min-bionic-x64']        = '/dev/sda1=:30:true:gp2,/dev/sdd=:220:true:gp2'
devMap['min-focal-x64']         = devMap['min-bionic-x64']
devMap['min-jammy-x64']         = devMap['min-bionic-x64']
devMap['min-noble-x64']         = devMap['min-bionic-x64']
devMap['min-centos-7-x64']      = devMap['min-bionic-x64']
devMap['fips-centos-7-x64']     = devMap['min-bionic-x64']
devMap['min-centos-8-x64']      = '/dev/sda1=:30:true:gp2,/dev/sdd=:220:true:gp2'
devMap['min-ol-8-x64']          = '/dev/sda1=:30:true:gp2,/dev/sdd=:220:true:gp2'
devMap['min-ol-9-x64']          = '/dev/sda1=:30:true:gp2,/dev/sdd=:220:true:gp2'
devMap['min-rhel-10-x64']       = '/dev/sda1=:30:true:gp3,/dev/sdd=:220:true:gp3'
devMap['min-al2023-x64']        = '/dev/xvda=:30:true:gp3,/dev/xvdd=:220:true:gp3'
devMap['min-buster-x64']        = '/dev/xvda=:30:true:gp2,/dev/sdd=:220:true:gp2'
devMap['min-bullseye-x64']      = '/dev/xvda=:30:true:gp2,/dev/sdd=:220:true:gp2'
devMap['min-bookworm-x64']      = '/dev/xvda=:30:true:gp2,/dev/sdd=:220:true:gp2'
devMap['min-trixie-x64']        = '/dev/xvda=:30:true:gp3,/dev/sdd=:220:true:gp3'
devMap['min-hirsute-x64-zenfs'] = '/dev/sda1=:30:true:gp2,/dev/sdd=:220:true:gp2'
devMap['min-focal-x64-zenfs']   = '/dev/sda1=:30:true:gp2,/dev/sdd=:220:true:gp2'
devMap['min-bionic-x64-zenfs']  = '/dev/sda1=:30:true:gp2,/dev/sdd=:220:true:gp2'

devMap['docker-32gb-aarch64'] = devMap['docker']
devMap['docker-64gb-aarch64'] = devMap['docker']
devMap['min-al2023-aarch64']  = '/dev/xvda=:30:true:gp3,/dev/xvdd=:220:true:gp3'
devMap['min-jammy-aarch64']   = '/dev/sda1=:30:true:gp3,/dev/sdd=:220:true:gp3'
devMap['min-noble-aarch64']   = '/dev/sda1=:30:true:gp3,/dev/sdd=:220:true:gp3'
devMap['min-bullseye-aarch64'] = '/dev/xvda=:30:true:gp3,/dev/sdd=:220:true:gp3'
devMap['min-bookworm-aarch64'] = '/dev/xvda=:30:true:gp3,/dev/sdd=:220:true:gp3'
devMap['min-trixie-aarch64']   = '/dev/xvda=:30:true:gp3,/dev/sdd=:220:true:gp3'

labelMap = [:]
labelMap['docker']            = ''
labelMap['docker-32gb']       = ''
labelMap['docker-64gb']       = ''
labelMap['docker2']           = ''
labelMap['micro-amazon']      = 'master'
labelMap['min-amazon-2-x64']  = ''
labelMap['min-bionic-x64']    = 'asan'
labelMap['min-focal-x64']     = ''
labelMap['min-jammy-x64']     = ''
labelMap['min-noble-x64']     = ''
labelMap['min-centos-7-x64']  = ''
labelMap['fips-centos-7-x64'] = ''
labelMap['min-centos-8-x64']  = ''
labelMap['min-ol-8-x64']      = ''
labelMap['min-ol-9-x64']      = ''
labelMap['min-rhel-10-x64']   = ''
labelMap['min-al2023-x64']    = ''
labelMap['min-buster-x64']    = ''
labelMap['min-bullseye-x64']  = ''
labelMap['min-bookworm-x64']  = ''
labelMap['min-trixie-x64']    = ''
labelMap['min-hirsute-x64-zenfs']  = ''
labelMap['min-focal-x64-zenfs']    = ''
labelMap['min-bionic-x64-zenfs']   = ''

labelMap['docker-32gb-aarch64'] = ''
labelMap['docker-64gb-aarch64'] = ''
labelMap['min-al2023-aarch64']  = ''
labelMap['min-jammy-aarch64']   = ''
labelMap['min-noble-aarch64']   = ''
labelMap['min-bullseye-aarch64'] = ''
labelMap['min-bookworm-aarch64'] = ''
labelMap['min-trixie-aarch64']   = ''

jvmoptsMap = [:]
jvmoptsMap['docker']            = '-Xmx512m -Xms512m'
jvmoptsMap['docker-32gb']       = jvmoptsMap['docker']
jvmoptsMap['docker-64gb']       = jvmoptsMap['docker']
jvmoptsMap['docker2']           = jvmoptsMap['docker']
jvmoptsMap['micro-amazon']      = jvmoptsMap['docker']
jvmoptsMap['min-amazon-2-x64']  = jvmoptsMap['docker']
jvmoptsMap['min-bionic-x64']    = jvmoptsMap['docker']
jvmoptsMap['min-focal-x64']     = jvmoptsMap['docker']
jvmoptsMap['min-jammy-x64']     = jvmoptsMap['docker']
jvmoptsMap['min-noble-x64']     = jvmoptsMap['docker']
jvmoptsMap['min-centos-7-x64']  = jvmoptsMap['docker']
jvmoptsMap['fips-centos-7-x64'] = jvmoptsMap['docker']
jvmoptsMap['min-centos-8-x64']  = jvmoptsMap['docker']
jvmoptsMap['min-ol-8-x64']      = jvmoptsMap['docker']
jvmoptsMap['min-ol-9-x64']      = jvmoptsMap['docker']
jvmoptsMap['min-rhel-10-x64']   = jvmoptsMap['docker']
jvmoptsMap['min-al2023-x64']    = jvmoptsMap['docker']
jvmoptsMap['min-buster-x64']    = jvmoptsMap['docker']
jvmoptsMap['min-bullseye-x64']  = jvmoptsMap['docker']
jvmoptsMap['min-bookworm-x64']  = '-Xmx512m -Xms512m --add-opens=java.base/java.lang=ALL-UNNAMED --add-opens=java.base/java.lang.reflect=ALL-UNNAMED'
jvmoptsMap['min-trixie-x64']    = jvmoptsMap['min-bookworm-x64']
jvmoptsMap['min-hirsute-x64-zenfs']  = jvmoptsMap['docker']
jvmoptsMap['min-focal-x64-zenfs']    = jvmoptsMap['docker']
jvmoptsMap['min-bionic-x64-zenfs']   = jvmoptsMap['docker']

jvmoptsMap['docker-32gb-aarch64'] = jvmoptsMap['docker']
jvmoptsMap['docker-64gb-aarch64'] = jvmoptsMap['docker']
jvmoptsMap['min-al2023-aarch64']  = jvmoptsMap['docker']
jvmoptsMap['min-jammy-aarch64']   = jvmoptsMap['docker']
jvmoptsMap['min-noble-aarch64']   = jvmoptsMap['docker']
jvmoptsMap['min-bullseye-aarch64'] = jvmoptsMap['docker']
jvmoptsMap['min-bookworm-aarch64'] = jvmoptsMap['min-bookworm-x64']
jvmoptsMap['min-trixie-aarch64']   = jvmoptsMap['min-bookworm-x64']

// https://github.com/jenkinsci/ec2-plugin/blob/ec2-1.41/src/main/java/hudson/plugins/ec2/SlaveTemplate.java
SlaveTemplate getTemplate(String OSType, String AZ) {
    return new SlaveTemplate(
        imageMap[AZ + '.' + OSType],                // String ami
        '',                                         // String zone
        new SpotConfiguration(true, priceMap[typeMap[OSType]], false, '0'), // SpotConfiguration spotConfig
        'default',                                  // String securityGroups
        '/mnt/jenkins',                             // String remoteFS
        InstanceType.fromValue(typeMap[OSType]),    // InstanceType type
        ( typeMap[OSType].startsWith("c4") || typeMap[OSType].startsWith("m4") || typeMap[OSType].startsWith("c5") || typeMap[OSType].startsWith("m5") ), // boolean ebsOptimized
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
            new EC2Tag('Name', 'jenkins-rel-' + OSType),
            new EC2Tag('iit-billing-tag', 'jenkins-rel-worker')
        ],                                          // List<EC2Tag> tags
        '3',                                        // String idleTerminationMinutes
        0,                                          // Init minimumNumberOfInstances
        0,                                          // minimumNumberOfSpareInstances
        capMap[typeMap[OSType]],                    // String instanceCapStr
        'arn:aws:iam::119175775298:instance-profile/jenkins-rel-worker', // String iamInstanceProfile
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

String sshKeysCredentialsId = '453041d3-f7eb-4ff3-a214-7c3767e36102'

String region = 'eu-west-1'
('b'..'c').each {
    // https://github.com/jenkinsci/ec2-plugin/blob/ec2-1.41/src/main/java/hudson/plugins/ec2/AmazonEC2Cloud.java
    EC2Cloud ec2Cloud = new EC2Cloud(
        "AWS-Dev ${it}",                        // String cloudName
        true,                                   // boolean useInstanceProfileForCredentials
        '',                                     // String credentialsId
        region,                                 // String region
        privateKey,                             // String privateKey
        sshKeysCredentialsId,                   // String sshKeysCredentialsId
        '240',                                   // String instanceCapStr
        [
            getTemplate('docker',                "${region}${it}"),
            getTemplate('docker-32gb',           "${region}${it}"),
            getTemplate('docker-64gb',           "${region}${it}"),
            getTemplate('micro-amazon',          "${region}${it}"),
            getTemplate('min-amazon-2-x64',      "${region}${it}"),
            getTemplate('min-jammy-x64',         "${region}${it}"),
            getTemplate('min-noble-x64',         "${region}${it}"),
            getTemplate('min-focal-x64',         "${region}${it}"),
            getTemplate('min-bionic-x64',        "${region}${it}"),
            getTemplate('min-buster-x64',        "${region}${it}"),
            getTemplate('min-ol-8-x64',          "${region}${it}"),
            getTemplate('min-ol-9-x64',          "${region}${it}"),
            getTemplate('min-rhel-10-x64',       "${region}${it}"),
            getTemplate('min-al2023-x64',        "${region}${it}"),
            getTemplate('min-centos-8-x64',      "${region}${it}"),
            getTemplate('min-centos-7-x64',      "${region}${it}"),
            getTemplate('min-bullseye-x64',      "${region}${it}"),
            getTemplate('min-bookworm-x64',      "${region}${it}"),
            getTemplate('min-trixie-x64',        "${region}${it}"),
            // getTemplate('min-hirsute-x64-zenfs', "${region}${it}"),
            getTemplate('min-focal-x64-zenfs',   "${region}${it}"),
            getTemplate('min-bionic-x64-zenfs',  "${region}${it}"),
            getTemplate('docker-32gb-aarch64',   "${region}${it}"),
            getTemplate('docker-64gb-aarch64',   "${region}${it}"),
            getTemplate('min-al2023-aarch64',    "${region}${it}"),
            getTemplate('min-jammy-aarch64',     "${region}${it}"),
            getTemplate('min-noble-aarch64',     "${region}${it}"),
            getTemplate('min-bullseye-aarch64',  "${region}${it}"),
            getTemplate('min-bookworm-aarch64',  "${region}${it}"),
            getTemplate('min-trixie-aarch64',    "${region}${it}"),
        ],                                       // List<? extends SlaveTemplate> templates
        '',
        ''
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
