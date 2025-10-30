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
netMap['eu-west-1b'] = 'subnet-05f58e38549072404'
netMap['eu-west-1c'] = 'subnet-0b5ee1ef341aca9db'

imageMap = [:]
imageMap['eu-west-1a.docker'] = 'ami-07e9032b01a41341a'                // Amazon Linux 2 x86_64
imageMap['eu-west-1a.docker-32gb'] = 'ami-07e9032b01a41341a'            // Amazon Linux 2 x86_64
imageMap['eu-west-1a.docker2'] = 'ami-07e9032b01a41341a'                // Amazon Linux 2 x86_64
imageMap['eu-west-1a.micro-amazon'] = 'ami-07e9032b01a41341a'            // Amazon Linux 2 x86_64
imageMap['eu-west-1a.min-al2023-x64'] = 'ami-091a906f2e1e40076'         // Amazon Linux 2023 x86_64
imageMap['eu-west-1a.fips-centos-7-x64'] = 'ami-00d464afa64e1fc69'      // CentOS 7 x86_64 (keep existing)
imageMap['eu-west-1a.min-centos-7-x64'] = 'ami-00d464afa64e1fc69'       // CentOS 7 x86_64 (keep existing)
imageMap['eu-west-1a.min-ol-8-x64'] = 'ami-008970b8da0f7ef4a'           // Oracle Linux 8 x86_64
imageMap['eu-west-1a.min-ol-9-x64'] = 'ami-0842239ad749d2730'           // Oracle Linux 9 x86_64
imageMap['eu-west-1a.min-rhel-10-x64'] = 'ami-0b93e28d1386a8580'        // RHEL 10 x86_64
imageMap['eu-west-1a.min-bookworm-x64'] = 'ami-0ae2703824063fcf1'          // Debian 12 x86_64
imageMap['eu-west-1a.min-bullseye-x64'] = 'ami-089f338f3a2e69431'        // Debian 11 x86_64
imageMap['eu-west-1a.min-trixie-x64'] = 'ami-04034bfd5da1fa2ed'          // Debian 13 x86_64
imageMap['eu-west-1a.min-bionic-x64'] = 'ami-09725ca3f13c523fd'          // Ubuntu 18.04 x86_64
imageMap['eu-west-1a.min-jammy-x64'] = 'ami-0b126f69ececf3c3d'           // Ubuntu 22.04 x86_64
imageMap['eu-west-1a.min-noble-x64'] = 'ami-0776c814353b4814d'           // Ubuntu 24.04 x86_64
imageMap['eu-west-1a.min-stretch-x64'] = 'ami-02e52747ec1f04026'         // Debian 9 x86_64
imageMap['eu-west-1a.min-xenial-x64'] = 'ami-016ee74f2cf016914'          // Ubuntu 16.04 x86_64

// ARM64 platforms
imageMap['eu-west-1a.docker-32gb-aarch64'] = 'ami-004aa048722f3ac3e'       // Amazon Linux 2 ARM64
imageMap['eu-west-1a.min-bookworm-aarch64'] = 'ami-02dda204c4af0aecc'      // Debian 12 ARM64
imageMap['eu-west-1a.min-bullseye-aarch64'] = 'ami-0083086bc808b1d71'      // Debian 11 ARM64
imageMap['eu-west-1a.min-jammy-aarch64'] = 'ami-0fd301a23be2fbe30'         // Ubuntu 22.04 ARM64
imageMap['eu-west-1a.min-noble-aarch64'] = 'ami-0a636034c582e2138'         // Ubuntu 24.04 ARM64
imageMap['eu-west-1a.min-trixie-aarch64'] = 'ami-08aa48334e0370a03'        // Debian 13 ARM64
imageMap['eu-west-1a.min-al2023-aarch64'] = 'ami-0b24063151d1c59e7'        // Amazon Linux 2023 ARM64

