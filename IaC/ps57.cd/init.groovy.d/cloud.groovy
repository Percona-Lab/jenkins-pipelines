import com.amazonaws.services.ec2.model.InstanceType
import hudson.model.*
import hudson.plugins.ec2.AmazonEC2Cloud
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
netMap['eu-central-1b'] = 'subnet-0f06a2ce06ea122c8'
netMap['eu-central-1c'] = 'subnet-01d902b8d64d9b0dc'

imageMap = [:]
imageMap['eu-central-1a.docker']            = 'ami-0444794b421ec32e4'
imageMap['eu-central-1a.docker-32gb']       = 'ami-0444794b421ec32e4'
imageMap['eu-central-1a.docker2']           = 'ami-0444794b421ec32e4'
imageMap['eu-central-1a.micro-amazon']      = 'ami-0444794b421ec32e4'
imageMap['eu-central-1a.min-amazon-2-x64']  = 'ami-0444794b421ec32e4'
imageMap['eu-central-1a.min-al2023-x64']    = 'ami-0444794b421ec32e4'
imageMap['eu-central-1a.min-ol-8-x64']      = 'ami-0f18276190490b6e3'
imageMap['eu-central-1a.min-ol-9-x64']      = 'ami-08b280891de0645a1'
imageMap['eu-central-1a.min-rhel-10-x64']   = 'ami-076447f21b83bf9fb'
imageMap['eu-central-1a.min-centos-8-x64']  = 'ami-0a2dc38dc30ba417e'
imageMap['eu-central-1a.min-centos-7-x64']  = 'ami-0afcbcee3dfbce929'
imageMap['eu-central-1a.fips-centos-7-x64'] = 'ami-0f4ad402d76e82cbe'
imageMap['eu-central-1a.min-centos-6-x64']  = 'ami-01fc903dce948db3f'
imageMap['eu-central-1a.min-buster-x64']    = 'ami-0c984d7a384cafb51'
imageMap['eu-central-1a.min-bullseye-x64']  = 'ami-08f13e5792295e1b2'
imageMap['eu-central-1a.min-bookworm-x64']  = 'ami-037808b7c78a7263f'
imageMap['eu-central-1a.min-trixie-x64']    = 'ami-0aeb8600ead64c406'
imageMap['eu-central-1a.min-jammy-x64']     = 'ami-0e2108df824ee2a7b'
imageMap['eu-central-1a.min-noble-x64']     = 'ami-01e444924a2233b07'
imageMap['eu-central-1a.min-focal-x64']     = 'ami-0d497a49e7d359666'
imageMap['eu-central-1a.min-bionic-x64']    = 'ami-0cc29ffa555d90047'
imageMap['eu-central-1a.min-stretch-x64']   = 'ami-0d78ea9dc521d7ed3'
imageMap['eu-central-1a.min-xenial-x64']    = 'ami-0a86f18b52e547759'

