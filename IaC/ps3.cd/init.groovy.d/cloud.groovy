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

System.setProperty("hudson.model.DirectoryBrowserSupport.CSP", "sandbox allow-scripts; script-src 'unsafe-inline' https://www.gstatic.com;")

def logger = Logger.getLogger("")
logger.info("Cloud init started")

// get Jenkins instance
Jenkins jenkins = Jenkins.getInstance()

netMap = [:]
netMap['eu-west-1b'] = 'subnet-06b7b6c7fd86a48e8'
netMap['eu-west-1c'] = 'subnet-0de17643aea1f04a4'

// ===== Common block of global config starts
def home_dir = System.properties['JENKINS_HOME']
assert home_dir != ""

File initGroovyDir = new File("$home_dir/init.groovy.d")
if (!initGroovyDir.exists()) {
    initGroovyDir.mkdirs()
}
File amiProperties = new File(initGroovyDir, "ami-defs.properties")
try {
    def propertiesChecksum = new URL("https://raw.githubusercontent.com/vorsel/jenkins-pipelines/PKG-1092_ps3.cd_update_workers/IaC/init.groovy.d/ami-defs.properties.sha256").text.trim()
    boolean writeProperties = true
    if (amiProperties.exists()) {
        // String.digest() is not available in Groovy 2.4 :-(
        //if (amiProperties.text.digest('SHA-256') == propertiesChecksum) {
        if (org.apache.commons.codec.digest.DigestUtils.sha256Hex(amiProperties.text) == propertiesChecksum) {
            writeProperties = false
        }
    }
    if (writeProperties) {
        def propertiesText = new URL("https://raw.githubusercontent.com/vorsel/jenkins-pipelines/PKG-1092_ps3.cd_update_workers/IaC/init.groovy.d/ami-defs.properties").text
        // We should continue with existing properties file, so we can't just assert:
        // assert propertiesText.digest('SHA-256') == propertiesChecksum
        //if (propertiesText.digest('SHA-256') == propertiesChecksum) {
        if (org.apache.commons.codec.digest.DigestUtils.sha256Hex(propertiesText) == propertiesChecksum) {
            if (amiProperties.exists()) {
                // Looks like we do not need tmpFile here, but let's use it to avoid misunderstanings
                File tmpFile = new File(amiProperties.toURI())
                tmpFile.renameTo(new File(initGroovyDir, "ami-defs." + new Date().getTime().toString() + ".properties").absolutePath)
                //println(tmpFile.toURI().toString())
            }
            amiProperties.write(propertiesText)
        }
    }
} catch (Exception ex) {
    println(ex.toString())
}

assert amiProperties.exists()
assert amiProperties.text != ""

def properties = new ConfigSlurper().parse(amiProperties.toURI().toURL())
//println(properties.toString())
// ===== Common block of global config ends

imageMap = [:]
imageMap['eu-west-1a.docker']               = properties.AwsAmi['AmazonLinux2_x86_64']['euWest1']
imageMap['eu-west-1a.docker-32gb']          = properties.AwsAmi['AmazonLinux2_x86_64']['euWest1']
imageMap['eu-west-1a.docker2']              = properties.AwsAmi['AmazonLinux2_x86_64']['euWest1']
imageMap['eu-west-1a.micro-amazon']         = properties.AwsAmi['AmazonLinux2_x86_64']['euWest1']
imageMap['eu-west-1a.min-al2023-x64']       = properties.AwsAmi['AmazonLinux2023_x86_64']['euWest1']
imageMap['eu-west-1a.fips-centos-7-x64']    = properties.AwsAmi['FipsCentos7_x86_64']['euWest1']

imageMap['eu-west-1a.min-centos-7-x64']     = properties.AwsAmi['Centos7_x86_64']['euWest1']
imageMap['eu-west-1a.min-ol-8-x64']         = properties.AwsAmi['OracleLinux8_x86_64']['euWest1']
imageMap['eu-west-1a.min-ol-9-x64']         = properties.AwsAmi['OracleLinux9_x86_64']['euWest1']
imageMap['eu-west-1a.min-rhel-10-x64']      = properties.AwsAmi['RHEL10_x86_64']['euWest1']
imageMap['eu-west-1a.min-bookworm-x64']     = properties.AwsAmi['Debian12_x86_64']['euWest1']
imageMap['eu-west-1a.min-bullseye-x64']     = properties.AwsAmi['Debian11_x86_64']['euWest1']
imageMap['eu-west-1a.min-buster-x64']       = properties.AwsAmi['Debian10_x86_64']['euWest1']
imageMap['eu-west-1a.min-bionic-x64']       = properties.AwsAmi['Ubuntu1804_x86_64']['euWest1']
imageMap['eu-west-1a.min-stretch-x64']      = properties.AwsAmi['Debian9_x86_64']['euWest1']
imageMap['eu-west-1a.min-xenial-x64']       = properties.AwsAmi['Ubuntu1604_x86_64']['euWest1']
imageMap['eu-west-1a.min-trixie-x64']       = properties.AwsAmi['Debian13_x86_64']['euWest1']
imageMap['eu-west-1a.docker-32gb-hirsute']  = properties.AwsAmi['Ubuntu2104_x86_64']['euWest1']
imageMap['eu-west-1a.docker-32gb-focal']    = properties.AwsAmi['Ubuntu2004_x86_64']['euWest1']
imageMap['eu-west-1a.docker-32gb-jammy']    = properties.AwsAmi['Ubuntu2204_x86_64']['euWest1']
imageMap['eu-west-1a.docker-32gb-noble']    = properties.AwsAmi['Ubuntu2404_x86_64']['euWest1']
imageMap['eu-west-1a.docker-32gb-bullseye'] = imageMap['eu-west-1a.min-bullseye-x64']