imageMap['eu-west-1b.docker'] = imageMap['eu-west-1a.docker']
imageMap['eu-west-1b.docker-32gb'] = imageMap['eu-west-1a.docker-32gb']
imageMap['eu-west-1b.docker2'] = imageMap['eu-west-1a.docker2']
imageMap['eu-west-1b.micro-amazon'] = imageMap['eu-west-1a.micro-amazon']
imageMap['eu-west-1b.min-al2023-x64'] = imageMap['eu-west-1a.min-al2023-x64']
imageMap['eu-west-1b.fips-centos-7-x64'] = imageMap['eu-west-1a.fips-centos-7-x64']
imageMap['eu-west-1b.min-centos-7-x64'] = imageMap['eu-west-1a.min-centos-7-x64']
imageMap['eu-west-1b.min-ol-8-x64'] = imageMap['eu-west-1a.min-ol-8-x64']
imageMap['eu-west-1b.min-ol-9-x64'] = imageMap['eu-west-1a.min-ol-9-x64']
imageMap['eu-west-1b.min-rhel-10-x64'] = imageMap['eu-west-1a.min-rhel-10-x64']
imageMap['eu-west-1b.min-bookworm-x64'] = imageMap['eu-west-1a.min-bookworm-x64']
imageMap['eu-west-1b.min-bullseye-x64'] = imageMap['eu-west-1a.min-bullseye-x64']
imageMap['eu-west-1b.min-trixie-x64'] = imageMap['eu-west-1a.min-trixie-x64']
imageMap['eu-west-1b.min-bionic-x64'] = imageMap['eu-west-1a.min-bionic-x64']
imageMap['eu-west-1b.min-jammy-x64'] = imageMap['eu-west-1a.min-jammy-x64']
imageMap['eu-west-1b.min-noble-x64'] = imageMap['eu-west-1a.min-noble-x64']
imageMap['eu-west-1b.min-stretch-x64'] = imageMap['eu-west-1a.min-stretch-x64']
imageMap['eu-west-1b.min-xenial-x64'] = imageMap['eu-west-1a.min-xenial-x64']

// ARM64 platforms
imageMap['eu-west-1b.docker-32gb-aarch64'] = imageMap['eu-west-1a.docker-32gb-aarch64']
imageMap['eu-west-1b.min-bookworm-aarch64'] = imageMap['eu-west-1a.min-bookworm-aarch64']
imageMap['eu-west-1b.min-bullseye-aarch64'] = imageMap['eu-west-1a.min-bullseye-aarch64']
imageMap['eu-west-1b.min-jammy-aarch64'] = imageMap['eu-west-1a.min-jammy-aarch64']
imageMap['eu-west-1b.min-noble-aarch64'] = imageMap['eu-west-1a.min-noble-aarch64']
imageMap['eu-west-1b.min-trixie-aarch64'] = imageMap['eu-west-1a.min-trixie-aarch64']
imageMap['eu-west-1b.min-al2023-aarch64'] = imageMap['eu-west-1a.min-al2023-aarch64']

imageMap['eu-west-1c.docker'] = imageMap['eu-west-1a.docker']
imageMap['eu-west-1c.docker-32gb'] = imageMap['eu-west-1a.docker-32gb']
imageMap['eu-west-1c.docker2'] = imageMap['eu-west-1a.docker2']
imageMap['eu-west-1c.micro-amazon'] = imageMap['eu-west-1a.micro-amazon']
imageMap['eu-west-1c.min-al2023-x64'] = imageMap['eu-west-1a.min-al2023-x64']
imageMap['eu-west-1c.fips-centos-7-x64'] = imageMap['eu-west-1a.fips-centos-7-x64']
imageMap['eu-west-1c.min-centos-7-x64'] = imageMap['eu-west-1a.min-centos-7-x64']
imageMap['eu-west-1c.min-ol-8-x64'] = imageMap['eu-west-1a.min-ol-8-x64']
imageMap['eu-west-1c.min-ol-9-x64'] = imageMap['eu-west-1a.min-ol-9-x64']
imageMap['eu-west-1c.min-rhel-10-x64'] = imageMap['eu-west-1a.min-rhel-10-x64']
imageMap['eu-west-1c.min-bookworm-x64'] = imageMap['eu-west-1a.min-bookworm-x64']
imageMap['eu-west-1c.min-bullseye-x64'] = imageMap['eu-west-1a.min-bullseye-x64']
imageMap['eu-west-1c.min-trixie-x64'] = imageMap['eu-west-1a.min-trixie-x64']
imageMap['eu-west-1c.min-bionic-x64'] = imageMap['eu-west-1a.min-bionic-x64']
imageMap['eu-west-1c.min-jammy-x64'] = imageMap['eu-west-1a.min-jammy-x64']
imageMap['eu-west-1c.min-noble-x64'] = imageMap['eu-west-1a.min-noble-x64']
imageMap['eu-west-1c.min-stretch-x64'] = imageMap['eu-west-1a.min-stretch-x64']
imageMap['eu-west-1c.min-xenial-x64'] = imageMap['eu-west-1a.min-xenial-x64']