imageMap['eu-central-1b.docker']            = imageMap['eu-central-1a.docker']
imageMap['eu-central-1b.docker-32gb']       = imageMap['eu-central-1a.docker-32gb']
imageMap['eu-central-1b.docker2']           = imageMap['eu-central-1a.docker2']
imageMap['eu-central-1b.micro-amazon']      = imageMap['eu-central-1a.micro-amazon']
imageMap['eu-central-1b.min-amazon-2-x64']  = imageMap['eu-central-1a.min-amazon-2-x64']
imageMap['eu-central-1b.min-al2023-x64']    = imageMap['eu-central-1a.min-al2023-x64']
imageMap['eu-central-1b.min-ol-8-x64']      = imageMap['eu-central-1a.min-ol-8-x64']
imageMap['eu-central-1b.min-ol-9-x64']      = imageMap['eu-central-1a.min-ol-9-x64']
imageMap['eu-central-1b.min-rhel-10-x64']   = imageMap['eu-central-1a.min-rhel-10-x64']
imageMap['eu-central-1b.min-centos-8-x64']  = imageMap['eu-central-1a.min-centos-8-x64']
imageMap['eu-central-1b.min-centos-7-x64']  = imageMap['eu-central-1a.min-centos-7-x64']
imageMap['eu-central-1b.fips-centos-7-x64'] = imageMap['eu-central-1a.fips-centos-7-x64']
imageMap['eu-central-1b.min-centos-6-x64']  = imageMap['eu-central-1a.min-centos-6-x64']
imageMap['eu-central-1b.min-buster-x64']    = imageMap['eu-central-1a.min-buster-x64']
imageMap['eu-central-1b.min-jammy-x64']     = imageMap['eu-central-1a.min-jammy-x64']
imageMap['eu-central-1b.min-noble-x64']     = imageMap['eu-central-1a.min-noble-x64']
imageMap['eu-central-1b.min-focal-x64']     = imageMap['eu-central-1a.min-focal-x64']
imageMap['eu-central-1b.min-bionic-x64']    = imageMap['eu-central-1a.min-bionic-x64']
imageMap['eu-central-1b.min-stretch-x64']   = imageMap['eu-central-1a.min-stretch-x64']
imageMap['eu-central-1b.min-xenial-x64']    = imageMap['eu-central-1a.min-xenial-x64']
imageMap['eu-central-1b.min-bullseye-x64']  = imageMap['eu-central-1a.min-bullseye-x64']
imageMap['eu-central-1b.min-bookworm-x64']  = imageMap['eu-central-1a.min-bookworm-x64']
imageMap['eu-central-1b.min-trixie-x64']    = imageMap['eu-central-1a.min-trixie-x64']

imageMap['eu-central-1c.docker']            = imageMap['eu-central-1a.docker']
imageMap['eu-central-1c.docker-32gb']       = imageMap['eu-central-1a.docker-32gb']
imageMap['eu-central-1c.docker2']           = imageMap['eu-central-1a.docker2']
imageMap['eu-central-1c.micro-amazon']      = imageMap['eu-central-1a.micro-amazon']
imageMap['eu-central-1c.min-amazon-2-x64']  = imageMap['eu-central-1a.min-amazon-2-x64']
imageMap['eu-central-1c.min-al2023-x64']    = imageMap['eu-central-1a.min-al2023-x64']
imageMap['eu-central-1c.min-ol-8-x64']      = imageMap['eu-central-1a.min-ol-8-x64']
imageMap['eu-central-1c.min-ol-9-x64']      = imageMap['eu-central-1a.min-ol-9-x64']
imageMap['eu-central-1c.min-rhel-10-x64']   = imageMap['eu-central-1a.min-rhel-10-x64']
imageMap['eu-central-1c.min-centos-8-x64']  = imageMap['eu-central-1a.min-centos-8-x64']
imageMap['eu-central-1c.min-centos-7-x64']  = imageMap['eu-central-1a.min-centos-7-x64']
imageMap['eu-central-1c.fips-centos-7-x64'] = imageMap['eu-central-1a.fips-centos-7-x64']
imageMap['eu-central-1c.min-centos-6-x64']  = imageMap['eu-central-1a.min-centos-6-x64']
imageMap['eu-central-1c.min-buster-x64']    = imageMap['eu-central-1a.min-buster-x64']
imageMap['eu-central-1c.min-jammy-x64']     = imageMap['eu-central-1a.min-jammy-x64']
imageMap['eu-central-1c.min-noble-x64']     = imageMap['eu-central-1a.min-noble-x64']
imageMap['eu-central-1c.min-focal-x64']     = imageMap['eu-central-1a.min-focal-x64']
imageMap['eu-central-1c.min-bionic-x64']    = imageMap['eu-central-1a.min-bionic-x64']
imageMap['eu-central-1c.min-stretch-x64']   = imageMap['eu-central-1a.min-stretch-x64']
imageMap['eu-central-1c.min-xenial-x64']    = imageMap['eu-central-1a.min-xenial-x64']
imageMap['eu-central-1c.min-bullseye-x64']  = imageMap['eu-central-1a.min-bullseye-x64']
imageMap['eu-central-1c.min-bookworm-x64']  = imageMap['eu-central-1a.min-bookworm-x64']
imageMap['eu-central-1c.min-trixie-x64']    = imageMap['eu-central-1a.min-trixie-x64']

