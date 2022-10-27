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

System.setProperty("hudson.model.DirectoryBrowserSupport.CSP", "sandbox allow-scripts; script-src 'unsafe-eval' 'unsafe-inline' https://www.google.com;")

def logger = Logger.getLogger("")
logger.info("Cloud init started")

// get Jenkins instance
Jenkins jenkins = Jenkins.getInstance()

netMap = [:]
netMap['us-west-1b'] = 'subnet-01d9a7d6b4722eb43'
netMap['us-west-1c'] = 'subnet-0550c1d2ffd688021'

imageMap = [:]
imageMap['micro-amazon']     = 'ami-061352bb71c4724b2'
imageMap['min-bionic-x64']   = 'ami-00753573f3369dd7c'
imageMap['min-focal-x64']    = 'ami-066c6938fb715719f'
imageMap['min-jammy-x64']    = 'ami-0dc5e9ff792ec08e3'
imageMap['min-centos-6-x32'] = 'ami-67e3cd22'
imageMap['min-centos-6-x64'] = 'ami-0d282a216ae4c0c42'
imageMap['min-centos-7-x64'] = 'ami-08d2d8b00f270d03b'
imageMap['min-centos-8-x64'] = 'ami-04adf3fcbc8a45c54'
imageMap['min-ol-8-x64']     = 'ami-01c58ced87b56372e'
imageMap['min-ol-9-x64']     = 'ami-0d1958c85fb6a7b3e'
imageMap['min-stretch-x64']  = 'ami-0280d841fec0493e6'
imageMap['min-xenial-x64']   = 'ami-0454207e5367abf01'
imageMap['min-buster-x64']   = 'ami-024fe42989cf9e876'
imageMap['docker']           = 'ami-061352bb71c4724b2'
imageMap['docker-32gb']      = imageMap['docker']
imageMap['min-bullseye-x64'] = 'ami-07af5f877b3db9f73'

imageMap['ramdisk-centos-6-x64'] = imageMap['min-centos-6-x64']
imageMap['ramdisk-centos-7-x64'] = imageMap['min-centos-7-x64']
imageMap['ramdisk-centos-8-x64'] = imageMap['min-centos-8-x64']
imageMap['ramdisk-ol-8-x64']     = imageMap['min-ol-8-x64']
imageMap['ramdisk-ol-9-x64']     = imageMap['min-ol-9-x64']
imageMap['ramdisk-stretch-x64']  = imageMap['min-stretch-x64']
imageMap['ramdisk-xenial-x64']   = imageMap['min-xenial-x64']
imageMap['ramdisk-bionic-x64']   = imageMap['min-bionic-x64']
imageMap['ramdisk-focal-x64']    = imageMap['min-focal-x64']
imageMap['ramdisk-jammy-x64']    = imageMap['min-jammy-x64']
imageMap['ramdisk-buster-x64']   = imageMap['min-buster-x64']
imageMap['ramdisk-bullseye-x64'] = imageMap['min-bullseye-x64']

imageMap['performance-centos-6-x64']   = imageMap['min-centos-7-x64']

priceMap = [:]
priceMap['m4.xlarge'] = '0.15'
priceMap['m1.medium'] = '0.07'
priceMap['c4.2xlarge'] = '0.15'
priceMap['r3.2xlarge'] = '0.19'
priceMap['m4.2xlarge'] = '0.20'
priceMap['r5.2xlarge'] = '0.23'

userMap = [:]
userMap['docker']            = 'ec2-user'
userMap['docker-32gb']       = userMap['docker']
userMap['micro-amazon']      = userMap['docker']
userMap['min-bionic-x64']    = 'ubuntu'
userMap['min-focal-x64']     = 'ubuntu'
userMap['min-jammy-x64']     = 'ubuntu'
userMap['min-centos-6-x32']  = 'root'
userMap['min-centos-6-x64']  = 'centos'
userMap['min-centos-7-x64']  = 'centos'
userMap['min-centos-8-x64']  = 'centos'
userMap['min-ol-8-x64']      = 'ec2-user'
userMap['min-ol-9-x64']      = 'ec2-user'
userMap['fips-centos-7-x64'] = 'centos'
userMap['min-stretch-x64']   = 'admin'
userMap['min-buster-x64']    = 'admin'
userMap['min-xenial-x64']    = 'ubuntu'
userMap['min-bullseye-x64']  = 'admin'