imageMap['eu-west-1a.docker-32gb-aarch64']  = properties.AwsAmi['AmazonLinux2_aarch64']['euWest1']
imageMap['eu-west-1a.min-centos-7-aarch64'] = properties.AwsAmi['Centos7_aarch64']['euWest1']
imageMap['eu-west-1a.min-bookworm-aarch64'] = properties.AwsAmi['Debian12_aarch64']['euWest1']
imageMap['eu-west-1a.min-bullseye-aarch64'] = properties.AwsAmi['Debian11_aarch64']['euWest1']
imageMap['eu-west-1a.min-jammy-aarch64']    = properties.AwsAmi['Ubuntu2204_aarch64']['euWest1']
imageMap['eu-west-1a.min-noble-aarch64']    = properties.AwsAmi['Ubuntu2404_aarch64']['euWest1']
imageMap['eu-west-1a.min-trixie-aarch64']   = properties.AwsAmi['Debian13_aarch64']['euWest1']
imageMap['eu-west-1a.min-al2023-aarch64']   = properties.AwsAmi['AmazonLinux2023_aarch64']['euWest1']

imageMap['eu-west-1b.docker']               = imageMap['eu-west-1a.docker']
imageMap['eu-west-1b.docker-32gb']          = imageMap['eu-west-1a.docker-32gb']
imageMap['eu-west-1b.docker2']              = imageMap['eu-west-1a.docker2']
imageMap['eu-west-1b.micro-amazon']         = imageMap['eu-west-1a.micro-amazon']
imageMap['eu-west-1b.min-centos-7-x64']     = imageMap['eu-west-1a.min-centos-7-x64']
imageMap['eu-west-1b.fips-centos-7-x64']    = imageMap['eu-west-1a.fips-centos-7-x64']
imageMap['eu-west-1b.min-ol-8-x64']         = imageMap['eu-west-1a.min-ol-8-x64']
imageMap['eu-west-1b.min-ol-9-x64']         = imageMap['eu-west-1a.min-ol-9-x64']
imageMap['eu-west-1b.min-rhel-10-x64']      = imageMap['eu-west-1a.min-rhel-10-x64']
imageMap['eu-west-1b.min-al2023-x64']       = imageMap['eu-west-1a.min-al2023-x64']

imageMap['eu-west-1b.min-bookworm-x64']     = imageMap['eu-west-1a.min-bookworm-x64']
imageMap['eu-west-1b.min-bullseye-x64']     = imageMap['eu-west-1a.min-bullseye-x64']
imageMap['eu-west-1b.min-buster-x64']       = imageMap['eu-west-1a.min-buster-x64']
imageMap['eu-west-1b.min-bionic-x64']       = imageMap['eu-west-1a.min-bionic-x64']
imageMap['eu-west-1b.min-stretch-x64']      = imageMap['eu-west-1a.min-stretch-x64']
imageMap['eu-west-1b.min-xenial-x64']       = imageMap['eu-west-1a.min-xenial-x64']
imageMap['eu-west-1b.min-trixie-x64']       = imageMap['eu-west-1a.min-trixie-x64']
imageMap['eu-west-1b.docker-32gb-hirsute']  = imageMap['eu-west-1a.docker-32gb-hirsute']
imageMap['eu-west-1b.docker-32gb-focal']    = imageMap['eu-west-1a.docker-32gb-focal']
imageMap['eu-west-1b.docker-32gb-jammy']    = imageMap['eu-west-1a.docker-32gb-jammy']
imageMap['eu-west-1b.docker-32gb-noble']    = imageMap['eu-west-1a.docker-32gb-noble']
imageMap['eu-west-1b.docker-32gb-bullseye'] = imageMap['eu-west-1a.docker-32gb-bullseye']