priceMap = [:]
priceMap['m5a.large'] = '0.09' // type=m5a.large, vCPU=2, memory=8GiB, saving=52%, interruption='<5%', price=0.056500
priceMap['m1.medium'] = '0.05' // centos6 x32
priceMap['m6i.xlarge'] = '0.15' // type=m6i.xlarge, vCPU=4, memory=16GiB, saving=65%, interruption='<5%', price=0.104100
priceMap['r6a.4xlarge'] = '0.58' // type=r6a.4xlarge, vCPU=16, memory=128GiB, saving=60%, interruption='<5%', price=0.503000
priceMap['c5d.xlarge'] = '0.17' // type=c5d.xlarge, vCPU=4, memory=8GiB, saving=60%, interruption='<5%', price=0.100700
priceMap['i3en.2xlarge'] = '0.40' // type=i3en.2xlarge, vCPU=8, memory=64GiB, saving=70%, interruption='<5%', price=0.324000

userMap = [:]
userMap['docker']            = 'ec2-user'
userMap['docker-32gb']       = userMap['docker']
userMap['docker2']           = userMap['docker']
userMap['micro-amazon']      = userMap['docker']
userMap['min-amazon-2-x64']  = userMap['docker']
userMap['min-al2023-x64']    = userMap['docker']
userMap['min-rhel-10-x64']   = userMap['docker']
userMap['min-jammy-x64']     = 'ubuntu'
userMap['min-noble-x64']     = 'ubuntu'
userMap['min-focal-x64']     = 'ubuntu'
userMap['min-bionic-x64']    = 'ubuntu'
userMap['min-trusty-x64']    = 'ubuntu'
userMap['min-xenial-x64']    = 'ubuntu'
userMap['min-centos-6-x32']  = 'root'
userMap['min-centos-6-x64']  = 'centos'
userMap['min-centos-7-x64']  = 'centos'
userMap['fips-centos-7-x64'] = 'centos'
userMap['min-centos-8-x64']  = 'centos'
userMap['min-ol-8-x64']      = 'ec2-user'
userMap['min-ol-9-x64']      = 'ec2-user'
userMap['min-stretch-x64']   = 'admin'
userMap['min-buster-x64']    = 'admin'
userMap['min-bullseye-x64']  = 'admin'
userMap['min-bookworm-x64']  = 'admin'
userMap['min-trixie-x64']    = 'admin'

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
    until sudo yum makecache; do
        sleep 1
        echo try again
    done

    sudo yum -y install java-17-amazon-corretto tzdata-java git docker p7zip cronie
    sudo yum -y remove awscli

    if ! $(aws --version | grep -q 'aws-cli/2'); then
        sudo rm -rf /tmp/aws* || true
        until curl "https://awscli.amazonaws.com/awscli-exe-linux-x86_64.zip" -o "/tmp/awscliv2.zip"; do
            sleep 1
            echo try again
        done

        7za -aoa -o/tmp x /tmp/awscliv2.zip
        cd /tmp/aws && sudo ./install
    fi

    sudo systemctl enable crond
    sudo systemctl start crond

    echo '10.30.6.9 repo.ci.percona.com' | sudo tee -a /etc/hosts

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
    echo "* * * * * root /usr/sbin/route add default gw 10.177.1.1 eth0" | sudo tee /etc/cron.d/fix-default-route
