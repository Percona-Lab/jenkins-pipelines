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
netMap['eu-west-1b'] = 'subnet-02e8446080b5a52ef'
netMap['eu-west-1c'] = 'subnet-0eb939327a262a348'

imageMap = [:]
imageMap['eu-west-1a.docker'] = 'ami-09d5dd12541e69077'
imageMap['eu-west-1a.docker-32gb'] = 'ami-09d5dd12541e69077'
imageMap['eu-west-1a.docker2'] = 'ami-09d5dd12541e69077'
imageMap['eu-west-1a.micro-amazon'] = 'ami-09d5dd12541e69077'

imageMap['eu-west-1a.min-centos-6-x64']  = 'ami-0451e9d3427711cb1'
imageMap['eu-west-1a.min-centos-6-x32']  = 'ami-25839351'
imageMap['eu-west-1a.min-centos-7-x64']  = 'ami-04f5641b0d178a27a'
imageMap['eu-west-1a.fips-centos-7-x64'] = 'ami-04f5641b0d178a27a'
imageMap['eu-west-1a.min-centos-8-x64']  = 'ami-0a75a5a43b05b4d5f'
imageMap['eu-west-1a.min-ol-8-x64']      = 'ami-0f7601d8419fac927'
imageMap['eu-west-1a.min-rhel-9-x64']    = 'ami-028f9616b17ba1d53'
imageMap['eu-west-1a.min-bullseye-x64']  = 'ami-01ebd2b650c37e4d6'
imageMap['eu-west-1a.min-buster-x64']    = 'ami-04e1d2f88740af5e1'
imageMap['eu-west-1a.min-stretch-x64']   = 'ami-097672ef083ca4411'
imageMap['eu-west-1a.min-jammy-x64']     = 'ami-00c90dbdc12232b58'
imageMap['eu-west-1a.min-focal-x64']     = 'ami-03caf24deed650e2c'
imageMap['eu-west-1a.min-bionic-x64']    = 'ami-0c259a97cbf621daf'
imageMap['eu-west-1a.min-xenial-x64']    = 'ami-038d7b856fe7557b3'
imageMap['eu-west-1a.min-xenial-x32']    = 'ami-0d842b54e0120f205'
imageMap['eu-west-1a.min-hirsute-x64-zenfs'] = 'ami-02469e1cc9f95b137'
imageMap['eu-west-1a.min-focal-x64-zenfs']   = 'ami-05a657c9227900694'
imageMap['eu-west-1a.min-bionic-x64-zenfs']  = 'ami-02d7fe93ba8353d4e'

imageMap['eu-west-1b.docker'] = imageMap['eu-west-1a.docker']
imageMap['eu-west-1b.docker-32gb'] = imageMap['eu-west-1a.docker-32gb']
imageMap['eu-west-1b.docker2'] = imageMap['eu-west-1a.docker2']
imageMap['eu-west-1b.micro-amazon'] = imageMap['eu-west-1a.micro-amazon']
imageMap['eu-west-1b.min-centos-7-x64'] = imageMap['eu-west-1a.min-centos-7-x64']
imageMap['eu-west-1b.fips-centos-7-x64'] = imageMap['eu-west-1a.fips-centos-7-x64']

imageMap['eu-west-1b.min-centos-6-x64'] = imageMap['eu-west-1a.min-centos-6-x64']
imageMap['eu-west-1b.min-centos-6-x32'] = imageMap['eu-west-1a.min-centos-6-x32']
imageMap['eu-west-1b.min-centos-7-x64'] = imageMap['eu-west-1a.min-centos-7-x64']
imageMap['eu-west-1b.min-centos-8-x64'] = imageMap['eu-west-1a.min-centos-8-x64']
imageMap['eu-west-1b.min-ol-8-x64']     = imageMap['eu-west-1a.min-ol-8-x64']
imageMap['eu-west-1b.min-rhel-9-x64']   = imageMap['eu-west-1a.min-rhel-9-x64']
imageMap['eu-west-1b.min-bullseye-x64'] = imageMap['eu-west-1a.min-bullseye-x64']
imageMap['eu-west-1b.min-buster-x64']   = imageMap['eu-west-1a.min-buster-x64']
imageMap['eu-west-1b.min-jammy-x64']    = imageMap['eu-west-1a.min-jammy-x64']
imageMap['eu-west-1b.min-focal-x64']    = imageMap['eu-west-1a.min-focal-x64']
imageMap['eu-west-1b.min-bionic-x64']   = imageMap['eu-west-1a.min-bionic-x64']
imageMap['eu-west-1b.min-stretch-x64']  = imageMap['eu-west-1a.min-stretch-x64']
imageMap['eu-west-1b.min-xenial-x64']   = imageMap['eu-west-1a.min-xenial-x64']
imageMap['eu-west-1b.min-xenial-x32']   = imageMap['eu-west-1a.min-xenial-x32']
imageMap['eu-west-1b.min-hirsute-x64-zenfs'] = imageMap['eu-west-1a.min-hirsute-x64-zenfs']
imageMap['eu-west-1b.min-focal-x64-zenfs'] = imageMap['eu-west-1a.min-focal-x64-zenfs']
imageMap['eu-west-1b.min-bionic-x64-zenfs']  = imageMap['eu-west-1a.min-bionic-x64-zenfs']