imageMap['eu-west-1b.docker-32gb-aarch64']    = imageMap['eu-west-1a.docker-32gb-aarch64']
imageMap['eu-west-1b.min-centos-7-aarch64']   = imageMap['eu-west-1a.min-centos-7-aarch64']
imageMap['eu-west-1b.min-bookworm-aarch64']   = imageMap['eu-west-1a.min-bookworm-aarch64']
imageMap['eu-west-1b.min-bullseye-aarch64']   = imageMap['eu-west-1a.min-bullseye-aarch64']
imageMap['eu-west-1b.min-jammy-aarch64']      = imageMap['eu-west-1a.min-jammy-aarch64']
imageMap['eu-west-1b.min-noble-aarch64']      = imageMap['eu-west-1a.min-noble-aarch64']
imageMap['eu-west-1b.min-trixie-aarch64']     = imageMap['eu-west-1a.min-trixie-aarch64']
imageMap['eu-west-1b.min-al2023-aarch64']     = imageMap['eu-west-1a.min-al2023-aarch64']

imageMap['eu-west-1c.docker']               = imageMap['eu-west-1a.docker']
imageMap['eu-west-1c.docker-32gb']          = imageMap['eu-west-1a.docker-32gb']
imageMap['eu-west-1c.docker2']              = imageMap['eu-west-1a.docker2']
imageMap['eu-west-1c.micro-amazon']         = imageMap['eu-west-1a.micro-amazon']
imageMap['eu-west-1c.min-centos-7-x64']     = imageMap['eu-west-1a.min-centos-7-x64']
imageMap['eu-west-1c.fips-centos-7-x64']    = imageMap['eu-west-1a.fips-centos-7-x64']
imageMap['eu-west-1c.min-ol-8-x64']         = imageMap['eu-west-1a.min-ol-8-x64']
imageMap['eu-west-1c.min-ol-9-x64']         = imageMap['eu-west-1a.min-ol-9-x64']
imageMap['eu-west-1c.min-rhel-10-x64']      = imageMap['eu-west-1a.min-rhel-10-x64']
imageMap['eu-west-1c.min-al2023-x64']       = imageMap['eu-west-1a.min-al2023-x64']

imageMap['eu-west-1c.min-bookworm-x64']     = imageMap['eu-west-1a.min-bookworm-x64']
imageMap['eu-west-1c.min-bullseye-x64']     = imageMap['eu-west-1a.min-bullseye-x64']
imageMap['eu-west-1c.min-buster-x64']       = imageMap['eu-west-1a.min-buster-x64']
imageMap['eu-west-1c.min-bionic-x64']       = imageMap['eu-west-1a.min-bionic-x64']
imageMap['eu-west-1c.min-stretch-x64']      = imageMap['eu-west-1a.min-stretch-x64']
imageMap['eu-west-1c.min-xenial-x64']       = imageMap['eu-west-1a.min-xenial-x64']
imageMap['eu-west-1c.min-trixie-x64']       = imageMap['eu-west-1a.min-trixie-x64']
imageMap['eu-west-1c.docker-32gb-hirsute']  = imageMap['eu-west-1a.docker-32gb-hirsute']
imageMap['eu-west-1c.docker-32gb-focal']    = imageMap['eu-west-1a.docker-32gb-focal']
imageMap['eu-west-1c.docker-32gb-jammy']    = imageMap['eu-west-1a.docker-32gb-jammy']
imageMap['eu-west-1c.docker-32gb-noble']    = imageMap['eu-west-1a.docker-32gb-noble']
imageMap['eu-west-1c.docker-32gb-bullseye'] = imageMap['eu-west-1a.docker-32gb-bullseye']

imageMap['eu-west-1c.docker-32gb-aarch64']    = imageMap['eu-west-1a.docker-32gb-aarch64']
imageMap['eu-west-1c.min-centos-7-aarch64']   = imageMap['eu-west-1a.min-centos-7-aarch64']
imageMap['eu-west-1c.min-bookworm-aarch64']   = imageMap['eu-west-1a.min-bookworm-aarch64']
imageMap['eu-west-1c.min-bullseye-aarch64']   = imageMap['eu-west-1a.min-bullseye-aarch64']
imageMap['eu-west-1c.min-jammy-aarch64']      = imageMap['eu-west-1a.min-jammy-aarch64']
imageMap['eu-west-1c.min-noble-aarch64']      = imageMap['eu-west-1a.min-noble-aarch64']
imageMap['eu-west-1c.min-trixie-aarch64']     = imageMap['eu-west-1a.min-trixie-aarch64']
imageMap['eu-west-1c.min-al2023-aarch64']     = imageMap['eu-west-1a.min-al2023-aarch64']