'''

initMap['rpmMap'] = '''
    set -o xtrace
    RHVER=$(rpm --eval %rhel)
    ARCH=$(uname -m)
    SYSREL=$(cat /etc/system-release | tr -dc '0-9.'|awk -F'.' {'print $1'})

    if ! mountpoint -q /mnt; then
        for DEVICE_NAME in $(lsblk -ndbo NAME,SIZE | sort -n -r | awk '{print $1}'); do
            if ! grep -qs "${DEVICE_NAME}" /proc/mounts; then
                DEVICE="/dev/${DEVICE_NAME}"
                break
            fi
        done
        if [ -n "${DEVICE}" ]; then
            sudo mkfs.ext2 ${DEVICE}
            sudo mount ${DEVICE} /mnt
        fi
    fi

    if [[ ${RHVER} -eq 6 ]]; then
        if [[ ${ARCH} == "x86_64" ]]; then
            sudo curl https://jenkins.percona.com/downloads/cent6/centos6-eol.repo --output /etc/yum.repos.d/CentOS-Base.repo
        else
            sudo curl -k https://jenkins.percona.com/downloads/cent6/centos6-eol-s3.repo --output /etc/yum.repos.d/CentOS-Base.repo
        fi
        until sudo yum makecache; do
            sleep 1
            echo try again
        done
        if [[ ${ARCH} == "x86_64" ]]; then
            PKGLIST="epel-release centos-release-scl"
        else
            PKGLIST="epel-release"
        fi
        until sudo yum -y install ${PKGLIST}; do
            sleep 1
            echo try again
        done
        sudo rm /etc/yum.repos.d/epel-testing.repo
        sudo curl https://jenkins.percona.com/downloads/cent6/centos6-epel-eol.repo --output /etc/yum.repos.d/epel.repo
        if [[ ${ARCH} == "x86_64" ]]; then
            sudo curl https://jenkins.percona.com/downloads/cent6/centos6-scl-eol.repo --output /etc/yum.repos.d/CentOS-SCLo-scl.repo
            sudo curl https://jenkins.percona.com/downloads/cent6/centos6-scl-rh-eol.repo --output /etc/yum.repos.d/CentOS-SCLo-scl-rh.repo
        fi
    fi

    # Determine Java version based on OS
    if [[ ${RHVER} -eq 10 ]]; then
        JAVA_VER="java-21-openjdk"
    elif [[ $SYSREL -eq 2023 ]]; then
        JAVA_VER="java-17-amazon-corretto"
    else
        JAVA_VER="java-17-openjdk"
    fi
    PKGLIST="unzip"

    if [[ ${RHVER} -eq 8 ]] || [[ ${RHVER} -eq 7 ]]; then
        sudo sed -i 's/mirrorlist/#mirrorlist/g' /etc/yum.repos.d/CentOS-*
        sudo sed -i 's|#baseurl=http://mirror.centos.org|baseurl=http://vault.centos.org|g' /etc/yum.repos.d/CentOS-*
    fi

    until sudo yum makecache; do
        sleep 1
        echo try again
    done

    sudo yum -y install git || :
    sudo yum -y remove java-1.8.0-openjdk || :
    sudo yum -y remove java-1.8.0-openjdk-headless || :
    sudo yum -y remove awscli || :
    sudo yum -y install ${JAVA_VER} tzdata-java || :
    sudo yum -y install ${PKGLIST} || :
    sudo yum -y remove java-1.7.0-openjdk || :
    sudo install -o $(id -u -n) -g $(id -g -n) -d /mnt/jenkins

    # Install AWS CLI v2 for all systems
    if ! $(aws --version | grep -q 'aws-cli/2'); then
        sudo rm -rf /tmp/aws* || true

        until curl "https://awscli.amazonaws.com/awscli-exe-linux-x86_64.zip" -o "/tmp/awscliv2.zip"; do
            sleep 1
            echo try again
        done

        cd /tmp && unzip -q awscliv2.zip
        sudo /tmp/aws/install
    fi

    # CentOS 6 x32 workarounds
    if [[ ${ARCH} != "x86_64" ]]; then
        echo 'Defaults !requiretty' | sudo tee /etc/sudoers.d/requiretty
        if [ ! -f /mnt/swapfile ]; then
            sudo dd if=/dev/zero of=/mnt/swapfile bs=1024 count=524288
            sudo chown root:root /mnt/swapfile
            sudo chmod 0600 /mnt/swapfile
            sudo mkswap /mnt/swapfile
            sudo swapon /mnt/swapfile
        fi
        sudo /bin/sed -i '/shm/s/defaults/defaults,size=2500M/' /etc/fstab
        sudo umount /dev/shm
        sudo mount /dev/shm
    fi

    echo '10.30.6.9 repo.ci.percona.com' | sudo tee -a /etc/hosts
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
            sudo mkfs.ext2 ${DEVICE}
            sudo mount ${DEVICE} /mnt
        fi
    fi

    until sudo apt-get install -y lsb-release; do
        sleep 1
        echo try again
    done
    DEB_VER=$(lsb_release -sc)

    # Fix Debian Stretch EOL repositories
    if [[ ${DEB_VER} == "stretch" ]]; then
        sudo sed -i 's|http://deb.debian.org/debian stretch|http://archive.debian.org/debian stretch|' /etc/apt/sources.list
        sudo sed -i 's|http://security.debian.org/debian-security stretch|http://archive.debian.org/debian-security stretch|' /etc/apt/sources.list
        sudo sed -i '/stretch-updates/d' /etc/apt/sources.list
    fi

    # Fix Debian Buster/Bullseye backports
    sudo sed -i '/buster-backports/ s/cdn-aws.deb.debian.org/archive.debian.org/' /etc/apt/sources.list
    sudo sed -i '/bullseye-backports/ s/cdn-aws.deb.debian.org/archive.debian.org/' /etc/apt/sources.list

    until sudo apt-get update; do
        sleep 1
        echo try again
    done

    # Determine Java version based on distribution
    if [[ ${DEB_VER} == "trixie" ]]; then
        JAVA_VER="openjdk-21-jre-headless"
    elif [[ ${DEB_VER} == "bookworm" ]] || [[ ${DEB_VER} == "jammy" ]] || [[ ${DEB_VER} == "noble" ]] || [[ ${DEB_VER} == "focal" ]] || [[ ${DEB_VER} == "bionic" ]] || [[ ${DEB_VER} == "xenial" ]]; then
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