imageMap['eu-west-1c.docker'] = imageMap['eu-west-1a.docker']
imageMap['eu-west-1c.docker-32gb'] = imageMap['eu-west-1a.docker-32gb']
imageMap['eu-west-1c.docker2'] = imageMap['eu-west-1a.docker2']
imageMap['eu-west-1c.micro-amazon'] = imageMap['eu-west-1a.micro-amazon']
imageMap['eu-west-1c.min-centos-7-x64'] = imageMap['eu-west-1a.min-centos-7-x64']
imageMap['eu-west-1c.fips-centos-7-x64'] = imageMap['eu-west-1a.fips-centos-7-x64']

imageMap['eu-west-1c.min-centos-6-x64'] = imageMap['eu-west-1a.min-centos-6-x64']
imageMap['eu-west-1c.min-centos-6-x32'] = imageMap['eu-west-1a.min-centos-6-x32']
imageMap['eu-west-1c.min-centos-7-x64'] = imageMap['eu-west-1a.min-centos-7-x64']
imageMap['eu-west-1c.min-centos-8-x64'] = imageMap['eu-west-1a.min-centos-8-x64']
imageMap['eu-west-1c.min-ol-8-x64']     = imageMap['eu-west-1a.min-ol-8-x64']
imageMap['eu-west-1c.min-rhel-9-x64']   = imageMap['eu-west-1a.min-rhel-9-x64']
imageMap['eu-west-1c.min-bullseye-x64'] = imageMap['eu-west-1a.min-bullseye-x64']
imageMap['eu-west-1c.min-buster-x64']   = imageMap['eu-west-1a.min-buster-x64']
imageMap['eu-west-1c.min-jammy-x64']    = imageMap['eu-west-1a.min-jammy-x64']
imageMap['eu-west-1c.min-focal-x64']    = imageMap['eu-west-1a.min-focal-x64'] 
imageMap['eu-west-1c.min-bionic-x64']   = imageMap['eu-west-1a.min-bionic-x64']
imageMap['eu-west-1c.min-stretch-x64']  = imageMap['eu-west-1a.min-stretch-x64']
imageMap['eu-west-1c.min-xenial-x64']   = imageMap['eu-west-1a.min-xenial-x64']
imageMap['eu-west-1c.min-xenial-x32']   = imageMap['eu-west-1a.min-xenial-x32']
imageMap['eu-west-1c.min-hirsute-x64-zenfs'] = imageMap['eu-west-1a.min-hirsute-x64-zenfs']
imageMap['eu-west-1c.min-focal-x64-zenfs'] = imageMap['eu-west-1a.min-focal-x64-zenfs']
imageMap['eu-west-1c.min-bionic-x64-zenfs']  = imageMap['eu-west-1a.min-bionic-x64-zenfs']

priceMap = [:]
priceMap['t2.small'] = '0.01'
priceMap['m1.medium'] = '0.05'
priceMap['c5.xlarge'] = '0.10'
priceMap['m4.xlarge'] = '0.10'
priceMap['m4.2xlarge'] = '0.20'
priceMap['r4.4xlarge'] = '0.38'
priceMap['m5d.2xlarge'] = '0.20'
priceMap['c5d.xlarge'] = '0.20'
priceMap['i4i.2xlarge'] = '0.40'

