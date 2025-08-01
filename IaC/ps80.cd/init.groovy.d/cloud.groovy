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
netMap['us-west-2b'] = 'subnet-028f5265edd1581c3'
netMap['us-west-2c'] = 'subnet-0fe430aa745b0116f'

imageMap = [:]
imageMap['us-west-2a.docker']            = 'ami-0f3b366cff2e46862'
imageMap['us-west-2a.docker-32gb']       = 'ami-0f3b366cff2e46862'
imageMap['us-west-2a.docker-32gb-hirsute']  = 'ami-0cbdf6c0f39fd3950'
imageMap['us-west-2a.docker-32gb-jammy']    = 'ami-04aa685cc800320b3'
imageMap['us-west-2a.docker-32gb-noble']    = 'ami-0cf2b4e024cdb6960'
imageMap['us-west-2a.docker-32gb-focal']    = 'ami-0ceeab680f529cc36'
imageMap['us-west-2a.docker-32gb-bullseye'] = 'ami-0c1b4dff690b5d229'
imageMap['us-west-2a.docker2']           = 'ami-0f3b366cff2e46862'
imageMap['us-west-2a.micro-amazon']      = 'ami-0f3b366cff2e46862'
imageMap['us-west-2a.min-amazon-2-x64']  = 'ami-0f3b366cff2e46862'
imageMap['us-west-2a.min-al2023-x64']    = 'ami-0005ee01bca55ab66'
imageMap['us-west-2a.min-centos-8-x64']  = 'ami-0155c31ea13d4abd2'
imageMap['us-west-2a.min-ol-8-x64']      = 'ami-0c32e4ead7507bc6f'
imageMap['us-west-2a.min-ol-9-x64']      = 'ami-00a5d5bcea31bb02c'
imageMap['us-west-2a.min-centos-7-x64']  = 'ami-04f798ca92cc13f74'
imageMap['us-west-2a.fips-centos-7-x64'] = 'ami-036d2cdf95d86d256'
imageMap['us-west-2a.min-centos-6-x64']  = 'ami-052ff42ae3be02b6a'
imageMap['us-west-2a.min-buster-x64']    = 'ami-0164ab05efc075cbc'
imageMap['us-west-2a.min-jammy-x64']     = 'ami-04aa685cc800320b3'
imageMap['us-west-2a.min-noble-x64']     = 'ami-0cf2b4e024cdb6960'
imageMap['us-west-2a.min-focal-x64']     = 'ami-0ceeab680f529cc36'
imageMap['us-west-2a.min-bionic-x64']    = 'ami-0efcd70d6d745b26c'
imageMap['us-west-2a.min-stretch-x64']   = 'ami-040a022e1b0c8b7f4'
imageMap['us-west-2a.min-xenial-x64']    = 'ami-079e7a3f57cc8e0d0'
imageMap['us-west-2a.min-bullseye-x64']  = 'ami-0c1b4dff690b5d229'
imageMap['us-west-2a.min-bookworm-x64']  = 'ami-0544719b13af6edc3'
imageMap['us-west-2a.min-rhel-10-x64']   = 'ami-0987e9d53da324257'

imageMap['us-west-2a.docker-32gb-aarch64'] = 'ami-0f1bcb5bf688257a2'
imageMap['us-west-2a.min-al2023-aarch64']  = 'ami-0c7104b910fd5acb5'

imageMap['us-west-2b.docker']            = imageMap['us-west-2a.docker']
imageMap['us-west-2b.docker-32gb']       = imageMap['us-west-2a.docker-32gb']
imageMap['us-west-2b.docker-32gb-hirsute']  = imageMap['us-west-2a.docker-32gb-hirsute']
imageMap['us-west-2b.docker-32gb-jammy']    = imageMap['us-west-2a.docker-32gb-jammy']
imageMap['us-west-2b.docker-32gb-noble']    = imageMap['us-west-2a.docker-32gb-noble']
imageMap['us-west-2b.docker-32gb-focal']    = imageMap['us-west-2a.docker-32gb-focal']
imageMap['us-west-2b.docker-32gb-bullseye'] = imageMap['us-west-2a.docker-32gb-bullseye']
imageMap['us-west-2b.docker2']           = imageMap['us-west-2a.docker2']
imageMap['us-west-2b.micro-amazon']      = imageMap['us-west-2a.micro-amazon']
imageMap['us-west-2b.min-amazon-2-x64']  = imageMap['us-west-2a.min-amazon-2-x64']
imageMap['us-west-2b.min-al2023-x64']    = imageMap['us-west-2a.min-al2023-x64']
imageMap['us-west-2b.min-centos-8-x64']  = imageMap['us-west-2a.min-centos-8-x64']
imageMap['us-west-2b.min-ol-8-x64']      = imageMap['us-west-2a.min-ol-8-x64']
imageMap['us-west-2b.min-ol-9-x64']      = imageMap['us-west-2a.min-ol-9-x64']
imageMap['us-west-2b.min-centos-7-x64']  = imageMap['us-west-2a.min-centos-7-x64']
imageMap['us-west-2b.fips-centos-7-x64'] = imageMap['us-west-2a.fips-centos-7-x64']
imageMap['us-west-2b.min-centos-6-x64']  = imageMap['us-west-2a.min-centos-6-x64']
imageMap['us-west-2b.min-buster-x64']    = imageMap['us-west-2a.min-buster-x64']
imageMap['us-west-2b.min-jammy-x64' ]    = imageMap['us-west-2a.min-jammy-x64']
imageMap['us-west-2b.min-noble-x64' ]    = imageMap['us-west-2a.min-noble-x64']
imageMap['us-west-2b.min-focal-x64' ]    = imageMap['us-west-2a.min-focal-x64']
imageMap['us-west-2b.min-bionic-x64']    = imageMap['us-west-2a.min-bionic-x64']
imageMap['us-west-2b.min-stretch-x64']   = imageMap['us-west-2a.min-stretch-x64']
imageMap['us-west-2b.min-xenial-x64']    = imageMap['us-west-2a.min-xenial-x64']
imageMap['us-west-2b.min-bullseye-x64']  = imageMap['us-west-2a.min-bullseye-x64']
imageMap['us-west-2b.min-bookworm-x64']  = imageMap['us-west-2a.min-bookworm-x64']
imageMap['us-west-2b.min-rhel-10-x64']   = imageMap['us-west-2a.min-rhel-10-x64']