// ARM64 platforms
imageMap['eu-west-1c.docker-32gb-aarch64'] = imageMap['eu-west-1a.docker-32gb-aarch64']
imageMap['eu-west-1c.min-bookworm-aarch64'] = imageMap['eu-west-1a.min-bookworm-aarch64']
imageMap['eu-west-1c.min-bullseye-aarch64'] = imageMap['eu-west-1a.min-bullseye-aarch64']
imageMap['eu-west-1c.min-jammy-aarch64'] = imageMap['eu-west-1a.min-jammy-aarch64']
imageMap['eu-west-1c.min-noble-aarch64'] = imageMap['eu-west-1a.min-noble-aarch64']
imageMap['eu-west-1c.min-trixie-aarch64'] = imageMap['eu-west-1a.min-trixie-aarch64']
imageMap['eu-west-1c.min-al2023-aarch64'] = imageMap['eu-west-1a.min-al2023-aarch64']

priceMap = [:]
priceMap['t2.small'] = '0.03'     // type=t2.small, vCPU=1, memory=2GiB, saving=64%, interruption='<5%', price=0.017500
priceMap['c4.xlarge'] = '0.10'    // type=c4.xlarge, vCPU=4, memory=7.5GiB, saving=61%, interruption='<5%', price=0.094100
priceMap['g4ad.2xlarge'] = '0.53' // type=g4ad.2xlarge, vCPU=8, memory=32GiB, saving=64%, interruption='<5%', price=0.479800
priceMap['i4i.4xlarge'] = '0.57'  // type=i4i.4xlarge, vCPU=16, memory=128GiB, saving=67%, interruption='<5%', price=0.534400
priceMap['r6g.2xlarge'] = '0.30'  // type=r6g.2xlarge, vCPU=8, memory=64GiB, ARM64

userMap = [:]
userMap['docker'] = 'ec2-user'
userMap['docker-32gb'] = userMap['docker']
userMap['docker2'] = userMap['docker']
userMap['micro-amazon'] = userMap['docker']
userMap['min-al2023-x64'] = 'ec2-user'
userMap['fips-centos-7-x64'] = 'centos'
userMap['min-centos-7-x64'] = 'centos'
userMap['min-ol-8-x64'] = 'ec2-user'
userMap['min-ol-9-x64'] = 'ec2-user'
userMap['min-rhel-10-x64'] = 'ec2-user'
userMap['min-bookworm-x64'] = 'admin'
userMap['min-bullseye-x64'] = 'admin'
userMap['min-trixie-x64'] = 'admin'
userMap['min-bionic-x64'] = 'ubuntu'
userMap['min-jammy-x64'] = 'ubuntu'
userMap['min-noble-x64'] = 'ubuntu'
userMap['min-stretch-x64'] = 'admin'
userMap['min-xenial-x64'] = 'ubuntu'
// ARM64 platforms
userMap['docker-32gb-aarch64'] = 'ec2-user'
userMap['min-bookworm-aarch64'] = 'admin'
userMap['min-bullseye-aarch64'] = 'admin'
userMap['min-jammy-aarch64'] = 'ubuntu'
userMap['min-noble-aarch64'] = 'ubuntu'
userMap['min-trixie-aarch64'] = 'admin'
userMap['min-al2023-aarch64'] = 'ec2-user'

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

    sudo amazon-linux-extras install epel -y
    sudo yum -y install java-17-amazon-corretto-headless || :
    sudo yum -y install git docker p7zip
    sudo yum -y remove awscli

    if ! $(aws --version | grep -q 'aws-cli/2'); then
        find /tmp -maxdepth 1 -name "*aws*" | xargs sudo rm -rf

        until curl "https://awscli.amazonaws.com/awscli-exe-linux-x86_64.zip" -o "/tmp/awscliv2.zip"; do
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
    echo "* * * * * root /usr/sbin/route add default gw 10.177.1.1 eth0" | sudo tee /etc/cron.d/fix-default-route