userMap['ramdisk-centos-6-x64'] = userMap['min-centos-6-x64']
userMap['ramdisk-centos-7-x64'] = userMap['min-centos-7-x64']
userMap['ramdisk-centos-8-x64'] = userMap['min-centos-8-x64']
userMap['ramdisk-ol-8-x64']     = userMap['min-ol-8-x64']
userMap['ramdisk-ol-9-x64']     = userMap['min-ol-9-x64']
userMap['ramdisk-stretch-x64']  = userMap['min-stretch-x64']
userMap['ramdisk-xenial-x64']   = userMap['min-xenial-x64']
userMap['ramdisk-bionic-x64']   = userMap['min-bionic-x64']
userMap['ramdisk-focal-x64']    = userMap['min-focal-x64']
userMap['ramdisk-jammy-x64']    = userMap['min-jammy-x64']
userMap['ramdisk-buster-x64']   = userMap['min-buster-x64']
userMap['ramdisk-bullseye-x64'] = userMap['min-bullseye-x64']

userMap['performance-centos-6-x64'] = userMap['min-centos-6-x64']

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
    sudo amazon-linux-extras install java-openjdk11 -y || :
    sudo yum -y install java-11-openjdk || :
    sudo yum -y install git docker p7zip
    sudo yum -y remove awscli

    if ! $(aws --version | grep -q 'aws-cli/2'); then
        sudo rm -rf /tmp/aws* || true

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
    echo sudo service docker status || sudo service docker start
    echo "* * * * * root /usr/sbin/route add default gw 10.177.1.1 eth0" | sudo tee /etc/cron.d/fix-default-route
'''
initMap['docker-32gb'] = initMap['docker']

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
            until sudo yum -y update; do
                sleep 1
                echo try again
            done
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
    if [[ $SYSREL -eq 2 ]]; then
        sudo amazon-linux-extras install epel -y
        sudo amazon-linux-extras install java-openjdk11 -y || :

        PKGLIST="p7zip"
    fi
    if [[ ${RHVER} -eq 8 ]]; then
        sudo sed -i 's/mirrorlist/#mirrorlist/g' /etc/yum.repos.d/CentOS-*
        sudo sed -i 's|#baseurl=http://mirror.centos.org|baseurl=http://vault.centos.org|g' /etc/yum.repos.d/CentOS-*
    fi
    until sudo yum makecache; do
        sleep 1
        echo try again
    done
    sudo yum -y install java-11-openjdk git ${PKGLIST} || :
    sudo install -o $(id -u -n) -g $(id -g -n) -d /mnt/jenkins
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
    if [[ $SYSREL -eq 2 ]]; then
        if ! $(aws --version | grep -q 'aws-cli/2'); then
            sudo rm -rf /tmp/aws* || true
            until curl "https://awscli.amazonaws.com/awscli-exe-linux-x86_64.zip" -o "/tmp/awscliv2.zip"; do
                sleep 1
                echo try again
            done
            7za -o/tmp x /tmp/awscliv2.zip 
            cd /tmp/aws && sudo ./install
        fi
    fi
'''