imageMap['us-west-2b.docker-32gb-aarch64'] = imageMap['us-west-2a.docker-32gb-aarch64']
imageMap['us-west-2b.min-al2023-aarch64']  = imageMap['us-west-2a.min-al2023-aarch64']

imageMap['us-west-2c.docker']            = imageMap['us-west-2a.docker']
imageMap['us-west-2c.docker-32gb']       = imageMap['us-west-2a.docker-32gb']
imageMap['us-west-2c.docker-32gb-hirsute']  = imageMap['us-west-2a.docker-32gb-hirsute']
imageMap['us-west-2c.docker-32gb-jammy']    = imageMap['us-west-2a.docker-32gb-jammy']
imageMap['us-west-2c.docker-32gb-noble']    = imageMap['us-west-2a.docker-32gb-noble']
imageMap['us-west-2c.docker-32gb-focal']    = imageMap['us-west-2a.docker-32gb-focal']
imageMap['us-west-2c.docker-32gb-bullseye'] = imageMap['us-west-2a.docker-32gb-bullseye']
imageMap['us-west-2c.docker2']           = imageMap['us-west-2a.docker2']
imageMap['us-west-2c.micro-amazon']      = imageMap['us-west-2a.micro-amazon']
imageMap['us-west-2c.min-amazon-2-x64']  = imageMap['us-west-2a.min-amazon-2-x64']
imageMap['us-west-2c.min-al2023-x64']    = imageMap['us-west-2a.min-al2023-x64']
imageMap['us-west-2c.min-centos-8-x64']  = imageMap['us-west-2a.min-centos-8-x64']
imageMap['us-west-2c.min-ol-8-x64']      = imageMap['us-west-2a.min-ol-8-x64']
imageMap['us-west-2c.min-ol-9-x64']      = imageMap['us-west-2a.min-ol-9-x64']
imageMap['us-west-2c.min-centos-7-x64']  = imageMap['us-west-2a.min-centos-7-x64']
imageMap['us-west-2c.fips-centos-7-x64'] = imageMap['us-west-2a.fips-centos-7-x64']
imageMap['us-west-2c.min-centos-6-x64']  = imageMap['us-west-2a.min-centos-6-x64']
imageMap['us-west-2c.min-buster-x64']    = imageMap['us-west-2a.min-buster-x64']
imageMap['us-west-2c.min-jammy-x64' ]    = imageMap['us-west-2a.min-jammy-x64']
imageMap['us-west-2c.min-noble-x64' ]    = imageMap['us-west-2a.min-noble-x64']
imageMap['us-west-2c.min-focal-x64' ]    = imageMap['us-west-2a.min-focal-x64']
imageMap['us-west-2c.min-bionic-x64']    = imageMap['us-west-2a.min-bionic-x64']
imageMap['us-west-2c.min-stretch-x64']   = imageMap['us-west-2a.min-stretch-x64']
imageMap['us-west-2c.min-xenial-x64']    = imageMap['us-west-2a.min-xenial-x64']
imageMap['us-west-2c.min-bullseye-x64']  = imageMap['us-west-2a.min-bullseye-x64']
imageMap['us-west-2c.min-bookworm-x64']  = imageMap['us-west-2a.min-bookworm-x64']
imageMap['us-west-2c.min-rhel-10-x64']   = imageMap['us-west-2a.min-rhel-10-x64']