'''
initMap['docker-32gb'] = initMap['docker']
initMap['docker2'] = initMap['docker']
initMap['micro-amazon'] = '''
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
    until sudo yum makecache; do
        sleep 1
        echo try again
    done
    sudo amazon-linux-extras install epel -y
    sudo yum -y install java-17-amazon-corretto-headless || :
    sudo yum -y install git aws-cli || :
    sudo install -o $(id -u -n) -g $(id -g -n) -d /mnt/jenkins
'''
initMap['min-centos-7-x64'] = '''
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

    if [[ ${RHVER} -eq 8 ]] || [[ ${RHVER} -eq 7 ]]; then
        sudo sed -i 's/mirrorlist/#mirrorlist/g' /etc/yum.repos.d/CentOS-*
        sudo sed -i 's|#baseurl=http://mirror.centos.org|baseurl=http://vault.centos.org|g' /etc/yum.repos.d/CentOS-*
    fi

    until sudo yum makecache; do
        sleep 1
        echo try again
    done
    
    # Install Java based on OS type and RHEL version - all agents need Java 17+
    if [[ -f /etc/system-release ]] && grep -q "Amazon Linux" /etc/system-release; then
        # Amazon Linux 2 - use Corretto 17
        sudo yum -y install java-17-amazon-corretto-headless || :
    elif [[ ${RHVER} -eq 7 ]]; then
        # CentOS 7 - try Corretto 17 first, fallback to OpenJDK 11
        sudo yum -y install java-17-amazon-corretto-headless || sudo yum -y install java-11-openjdk-headless || :
    else
        # Oracle Linux 8/9, CentOS 8+ - use OpenJDK 17
        sudo yum -y install java-17-openjdk-headless || :
    fi
    
    sudo yum -y install git tzdata-java || :
    sudo yum -y install aws-cli || :
    sudo install -o $(id -u -n) -g $(id -g -n) -d /mnt/jenkins
'''

// Additional init script mappings
initMap['docker-32gb'] = initMap['docker']
initMap['docker2'] = initMap['docker']
initMap['min-al2023-x64'] = initMap['micro-amazon']
initMap['fips-centos-7-x64'] = initMap['min-centos-7-x64']
initMap['min-ol-8-x64'] = initMap['min-centos-7-x64']  
initMap['min-ol-9-x64'] = initMap['min-centos-7-x64']  

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

    until sudo yum makecache; do
        sleep 1
        echo try again
    done
    sudo yum -y install java-21-openjdk-headless tzdata-java || :
    sudo yum -y install awscli2 || :
    sudo yum -y install git || :
    sudo install -o $(id -u -n) -g $(id -g -n) -d /mnt/jenkins
'''

// Debian-based init script
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

    sudo sed -i '/bullseye-backports/ s/cdn-aws.deb.debian.org/archive.debian.org/' /etc/apt/sources.list

    until sudo apt-get update; do
        sleep 1
        echo try again
    done
    until sudo apt-get install -y lsb-release; do
        sleep 1
        echo try again
    done

    DEB_VER=$(lsb_release -sc)

    if [[ ${DEB_VER} == "trixie" ]]; then
        JAVA_VER="openjdk-21-jre-headless"
    else
        JAVA_VER="openjdk-17-jre-headless"
    fi

    if [[ ${DEB_VER} == "trixie" ]] || [[ ${DEB_VER} == "bookworm" ]] || [[ ${DEB_VER} == "buster" ]]; then
        sudo apt-get -y install ${JAVA_VER} git
        sudo mv /etc/ssl /etc/ssl_old
        sudo apt-get -y install ${JAVA_VER}
        sudo cp -r /etc/ssl_old /etc/ssl
        sudo apt-get -y install ${JAVA_VER}
    else
        sudo apt-get -y install ${JAVA_VER} git
    fi

    sudo install -o $(id -u -n) -g $(id -g -n) -d /mnt/jenkins