initMap['rpmMapRamdisk'] = '''
    set -o xtrace
    RHVER=$(rpm --eval %rhel)

    if ! mountpoint -q /mnt; then
        sudo mount -t tmpfs -o size=20G tmpfs /mnt
    fi
    if [[ ${RHVER} -eq 6 ]]; then
        sudo curl https://jenkins.percona.com/downloads/cent6/centos6-eol.repo --output /etc/yum.repos.d/CentOS-Base.repo
        until sudo yum makecache; do
            sleep 1
            echo try again
        done
        until sudo yum -y install epel-release centos-release-scl; do    
            sleep 1
            echo try again
        done
        sudo rm /etc/yum.repos.d/epel-testing.repo
        sudo curl https://jenkins.percona.com/downloads/cent6/centos6-epel-eol.repo --output /etc/yum.repos.d/epel.repo
        sudo curl https://jenkins.percona.com/downloads/cent6/centos6-scl-eol.repo --output /etc/yum.repos.d/CentOS-SCLo-scl.repo
        sudo curl https://jenkins.percona.com/downloads/cent6/centos6-scl-rh-eol.repo --output /etc/yum.repos.d/CentOS-SCLo-scl-rh.repo
    fi
    if [[ ${RHVER} -eq 8 ]]; then
        sudo sed -i 's/mirrorlist/#mirrorlist/g' /etc/yum.repos.d/CentOS-*
        sudo sed -i 's|#baseurl=http://mirror.centos.org|baseurl=http://vault.centos.org|g' /etc/yum.repos.d/CentOS-*
    fi
    until sudo yum makecache; do
        sleep 1
        echo try again
    done
    sudo yum -y install java-11-openjdk git || :
    sudo yum -y install aws-cli || :
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
            sudo mkfs.ext2 ${DEVICE}
            sudo mount ${DEVICE} /mnt
        fi
    fi
    until sudo DEBIAN_FRONTEND=noninteractive apt-get update; do
        sleep 1
        echo try again
    done
    until sudo DEBIAN_FRONTEND=noninteractive apt-get install -y lsb-release; do
        sleep 1
        echo try again
    done
    DEB_VER=$(lsb_release -sc)
    if [[ ${DEB_VER} == "buster" ]] || [[ ${DEB_VER} == "bullseye" ]]; then
        JAVA_VER="openjdk-11-jre-headless"
    else
        JAVA_VER="openjdk-11-jre-headless"
    fi
    sudo DEBIAN_FRONTEND=noninteractive apt-get -y install ${JAVA_VER} git
    sudo install -o $(id -u -n) -g $(id -g -n) -d /mnt/jenkins
'''

initMap['debMapRamdisk'] = '''
    set -o xtrace
    if ! mountpoint -q /mnt; then
        sudo mount -t tmpfs -o size=20G tmpfs /mnt
    fi
    until sudo DEBIAN_FRONTEND=noninteractive apt-get update; do
        sleep 1
        echo try again
    done
    until sudo DEBIAN_FRONTEND=noninteractive apt-get install -y lsb-release; do
        sleep 1
        echo try again
    done
    DEB_VER=$(lsb_release -sc)
    if [[ ${DEB_VER} == "buster" ]]; then
        JAVA_VER="openjdk-11-jre-headless"
    else
        JAVA_VER="openjdk-11-jre-headless"
    fi
    sudo DEBIAN_FRONTEND=noninteractive apt-get -y install ${JAVA_VER} git
    sudo install -o $(id -u -n) -g $(id -g -n) -d /mnt/jenkins
'''

initMap['micro-amazon']      = initMap['rpmMap']
initMap['min-centos-6-x64']  = initMap['rpmMap']
initMap['min-centos-7-x64']  = initMap['rpmMap']
initMap['min-centos-8-x64']  = initMap['rpmMap']
initMap['min-ol-8-x64']      = initMap['rpmMap']
initMap['min-ol-9-x64']      = initMap['rpmMap']
initMap['fips-centos-7-x64'] = initMap['rpmMap']
initMap['min-centos-6-x32']  = initMap['rpmMap']

initMap['min-buster-x64']   = initMap['debMap']
initMap['min-bionic-x64']   = initMap['debMap']
initMap['min-bullseye-x64'] = initMap['debMap']
initMap['min-focal-x64']    = initMap['debMap']
initMap['min-jammy-x64']    = initMap['debMap']
initMap['min-stretch-x64']  = initMap['debMap']
initMap['min-xenial-x64']   = initMap['debMap']

initMap['ramdisk-centos-6-x64'] = initMap['rpmMapRamdisk']
initMap['ramdisk-centos-7-x64'] = initMap['rpmMapRamdisk']
initMap['ramdisk-centos-8-x64'] = initMap['rpmMapRamdisk']
initMap['ramdisk-ol-8-x64']     = initMap['rpmMapRamdisk']
initMap['ramdisk-ol-9-x64']     = initMap['rpmMapRamdisk']
initMap['ramdisk-buster-x64']   = initMap['debMapRamdisk']
initMap['ramdisk-bionic-x64']   = initMap['debMapRamdisk']
initMap['ramdisk-focal-x64']    = initMap['debMapRamdisk']
initMap['ramdisk-jammy-x64']    = initMap['debMapRamdisk']
initMap['ramdisk-stretch-x64']  = initMap['debMapRamdisk']
initMap['ramdisk-xenial-x64']   = initMap['debMapRamdisk']
initMap['ramdisk-bullseye-x64'] = initMap['debMapRamdisk']