imageMap['us-west-2c.docker-32gb-aarch64'] = imageMap['us-west-2a.docker-32gb-aarch64']
imageMap['us-west-2c.min-al2023-aarch64']  = imageMap['us-west-2a.min-al2023-aarch64']

imageMap['us-west-2d.docker']            = imageMap['us-west-2a.docker']
imageMap['us-west-2d.docker-32gb']       = imageMap['us-west-2a.docker-32gb']
imageMap['us-west-2d.docker-32gb-hirsute']  = imageMap['us-west-2a.docker-32gb-hirsute']
imageMap['us-west-2d.docker-32gb-jammy']    = imageMap['us-west-2a.docker-32gb-jammy']
imageMap['us-west-2d.docker-32gb-noble']    = imageMap['us-west-2a.docker-32gb-noble']
imageMap['us-west-2d.docker-32gb-focal']    = imageMap['us-west-2a.docker-32gb-focal']
imageMap['us-west-2d.docker-32gb-bullseye'] = imageMap['us-west-2a.docker-32gb-bullseye']
imageMap['us-west-2d.docker2']           = imageMap['us-west-2a.docker2']
imageMap['us-west-2d.micro-amazon']      = imageMap['us-west-2a.micro-amazon']
imageMap['us-west-2d.min-amazon-2-x64']  = imageMap['us-west-2a.min-amazon-2-x64']
imageMap['us-west-2d.min-al2023-x64']    = imageMap['us-west-2a.min-al2023-x64']
imageMap['us-west-2d.min-centos-8-x64']  = imageMap['us-west-2a.min-centos-8-x64']
imageMap['us-west-2d.min-ol-8-x64']      = imageMap['us-west-2a.min-ol-8-x64']
imageMap['us-west-2d.min-ol-9-x64']      = imageMap['us-west-2a.min-ol-9-x64']
imageMap['us-west-2d.min-centos-7-x64']  = imageMap['us-west-2a.min-centos-7-x64']
imageMap['us-west-2d.fips-centos-7-x64'] = imageMap['us-west-2a.fips-centos-7-x64']
imageMap['us-west-2d.min-centos-6-x64']  = imageMap['us-west-2a.min-centos-6-x64']
imageMap['us-west-2d.min-buster-x64']    = imageMap['us-west-2a.min-buster-x64']
imageMap['us-west-2d.min-jammy-x64' ]    = imageMap['us-west-2a.min-jammy-x64']
imageMap['us-west-2d.min-noble-x64' ]    = imageMap['us-west-2a.min-noble-x64']
imageMap['us-west-2d.min-focal-x64' ]    = imageMap['us-west-2a.min-focal-x64']
imageMap['us-west-2d.min-bionic-x64']    = imageMap['us-west-2a.min-bionic-x64']
imageMap['us-west-2d.min-stretch-x64']   = imageMap['us-west-2a.min-stretch-x64']
imageMap['us-west-2d.min-xenial-x64']    = imageMap['us-west-2a.min-xenial-x64']
imageMap['us-west-2d.min-bullseye-x64']  = imageMap['us-west-2a.min-bullseye-x64']
imageMap['us-west-2d.min-bookworm-x64']  = imageMap['us-west-2a.min-bookworm-x64']
imageMap['us-west-2d.min-rhel-10-x64']   = imageMap['us-west-2a.min-rhel-10-x64']

imageMap['us-west-2d.docker-32gb-aarch64'] = imageMap['us-west-2a.docker-32gb-aarch64']
imageMap['us-west-2d.min-al2023-aarch64']  = imageMap['us-west-2a.min-al2023-aarch64']

priceMap = [:]
priceMap['t2.medium'] = '0.07'   // type=t2.medium, vCPU=2, memory=4GiB, saving=69%, interruption='<5%', price=0.032000
priceMap['c5d.4xlarge'] = '0.40'  // type=c5d.4xlarge, vCPU=16, memory=32GiB, saving=62%, interruption='<5%', price=0.272000
priceMap['r5a.4xlarge'] = '0.65' // type=r5a.4xlarge, vCPU=16, memory=128GiB, saving=67%, interruption='<5%', price=0.583900
priceMap['m5n.2xlarge'] = '0.37' // type=m5n.2xlarge, vCPU=8, memory=32GiB, saving=64%, interruption='<5%', price=0.201900

priceMap['i4g.2xlarge'] = '0.34' // aarch

