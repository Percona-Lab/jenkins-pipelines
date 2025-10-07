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
netMap['eu-west-1b'] = 'subnet-04221bb8f6d0aeeff'
netMap['eu-west-1c'] = 'subnet-0b9a1fd4ba5296a8b'

imageMap = [:]
imageMap['eu-west-1a.docker']                = 'ami-05247819264504af0'
imageMap['eu-west-1a.docker-32gb']           = 'ami-05247819264504af0'
imageMap['eu-west-1a.docker2']               = 'ami-05247819264504af0'
imageMap['eu-west-1a.micro-amazon']          = 'ami-05247819264504af0'
imageMap['eu-west-1a.min-al2023-x64']        = 'ami-091a906f2e1e40076'
imageMap['eu-west-1a.docker-32gb-aarch64']   = 'ami-0b3f5005d71118f36'
imageMap['eu-west-1a.min-al2023-aarch64']    = 'ami-0b24063151d1c59e7'

imageMap['eu-west-1b.docker']                = imageMap['eu-west-1a.docker']
imageMap['eu-west-1b.docker-32gb']           = imageMap['eu-west-1a.docker-32gb']
imageMap['eu-west-1b.docker2']               = imageMap['eu-west-1a.docker2']
imageMap['eu-west-1b.micro-amazon']          = imageMap['eu-west-1a.micro-amazon']
imageMap['eu-west-1b.min-al2023-x64']        = imageMap['eu-west-1a.min-al2023-x64']
imageMap['eu-west-1b.docker-32gb-aarch64']   = imageMap['eu-west-1a.docker-32gb-aarch64']
imageMap['eu-west-1b.min-al2023-aarch64']    = imageMap['eu-west-1a.min-al2023-aarch64']

imageMap['eu-west-1c.docker']                = imageMap['eu-west-1a.docker']
imageMap['eu-west-1c.docker-32gb']           = imageMap['eu-west-1a.docker-32gb']
imageMap['eu-west-1c.docker2']               = imageMap['eu-west-1a.docker2']
imageMap['eu-west-1c.micro-amazon']          = imageMap['eu-west-1a.micro-amazon']
imageMap['eu-west-1c.min-al2023-x64']        = imageMap['eu-west-1a.min-al2023-x64']
imageMap['eu-west-1c.docker-32gb-aarch64']   = imageMap['eu-west-1a.docker-32gb-aarch64']
imageMap['eu-west-1c.min-al2023-aarch64']    = imageMap['eu-west-1a.min-al2023-aarch64']

priceMap = [:]
priceMap['t2.small']    = '0.02'      // type=t2.small, vCPU=1, memory=2GiB, saving=64%, interruption='<5%', price=0.009200
priceMap['c5.xlarge']   = '0.15'     // type=c5.xlarge, vCPU=4, memory=8GiB, saving=52%, interruption='<5%', price=0.096000
priceMap['m5n.2xlarge'] = '0.33'   // type=m5n.2xlarge, vCPU=8, memory=32GiB, saving=59%, interruption='<5%', price=0.254900
priceMap['r7a.4xlarge'] = '0.60'   // type=r7a.4xlarge, vCPU=16, memory=128GiB, saving=67%, interruption='<5%', price=0.532900
priceMap['c6g.4xlarge'] = '0.35'     // aarch64 type=c6g.4xlarge, vCPU=16, memory=32GiB, saving=54%, interruption='<5%', price=0.280900