initMap['docker-32gb'] = initMap['docker']
initMap['docker2'] = initMap['docker']

initMap['micro-amazon']      = initMap['rpmMap']
initMap['min-amazon-2-x64']  = initMap['rpmMap']
initMap['min-al2023-x64']    = initMap['rpmMap']
initMap['min-centos-6-x64']  = initMap['rpmMap']
initMap['min-centos-7-x64']  = initMap['rpmMap']
initMap['fips-centos-7-x64'] = initMap['rpmMap']
initMap['min-centos-8-x64']  = initMap['rpmMap']
initMap['min-ol-8-x64']      = initMap['rpmMap']
initMap['min-ol-9-x64']      = initMap['rpmMap']
initMap['min-rhel-10-x64']   = initMap['rpmMap']
initMap['min-centos-6-x32']  = initMap['rpmMap']

initMap['min-bionic-x64']   = initMap['debMap']
initMap['min-bookworm-x64'] = initMap['debMap']
initMap['min-bullseye-x64'] = initMap['debMap']
initMap['min-buster-x64']   = initMap['debMap']
initMap['min-jammy-x64']    = initMap['debMap']
initMap['min-noble-x64']    = initMap['debMap']
initMap['min-trixie-x64']   = initMap['debMap']
initMap['min-focal-x64']    = initMap['debMap']
initMap['min-stretch-x64']  = initMap['debMap']
initMap['min-xenial-x64']   = initMap['debMap']

capMap = [:]
capMap['m5a.large']  = '5'
capMap['i3en.2xlarge'] = '40'
capMap['r6a.4xlarge'] = '40'
capMap['c5d.xlarge'] = '60'
capMap['i3en.2xlarge'] = '40'