userMap = [:]
userMap['docker']            = 'ec2-user'
userMap['docker-32gb']       = userMap['docker']
userMap['docker-32gb-hirsute']  = 'ubuntu'
userMap['docker-32gb-jammy']    = 'ubuntu'
userMap['docker-32gb-noble']    = 'ubuntu'
userMap['docker-32gb-focal']    = 'ubuntu'
userMap['docker-32gb-bullseye'] = 'admin' // userMap['min-bullseye-x64']
userMap['docker2']           = userMap['docker']
userMap['micro-amazon']      = userMap['docker']
userMap['min-amazon-2-x64']  = userMap['docker']
userMap['min-al2023-x64']    = userMap['docker']
userMap['min-jammy-x64']     = 'ubuntu'
userMap['min-noble-x64']     = 'ubuntu'
userMap['min-focal-x64']     = 'ubuntu'
userMap['min-bionic-x64']    = 'ubuntu'
userMap['min-xenial-x64']    = 'ubuntu'
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
userMap['min-rhel-10-x64']   = 'ec2-user'

userMap['docker-32gb-aarch64'] = 'ec2-user'
userMap['min-al2023-aarch64']  = 'ec2-user'

userMap['psmdb'] = userMap['min-xenial-x64']

modeMap = [:]
modeMap['docker']            = 'Node.Mode.NORMAL'
modeMap['docker-32gb']       = modeMap['docker']
modeMap['docker-32gb-hirsute']  = modeMap['docker']
modeMap['docker-32gb-jammy']    = modeMap['docker']
modeMap['docker-32gb-noble']    = modeMap['docker']
modeMap['docker-32gb-focal']    = modeMap['docker']
modeMap['docker-32gb-bullseye'] = modeMap['docker']
modeMap['docker2']           = modeMap['docker']
modeMap['micro-amazon']      = modeMap['docker']
modeMap['min-amazon-2-x64']  = modeMap['docker']
modeMap['min-al2023-x64']    = modeMap['docker']
modeMap['min-jammy-x64']     = 'Node.Mode.EXCLUSIVE'
modeMap['min-noble-x64']     = 'Node.Mode.EXCLUSIVE'
modeMap['min-focal-x64']     = 'Node.Mode.EXCLUSIVE'
modeMap['min-bionic-x64']    = modeMap['min-focal-x64']
modeMap['min-xenial-x64']    = modeMap['min-focal-x64']
modeMap['min-centos-6-x64']  = modeMap['min-focal-x64']
modeMap['min-centos-7-x64']  = modeMap['min-focal-x64']
modeMap['min-centos-8-x64']  = modeMap['min-focal-x64']
modeMap['min-ol-8-x64']      = modeMap['min-focal-x64']
modeMap['min-ol-9-x64']      = modeMap['min-focal-x64']
modeMap['fips-centos-7-x64'] = modeMap['min-focal-x64']
modeMap['min-stretch-x64']   = modeMap['min-focal-x64']
modeMap['min-buster-x64']    = modeMap['min-focal-x64']
modeMap['min-bullseye-x64']  = modeMap['min-focal-x64']
modeMap['min-bookworm-x64']  = modeMap['min-focal-x64']
modeMap['min-rhel-10-x64']   = modeMap['min-focal-x64']

modeMap['docker-32gb-aarch64'] = 'Node.Mode.EXCLUSIVE'
modeMap['min-al2023-aarch64']  = 'Node.Mode.EXCLUSIVE'

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
    sudo sed -i.bak -e 's^ExecStart=.*^ExecStart=/usr/bin/dockerd --data-root=/mnt/docker --default-ulimit nofile=900000:900000^' /lib/systemd/system/docker.service
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
initMap['docker-32gb-hirsute'] = '''
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

    until sudo DEBIAN_FRONTEND=noninteractive apt-get -y install openjdk-11-jre-headless apt-transport-https ca-certificates curl gnupg lsb-release unzip; do
        sleep 1
        echo try again
    done

    curl -fsSL https://download.docker.com/linux/ubuntu/gpg | sudo gpg --dearmor -o /usr/share/keyrings/docker-archive-keyring.gpg
    echo "deb [arch=amd64 signed-by=/usr/share/keyrings/docker-archive-keyring.gpg] https://download.docker.com/linux/ubuntu $(lsb_release -cs) stable" | sudo tee /etc/apt/sources.list.d/docker.list > /dev/null
    until sudo DEBIAN_FRONTEND=noninteractive apt-get update; do
        sleep 1
        echo try again
    done
    until sudo DEBIAN_FRONTEND=noninteractive apt-get -y install docker-ce docker-ce-cli containerd.io; do
        sleep 1
        echo try again
    done

    if ! $(aws --version | grep -q 'aws-cli/2'); then
        find /tmp -maxdepth 1 -name "*aws*" | xargs sudo rm -rf

        until curl "https://awscli.amazonaws.com/awscli-exe-linux-x86_64.zip" -o "/tmp/awscliv2.zip"; do
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
initMap['docker-32gb-jammy'] = initMap['docker-32gb-hirsute']
initMap['docker-32gb-noble'] = initMap['docker-32gb-hirsute']
initMap['docker-32gb-focal'] = initMap['docker-32gb-hirsute']
initMap['docker-32gb-bullseye'] = '''
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

    until sudo DEBIAN_FRONTEND=noninteractive apt-get -y install openjdk-11-jre-headless apt-transport-https ca-certificates curl gnupg lsb-release unzip; do
        sleep 1
        echo try again
    done

    curl -fsSL https://download.docker.com/linux/debian/gpg | sudo gpg --dearmor -o /usr/share/keyrings/docker-archive-keyring.gpg
    echo "deb [arch=amd64 signed-by=/usr/share/keyrings/docker-archive-keyring.gpg] https://download.docker.com/linux/debian $(lsb_release -cs) stable" | sudo tee /etc/apt/sources.list.d/docker.list > /dev/null
    until sudo DEBIAN_FRONTEND=noninteractive apt-get update; do
        sleep 1
        echo try again
    done
    until sudo DEBIAN_FRONTEND=noninteractive apt-get -y install docker-ce docker-ce-cli containerd.io; do
        sleep 1
        echo try again
    done

    if ! $(aws --version | grep -q 'aws-cli/2'); then
        find /tmp -maxdepth 1 -name "*aws*" | xargs sudo rm -rf

        until curl "https://awscli.amazonaws.com/awscli-exe-linux-x86_64.zip" -o "/tmp/awscliv2.zip"; do
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

    if [[ ${RHVER} -eq 8 ]] || [[ ${RHVER} -eq 7 ]]; then
        sudo sed -i 's/mirrorlist/#mirrorlist/g' /etc/yum.repos.d/CentOS-*
        sudo sed -i 's|#baseurl=http://mirror.centos.org|baseurl=http://vault.centos.org|g' /etc/yum.repos.d/CentOS-*
    fi

    until sudo yum makecache; do
        sleep 1
        echo try again
    done
    sudo yum -y remove java-1.8.0-openjdk || :
    sudo yum -y remove java-1.8.0-openjdk-headless || :
    sudo amazon-linux-extras install epel -y || :
    sudo amazon-linux-extras install java-openjdk11 -y || :
    sudo yum -y install java-11-openjdk tzdata-java || :
    sudo yum -y install aws-cli || :
    sudo yum -y install git || :
    sudo install -o $(id -u -n) -g $(id -g -n) -d /mnt/jenkins