initMap['performance-centos-6-x64']  = '''
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

    sudo yum -y install java-1.8.0-openjdk git aws-cli || :
    sudo yum -y remove java-1.7.0-openjdk || :
    sudo install -o $(id -u -n) -g $(id -g -n) -d /mnt/jenkins

    echo "*  soft  nofile  1048575" | sudo tee -a /etc/security/limits.conf
    echo "*  hard  nofile  1048575" | sudo tee -a /etc/security/limits.conf
    echo "*  soft  nproc  1048575"  | sudo tee -a /etc/security/limits.conf
    echo "*  hard  nproc  1048575"  | sudo tee -a /etc/security/limits.conf
'''

capMap = [:]
capMap['c4.2xlarge'] = '40'
capMap['m4.2xlarge'] = '40'
capMap['r5.2xlarge'] = '40'
capMap['r3.2xlarge'] = '40'

typeMap = [:]
typeMap['micro-amazon'] = 'm4.xlarge'
typeMap['docker']       = 'c4.2xlarge'
typeMap['docker-32gb']  = 'r5.2xlarge'

typeMap['performance-centos-6-x64'] = typeMap['docker-32gb']

typeMap['min-centos-7-x64']  = typeMap['docker-32gb']
typeMap['min-centos-8-x64']  = typeMap['docker-32gb']
typeMap['min-ol-8-x64']      = typeMap['docker-32gb']
typeMap['min-ol-9-x64']      = typeMap['docker-32gb']
typeMap['fips-centos-7-x64'] = typeMap['min-centos-7-x64']
typeMap['min-bionic-x64']    = typeMap['min-centos-7-x64']
typeMap['min-focal-x64']     = typeMap['min-centos-7-x64']
typeMap['min-jammy-x64']     = typeMap['min-centos-7-x64']
typeMap['min-centos-6-x32']  = 'm1.medium'
typeMap['min-centos-6-x64']  = 'r3.2xlarge'
typeMap['min-buster-x64']    = typeMap['min-centos-7-x64']
typeMap['min-stretch-x64']   = typeMap['min-centos-7-x64']
typeMap['min-xenial-x64']    = typeMap['min-centos-7-x64']
typeMap['min-bullseye-x64']  = typeMap['min-centos-7-x64']

typeMap['ramdisk-centos-6-x64'] = 'r3.2xlarge'
typeMap['ramdisk-centos-7-x64'] = typeMap['docker-32gb']
typeMap['ramdisk-centos-8-x64'] = typeMap['docker-32gb']
typeMap['ramdisk-ol-8-x64']     = typeMap['docker-32gb']
typeMap['ramdisk-ol-9-x64']     = typeMap['docker-32gb']
typeMap['ramdisk-stretch-x64']  = typeMap['docker-32gb']
typeMap['ramdisk-xenial-x64']   = typeMap['docker-32gb']
typeMap['ramdisk-bionic-x64']   = typeMap['docker-32gb']
typeMap['ramdisk-focal-x64']    = typeMap['docker-32gb']
typeMap['ramdisk-jammy-x64']    = typeMap['docker-32gb']
typeMap['ramdisk-buster-x64']   = typeMap['docker-32gb']
typeMap['ramdisk-bullseye-x64'] = typeMap['docker-32gb']

execMap = [:]
execMap['docker'] = '1'
execMap['docker-32gb'] = execMap['docker']
execMap['micro-amazon'] = '30'
execMap['min-bionic-x64'] = '1'
execMap['min-focal-x64']  = '1'
execMap['min-jammy-x64']  = '1'
execMap['min-centos-6-x32'] = '1'
execMap['min-centos-6-x64'] = '1'
execMap['min-centos-7-x64'] = '1'
execMap['min-centos-8-x64'] = '1'
execMap['min-ol-8-x64']     = '1'
execMap['min-ol-9-x64']     = '1'
execMap['fips-centos-7-x64'] = '1'
execMap['min-buster-x64'] = '1'
execMap['min-stretch-x64'] = '1'
execMap['min-xenial-x64'] = '1'
execMap['min-bullseye-x64'] = '1'