typeMap = [:]
typeMap['micro-amazon']      = 'm5a.large'
typeMap['docker']            = 'c5d.xlarge'
typeMap['docker-32gb']       = 'i3en.2xlarge'
typeMap['docker2']           = 'r6a.4xlarge'
typeMap['min-centos-7-x64']  = typeMap['docker']
typeMap['fips-centos-7-x64'] = typeMap['min-centos-7-x64']
typeMap['min-centos-8-x64']  = typeMap['min-centos-7-x64']
typeMap['min-ol-8-x64']      = typeMap['min-centos-7-x64']
typeMap['min-ol-9-x64']      = 'i3en.2xlarge'
typeMap['min-rhel-10-x64']   = typeMap['min-centos-7-x64']
typeMap['min-jammy-x64']     = typeMap['min-centos-7-x64']
typeMap['min-noble-x64']     = typeMap['min-centos-7-x64']
typeMap['min-focal-x64']     = typeMap['min-centos-7-x64']
typeMap['min-bionic-x64']    = typeMap['min-centos-7-x64']
typeMap['min-buster-x64']    = typeMap['min-centos-7-x64']
typeMap['min-centos-6-x32']  = 'm1.medium'
typeMap['min-centos-6-x64']  = 'm6i.xlarge'
typeMap['min-stretch-x64']   = typeMap['docker']
typeMap['min-xenial-x64']    = typeMap['docker']
typeMap['min-amazon-2-x64']  = typeMap['micro-amazon']
typeMap['min-al2023-x64']    = typeMap['micro-amazon']
typeMap['min-bullseye-x64']  = typeMap['docker']
typeMap['min-bookworm-x64']  = typeMap['docker']
typeMap['min-trixie-x64']    = typeMap['docker']

execMap = [:]
execMap['docker']            = '1'
execMap['docker-32gb']       = execMap['docker']
execMap['docker2']           = execMap['docker']
execMap['micro-amazon']      = '30'
execMap['min-amazon-2-x64']  = '1'
execMap['min-al2023-x64']    = '1'
execMap['min-jammy-x64']     = '1'
execMap['min-noble-x64']     = '1'
execMap['min-focal-x64']     = '1'
execMap['min-bionic-x64']    = '1'
execMap['min-centos-6-x32']  = '1'
execMap['min-centos-6-x64']  = '1'
execMap['min-centos-7-x64']  = '1'
execMap['min-centos-8-x64']  = '1'
execMap['min-ol-8-x64']      = '1'
execMap['min-ol-9-x64']      = '1'
execMap['min-rhel-10-x64']   = '1'
execMap['fips-centos-7-x64'] = '1'
execMap['min-stretch-x64']   = '1'
execMap['min-xenial-x64']    = '1'
execMap['min-buster-x64']    = '1'
execMap['min-bullseye-x64']  = '1'
execMap['min-bookworm-x64']  = '1'
execMap['min-trixie-x64']    = '1'