priceMap = [:]
priceMap['c5n.2xlarge'] = '0.36' // type=c5n.2xlarge, vCPU=8, memory=21GiB, saving=60%, interruption='<5%', price=0.283100
priceMap['t3.2xlarge'] = '0.31'  // type=t3.2xlarge, vCPU=8, memory=32GiB, saving=62%, interruption='<5%', price=0.238600
priceMap['i4i.2xlarge'] = '0.36' // type=i4i.2xlarge, vCPU=8, memory=64GiB, saving=69%, interruption='<5%', price=0.285600
priceMap['m5.2xlarge'] = '0.29'  // type=m5.2xlarge, vCPU=8, memory=32GiB, saving=65%, interruption='<5%', price=0.220600
priceMap['r6g.2xlarge'] = '0.33' // type=r6g.2xlarge, vCPU=8, memory=64GiB, saving=61%, interruption='<5%', price=0.254700

userMap = [:]
userMap['docker']               = properties.AwsAmi['AmazonLinux2_x86_64']['user']
userMap['docker-32gb']          = properties.AwsAmi['AmazonLinux2_x86_64']['user']
userMap['docker2']              = properties.AwsAmi['AmazonLinux2_x86_64']['user']
userMap['micro-amazon']         = properties.AwsAmi['AmazonLinux2_x86_64']['user']
userMap['min-al2023-x64']       = properties.AwsAmi['AmazonLinux2023_x86_64']['user']
userMap['min-bionic-x64']       = properties.AwsAmi['Ubuntu1804_x86_64']['user']
userMap['min-xenial-x64']       = properties.AwsAmi['Ubuntu1604_x86_64']['user']
userMap['min-centos-7-x64']     = properties.AwsAmi['Centos7_x86_64']['user']
userMap['fips-centos-7-x64']    = properties.AwsAmi['FipsCentos7_x86_64']['user']
userMap['min-ol-8-x64']         = properties.AwsAmi['OracleLinux8_x86_64']['user']
userMap['min-ol-9-x64']         = properties.AwsAmi['OracleLinux9_x86_64']['user']
userMap['min-rhel-10-x64']      = properties.AwsAmi['RHEL10_x86_64']['user']
userMap['min-bookworm-x64']     = properties.AwsAmi['Debian12_x86_64']['user']
userMap['min-bullseye-x64']     = properties.AwsAmi['Debian11_x86_64']['user']
userMap['min-stretch-x64']      = properties.AwsAmi['Debian9_x86_64']['user']
userMap['min-buster-x64']       = properties.AwsAmi['Debian10_x86_64']['user']
userMap['min-trixie-x64']       = properties.AwsAmi['Debian13_x86_64']['user']
userMap['docker-32gb-hirsute']  = properties.AwsAmi['Ubuntu2104_x86_64']['user']
userMap['docker-32gb-focal']    = properties.AwsAmi['Ubuntu2004_x86_64']['user']
userMap['docker-32gb-jammy']    = properties.AwsAmi['Ubuntu2204_x86_64']['user']
userMap['docker-32gb-noble']    = properties.AwsAmi['Ubuntu2404_x86_64']['user']
userMap['docker-32gb-bullseye'] = properties.AwsAmi['Debian11_x86_64']['user']

userMap['docker-32gb-aarch64']    = properties.AwsAmi['AmazonLinux2_aarch64']['user']
userMap['min-centos-7-aarch64']   = properties.AwsAmi['Centos7_aarch64']['user']
userMap['min-bookworm-aarch64']   = properties.AwsAmi['Debian12_aarch64']['user']
userMap['min-bullseye-aarch64']   = properties.AwsAmi['Debian11_aarch64']['user']
userMap['min-jammy-aarch64']      = properties.AwsAmi['Ubuntu2204_aarch64']['user']
userMap['min-noble-aarch64']      = properties.AwsAmi['Ubuntu2404_aarch64']['user']
userMap['min-trixie-aarch64']     = properties.AwsAmi['Debian13_aarch64']['user']
userMap['min-al2023-aarch64']     = properties.AwsAmi['AmazonLinux2023_aarch64']['user']


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
    sudo amazon-linux-extras install java-openjdk11 -y
    sudo yum -y install java-17-amazon-corretto-headless
    sudo yum -y install git docker p7zip tzdata-java
    sudo yum -y remove awscli

    if ! $(aws --version | grep -q 'aws-cli/2'); then
        sudo rm -rf /tmp/aws* || true

        until curl "https://awscli.amazonaws.com/awscli-exe-linux-$(uname -m).zip" -o "/tmp/awscliv2.zip"; do
            sleep 1
            echo try again
        done

        7za -aoa -o/tmp x /tmp/awscliv2.zip
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
    echo '{"experimental": true}' | sudo tee /etc/docker/daemon.json
    sudo systemctl status docker || sudo systemctl start docker
    sudo service docker status || sudo service docker start
    echo "* * * * * root /usr/sbin/route add default gw 10.177.1.1 eth0" | sudo tee /etc/cron.d/fix-default-route