execMap['ramdisk-centos-6-x64'] = execMap['docker-32gb']
execMap['ramdisk-centos-7-x64'] = execMap['docker-32gb']
execMap['ramdisk-centos-8-x64'] = execMap['docker-32gb']
execMap['ramdisk-ol-8-x64']     = execMap['docker-32gb']
execMap['ramdisk-ol-9-x64']     = execMap['docker-32gb']
execMap['ramdisk-stretch-x64']  = execMap['docker-32gb']
execMap['ramdisk-xenial-x64']   = execMap['docker-32gb']
execMap['ramdisk-bionic-x64']   = execMap['docker-32gb']
execMap['ramdisk-focal-x64']    = execMap['docker-32gb']
execMap['ramdisk-jammy-x64']    = execMap['docker-32gb']
execMap['ramdisk-buster-x64']   = execMap['docker-32gb']
execMap['ramdisk-bullseye-x64'] = execMap['docker-32gb']

execMap['performance-centos-6-x64']   = execMap['docker-32gb']

devMap = [:]
devMap['docker']            = '/dev/xvda=:8:true:gp2,/dev/xvdd=:80:true:gp2'
devMap['docker-32gb']       = devMap['docker']
devMap['micro-amazon']      = devMap['docker']
devMap['min-bionic-x64']    = '/dev/sda1=:8:true:gp2,/dev/sdd=:80:true:gp2'
devMap['min-focal-x64']     = devMap['min-bionic-x64']
devMap['min-jammy-x64']     = devMap['min-bionic-x64']
devMap['min-centos-6-x64']  = devMap['min-bionic-x64']
devMap['min-centos-7-x64']  = devMap['min-bionic-x64']
devMap['min-centos-8-x64']  = '/dev/sda1=:10:true:gp2,/dev/sdd=:80:true:gp2'
devMap['min-ol-8-x64']      = '/dev/sda1=:10:true:gp2,/dev/sdd=:80:true:gp2'
devMap['min-ol-9-x64']      = '/dev/sda1=:10:true:gp2,/dev/sdd=:80:true:gp2'
devMap['fips-centos-7-x64'] = devMap['min-bionic-x64']
devMap['min-stretch-x64']   = 'xvda=:8:true:gp2,xvdd=:80:true:gp2'
devMap['min-buster-x64']    = '/dev/xvda=:8:true:gp2,/dev/xvdd=:80:true:gp2'
devMap['min-bullseye-x64']  = '/dev/xvda=:8:true:gp2,/dev/xvdd=:80:true:gp2'
devMap['min-xenial-x64']    = devMap['min-bionic-x64']
devMap['min-centos-6-x32']  = '/dev/sda=:8:true:gp2,/dev/sdd=:80:true:gp2'

devMap['ramdisk-centos-6-x64'] = '/dev/sda1=:8:true:gp2'
devMap['ramdisk-centos-7-x64'] = devMap['ramdisk-centos-6-x64']
devMap['ramdisk-centos-8-x64'] = '/dev/sda1=:10:true:gp2'
devMap['ramdisk-ol-8-x64']     = '/dev/sda1=:10:true:gp2'
devMap['ramdisk-ol-9-x64']     = '/dev/sda1=:10:true:gp2'
devMap['ramdisk-bionic-x64']   = devMap['ramdisk-centos-6-x64']
devMap['ramdisk-focal-x64']    = devMap['ramdisk-centos-6-x64']
devMap['ramdisk-jammy-x64']    = devMap['ramdisk-centos-6-x64']
devMap['ramdisk-xenial-x64']   = devMap['ramdisk-centos-6-x64']
devMap['ramdisk-stretch-x64']  = 'xvda=:8:true:gp2'
devMap['ramdisk-buster-x64']   = '/dev/xvda=:8:true:gp2'
devMap['ramdisk-bullseye-x64'] = '/dev/xvda=:8:true:gp2'

devMap['performance-centos-6-x64'] = '/dev/sda1=:8:true:gp2,/dev/sdd=:120:true:gp2'