devMap = [:]
devMap['docker']            = '/dev/xvda=:20:true:gp2,/dev/xvdd=:100:true:gp2'
devMap['docker2']           = '/dev/xvda=:20:true:gp2,/dev/xvdd=:80:true:gp2'
devMap['docker-32gb']       = devMap['docker']
devMap['micro-amazon']      = devMap['docker']
devMap['min-amazon-2-x64']  = '/dev/xvda=:30:true:gp2,/dev/xvdd=:80:true:gp2'
devMap['min-al2023-x64']    = devMap['min-amazon-2-x64']
devMap['min-bionic-x64']    = '/dev/sda1=:30:true:gp2,/dev/sdd=:80:true:gp2'
devMap['min-jammy-x64']     = devMap['min-bionic-x64']
devMap['min-noble-x64']     = devMap['min-bionic-x64']
devMap['min-focal-x64']     = devMap['min-bionic-x64']
devMap['min-centos-6-x64']  = devMap['min-bionic-x64']
devMap['min-centos-7-x64']  = devMap['min-bionic-x64']
devMap['min-centos-8-x64']  = '/dev/sda1=:30:true:gp2,/dev/sdd=:80:true:gp2'
devMap['min-ol-8-x64']      = '/dev/sda1=:30:true:gp2,/dev/sdd=:80:true:gp2'
devMap['min-ol-9-x64']      = '/dev/sda1=:30:true:gp2,/dev/sdd=:80:true:gp2'
devMap['min-rhel-10-x64']   = '/dev/sda1=:30:true:gp2,/dev/sdd=:80:true:gp2'
devMap['fips-centos-7-x64'] = devMap['min-bionic-x64']
devMap['min-stretch-x64']   = 'xvda=:30:true:gp2,xvdd=:80:true:gp2'
devMap['min-xenial-x64']    = devMap['min-bionic-x64']
devMap['min-centos-6-x32']  = '/dev/sda=:30:true:gp2,/dev/sdd=:80:true:gp2'
devMap['min-buster-x64']    = '/dev/xvda=:30:true:gp2,/dev/xvdd=:80:true:gp2'
devMap['min-bullseye-x64']  = '/dev/xvda=:30:true:gp2,/dev/xvdd=:80:true:gp2'
devMap['min-bookworm-x64']  = '/dev/xvda=:30:true:gp2,/dev/xvdd=:80:true:gp2'
devMap['min-trixie-x64']    = '/dev/xvda=:30:true:gp2,/dev/xvdd=:80:true:gp2'

labelMap = [:]
labelMap['docker']            = ''
labelMap['docker-32gb']       = ''
labelMap['docker2']           = 'docker2'
labelMap['micro-amazon']      = 'master'
labelMap['min-amazon-2-x64']  = ''
labelMap['min-al2023-x64']    = ''
labelMap['min-bionic-x64']    = 'asan'
labelMap['min-jammy-x64']     = ''
labelMap['min-noble-x64']     = ''
labelMap['min-focal-x64']     = ''
labelMap['min-centos-6-x32']  = ''
labelMap['min-centos-6-x64']  = ''
labelMap['min-centos-7-x64']  = ''
labelMap['min-centos-8-x64']  = ''
labelMap['min-ol-8-x64']      = ''
labelMap['min-ol-9-x64']      = ''
labelMap['min-rhel-10-x64']   = ''
labelMap['fips-centos-7-x64'] = ''
labelMap['min-stretch-x64']   = ''
labelMap['min-xenial-x64']    = ''
labelMap['min-buster-x64']    = ''
labelMap['min-bullseye-x64']  = ''
labelMap['min-bookworm-x64']  = ''
labelMap['min-trixie-x64']    = ''

jvmoptsMap = [:]
jvmoptsMap['docker']            = '-Xmx512m -Xms512m'
jvmoptsMap['docker-32gb']       = jvmoptsMap['docker']
jvmoptsMap['docker2']           = jvmoptsMap['docker']
jvmoptsMap['micro-amazon']      = jvmoptsMap['docker']
jvmoptsMap['min-amazon-2-x64']  = jvmoptsMap['docker']
jvmoptsMap['min-al2023-x64']    = jvmoptsMap['docker']
jvmoptsMap['min-bionic-x64']    = jvmoptsMap['docker']
jvmoptsMap['min-jammy-x64']     = jvmoptsMap['docker']
jvmoptsMap['min-noble-x64']     = jvmoptsMap['docker']
jvmoptsMap['min-focal-x64']     = jvmoptsMap['docker']
jvmoptsMap['min-centos-6-x32']  = jvmoptsMap['docker']
jvmoptsMap['min-centos-6-x64']  = jvmoptsMap['docker']
jvmoptsMap['min-centos-7-x64']  = jvmoptsMap['docker']
jvmoptsMap['min-centos-8-x64']  = jvmoptsMap['docker']
jvmoptsMap['min-ol-8-x64']      = jvmoptsMap['docker']
jvmoptsMap['min-ol-9-x64']      = jvmoptsMap['docker']
jvmoptsMap['min-rhel-10-x64']   = jvmoptsMap['docker']
jvmoptsMap['fips-centos-7-x64'] = jvmoptsMap['docker']
jvmoptsMap['min-stretch-x64']   = jvmoptsMap['docker']
jvmoptsMap['min-xenial-x64']    = jvmoptsMap['docker']
jvmoptsMap['min-buster-x64']    = jvmoptsMap['docker']
jvmoptsMap['min-bullseye-x64']  = jvmoptsMap['docker']
jvmoptsMap['min-bookworm-x64']  = '-Xmx512m -Xms512m --add-opens=java.base/java.lang=ALL-UNNAMED --add-opens=java.base/java.lang.reflect=ALL-UNNAMED'
jvmoptsMap['min-trixie-x64']    = jvmoptsMap['min-bookworm-x64']

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
            new EC2Tag('Name', 'jenkins-ps57-' + OSType),
            new EC2Tag('iit-billing-tag', 'jenkins-ps57-worker')
        ],                                          // List<EC2Tag> tags
        '3',                                        // String idleTerminationMinutes
        0,                                          // Init minimumNumberOfInstances
        0,                                          // minimumNumberOfSpareInstances
        capMap[typeMap[OSType]],                    // String instanceCapStr
        'arn:aws:iam::119175775298:instance-profile/jenkins-ps57-worker', // String iamInstanceProfile
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