'''

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
    export DEBIAN_FRONTEND=noninteractive
    until sudo apt-get update; do
        sleep 1
        echo try again
    done

    until sudo apt-get -y install openjdk-11-jre-headless apt-transport-https ca-certificates curl gnupg lsb-release unzip; do
        sleep 1
        echo try again
    done

    curl -fsSL https://download.docker.com/linux/ubuntu/gpg | sudo gpg --dearmor -o /usr/share/keyrings/docker-archive-keyring.gpg
    echo "deb [arch=amd64 signed-by=/usr/share/keyrings/docker-archive-keyring.gpg] https://download.docker.com/linux/ubuntu $(lsb_release -cs) stable" | sudo tee /etc/apt/sources.list.d/docker.list > /dev/null
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
initMap['docker-32gb-focal'] = initMap['docker-32gb-hirsute']
initMap['docker-32gb-jammy'] = initMap['docker-32gb-hirsute']
initMap['docker-32gb-noble'] = initMap['docker-32gb-hirsute']

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
    export DEBIAN_FRONTEND=noninteractive
    until sudo apt-get update; do
        sleep 1
        echo try again
    done
    until sudo apt-get -y install openjdk-11-jre-headless apt-transport-https ca-certificates curl gnupg lsb-release unzip; do
        sleep 1
        echo try again
    done
    curl -fsSL https://download.docker.com/linux/debian/gpg | sudo gpg --dearmor -o /usr/share/keyrings/docker-archive-keyring.gpg
    echo "deb [arch=amd64 signed-by=/usr/share/keyrings/docker-archive-keyring.gpg] https://download.docker.com/linux/debian $(lsb_release -cs) stable" | sudo tee /etc/apt/sources.list.d/docker.list > /dev/null
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

    # Install Java based on RHEL version and OS type
    if [[ -f /etc/system-release ]] && grep -q "Amazon Linux" /etc/system-release; then
        sudo yum -y install java-17-amazon-corretto-headless || :
    elif [[ ${RHVER} -eq 7 ]]; then
        sudo yum -y install java-17-openjdk-headless || sudo yum -y install java-11-openjdk-headless || :
    else
        sudo yum -y install java-17-openjdk-headless || :
    fi

    sudo yum -y install git tzdata-java || :
    sudo yum -y install aws-cli || :
    sudo install -o $(id -u -n) -g $(id -g -n) -d /mnt/jenkins

'''

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

    sudo yum -y install java-17-amazon-corretto-headless || :
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

initMap['docker-32gb'] = initMap['docker']
initMap['docker2'] = initMap['docker']

initMap['micro-amazon'] = initMap['rpmMap']
initMap['min-centos-7-x64']  = initMap['rpmMap']
initMap['fips-centos-7-x64'] = initMap['rpmMap']
initMap['min-ol-8-x64']      = initMap['rpmMap']
initMap['min-ol-9-x64']      = initMap['rpmMap']
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

initMap['min-bookworm-x64'] = initMap['debMap']
initMap['min-bullseye-x64'] = initMap['debMap']
initMap['min-buster-x64']  = initMap['debMap']
initMap['min-bionic-x64']  = initMap['debMap']
initMap['min-stretch-x64'] = initMap['debMap']
initMap['min-xenial-x64']  = initMap['debMap']
initMap['min-trixie-x64']  = initMap['debMap']

initMap['docker-32gb-aarch64']  = initMap['docker']
initMap['min-centos-7-aarch64'] = initMap['rpmMap']
initMap['min-bookworm-aarch64'] = initMap['debMap']
initMap['min-bullseye-aarch64'] = initMap['debMap']
initMap['min-jammy-aarch64']    = initMap['debMap']
initMap['min-noble-aarch64']    = initMap['debMap']
initMap['min-trixie-aarch64']   = initMap['debMap']
initMap['min-al2023-aarch64']   = initMap['docker']

capMap = [:]
capMap['c5n.2xlarge'] = '60'
capMap['t3.2xlarge']  = '60'
capMap['i4i.2xlarge'] = '40'
capMap['m5.2xlarge']  = '10'
capMap['r6g.2xlarge'] = '40'