'''
initMap['min-amazon-2-x64'] = initMap['micro-amazon']
initMap['min-al2023-x64'] = '''
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

    sudo yum -y install java-11-amazon-corretto-headless || :
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
    sudo sed -i.bak -e 's^ExecStart=.*^ExecStart=/usr/bin/dockerd --data-root=/mnt/docker --default-ulimit nofile=900000:900000^' /lib/systemd/system/docker.service
    sudo systemctl daemon-reload
    sudo install -o root -g root -d /mnt/docker
    sudo usermod -aG docker $(id -u -n)
    sudo mkdir -p /etc/docker
    echo '{"experimental": true, "ipv6": true, "fixed-cidr-v6": "fd3c:a8b0:18eb:5c06::/64"}' | sudo tee /etc/docker/daemon.json
    sudo systemctl status docker || sudo systemctl start docker
    sudo service docker status || sudo service docker start
    #echo "* * * * * root /usr/sbin/route add default gw 10.177.1.1 eth0" | sudo tee /etc/cron.d/fix-default-route
'''
initMap['min-al2023-aarch64'] = initMap['min-al2023-x64']
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

    sudo yum -y install java-1.8.0-openjdk aws-cli || :
    sudo yum -y install git || :
    sudo yum -y remove java-1.7.0-openjdk || :
    sudo install -o $(id -u -n) -g $(id -g -n) -d /mnt/jenkins
'''
initMap['min-centos-7-x64']  = initMap['micro-amazon']
initMap['fips-centos-7-x64'] = initMap['micro-amazon']
initMap['min-centos-8-x64']  = initMap['micro-amazon']
initMap['min-ol-8-x64']      = initMap['micro-amazon']
initMap['min-ol-9-x64']      = initMap['micro-amazon']
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
    until sudo DEBIAN_FRONTEND=noninteractive apt-get update; do
        sleep 1
        echo try again
    done
    sudo DEBIAN_FRONTEND=noninteractive apt-get -y install openjdk-11-jre-headless git
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

    sudo sed -i '/buster-backports/ s/cdn-aws.deb.debian.org/archive.debian.org/' /etc/apt/sources.list

    until sudo DEBIAN_FRONTEND=noninteractive apt-get update; do
        sleep 1
        echo try again
    done
    until sudo DEBIAN_FRONTEND=noninteractive apt-get install -y lsb-release; do
        sleep 1
        echo try again
    done
    DEB_VER=$(lsb_release -sc)
    if [[ ${DEB_VER} == "bookworm" ]]; then
        JAVA_VER="openjdk-17-jre-headless"
    else
        JAVA_VER="openjdk-11-jre-headless"
    fi
    if [[ ${DEB_VER} == "bookworm" ]] || [[ ${DEB_VER} == "buster" ]]; then
        sudo DEBIAN_FRONTEND=noninteractive sudo apt-get -y install ${JAVA_VER} git
        sudo mv /etc/ssl /etc/ssl_old
        sudo DEBIAN_FRONTEND=noninteractive sudo apt-get -y install ${JAVA_VER}
        sudo cp -r /etc/ssl_old /etc/ssl
        sudo DEBIAN_FRONTEND=noninteractive sudo apt-get -y install ${JAVA_VER}
    else
        sudo DEBIAN_FRONTEND=noninteractive sudo apt-get -y install ${JAVA_VER} git
    fi
    sudo install -o $(id -u -n) -g $(id -g -n) -d /mnt/jenkins