'''

// Map Debian/Ubuntu platforms to debMap
initMap['min-bookworm-x64'] = initMap['debMap']
initMap['min-bullseye-x64'] = initMap['debMap']
initMap['min-trixie-x64'] = initMap['debMap']
initMap['min-bionic-x64'] = initMap['debMap']
initMap['min-jammy-x64'] = initMap['debMap']
initMap['min-noble-x64'] = initMap['debMap']
initMap['min-stretch-x64'] = initMap['debMap']
initMap['min-xenial-x64'] = initMap['debMap']

// ARM64 platforms
initMap['docker-32gb-aarch64'] = initMap['docker']
initMap['min-bookworm-aarch64'] = initMap['debMap']
initMap['min-bullseye-aarch64'] = initMap['debMap']
initMap['min-jammy-aarch64'] = initMap['debMap']
initMap['min-noble-aarch64'] = initMap['debMap']
initMap['min-trixie-aarch64'] = initMap['debMap']
initMap['min-al2023-aarch64'] = initMap['micro-amazon']

capMap = [:]
capMap['c4.xlarge'] = '60'
capMap['g4ad.2xlarge'] = '40'
capMap['i4i.4xlarge'] = '40'
capMap['t2.small'] = '60'
capMap['r6g.2xlarge'] = '40'

typeMap = [:]
typeMap['micro-amazon'] = 't2.small'
typeMap['docker'] = 'c4.xlarge'
typeMap['docker-32gb'] = 'g4ad.2xlarge'
typeMap['docker2'] = 'i4i.4xlarge'
typeMap['min-al2023-x64'] = typeMap['docker']
typeMap['fips-centos-7-x64'] = typeMap['docker']
typeMap['min-centos-7-x64'] = typeMap['docker']
typeMap['min-ol-8-x64'] = typeMap['docker']
typeMap['min-ol-9-x64'] = 'i4i.4xlarge'
typeMap['min-rhel-10-x64'] = typeMap['docker']
typeMap['min-bookworm-x64'] = typeMap['docker']
typeMap['min-bullseye-x64'] = typeMap['docker']
typeMap['min-trixie-x64'] = typeMap['docker']
typeMap['min-bionic-x64'] = typeMap['docker']
typeMap['min-jammy-x64'] = typeMap['docker']
typeMap['min-noble-x64'] = typeMap['docker']
typeMap['min-stretch-x64'] = typeMap['docker']
typeMap['min-xenial-x64'] = typeMap['docker']
// ARM64 platforms
typeMap['docker-32gb-aarch64'] = 'r6g.2xlarge'
typeMap['min-bookworm-aarch64'] = typeMap['docker-32gb-aarch64']
typeMap['min-bullseye-aarch64'] = typeMap['docker-32gb-aarch64']
typeMap['min-jammy-aarch64'] = typeMap['docker-32gb-aarch64']
typeMap['min-noble-aarch64'] = typeMap['docker-32gb-aarch64']
typeMap['min-trixie-aarch64'] = typeMap['docker-32gb-aarch64']
typeMap['min-al2023-aarch64'] = typeMap['docker-32gb-aarch64']

execMap = [:]
execMap['docker'] = '1'
execMap['docker-32gb'] = execMap['docker']
execMap['docker2'] = execMap['docker']
execMap['micro-amazon'] = '30'
execMap['min-al2023-x64'] = '1'
execMap['fips-centos-7-x64'] = '1'
execMap['min-centos-7-x64'] = '1'
execMap['min-ol-8-x64'] = '1'
execMap['min-ol-9-x64'] = '1'
execMap['min-rhel-10-x64'] = '1'
execMap['min-bookworm-x64'] = '1'
execMap['min-bullseye-x64'] = '1'
execMap['min-trixie-x64'] = '1'
execMap['min-bionic-x64'] = '1'
execMap['min-jammy-x64'] = '1'
execMap['min-noble-x64'] = '1'
execMap['min-stretch-x64'] = '1'
execMap['min-xenial-x64'] = '1'
// ARM64 platforms
execMap['docker-32gb-aarch64'] = '1'
execMap['min-bookworm-aarch64'] = '1'
execMap['min-bullseye-aarch64'] = '1'
execMap['min-jammy-aarch64'] = '1'
execMap['min-noble-aarch64'] = '1'
execMap['min-trixie-aarch64'] = '1'
execMap['min-al2023-aarch64'] = '1'

devMap = [:]
devMap['docker'] = '/dev/xvda=:8:true:gp2,/dev/xvdd=:80:true:gp2'
devMap['docker2'] = '/dev/xvda=:8:true:gp2,/dev/xvdd=:120:true:gp2'
devMap['docker-32gb'] = '/dev/xvda=:8:true:gp2,/dev/xvdd=:120:true:gp2'
devMap['micro-amazon'] = devMap['docker']
devMap['min-al2023-x64'] = devMap['docker']
devMap['fips-centos-7-x64'] = '/dev/sda1=:8:true:gp2,/dev/sdd=:80:true:gp2'
devMap['min-centos-7-x64'] = '/dev/sda1=:8:true:gp2,/dev/sdd=:80:true:gp2'
devMap['min-ol-8-x64'] = '/dev/sda1=:8:true:gp2,/dev/sdd=:80:true:gp2'
devMap['min-ol-9-x64'] = '/dev/sda1=:10:true:gp2,/dev/sdd=:80:true:gp2'
devMap['min-rhel-10-x64'] = '/dev/sda1=:30:true:gp3,/dev/sdd=:80:true:gp3'
devMap['min-bookworm-x64'] = '/dev/xvda=:8:true:gp2,/dev/xvdd=:80:true:gp2'
devMap['min-bullseye-x64'] = '/dev/xvda=:8:true:gp2,/dev/xvdd=:80:true:gp2'
devMap['min-trixie-x64'] = '/dev/xvda=:8:true:gp2,/dev/xvdd=:80:true:gp2'
devMap['min-bionic-x64'] = '/dev/sda1=:8:true:gp2,/dev/sdd=:80:true:gp2'
devMap['min-jammy-x64'] = '/dev/sda1=:8:true:gp2,/dev/sdd=:80:true:gp2'
devMap['min-noble-x64'] = '/dev/sda1=:8:true:gp2,/dev/sdd=:80:true:gp2'
devMap['min-stretch-x64'] = '/dev/xvda=:8:true:gp2,/dev/xvdd=:80:true:gp2'
devMap['min-xenial-x64'] = '/dev/sda1=:8:true:gp2,/dev/sdd=:80:true:gp2'
// ARM64 platforms
devMap['docker-32gb-aarch64'] = '/dev/xvda=:8:true:gp2,/dev/xvdd=:80:true:gp2'
devMap['min-bookworm-aarch64'] = '/dev/xvda=:8:true:gp2,/dev/xvdd=:80:true:gp2'
devMap['min-bullseye-aarch64'] = '/dev/xvda=:8:true:gp2,/dev/xvdd=:80:true:gp2'
devMap['min-jammy-aarch64'] = '/dev/xvda=:8:true:gp2,/dev/xvdd=:80:true:gp2'
devMap['min-noble-aarch64'] = '/dev/xvda=:8:true:gp2,/dev/xvdd=:80:true:gp2'
devMap['min-trixie-aarch64'] = '/dev/xvda=:8:true:gp2,/dev/xvdd=:80:true:gp2'
devMap['min-al2023-aarch64'] = '/dev/xvda=:8:true:gp2,/dev/xvdd=:80:true:gp2'

labelMap = [:]
labelMap['docker'] = ''
labelMap['docker-32gb'] = ''
labelMap['docker2'] = ''
labelMap['micro-amazon'] = 'master'
labelMap['min-al2023-x64'] = ''
labelMap['fips-centos-7-x64'] = ''
labelMap['min-centos-7-x64'] = ''
labelMap['min-ol-8-x64'] = ''
labelMap['min-ol-9-x64'] = ''
labelMap['min-rhel-10-x64'] = ''
labelMap['min-bookworm-x64'] = ''
labelMap['min-bullseye-x64'] = ''
labelMap['min-trixie-x64'] = ''
labelMap['min-bionic-x64'] = 'asan'
labelMap['min-jammy-x64'] = ''
labelMap['min-noble-x64'] = ''
labelMap['min-stretch-x64'] = ''
labelMap['min-xenial-x64'] = ''
// ARM64 platforms
labelMap['docker-32gb-aarch64'] = ''
labelMap['min-bookworm-aarch64'] = ''
labelMap['min-bullseye-aarch64'] = ''
labelMap['min-jammy-aarch64'] = ''
labelMap['min-noble-aarch64'] = ''
labelMap['min-trixie-aarch64'] = ''
labelMap['min-al2023-aarch64'] = ''

// https://github.com/jenkinsci/ec2-plugin/blob/ec2-1.41/src/main/java/hudson/plugins/ec2/SlaveTemplate.java
SlaveTemplate getTemplate(String OSType, String AZ) {
    return new SlaveTemplate(
        imageMap[AZ + '.' + OSType],                // String ami
        '',                                         // String zone
        new SpotConfiguration(true, priceMap[typeMap[OSType]], false, '0'), // SpotConfiguration spotConfig
        'default',                                  // String securityGroups
        '/mnt/jenkins',                             // String remoteFS
        InstanceType.fromValue(typeMap[OSType]),    // InstanceType type
        ( typeMap[OSType].startsWith("c4") || typeMap[OSType].startsWith("m4") || typeMap[OSType].startsWith("c5") || typeMap[OSType].startsWith("m5") || typeMap[OSType].startsWith("r6") ), // boolean ebsOptimized
        OSType + ' ' + labelMap[OSType],            // String labelString
        Node.Mode.NORMAL,                           // Node.Mode mode
        OSType,                                     // String description
        initMap[OSType],                            // String initScript
        '',                                         // String tmpDir
        '',                                         // String userData
        execMap[OSType],                            // String numExecutors
        userMap[OSType],                            // String remoteAdmin
        new UnixData('', '', '', '22', ''),         // AMITypeData amiType
        '-Xmx512m -Xms512m',                        // String jvmopts
        false,                                      // boolean stopOnTerminate
        netMap[AZ],                                 // String subnetId
        [
            new EC2Tag('Name', 'jenkins-fb-' + OSType),
            new EC2Tag('iit-billing-tag', 'jenkins-fb-worker')
        ],                                          // List<EC2Tag> tags
        '3',                                        // String idleTerminationMinutes
        0,                                          // Init minimumNumberOfInstances
        0,                                          // minimumNumberOfSpareInstances
        capMap[typeMap[OSType]],                    // String instanceCapStr
        'arn:aws:iam::119175775298:instance-profile/jenkins-fb-worker', // String iamInstanceProfile
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

String sshKeysCredentialsId = '1b53b7b1-f4fb-4109-8080-28ac2ee46b41'

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
            getTemplate('docker',               "${region}${it}"),
            getTemplate('docker-32gb',          "${region}${it}"),
            getTemplate('docker2',              "${region}${it}"),
            getTemplate('micro-amazon',         "${region}${it}"),
            getTemplate('min-al2023-x64',       "${region}${it}"),
            getTemplate('fips-centos-7-x64',    "${region}${it}"),
            getTemplate('min-centos-7-x64',     "${region}${it}"),
            getTemplate('min-ol-8-x64',         "${region}${it}"),
            getTemplate('min-ol-9-x64',         "${region}${it}"),
            getTemplate('min-rhel-10-x64',      "${region}${it}"),
            getTemplate('min-bionic-x64',       "${region}${it}"),
            getTemplate('min-bookworm-x64',     "${region}${it}"),
            getTemplate('min-bullseye-x64',     "${region}${it}"),
            getTemplate('min-trixie-x64',       "${region}${it}"),
            getTemplate('min-jammy-x64',        "${region}${it}"),
            getTemplate('min-noble-x64',        "${region}${it}"),
            getTemplate('min-stretch-x64',      "${region}${it}"),
            getTemplate('min-xenial-x64',       "${region}${it}"),
            getTemplate('docker-32gb-aarch64',  "${region}${it}"),
            getTemplate('min-bookworm-aarch64', "${region}${it}"),
            getTemplate('min-bullseye-aarch64', "${region}${it}"),
            getTemplate('min-jammy-aarch64',    "${region}${it}"),
            getTemplate('min-noble-aarch64',    "${region}${it}"),
            getTemplate('min-trixie-aarch64',   "${region}${it}"),
            getTemplate('min-al2023-aarch64',   "${region}${it}"),
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