typeMap = [:]
typeMap['micro-amazon']      = 'c5n.2xlarge'
typeMap['docker']            = 'c5n.2xlarge'
typeMap['docker-32gb']       = 'i4i.2xlarge'
typeMap['docker2']           = 'm5.2xlarge'
typeMap['min-al2023-x64']    = typeMap['docker']
typeMap['min-centos-7-x64']  = typeMap['docker']
typeMap['fips-centos-7-x64'] = typeMap['min-centos-7-x64']
typeMap['min-ol-8-x64']      = typeMap['min-centos-7-x64']
typeMap['min-ol-9-x64']      = 'i4i.2xlarge'
typeMap['min-rhel-10-x64']   = typeMap['docker']
typeMap['min-bionic-x64']    = typeMap['min-centos-7-x64']
typeMap['min-bookworm-x64']  = typeMap['min-centos-7-x64']
typeMap['min-bullseye-x64']  = typeMap['min-centos-7-x64']
typeMap['min-buster-x64']    = typeMap['min-centos-7-x64']
typeMap['min-stretch-x64']   = typeMap['min-centos-7-x64']
typeMap['min-xenial-x64']    = typeMap['min-centos-7-x64']
typeMap['min-trixie-x64']    = typeMap['min-centos-7-x64']
typeMap['docker-32gb-hirsute'] = 'i4i.2xlarge'
typeMap['docker-32gb-focal'] = 'i4i.2xlarge'
typeMap['docker-32gb-jammy'] = 'i4i.2xlarge'
typeMap['docker-32gb-noble'] = 'i4i.2xlarge'
typeMap['docker-32gb-bullseye'] = 'i4i.2xlarge'

typeMap['docker-32gb-aarch64']  = 'r6g.2xlarge'
typeMap['min-centos-7-aarch64'] = typeMap['docker-32gb-aarch64']
typeMap['min-bookworm-aarch64'] = typeMap['docker-32gb-aarch64']
typeMap['min-bullseye-aarch64'] = typeMap['docker-32gb-aarch64']
typeMap['min-jammy-aarch64']    = typeMap['docker-32gb-aarch64']
typeMap['min-noble-aarch64']    = typeMap['docker-32gb-aarch64']
typeMap['min-trixie-aarch64']   = typeMap['docker-32gb-aarch64']
typeMap['min-al2023-aarch64']   = typeMap['docker-32gb-aarch64']

execMap = [:]
execMap['docker']            = '1'
execMap['docker-32gb']       = execMap['docker']
execMap['docker2']           = execMap['docker']
execMap['micro-amazon']      = '30'
execMap['min-al2023-x64']    = '1'
execMap['min-bionic-x64']    = '1'
execMap['min-centos-7-x64']  = '1'
execMap['fips-centos-7-x64'] = '1'
execMap['min-ol-8-x64']      = '1'
execMap['min-ol-9-x64']      = '1'
execMap['min-rhel-10-x64']   = '1'
execMap['min-stretch-x64']   = '1'
execMap['min-xenial-x64']    = '1'
execMap['min-buster-x64']    = '1'
execMap['docker-32gb-hirsute'] = '1'
execMap['docker-32gb-focal'] = '1'
execMap['docker-32gb-jammy'] = '1'
execMap['docker-32gb-noble'] = '1'
execMap['min-bookworm-x64']  = '1'
execMap['min-bullseye-x64']  = '1'
execMap['docker-32gb-bullseye']  = '1'
execMap['min-trixie-x64']        = '1'

execMap['docker-32gb-aarch64']  = '1'
execMap['min-centos-7-aarch64'] = '1'
execMap['min-bookworm-aarch64'] = '1'
execMap['min-bullseye-aarch64'] = '1'
execMap['min-jammy-aarch64']    = '1'
execMap['min-noble-aarch64']    = '1'
execMap['min-trixie-aarch64']   = '1'
execMap['min-al2023-aarch64']   = '1'

devMap = [:]
devMap['docker']            = '/dev/xvda=:8:true:gp2,/dev/xvdd=:80:true:gp2'
devMap['docker2']           = '/dev/xvda=:8:true:gp2,/dev/xvdd=:80:true:gp2'
devMap['docker-32gb']       = devMap['docker']
devMap['micro-amazon']      = devMap['docker']
devMap['min-al2023-x64']    = devMap['docker']
devMap['min-bionic-x64']    = '/dev/sda1=:8:true:gp2,/dev/sdd=:80:true:gp2'
devMap['min-centos-7-x64']  = devMap['min-bionic-x64']
devMap['fips-centos-7-x64'] = devMap['min-bionic-x64']
devMap['min-ol-8-x64']      = devMap['min-bionic-x64']
devMap['min-ol-9-x64']      = '/dev/sda1=:10:true:gp2,/dev/sdd=:80:true:gp2'
devMap['min-rhel-10-x64']   = '/dev/sda1=:30:true:gp3,/dev/sdd=:80:true:gp3'
devMap['min-jessie-x64']    = devMap['micro-amazon']
devMap['min-stretch-x64']   = 'xvda=:8:true:gp2,xvdd=:80:true:gp2'
devMap['min-xenial-x64']    = devMap['min-bionic-x64']
devMap['min-buster-x64']    = '/dev/xvda=:8:true:gp2,/dev/xvdd=:80:true:gp2'
devMap['docker-32gb-hirsute'] = devMap['docker']
devMap['docker-32gb-focal'] = devMap['docker']
devMap['docker-32gb-jammy'] = devMap['docker']
devMap['docker-32gb-noble'] = devMap['docker']
devMap['min-bookworm-x64']  = '/dev/xvda=:8:true:gp2,/dev/xvdd=:80:true:gp2'
devMap['min-bullseye-x64']  = '/dev/xvda=:8:true:gp2,/dev/xvdd=:80:true:gp2'
devMap['min-trixie-x64']    = '/dev/xvda=:8:true:gp2,/dev/xvdd=:80:true:gp2'
devMap['docker-32gb-bullseye']  = '/dev/xvda=:8:true:gp2,/dev/xvdd=:80:true:gp2'