labelMap = [:]
labelMap['docker']            = 'docker'
labelMap['docker-32gb']       = 'docker-32gb'
labelMap['micro-amazon']      = 'master micro-amazon'
labelMap['min-bionic-x64']    = 'min-bionic-x6 asan'
labelMap['min-focal-x64']     = 'min-focal-x64'
labelMap['min-jammy-x64']     = 'min-jammy-x64'
labelMap['min-centos-6-x32']  = 'min-centos-6-x32'
labelMap['min-centos-6-x64']  = 'min-centos-6-x64'
labelMap['min-centos-7-x64']  = 'min-centos-7-x64'
labelMap['min-centos-8-x64']  = 'min-centos-8-x64'
labelMap['min-ol-8-x64']      = 'min-ol-8-x64'
labelMap['min-ol-9-x64']      = 'min-ol-9-x64'
labelMap['fips-centos-7-x64'] = 'fips-centos-7-x64'
labelMap['min-stretch-x64']   = 'min-stretch-x64'
labelMap['min-buster-x64']    = 'min-buster-x64'
labelMap['min-xenial-x64']    = 'min-xenial-x64'
labelMap['min-bullseye-x64']  = 'min-bullseye-x64'

labelMap['ramdisk-centos-6-x64'] = 'ramdisk-centos-6-x64'
labelMap['ramdisk-centos-7-x64'] = 'ramdisk-centos-7-x64'
labelMap['ramdisk-centos-8-x64'] = 'ramdisk-centos-8-x64'
labelMap['ramdisk-ol-8-x64']     = 'ramdisk-ol-8-x64'
labelMap['ramdisk-ol-9-x64']     = 'ramdisk-ol-9-x64'
labelMap['ramdisk-bionic-x64']   = 'ramdisk-bionic-x64'
labelMap['ramdisk-focal-x64']    = 'ramdisk-focal-x64'
labelMap['ramdisk-jammy-x64']    = 'ramdisk-jammy-x64'
labelMap['ramdisk-xenial-x64']   = 'ramdisk-xenial-x64'
labelMap['ramdisk-stretch-x64']  = 'ramdisk-stretch-x64'
labelMap['ramdisk-buster-x64']   = 'ramdisk-buster-x64'
labelMap['ramdisk-bullseye-x64'] = 'ramdisk-bullseye-x64'

labelMap['performance-centos-6-x64'] = 'perf-centos-6-x64'

maxUseMap = [:]
maxUseMap['singleUse'] = 1
maxUseMap['multipleUse'] = -1

maxUseMap['docker']            = maxUseMap['multipleUse']
maxUseMap['docker-32gb']       = maxUseMap['multipleUse']
maxUseMap['micro-amazon']      = maxUseMap['multipleUse']
maxUseMap['min-bionic-x64']    = maxUseMap['singleUse']
maxUseMap['min-focal-x64']     = maxUseMap['singleUse']
maxUseMap['min-jammy-x64']     = maxUseMap['singleUse']
maxUseMap['min-centos-6-x32']  = maxUseMap['singleUse']
maxUseMap['min-centos-6-x64']  = maxUseMap['singleUse']
maxUseMap['min-centos-7-x64']  = maxUseMap['singleUse']
maxUseMap['min-centos-8-x64']  = maxUseMap['singleUse']
maxUseMap['min-ol-8-x64']      = maxUseMap['singleUse']
maxUseMap['min-ol-9-x64']      = maxUseMap['singleUse']
maxUseMap['fips-centos-7-x64'] = maxUseMap['singleUse']
maxUseMap['min-stretch-x64']   = maxUseMap['singleUse']
maxUseMap['min-buster-x64']    = maxUseMap['singleUse']
maxUseMap['min-xenial-x64']    = maxUseMap['singleUse']
maxUseMap['min-bullseye-x64']  = maxUseMap['singleUse']

maxUseMap['ramdisk-centos-6-x64'] = maxUseMap['singleUse']
maxUseMap['ramdisk-centos-7-x64'] = maxUseMap['singleUse']
maxUseMap['ramdisk-centos-8-x64'] = maxUseMap['singleUse']
maxUseMap['ramdisk-ol-8-x64']     = maxUseMap['singleUse']
maxUseMap['ramdisk-ol-9-x64']     = maxUseMap['singleUse']
maxUseMap['ramdisk-bionic-x64']   = maxUseMap['singleUse']
maxUseMap['ramdisk-focal-x64']    = maxUseMap['singleUse']
maxUseMap['ramdisk-jammy-x64']    = maxUseMap['singleUse']
maxUseMap['ramdisk-xenial-x64']   = maxUseMap['singleUse']
maxUseMap['ramdisk-stretch-x64']  = maxUseMap['singleUse']
maxUseMap['ramdisk-buster-x64']   = maxUseMap['singleUse']
maxUseMap['ramdisk-bullseye-x64'] = maxUseMap['singleUse']