userMap = [:]
userMap['docker']            = 'ec2-user'
userMap['docker-32gb']       = userMap['docker']
userMap['docker2']           = userMap['docker']
userMap['micro-amazon']      = userMap['docker']
userMap['min-jammy-x64']     = 'ubuntu'
userMap['min-focal-x64']     = 'ubuntu'
userMap['min-bionic-x64']    = 'ubuntu'
userMap['min-xenial-x64']    = 'ubuntu'
userMap['min-xenial-x32']    = 'ubuntu'
userMap['min-centos-6-x32']  = 'root'
userMap['min-centos-6-x64']  = 'centos'
userMap['min-centos-7-x64']  = 'centos'
userMap['min-centos-8-x64']  = 'centos'
userMap['min-ol-8-x64']      = 'ec2-user'
userMap['min-rhel-9-x64']    = 'ec2-user'
userMap['fips-centos-7-x64'] = 'centos'
userMap['min-stretch-x64']   = 'admin'
userMap['min-buster-x64']    = 'admin'
userMap['min-bullseye-x64']  = 'admin'
userMap['min-hirsute-x64-zenfs']    = 'ubuntu'
userMap['min-focal-x64-zenfs']    = 'ubuntu'
userMap['min-bionic-x64-zenfs']   = 'ubuntu'

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
    sudo yum -y install java-1.8.0-openjdk git docker p7zip
    sudo yum -y remove java-1.7.0-openjdk awscli

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
    echo "* * * * * root /usr/sbin/route add default gw 10.199.1.1 eth0" | sudo tee /etc/cron.d/fix-default-route
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

    echo '10.30.6.9 repo.ci.percona.com' | sudo tee -a /etc/hosts

    if [ -f /etc/redhat-release ]; then
        if grep -q 'CentOS.* 8\\.' /etc/redhat-release; then
            sudo sed -i 's/mirrorlist/#mirrorlist/g' /etc/yum.repos.d/CentOS-Linux-*
            sudo sed -i 's|#baseurl=http://mirror.centos.org|baseurl=http://vault.centos.org|g' /etc/yum.repos.d/CentOS-Linux-*
        fi
    fi

    until sudo yum makecache; do
        sleep 1
        echo try again
    done
    sudo yum -y install java-1.8.0-openjdk git || :
    sudo yum -y install aws-cli || :
    sudo yum -y remove java-1.7.0-openjdk || :
    sudo install -o $(id -u -n) -g $(id -g -n) -d /mnt/jenkins
'''
initMap['min-centos-6-x64'] = '''
    set -o xtrace
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

    echo '10.30.6.9 repo.ci.percona.com' | sudo tee -a /etc/hosts
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

    sudo yum -y install java-1.8.0-openjdk git aws-cli || :
    sudo yum -y remove java-1.7.0-openjdk || :
    sudo install -o $(id -u -n) -g $(id -g -n) -d /mnt/jenkins
'''
initMap['min-centos-7-x64']  = initMap['micro-amazon']
initMap['fips-centos-7-x64'] = initMap['micro-amazon']
initMap['min-centos-8-x64']  = initMap['micro-amazon']
initMap['min-ol-8-x64']      = initMap['micro-amazon']
initMap['min-rhel-9-x64']    = initMap['micro-amazon']
initMap['min-centos-6-x32'] = '''
    set -o xtrace
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

    echo '10.30.6.9 repo.ci.percona.com' | sudo tee -a /etc/hosts
    sudo curl -k https://jenkins.percona.com/downloads/cent6/centos6-eol-s3.repo --output /etc/yum.repos.d/CentOS-Base.repo

    until sudo yum makecache; do
        sleep 1
        echo try again
    done
    until sudo yum -y update; do
        sleep 1
        echo try again
    done
    until sudo yum -y install epel-release; do    
        sleep 1
        echo try again
    done
    sudo rm /etc/yum.repos.d/epel-testing.repo
    sudo curl https://jenkins.percona.com/downloads/cent6/centos6-epel-eol.repo --output /etc/yum.repos.d/epel.repo

    sudo yum -y install java-1.8.0-openjdk git aws-cli || :
    sudo yum -y remove java-1.7.0-openjdk || :
    sudo install -o $(id -u -n) -g $(id -g -n) -d /mnt/jenkins

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
    until sudo apt-get -y install openjdk-8-jre-headless git; do
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

    until sudo apt-get update; do
        sleep 1
        echo try again
    done
    sudo apt-get -y install openjdk-11-jre-headless git
    sudo install -o $(id -u -n) -g $(id -g -n) -d /mnt/jenkins