devMap['docker-32gb-aarch64']  = '/dev/xvda=:8:true:gp2,/dev/xvdd=:80:true:gp2'
devMap['min-centos-7-aarch64'] = '/dev/xvda=:8:true:gp2,/dev/xvdd=:80:true:gp2'
devMap['min-bookworm-aarch64'] = '/dev/xvda=:8:true:gp2,/dev/xvdd=:80:true:gp2'
devMap['min-bullseye-aarch64'] = '/dev/xvda=:8:true:gp2,/dev/xvdd=:80:true:gp2'
devMap['min-jammy-aarch64']    = '/dev/xvda=:8:true:gp2,/dev/xvdd=:80:true:gp2'
devMap['min-noble-aarch64']    = '/dev/xvda=:8:true:gp2,/dev/xvdd=:80:true:gp2'
devMap['min-trixie-aarch64']   = '/dev/xvda=:8:true:gp2,/dev/xvdd=:80:true:gp2'
devMap['min-al2023-aarch64']   = '/dev/xvda=:8:true:gp2,/dev/xvdd=:80:true:gp2'

labelMap = [:]
labelMap['docker']            = ''
labelMap['docker-32gb']       = ''
labelMap['docker2']           = 'docker-32gb'
labelMap['micro-amazon']      = 'master'
labelMap['min-al2023-x64']    = ''
labelMap['min-bionic-x64']    = 'asan'
labelMap['min-centos-7-x64']  = ''
labelMap['fips-centos-7-x64'] = ''
labelMap['min-ol-8-x64']      = ''
labelMap['min-ol-9-x64']      = ''
labelMap['min-rhel-10-x64']   = ''
labelMap['min-stretch-x64']   = ''
labelMap['min-xenial-x64']    = ''
labelMap['min-buster-x64']    = ''
labelMap['docker-32gb-hirsute'] = ''
labelMap['docker-32gb-focal'] = ''
labelMap['docker-32gb-jammy'] = ''
labelMap['docker-32gb-noble'] = ''
labelMap['min-bookworm-x64']  = ''
labelMap['min-bullseye-x64']  = ''
labelMap['min-trixie-x64']    = ''
labelMap['docker-32gb-bullseye']  = ''

labelMap['docker-32gb-aarch64']  = ''
labelMap['min-centos-7-aarch64'] = ''
labelMap['min-bookworm-aarch64'] = ''
labelMap['min-bullseye-aarch64'] = ''
labelMap['min-jammy-aarch64']    = ''
labelMap['min-noble-aarch64']    = ''
labelMap['min-trixie-aarch64']   = ''
labelMap['min-al2023-aarch64']   = ''

jvmoptsMap = [:]
jvmoptsMap['docker']            = '-Xmx512m -Xms512m'
jvmoptsMap['docker-32gb']       = jvmoptsMap['docker']
jvmoptsMap['docker2']           = jvmoptsMap['docker']
jvmoptsMap['micro-amazon']      = jvmoptsMap['docker']
jvmoptsMap['min-al2023-x64']    = jvmoptsMap['docker']
jvmoptsMap['min-bionic-x64']    = jvmoptsMap['docker']
jvmoptsMap['min-centos-7-x64']  = jvmoptsMap['docker']
jvmoptsMap['fips-centos-7-x64'] = jvmoptsMap['docker']
jvmoptsMap['min-ol-8-x64']      = jvmoptsMap['docker']
jvmoptsMap['min-ol-9-x64']      = jvmoptsMap['docker']
jvmoptsMap['min-rhel-10-x64']   = jvmoptsMap['docker']
jvmoptsMap['min-stretch-x64']   = jvmoptsMap['docker']
jvmoptsMap['min-xenial-x64']    = jvmoptsMap['docker']
jvmoptsMap['min-buster-x64']    = jvmoptsMap['docker']
jvmoptsMap['docker-32gb-hirsute'] = jvmoptsMap['docker']
jvmoptsMap['docker-32gb-focal'] = jvmoptsMap['docker']
jvmoptsMap['docker-32gb-jammy'] = jvmoptsMap['docker']
jvmoptsMap['docker-32gb-noble'] = jvmoptsMap['docker']
jvmoptsMap['min-bookworm-x64']  = '-Xmx512m -Xms512m --add-opens=java.base/java.lang=ALL-UNNAMED --add-opens=java.base/java.lang.reflect=ALL-UNNAMED'
jvmoptsMap['min-bullseye-x64']  = jvmoptsMap['docker']
jvmoptsMap['min-trixie-x64']    = '-Xmx512m -Xms512m --add-opens=java.base/java.lang=ALL-UNNAMED --add-opens=java.base/java.lang.reflect=ALL-UNNAMED'
jvmoptsMap['docker-32gb-bullseye']  = jvmoptsMap['docker']