userMap = [:]
userMap['docker']                = 'ec2-user'
userMap['docker-32gb']           = userMap['docker']
userMap['docker2']               = userMap['docker']
userMap['micro-amazon']          = userMap['docker']
userMap['min-al2023-x64']        = userMap['docker']
userMap['docker-32gb-aarch64']   = userMap['docker']
userMap['min-al2023-aarch64']    = userMap['docker']

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
    sudo amazon-linux-extras install java-openjdk17 -y
    sudo yum -y install git docker p7zip
    sudo yum -y install npm --disableplugin=priorities
    sudo yum -y remove awscli

    if ! $(aws --version | grep -q 'aws-cli/2'); then
        find /tmp -maxdepth 1 -name "*aws*" | xargs sudo rm -rf

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
initMap['docker2'] = initMap['docker']
initMap['docker-32gb'] = '''
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
    sudo amazon-linux-extras install java-openjdk17 -y
    sudo yum -y install git docker p7zip
    sudo yum -y remove awscli

    if ! $(aws --version | grep -q 'aws-cli/2'); then
        find /tmp -maxdepth 1 -name "*aws*" | xargs sudo rm -rf

        until curl "https://awscli.amazonaws.com/awscli-exe-linux-x86_64.zip" -o "/tmp/awscliv2.zip"; do
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
    sudo sed -i.bak -e 's^ExecStart=.*^ExecStart=/usr/bin/dockerd --data-root=/mnt/docker --default-ulimit nofile=900000:900000^' /usr/lib/systemd/system/docker.service
    sudo systemctl daemon-reload
    sudo install -o root -g root -d /mnt/docker
    sudo usermod -aG docker $(id -u -n)
    sudo mkdir -p /etc/docker
    sudo systemctl status docker || sudo systemctl start docker
    sudo service docker status || sudo service docker start
    echo "* * * * * root /usr/sbin/route add default gw 10.177.1.1 eth0" | sudo tee /etc/cron.d/fix-default-route
    CRI_DOCKERD_LATEST_VERSION=$(curl -s https://api.github.com/repos/Mirantis/cri-dockerd/releases/latest|grep tag_name | cut -d '"' -f 4 | grep -Eo '([0-9].)+[0-9]')
    sudo curl -Lo /tmp/cri-dockerd-${CRI_DOCKERD_LATEST_VERSION}.amd64.tgz https://github.com/Mirantis/cri-dockerd/releases/download/v${CRI_DOCKERD_LATEST_VERSION}/cri-dockerd-${CRI_DOCKERD_LATEST_VERSION}.amd64.tgz
    sudo tar xvfz /tmp/cri-dockerd-${CRI_DOCKERD_LATEST_VERSION}.amd64.tgz -C /tmp/
    sudo mv /tmp/cri-dockerd/cri-dockerd /usr/bin
    sudo chmod +x /usr/bin/cri-dockerd
    sudo curl -Lo /etc/systemd/system/cri-docker.service https://raw.githubusercontent.com/Mirantis/cri-dockerd/v${CRI_DOCKERD_LATEST_VERSION}/packaging/systemd/cri-docker.service
    sudo curl -Lo /etc/systemd/system/cri-docker.socket https://raw.githubusercontent.com/Mirantis/cri-dockerd/v${CRI_DOCKERD_LATEST_VERSION}/packaging/systemd/cri-docker.socket
    sudo systemctl daemon-reload
    sudo systemctl enable cri-docker.service
    sudo systemctl enable --now cri-docker.socket
    sudo systemctl start cri-docker.service
    CRICTL_LATEST_VERSION=$(curl -s https://api.github.com/repos/kubernetes-sigs/cri-tools/releases/latest|grep tag_name | cut -d '"' -f 4)
    sudo curl -Lo /tmp/crictl-${CRICTL_LATEST_VERSION}-linux-amd64.tar.gz https://github.com/kubernetes-sigs/cri-tools/releases/download/${CRICTL_LATEST_VERSION}/crictl-${CRICTL_LATEST_VERSION}-linux-amd64.tar.gz
    sudo tar xvfz /tmp/crictl-${CRICTL_LATEST_VERSION}-linux-amd64.tar.gz -C /usr/bin/
'''
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
    sudo amazon-linux-extras install java-openjdk17 -y || :
    sudo yum -y install git aws-cli || :
    sudo install -o $(id -u -n) -g $(id -g -n) -d /mnt/jenkins