'''
initMap['min-jammy-x64'] = initMap['min-bionic-x64']
initMap['min-focal-x64'] = initMap['min-bionic-x64']
initMap['min-stretch-x64'] = initMap['min-bionic-x64']
initMap['min-bullseye-x64'] = initMap['min-buster-x64']
initMap['min-xenial-x64'] = initMap['min-bionic-x64']
initMap['min-xenial-x32'] = initMap['min-bionic-x64']
initMap['min-hirsute-x64-zenfs'] = initMap['min-bionic-x64']
initMap['min-focal-x64-zenfs'] = initMap['min-bionic-x64']
initMap['min-bionic-x64-zenfs'] = initMap['min-bionic-x64']

capMap = [:]
capMap['c5.xlarge']   = '60'
capMap['m4.xlarge']   = '5'
capMap['m4.2xlarge']  = '40'
capMap['r4.4xlarge']  = '40'
capMap['c5d.xlarge']  = '10'
capMap['i4i.2xlarge'] = '40'

typeMap = [:]
typeMap['micro-amazon']      = 't2.small'
typeMap['docker']            = 'c5.xlarge'
typeMap['docker-32gb']       = 'm4.2xlarge'
typeMap['docker2']           = 'r4.4xlarge'
typeMap['min-centos-7-x64']  = typeMap['docker-32gb']
typeMap['min-centos-8-x64']  = typeMap['min-centos-7-x64']
typeMap['min-ol-8-x64']      = typeMap['min-centos-7-x64']
typeMap['min-rhel-9-x64']    = 'i4i.2xlarge'
typeMap['fips-centos-7-x64'] = typeMap['min-centos-7-x64']
typeMap['min-jammy-x64']     = typeMap['min-centos-7-x64']
typeMap['min-focal-x64']     = typeMap['min-centos-7-x64']
typeMap['min-bionic-x64']    = typeMap['min-centos-7-x64']
typeMap['min-centos-6-x32']  = 'm1.medium'
typeMap['min-centos-6-x64']  = typeMap['min-centos-7-x64']
typeMap['min-bullseye-x64']  = typeMap['min-centos-7-x64']
typeMap['min-buster-x64']    = typeMap['min-centos-7-x64']
typeMap['min-stretch-x64']   = typeMap['min-centos-7-x64']
typeMap['min-xenial-x64']    = typeMap['min-centos-7-x64']
typeMap['min-xenial-x32']    = 'm1.medium'
typeMap['min-hirsute-x64-zenfs'] = typeMap['min-centos-7-x64']
typeMap['min-focal-x64-zenfs'] = typeMap['min-centos-7-x64']
typeMap['min-bionic-x64-zenfs'] = typeMap['min-centos-7-x64']

execMap = [:]
execMap['docker']            = '1'
execMap['docker-32gb']       = execMap['docker']
execMap['docker2']           = execMap['docker']
execMap['micro-amazon']      = '30'
execMap['min-bionic-x64']    = '1'
execMap['min-focal-x64']     = '1'
execMap['min-jammy-x64']     = '1'
execMap['min-centos-6-x32']  = '1'
execMap['min-centos-6-x64']  = '1'
execMap['min-centos-7-x64']  = '1'
execMap['fips-centos-7-x64'] = '1'
execMap['min-centos-8-x64']  = '1'
execMap['min-ol-8-x64']      = '1'
execMap['min-rhel-9-x64']    = '1'
execMap['min-stretch-x64']   = '1'
execMap['min-xenial-x64']    = '1'
execMap['min-xenial-x32']    = '1'
execMap['min-buster-x64']    = '1'
execMap['min-bullseye-x64']  = '1'
execMap['min-hirsute-x64-zenfs'] = '1'
execMap['min-focal-x64-zenfs'] = '1'
execMap['min-bionic-x64-zenfs'] = '1'

devMap = [:]
devMap['docker']            = '/dev/xvda=:8:true:gp2,/dev/xvdd=:180:true:gp2'
devMap['docker2']           = '/dev/xvda=:8:true:gp2,/dev/xvdd=:180:true:gp2'
devMap['docker-32gb']       = devMap['docker']
devMap['micro-amazon']      = devMap['docker']
devMap['min-bionic-x64']    = '/dev/sda1=:8:true:gp2,/dev/sdd=:180:true:gp2'
devMap['min-focal-x64']     = devMap['min-bionic-x64']
devMap['min-jammy-x64']     = devMap['min-bionic-x64']
devMap['min-centos-6-x64']  = devMap['min-bionic-x64']
devMap['min-centos-7-x64']  = devMap['min-bionic-x64']
devMap['fips-centos-7-x64'] = devMap['min-bionic-x64']
devMap['min-centos-8-x64']  = '/dev/sda1=:10:true:gp2,/dev/sdd=:180:true:gp2'
devMap['min-ol-8-x64']      = '/dev/sda1=:10:true:gp2,/dev/sdd=:180:true:gp2'
devMap['min-rhel-9-x64']    = '/dev/sda1=:10:true:gp2,/dev/sdd=:180:true:gp2'
devMap['min-stretch-x64']   = 'xvda=:8:true:gp2,xvdd=:180:true:gp2'
devMap['min-xenial-x64']    = devMap['min-bionic-x64']
devMap['min-xenial-x32']    = '/dev/sda1=:10:false:gp2,/dev/sdd=:180:false:gp2'
devMap['min-centos-6-x32']  = '/dev/sda=:8:true:gp2,/dev/sdd=:180:true:gp2'
devMap['min-buster-x64']    = devMap['docker']
devMap['min-bullseye-x64']  = devMap['docker']
devMap['min-hirsute-x64-zenfs'] = '/dev/sda1=:10:true:gp2,/dev/sdd=:180:true:gp2'
devMap['min-focal-x64-zenfs'] = '/dev/sda1=:10:true:gp2,/dev/sdd=:180:true:gp2'
devMap['min-bionic-x64-zenfs'] = '/dev/sda1=:10:true:gp2,/dev/sdd=:180:true:gp2'

labelMap = [:]
labelMap['docker']            = ''
labelMap['docker-32gb']       = ''
labelMap['docker2']           = ''
labelMap['micro-amazon']      = 'master'
labelMap['min-bionic-x64']    = 'asan'
labelMap['min-focal-x64']     = ''
labelMap['min-jammy-x64']     = ''
labelMap['min-centos-6-x32']  = ''
labelMap['min-centos-6-x64']  = ''
labelMap['min-centos-7-x64']  = ''
labelMap['fips-centos-7-x64'] = ''
labelMap['min-centos-8-x64']  = ''
labelMap['min-ol-8-x64']      = ''
labelMap['min-rhel-9-x64']    = ''
labelMap['min-stretch-x64']   = ''
labelMap['min-xenial-x64']    = ''
labelMap['min-xenial-x32']    = ''
labelMap['min-buster-x64']    = ''
labelMap['min-bullseye-x64']  = ''
labelMap['min-hirsute-x64-zenfs']  = ''
labelMap['min-focal-x64-zenfs']    = ''
labelMap['min-bionic-x64-zenfs']   = ''

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
        new UnixData('', '', '', '22'),             // AMITypeData amiType
        '-Xmx512m -Xms512m',                        // String jvmopts
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
    AmazonEC2Cloud ec2Cloud = new AmazonEC2Cloud(
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
            getTemplate('micro-amazon',          "${region}${it}"),
            getTemplate('min-xenial-x64',        "${region}${it}"),
            getTemplate('min-xenial-x32',        "${region}${it}"),
            getTemplate('min-stretch-x64',       "${region}${it}"),
            getTemplate('min-jammy-x64',         "${region}${it}"),
            getTemplate('min-focal-x64',         "${region}${it}"),
            getTemplate('min-bionic-x64',        "${region}${it}"),
            getTemplate('min-buster-x64',        "${region}${it}"),
            getTemplate('min-ol-8-x64',          "${region}${it}"),
            getTemplate('min-rhel-9-x64',        "${region}${it}"),
            getTemplate('min-centos-8-x64',      "${region}${it}"),
            getTemplate('min-centos-7-x64',      "${region}${it}"),
            getTemplate('min-centos-6-x64',      "${region}${it}"),
            getTemplate('min-centos-6-x32',      "${region}${it}"),
            getTemplate('min-bullseye-x64',      "${region}${it}"),
            getTemplate('min-hirsute-x64-zenfs', "${region}${it}"),
            getTemplate('min-focal-x64-zenfs',   "${region}${it}"),
            getTemplate('min-bionic-x64-zenfs',  "${region}${it}"),
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