jvmoptsMap['docker-32gb-aarch64']  = jvmoptsMap['docker']
jvmoptsMap['min-centos-7-aarch64'] = jvmoptsMap['docker']
jvmoptsMap['min-bookworm-aarch64'] = jvmoptsMap['docker']
jvmoptsMap['min-bullseye-aarch64'] = jvmoptsMap['docker']
jvmoptsMap['min-jammy-aarch64']    = jvmoptsMap['docker']
jvmoptsMap['min-noble-aarch64']    = jvmoptsMap['docker']
jvmoptsMap['min-trixie-aarch64']   = jvmoptsMap['docker']
jvmoptsMap['min-al2023-aarch64']   = jvmoptsMap['docker']

// https://github.com/jenkinsci/ec2-plugin/blob/ec2-1.41/src/main/java/hudson/plugins/ec2/SlaveTemplate.java
SlaveTemplate getTemplate(String OSType, String AZ) {
    return new SlaveTemplate(
        imageMap[AZ + '.' + OSType].toString(),     // String ami
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
        userMap[OSType].toString(),                 // String remoteAdmin
        new UnixData('', '', '', '22', ''),         // AMITypeData amiType
        jvmoptsMap[OSType],                         // String jvmopts
        false,                                      // boolean stopOnTerminate
        netMap[AZ],                                 // String subnetId
        [
            new EC2Tag('Name', 'jenkins-ps3-' + OSType),
            new EC2Tag('iit-billing-tag', 'jenkins-ps3-worker')
        ],                                          // List<EC2Tag> tags
        '3',                                        // String idleTerminationMinutes
        0,                                          // Init minimumNumberOfInstances
        0,                                          // minimumNumberOfSpareInstances
        capMap[typeMap[OSType]],                    // String instanceCapStr
        'arn:aws:iam::119175775298:instance-profile/jenkins-ps3-worker', // String iamInstanceProfile
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
    if (it.hasProperty('name') && it.name == 'AWS-Dev b') {
        privateKey = it['privateKey']
    }
}

String sshKeysCredentialsId = '2ab73cff-5575-4ca2-be48-17761b165103'

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
            getTemplate('docker',               "${region}${it}"),
            getTemplate('docker-32gb',          "${region}${it}"),
            getTemplate('micro-amazon',         "${region}${it}"),
            getTemplate('min-al2023-x64',       "${region}${it}"),
            getTemplate('min-centos-7-x64',     "${region}${it}"),
            getTemplate('fips-centos-7-x64',    "${region}${it}"),
            getTemplate('min-ol-8-x64',         "${region}${it}"),
            getTemplate('min-ol-9-x64',         "${region}${it}"),
            getTemplate('min-rhel-10-x64',      "${region}${it}"),
            getTemplate('min-bionic-x64',       "${region}${it}"),
            getTemplate('min-buster-x64',       "${region}${it}"),
            getTemplate('min-bookworm-x64',     "${region}${it}"),
            getTemplate('min-bullseye-x64',     "${region}${it}"),
            getTemplate('min-trixie-x64',       "${region}${it}"),
            getTemplate('min-stretch-x64',      "${region}${it}"),
            getTemplate('min-xenial-x64',       "${region}${it}"),
            getTemplate('docker-32gb-hirsute',  "${region}${it}"),
            getTemplate('docker-32gb-focal',    "${region}${it}"),
            getTemplate('docker-32gb-jammy',    "${region}${it}"),
            getTemplate('docker-32gb-noble',    "${region}${it}"),
            getTemplate('docker-32gb-bullseye', "${region}${it}"),
            getTemplate('docker-32gb-aarch64',  "${region}${it}"),
            getTemplate('min-centos-7-aarch64', "${region}${it}"),
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
        if (it.hasProperty('cloudName') && it['cloudName'] == ec2Cloud['cloudName']) {
            jenkins.clouds.remove(it)
        }
    }
    jenkins.clouds.add(ec2Cloud)
}

// save current Jenkins state to disk
jenkins.save()

logger.info("Cloud init finished")