'''
initMap['docker-32gb-aarch64'] = initMap['docker']
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

    sudo yum -y install java-17-amazon-corretto tzdata-java git docker p7zip cronie
    sudo yum -y install npm --disableplugin=priorities
    sudo yum -y remove awscli
    sudo systemctl enable crond
    sudo systemctl start crond

    if ! $(aws --version | grep -q 'aws-cli/2'); then
        find /tmp -maxdepth 1 -name "*aws*" | xargs sudo rm -rf

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
initMap['min-al2023-aarch64'] = initMap['min-al2023-x64']

capMap = [:]
capMap['c5.xlarge']   = '40'
capMap['m5n.2xlarge'] = '40'
capMap['r7a.4xlarge'] = '40'
capMap['c6g.4xlarge'] = '40'

typeMap = [:]
typeMap['micro-amazon']          = 't2.small'
typeMap['docker']                = 'c5.xlarge'
typeMap['docker-32gb']           = 'm5n.2xlarge'
typeMap['docker2']               = 'r7a.4xlarge'
typeMap['min-al2023-x64']        = 'c5.xlarge'
typeMap['docker-32gb-aarch64']   = 'c6g.4xlarge'
typeMap['min-al2023-aarch64']    = 'c6g.4xlarge'

execMap = [:]
execMap['docker']                = '1'
execMap['docker-32gb']           = execMap['docker']
execMap['docker-32gb-aarch64']   = execMap['docker']
execMap['docker2']               = execMap['docker']
execMap['micro-amazon']          = '30'
execMap['min-al2023-x64']        = execMap['docker']
execMap['min-al2023-aarch64']    = execMap['docker']

devMap = [:]
devMap['docker']                 = '/dev/xvda=:15:true:gp2,/dev/xvdd=:80:true:gp2'
devMap['docker2']                = '/dev/xvda=:15:true:gp2,/dev/xvdd=:80:true:gp2'
devMap['docker-32gb']            = devMap['docker']
devMap['docker-32gb-aarch64']    = devMap['docker']
devMap['micro-amazon']           = devMap['docker']
devMap['min-al2023-x64']         = '/dev/xvda=:15:true:gp3,/dev/xvdd=:80:true:gp3'
devMap['min-al2023-aarch64']     = '/dev/xvda=:15:true:gp3,/dev/xvdd=:80:true:gp3'

labelMap = [:]
labelMap['docker']                 = ''
labelMap['docker-32gb']            = ''
labelMap['docker-32gb-aarch64']    = 'docker-32gb-aarch64'
labelMap['docker2']                = 'docker-32gb'
labelMap['micro-amazon']           = 'master'
labelMap['min-al2023-x64']         = ''
labelMap['min-al2023-aarch64']     = ''

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
        '-Xmx512m -Xms512m',                        // String jvmopts
        false,                                      // boolean stopOnTerminate
        netMap[AZ],                                 // String subnetId
        [
            new EC2Tag('Name', 'jenkins-cloud-' + OSType),
            new EC2Tag('iit-billing-tag', 'jenkins-cloud-worker')
        ],                                          // List<EC2Tag> tags
        '3',                                        // String idleTerminationMinutes
        0,                                          // Init minimumNumberOfInstances
        0,                                          // minimumNumberOfSpareInstances
        capMap[typeMap[OSType]],                    // String instanceCapStr
        'arn:aws:iam::119175775298:instance-profile/jenkins-cloud-worker', // String iamInstanceProfile
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

String sshKeysCredentialsId = '66e185f8-bc7c-46ed-9e84-5cc99fa71fc8'

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
            getTemplate('docker',              "${region}${it}"),
            getTemplate('docker-32gb',         "${region}${it}"),
            getTemplate('micro-amazon',        "${region}${it}"),
            getTemplate('min-al2023-x64',      "${region}${it}"),
            getTemplate('docker-32gb-aarch64', "${region}${it}"),
            getTemplate('min-al2023-aarch64',  "${region}${it}"),
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