String sshKeysCredentialsId = '8c46bb5e-04d9-4e07-b890-d097adfc7a2b'

String region = 'eu-central-1'
('b'..'c').each {
    // https://github.com/jenkinsci/ec2-plugin/blob/ec2-1.41/src/main/java/hudson/plugins/ec2/AmazonEC2Cloud.java
    AmazonEC2Cloud ec2Cloud = new AmazonEC2Cloud(
        "AWS-Dev ${it}",                        // String cloudName
        true,                                   // boolean useInstanceProfileForCredentials
        '',                                     // String credentialsId
        region,                                 // String region
        privateKey,                             // String privateKey
        sshKeysCredentialsId,                   // String sshKeysCredentialsId
        '240',                                   // String instanceCapStr
        [
            getTemplate('docker',             "${region}${it}"),
            getTemplate('docker-32gb',        "${region}${it}"),
            getTemplate('micro-amazon',       "${region}${it}"),
            getTemplate('min-amazon-2-x64',   "${region}${it}"),
            getTemplate('min-al2023-x64',     "${region}${it}"),
            getTemplate('min-ol-8-x64',       "${region}${it}"),
            getTemplate('min-ol-9-x64',       "${region}${it}"),
            getTemplate('min-rhel-10-x64',    "${region}${it}"),
            getTemplate('min-centos-8-x64',   "${region}${it}"),
            getTemplate('min-centos-7-x64',   "${region}${it}"),
            getTemplate('fips-centos-7-x64',  "${region}${it}"),
            // getTemplate('min-centos-6-x64',   "${region}${it}"),
            getTemplate('min-jammy-x64',      "${region}${it}"),
            getTemplate('min-noble-x64',      "${region}${it}"),
            getTemplate('min-focal-x64',      "${region}${it}"),
            getTemplate('min-bionic-x64',     "${region}${it}"),
            getTemplate('min-buster-x64',     "${region}${it}"),
            // getTemplate('min-stretch-x64',    "${region}${it}"),
            // getTemplate('min-xenial-x64',     "${region}${it}"),
            getTemplate('min-bullseye-x64',   "${region}${it}"),
            getTemplate('min-bookworm-x64',   "${region}${it}"),
            getTemplate('min-trixie-x64',     "${region}${it}"),
        ],                                       // List<? extends SlaveTemplate> templates
        '',
        ''
    )

    // add cloud configuration to Jenkins
    jenkins.clouds.each {
        if (it.hasProperty('cloudName') && it['cloudName'] == ec2Cloud['cloudName']) {
            jenkins.clouds.remove(it)
        }
    }
    jenkins.clouds.add(ec2Cloud)
}

// save current Jenkins state to disk
jenkins.save()

logger.info("Cloud init finished")