'''
initMap['min-jammy-x64'] = initMap['min-bionic-x64']
initMap['min-noble-x64'] = initMap['min-bionic-x64']
initMap['min-focal-x64'] = initMap['min-bionic-x64']
initMap['min-stretch-x64'] = initMap['min-bionic-x64']
initMap['min-xenial-x64'] = initMap['min-bionic-x64']
initMap['min-bullseye-x64'] = initMap['min-buster-x64']
initMap['min-bookworm-x64'] = initMap['min-buster-x64']
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

initMap['docker-32gb-aarch64'] = initMap['docker']

capMap = [:]
capMap['c5d.4xlarge'] = '80'
capMap['r5a.4xlarge'] = '60'
capMap['m5n.2xlarge'] = '60'
capMap['i4g.2xlarge'] = '40'

typeMap = [:]
typeMap['micro-amazon']      = 't2.medium'
typeMap['docker']            = 'm5n.2xlarge'
typeMap['docker-32gb']       = 'c5d.4xlarge'
typeMap['docker-32gb-hirsute']  = 'r5a.4xlarge'
typeMap['docker-32gb-jammy']    = 'r5a.4xlarge'
typeMap['docker-32gb-noble']    = 'r5a.4xlarge'
typeMap['docker-32gb-focal']    = 'r5a.4xlarge'
typeMap['docker-32gb-bullseye'] = 'r5a.4xlarge'
typeMap['docker2']           = 'r5a.4xlarge'
typeMap['min-centos-7-x64']  = typeMap['docker']
typeMap['min-centos-8-x64']  = typeMap['docker']
typeMap['min-ol-8-x64']      = typeMap['docker']
typeMap['min-ol-9-x64']      = typeMap['docker']
typeMap['fips-centos-7-x64'] = typeMap['docker-32gb']
typeMap['min-jammy-x64']     = 'r5a.4xlarge'
typeMap['min-noble-x64']     = 'r5a.4xlarge'
typeMap['min-focal-x64']     = typeMap['docker']
typeMap['min-bionic-x64']    = typeMap['min-centos-7-x64']
typeMap['min-buster-x64']    = typeMap['min-centos-7-x64']
typeMap['min-centos-6-x64']  = 'm5n.2xlarge'
typeMap['min-stretch-x64']   = typeMap['docker']
typeMap['min-xenial-x64']    = typeMap['docker']
typeMap['min-amazon-2-x64']  = typeMap['docker']
typeMap['min-al2023-x64']    = 'm5n.2xlarge'
typeMap['min-bullseye-x64']  = typeMap['docker']
typeMap['min-bookworm-x64']  = typeMap['docker']
typeMap['min-rhel-10-x64']   = typeMap['micro-amazon']

typeMap['docker-32gb-aarch64'] = 'i4g.2xlarge'
typeMap['min-al2023-aarch64']  = 'i4g.2xlarge'

execMap = [:]
execMap['docker']            = '1'
execMap['docker-32gb']       = execMap['docker']
execMap['docker-32gb-hirsute']  = execMap['docker']
execMap['docker-32gb-jammy']    = execMap['docker']
execMap['docker-32gb-noble']    = execMap['docker']
execMap['docker-32gb-focal']    = execMap['docker']
execMap['docker-32gb-bullseye'] = execMap['docker']
execMap['docker2']           = execMap['docker']
execMap['micro-amazon']      = '30'
execMap['min-amazon-2-x64']  = '1'
execMap['min-al2023-x64']    = '1'
execMap['min-bionic-x64']    = '1'
execMap['min-focal-x64']     = '1'
execMap['min-jammy-x64']     = '1'
execMap['min-noble-x64']     = '1'
execMap['min-centos-6-x64']  = '1'
execMap['min-centos-7-x64']  = '1'
execMap['fips-centos-7-x64'] = '1'
execMap['min-centos-8-x64']  = '1'
execMap['min-ol-8-x64']      = '1'
execMap['min-ol-9-x64']      = '1'
execMap['min-stretch-x64']   = '1'
execMap['min-xenial-x64']    = '1'
execMap['min-buster-x64']    = '1'
execMap['min-bullseye-x64']  = '1'
execMap['min-bookworm-x64']  = '1'
execMap['min-rhel-10-x64']   = '1'

execMap['docker-32gb-aarch64'] = '1'
execMap['min-al2023-aarch64'] = '1'

devMap = [:]
devMap['docker']            = '/dev/xvda=:8:true:gp2,/dev/xvdd=:120:true:gp2'
devMap['docker2']           = '/dev/xvda=:8:true:gp2,/dev/xvdd=:80:true:gp2'
devMap['docker-32gb']       = devMap['docker']
devMap['docker-32gb-hirsute']  = devMap['docker']
devMap['docker-32gb-jammy']    = devMap['docker']
devMap['docker-32gb-noble']    = devMap['docker']
devMap['docker-32gb-focal']    = devMap['docker']
devMap['docker-32gb-bullseye'] = devMap['docker']
devMap['micro-amazon']      = '/dev/xvda=:30:true:gp2,/dev/xvdd=:80:true:gp2'
devMap['min-amazon-2-x64']  = '/dev/xvda=:30:true:gp2,/dev/xvdd=:120:true:gp2'
devMap['min-al2023-x64']    = '/dev/xvda=:30:true:gp2,/dev/xvdd=:120:true:gp2'
devMap['min-bionic-x64']    = '/dev/sda1=:30:true:gp2,/dev/sdd=:120:true:gp2'
devMap['min-focal-x64']     = devMap['min-bionic-x64']
devMap['min-jammy-x64']     = devMap['min-bionic-x64']
devMap['min-noble-x64']     = devMap['min-bionic-x64']
devMap['min-centos-6-x64']  = devMap['min-bionic-x64']
devMap['min-centos-7-x64']  = devMap['min-bionic-x64']
devMap['fips-centos-7-x64'] = devMap['min-bionic-x64']
devMap['min-centos-8-x64']  = '/dev/sda1=:30:true:gp2,/dev/sdd=:80:true:gp2'
devMap['min-ol-8-x64']      = '/dev/sda1=:30:true:gp2,/dev/sdd=:80:true:gp2'
devMap['min-ol-9-x64']      = '/dev/sda1=:30:true:gp2,/dev/sdd=:80:true:gp2'
devMap['min-stretch-x64']   = 'xvda=:30:true:gp2,xvdd=:120:true:gp2'
devMap['min-xenial-x64']    = devMap['min-bionic-x64']
devMap['min-buster-x64']    = '/dev/xvda=:30:true:gp2,/dev/xvdd=:120:true:gp2'
devMap['min-bullseye-x64']  = '/dev/xvda=:30:true:gp2,/dev/xvdd=:120:true:gp2'
devMap['min-bookworm-x64']  = '/dev/xvda=:30:true:gp2,/dev/xvdd=:120:true:gp2'
devMap['min-rhel-10-x64']   = '/dev/sda1=:30:true:gp3,/dev/sdd=:80:true:gp3'

devMap['docker-32gb-aarch64'] = '/dev/xvda=:8:true:gp2,/dev/xvdd=:120:true:gp2'
devMap['min-al2023-aarch64']  = '/dev/xvda=:30:true:gp2,/dev/xvdd=:120:true:gp2'

labelMap = [:]
labelMap['docker']            = ''
labelMap['docker-32gb']       = ''
labelMap['docker-32gb-hirsute']  = ''
labelMap['docker-32gb-jammy']    = ''
labelMap['docker-32gb-noble']    = ''
labelMap['docker-32gb-focal']    = ''
labelMap['docker-32gb-bullseye'] = ''
labelMap['docker2']           = ''
labelMap['micro-amazon']      = 'master'
labelMap['min-amazon-2-x64']  = ''
labelMap['min-al2023-x64']    = ''
labelMap['min-bionic-x64']    = 'asan'
labelMap['min-focal-x64']     = ''
labelMap['min-jammy-x64']     = ''
labelMap['min-noble-x64']     = ''
labelMap['min-centos-6-x64']  = ''
labelMap['min-centos-7-x64']  = ''
labelMap['fips-centos-7-x64'] = ''
labelMap['min-centos-8-x64']  = ''
labelMap['min-ol-8-x64']      = ''
labelMap['min-ol-9-x64']      = ''
labelMap['min-stretch-x64']   = ''
labelMap['min-xenial-x64']    = ''
labelMap['min-buster-x64']    = ''
labelMap['min-bullseye-x64']  = ''
labelMap['min-bookworm-x64']  = ''
labelMap['min-rhel-10-x64']   = ''

labelMap['docker-32gb-aarch64'] = ''
labelMap['min-al2023-aarch64']  = ''

jvmoptsMap = [:]
jvmoptsMap['docker']            = '-Xmx512m -Xms512m'
jvmoptsMap['docker-32gb']       = jvmoptsMap['docker']
jvmoptsMap['docker-32gb-hirsute']  = jvmoptsMap['docker']
jvmoptsMap['docker-32gb-jammy']    = jvmoptsMap['docker']
jvmoptsMap['docker-32gb-noble']    = jvmoptsMap['docker']
jvmoptsMap['docker-32gb-focal']    = jvmoptsMap['docker']
jvmoptsMap['docker-32gb-bullseye'] = jvmoptsMap['docker']
jvmoptsMap['docker2']           = jvmoptsMap['docker']
jvmoptsMap['micro-amazon']      = jvmoptsMap['docker']
jvmoptsMap['min-amazon-2-x64']  = jvmoptsMap['docker']
jvmoptsMap['min-al2023-x64']    = '-Xmx512m -Xms512m'
jvmoptsMap['min-bionic-x64']    = jvmoptsMap['docker']
jvmoptsMap['min-focal-x64']     = jvmoptsMap['docker']
jvmoptsMap['min-jammy-x64']     = jvmoptsMap['docker']
jvmoptsMap['min-noble-x64']     = jvmoptsMap['docker']
jvmoptsMap['min-centos-6-x64']  = jvmoptsMap['docker']
jvmoptsMap['min-centos-7-x64']  = jvmoptsMap['docker']
jvmoptsMap['fips-centos-7-x64'] = jvmoptsMap['docker']
jvmoptsMap['min-centos-8-x64']  = jvmoptsMap['docker']
jvmoptsMap['min-ol-8-x64']      = jvmoptsMap['docker']
jvmoptsMap['min-ol-9-x64']      = jvmoptsMap['docker']
jvmoptsMap['min-stretch-x64']   = jvmoptsMap['docker']
jvmoptsMap['min-xenial-x64']    = jvmoptsMap['docker']
jvmoptsMap['min-buster-x64']    = jvmoptsMap['docker']
jvmoptsMap['min-bullseye-x64']  = jvmoptsMap['docker']
jvmoptsMap['min-bookworm-x64']  = '-Xmx512m -Xms512m --add-opens=java.base/java.lang=ALL-UNNAMED --add-opens=java.base/java.lang.reflect=ALL-UNNAMED'
jvmoptsMap['min-rhel-10-x64']    = jvmoptsMap['docker']

jvmoptsMap['docker-32gb-aarch64'] = jvmoptsMap['docker']
jvmoptsMap['min-al2023-aarch64']  = '-Xmx512m -Xms512m'

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
            new EC2Tag('Name', 'jenkins-ps80-' + OSType),
            new EC2Tag('iit-billing-tag', 'jenkins-ps80-worker')
        ],                                          // List<EC2Tag> tags
        '3',                                        // String idleTerminationMinutes
        0,                                          // Init minimumNumberOfInstances
        0,                                          // minimumNumberOfSpareInstances
        capMap[typeMap[OSType]],                    // String instanceCapStr
        'arn:aws:iam::119175775298:instance-profile/jenkins-ps80-worker', // String iamInstanceProfile
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

String sshKeysCredentialsId = '8af5d00a-aeaf-45bd-a5b1-4a7680c9d500'

String region = 'us-west-2'
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
            getTemplate('docker',               "${region}${it}"),
            getTemplate('docker-32gb',          "${region}${it}"),
            getTemplate('docker-32gb-hirsute',  "${region}${it}"),
            getTemplate('docker-32gb-jammy',    "${region}${it}"),
            getTemplate('docker-32gb-noble',    "${region}${it}"),
            getTemplate('docker-32gb-focal',    "${region}${it}"),
            getTemplate('docker-32gb-bullseye', "${region}${it}"),
            getTemplate('micro-amazon',         "${region}${it}"),
            getTemplate('min-amazon-2-x64',     "${region}${it}"),
            getTemplate('min-al2023-x64',       "${region}${it}"),
            getTemplate('min-centos-8-x64',     "${region}${it}"),
            getTemplate('min-ol-8-x64',         "${region}${it}"),
            getTemplate('min-ol-9-x64',         "${region}${it}"),
            getTemplate('min-centos-7-x64',     "${region}${it}"),
            getTemplate('fips-centos-7-x64',    "${region}${it}"),
            getTemplate('min-centos-6-x64',     "${region}${it}"),
            getTemplate('min-bionic-x64',       "${region}${it}"),
            getTemplate('min-focal-x64',        "${region}${it}"),
            getTemplate('min-jammy-x64',        "${region}${it}"),
            getTemplate('min-noble-x64',        "${region}${it}"),
            getTemplate('min-buster-x64',       "${region}${it}"),
            getTemplate('min-stretch-x64',      "${region}${it}"),
            getTemplate('min-xenial-x64',       "${region}${it}"),
            getTemplate('min-bullseye-x64',     "${region}${it}"),
            getTemplate('min-bookworm-x64',     "${region}${it}"),
            getTemplate('min-rhel-10-x64',      "${region}${it}"),
            getTemplate('docker-32gb-aarch64',  "${region}${it}"),
            getTemplate('min-al2023-aarch64',   "${region}${it}"),
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