maxUseMap['performance-centos-6-x64'] = maxUseMap['singleUse']

// https://github.com/jenkinsci/ec2-plugin/blob/ec2-1.39/src/main/java/hudson/plugins/ec2/SlaveTemplate.java
SlaveTemplate getTemplate(String OSType, String AZ) {
    return new SlaveTemplate(
        imageMap[OSType],                           // String ami
        '',                                         // String zone
        new SpotConfiguration(true, priceMap[typeMap[OSType]], false, '0'), // SpotConfiguration spotConfig
        'default',                                  // String securityGroups
        '/mnt/jenkins',                             // String remoteFS
        InstanceType.fromValue(typeMap[OSType]),    // InstanceType type
        ( typeMap[OSType].startsWith("c4") || typeMap[OSType].startsWith("m4") || typeMap[OSType].startsWith("c5") || typeMap[OSType].startsWith("m5") || typeMap[OSType].startsWith("r3") ), // boolean ebsOptimized
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
            new EC2Tag('Name', 'jenkins-pxc-' + OSType),
            new EC2Tag('iit-billing-tag', 'jenkins-pxc-worker')
        ],                                          // List<EC2Tag> tags
        '3',                                        // String idleTerminationMinutes
        0,                                          // Init minimumNumberOfInstances
        0,                                          // minimumNumberOfSpareInstances
        capMap[typeMap[OSType]],                    // String instanceCapStr
        'arn:aws:iam::119175775298:instance-profile/jenkins-pxc-worker', // String iamInstanceProfile
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
        maxUseMap[OSType],                          // int maxTotalUses
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

String sshKeysCredentialsId = '436191c0-07ed-4025-b049-8d5c5321e4a9'

String region = 'us-west-1'
('b'..'c').each {
    // https://github.com/jenkinsci/ec2-plugin/blob/ec2-1.39/src/main/java/hudson/plugins/ec2/AmazonEC2Cloud.java
    AmazonEC2Cloud ec2Cloud = new AmazonEC2Cloud(
        "AWS-Dev ${it}",                        // String cloudName
        true,                                   // boolean useInstanceProfileForCredentials
        '',                                     // String credentialsId
        region,                                 // String region
        privateKey,                             // String privateKey
        sshKeysCredentialsId,                   // String sshKeysCredentialsId
        '240',                                   // String instanceCapStr
        [
            getTemplate('micro-amazon',     "${region}${it}"),
            getTemplate('docker',           "${region}${it}"),
            getTemplate('docker-32gb',      "${region}${it}"),
            getTemplate('min-centos-6-x32', "${region}${it}"),
            getTemplate('min-centos-6-x64', "${region}${it}"),
            getTemplate('min-centos-7-x64', "${region}${it}"),
            getTemplate('min-centos-8-x64', "${region}${it}"),
            getTemplate('min-ol-8-x64',     "${region}${it}"),
            getTemplate('min-ol-9-x64',     "${region}${it}"),
            getTemplate('min-stretch-x64',  "${region}${it}"),
            getTemplate('min-buster-x64',   "${region}${it}"),
            getTemplate('min-bullseye-x64', "${region}${it}"),
            getTemplate('min-xenial-x64',   "${region}${it}"),
            getTemplate('min-bionic-x64',   "${region}${it}"),
            getTemplate('min-focal-x64',    "${region}${it}"),
            getTemplate('min-jammy-x64',    "${region}${it}"),
            getTemplate('ramdisk-centos-6-x64', "${region}${it}"),
            getTemplate('ramdisk-centos-7-x64', "${region}${it}"),
            getTemplate('ramdisk-centos-8-x64', "${region}${it}"),
            getTemplate('ramdisk-ol-8-x64',     "${region}${it}"),
            getTemplate('ramdisk-ol-9-x64',     "${region}${it}"),
            getTemplate('ramdisk-stretch-x64',  "${region}${it}"),
            getTemplate('ramdisk-xenial-x64',   "${region}${it}"),
            getTemplate('ramdisk-bionic-x64',   "${region}${it}"),
            getTemplate('ramdisk-focal-x64',    "${region}${it}"),
            getTemplate('ramdisk-jammy-x64',    "${region}${it}"),
            getTemplate('ramdisk-buster-x64',   "${region}${it}"),
            getTemplate('performance-centos-6-x64', "${region}${it}"),
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
