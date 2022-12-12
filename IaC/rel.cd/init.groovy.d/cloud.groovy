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

imageMap['eu-west-1a.min-centos-7-x64']  = 'ami-04f5641b0d178a27a'
imageMap['eu-west-1a.fips-centos-7-x64'] = 'ami-04f5641b0d178a27a'
imageMap['eu-west-1a.min-centos-8-x64']  = 'ami-0a75a5a43b05b4d5f'
imageMap['eu-west-1a.min-ol-8-x64']      = 'ami-0f7601d8419fac927'
imageMap['eu-west-1a.min-ol-9-x64']      = 'ami-04e2c29c7de5e0f5a'
imageMap['eu-west-1a.min-bullseye-x64']  = 'ami-01ebd2b650c37e4d6'
imageMap['eu-west-1a.min-buster-x64']    = 'ami-04e1d2f88740af5e1'
imageMap['eu-west-1a.min-jammy-x64']     = 'ami-00c90dbdc12232b58'
imageMap['eu-west-1a.min-focal-x64']     = 'ami-03caf24deed650e2c'
imageMap['eu-west-1a.min-bionic-x64']    = 'ami-0c259a97cbf621daf'
imageMap['eu-west-1a.min-hirsute-x64-zenfs'] = 'ami-02469e1cc9f95b137'
imageMap['eu-west-1a.min-focal-x64-zenfs']   = 'ami-05a657c9227900694'
imageMap['eu-west-1a.min-bionic-x64-zenfs']  = 'ami-02d7fe93ba8353d4e'

imageMap['eu-west-1b.docker'] = imageMap['eu-west-1a.docker']
imageMap['eu-west-1b.docker-32gb'] = imageMap['eu-west-1a.docker-32gb']
imageMap['eu-west-1b.docker2'] = imageMap['eu-west-1a.docker2']
imageMap['eu-west-1b.micro-amazon'] = imageMap['eu-west-1a.micro-amazon']
imageMap['eu-west-1b.min-centos-7-x64'] = imageMap['eu-west-1a.min-centos-7-x64']
imageMap['eu-west-1b.fips-centos-7-x64'] = imageMap['eu-west-1a.fips-centos-7-x64']

imageMap['eu-west-1b.min-centos-7-x64'] = imageMap['eu-west-1a.min-centos-7-x64']
imageMap['eu-west-1b.min-centos-8-x64'] = imageMap['eu-west-1a.min-centos-8-x64']
imageMap['eu-west-1b.min-ol-8-x64']     = imageMap['eu-west-1a.min-ol-8-x64']
imageMap['eu-west-1b.min-ol-9-x64']     = imageMap['eu-west-1a.min-ol-9-x64']
imageMap['eu-west-1b.min-bullseye-x64'] = imageMap['eu-west-1a.min-bullseye-x64']
imageMap['eu-west-1b.min-buster-x64']   = imageMap['eu-west-1a.min-buster-x64']
imageMap['eu-west-1b.min-jammy-x64']    = imageMap['eu-west-1a.min-jammy-x64']
imageMap['eu-west-1b.min-focal-x64']    = imageMap['eu-west-1a.min-focal-x64']
imageMap['eu-west-1b.min-bionic-x64']   = imageMap['eu-west-1a.min-bionic-x64']
imageMap['eu-west-1b.min-hirsute-x64-zenfs'] = imageMap['eu-west-1a.min-hirsute-x64-zenfs']
imageMap['eu-west-1b.min-focal-x64-zenfs'] = imageMap['eu-west-1a.min-focal-x64-zenfs']
imageMap['eu-west-1b.min-bionic-x64-zenfs']  = imageMap['eu-west-1a.min-bionic-x64-zenfs']

imageMap['eu-west-1c.docker'] = imageMap['eu-west-1a.docker']
imageMap['eu-west-1c.docker-32gb'] = imageMap['eu-west-1a.docker-32gb']
imageMap['eu-west-1c.docker2'] = imageMap['eu-west-1a.docker2']
imageMap['eu-west-1c.micro-amazon'] = imageMap['eu-west-1a.micro-amazon']
imageMap['eu-west-1c.min-centos-7-x64'] = imageMap['eu-west-1a.min-centos-7-x64']
imageMap['eu-west-1c.fips-centos-7-x64'] = imageMap['eu-west-1a.fips-centos-7-x64']

imageMap['eu-west-1c.min-centos-7-x64'] = imageMap['eu-west-1a.min-centos-7-x64']
imageMap['eu-west-1c.min-centos-8-x64'] = imageMap['eu-west-1a.min-centos-8-x64']
imageMap['eu-west-1c.min-ol-8-x64']     = imageMap['eu-west-1a.min-ol-8-x64']
imageMap['eu-west-1c.min-ol-9-x64']     = imageMap['eu-west-1a.min-ol-9-x64']
imageMap['eu-west-1c.min-bullseye-x64'] = imageMap['eu-west-1a.min-bullseye-x64']
imageMap['eu-west-1c.min-buster-x64']   = imageMap['eu-west-1a.min-buster-x64']
imageMap['eu-west-1c.min-jammy-x64']    = imageMap['eu-west-1a.min-jammy-x64']
imageMap['eu-west-1c.min-focal-x64']    = imageMap['eu-west-1a.min-focal-x64'] 
imageMap['eu-west-1c.min-bionic-x64']   = imageMap['eu-west-1a.min-bionic-x64']
imageMap['eu-west-1c.min-hirsute-x64-zenfs'] = imageMap['eu-west-1a.min-hirsute-x64-zenfs']
imageMap['eu-west-1c.min-focal-x64-zenfs'] = imageMap['eu-west-1a.min-focal-x64-zenfs']
imageMap['eu-west-1c.min-bionic-x64-zenfs']  = imageMap['eu-west-1a.min-bionic-x64-zenfs']

imageMap['eu-west-1a.docker-32gb-aarch64']  = 'ami-06db897520fb93106'
imageMap['eu-west-1b.docker-32gb-aarch64']  = imageMap['eu-west-1a.docker-32gb-aarch64']
imageMap['eu-west-1c.docker-32gb-aarch64']  = imageMap['eu-west-1a.docker-32gb-aarch64']

priceMap = [:]
priceMap['t2.small'] = '0.01'
priceMap['m1.medium'] = '0.05'
priceMap['c5.xlarge'] = '0.15'   // old 0.10
priceMap['m4.xlarge'] = '0.15'   // old 0.10
priceMap['r5b.2xlarge'] = '0.45' // old 0.32
priceMap['r4.4xlarge'] = '0.48'  // old 0.38
priceMap['m5d.2xlarge'] = '0.35' // old 0.20
priceMap['c5d.xlarge'] = '0.35'  // old 0.20
priceMap['i4i.2xlarge'] = '0.50' // old 0.40

priceMap['m6gd.2xlarge'] = '0.23' // aarch64 type=m6gd.2xlarge, vCPU=8, memory=32GiB, saving=62%, interruption='<5%', price=0.151500

userMap = [:]
userMap['docker']            = 'ec2-user'
userMap['docker-32gb']       = userMap['docker']
userMap['docker2']           = userMap['docker']
userMap['micro-amazon']      = userMap['docker']
userMap['min-jammy-x64']     = 'ubuntu'
userMap['min-focal-x64']     = 'ubuntu'
userMap['min-bionic-x64']    = 'ubuntu'
userMap['min-centos-7-x64']  = 'centos'
userMap['min-centos-8-x64']  = 'centos'
userMap['min-ol-8-x64']      = 'ec2-user'
userMap['min-ol-9-x64']      = 'ec2-user'
userMap['fips-centos-7-x64'] = 'centos'
userMap['min-buster-x64']    = 'admin'
userMap['min-bullseye-x64']  = 'admin'
userMap['min-hirsute-x64-zenfs']    = 'ubuntu'
userMap['min-focal-x64-zenfs']    = 'ubuntu'
userMap['min-bionic-x64-zenfs']   = 'ubuntu'

userMap['docker-32gb-aarch64'] = userMap['docker']

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
    sudo amazon-linux-extras install java-openjdk11 -y || :
    sudo yum -y install java-11-openjdk || :
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
    sudo amazon-linux-extras install epel -y
    sudo amazon-linux-extras install java-openjdk11 -y || :
    sudo yum -y install java-11-openjdk || :
    sudo yum -y install git || :
    sudo yum -y install aws-cli || :
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

    echo '10.30.6.9 repo.ci.percona.com' | sudo tee -a /etc/hosts

    until sudo apt-get update; do
        sleep 1
        echo try again
    done
    until sudo apt-get -y install openjdk-11-jre-headless git; do
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
initMap['min-bullseye-x64'] = initMap['min-buster-x64']
initMap['min-hirsute-x64-zenfs'] = initMap['min-bionic-x64']
initMap['min-focal-x64-zenfs'] = initMap['min-bionic-x64']
initMap['min-bionic-x64-zenfs'] = initMap['min-bionic-x64']

initMap['docker-32gb-aarch64'] = initMap['docker']

capMap = [:]
capMap['c5.xlarge']    = '60'
capMap['m4.xlarge']    = '5'
capMap['r5b.2xlarge'] = '40'
capMap['r4.4xlarge']   = '40'
capMap['c5d.xlarge']   = '10'
capMap['i4i.2xlarge']  = '40'

capMap['m6gd.2xlarge'] = '20'

typeMap = [:]
typeMap['micro-amazon']      = 't2.small'
typeMap['docker']            = 'c5.xlarge'
typeMap['docker-32gb']       = 'r5b.2xlarge'
typeMap['docker2']           = 'r4.4xlarge'
typeMap['min-centos-7-x64']  = typeMap['docker-32gb']
typeMap['min-centos-8-x64']  = typeMap['min-centos-7-x64']
typeMap['min-ol-8-x64']      = typeMap['min-centos-7-x64']
typeMap['min-ol-9-x64']      = 'i4i.2xlarge'
typeMap['fips-centos-7-x64'] = typeMap['min-centos-7-x64']
typeMap['min-jammy-x64']     = typeMap['min-centos-7-x64']
typeMap['min-focal-x64']     = typeMap['min-centos-7-x64']
typeMap['min-bionic-x64']    = typeMap['min-centos-7-x64']
typeMap['min-bullseye-x64']  = typeMap['min-centos-7-x64']
typeMap['min-buster-x64']    = typeMap['min-centos-7-x64']
typeMap['min-hirsute-x64-zenfs'] = typeMap['min-centos-7-x64']
typeMap['min-focal-x64-zenfs'] = typeMap['min-centos-7-x64']
typeMap['min-bionic-x64-zenfs'] = typeMap['min-centos-7-x64']

typeMap['docker-32gb-aarch64'] = 'm6gd.2xlarge'

execMap = [:]
execMap['docker']            = '1'
execMap['docker-32gb']       = execMap['docker']
execMap['docker2']           = execMap['docker']
execMap['micro-amazon']      = '30'
execMap['min-bionic-x64']    = '1'
execMap['min-focal-x64']     = '1'
execMap['min-jammy-x64']     = '1'
execMap['min-centos-7-x64']  = '1'
execMap['fips-centos-7-x64'] = '1'
execMap['min-centos-8-x64']  = '1'
execMap['min-ol-8-x64']      = '1'
execMap['min-ol-9-x64']      = '1'
execMap['min-buster-x64']    = '1'
execMap['min-bullseye-x64']  = '1'
execMap['min-hirsute-x64-zenfs'] = '1'
execMap['min-focal-x64-zenfs'] = '1'
execMap['min-bionic-x64-zenfs'] = '1'

execMap['docker-32gb-aarch64'] = execMap['docker']

devMap = [:]
devMap['docker']            = '/dev/xvda=:8:true:gp2,/dev/xvdd=:180:true:gp2'
devMap['docker2']           = '/dev/xvda=:8:true:gp2,/dev/xvdd=:180:true:gp2'
devMap['docker-32gb']       = devMap['docker']
devMap['micro-amazon']      = devMap['docker']
devMap['min-bionic-x64']    = '/dev/sda1=:8:true:gp2,/dev/sdd=:180:true:gp2'
devMap['min-focal-x64']     = devMap['min-bionic-x64']
devMap['min-jammy-x64']     = devMap['min-bionic-x64']
devMap['min-centos-7-x64']  = devMap['min-bionic-x64']
devMap['fips-centos-7-x64'] = devMap['min-bionic-x64']
devMap['min-centos-8-x64']  = '/dev/sda1=:10:true:gp2,/dev/sdd=:180:true:gp2'
devMap['min-ol-8-x64']      = '/dev/sda1=:10:true:gp2,/dev/sdd=:180:true:gp2'
devMap['min-ol-9-x64']      = '/dev/sda1=:10:true:gp2,/dev/sdd=:180:true:gp2'
devMap['min-buster-x64']    = devMap['docker']
devMap['min-bullseye-x64']  = devMap['docker']
devMap['min-hirsute-x64-zenfs'] = '/dev/sda1=:10:true:gp2,/dev/sdd=:180:true:gp2'
devMap['min-focal-x64-zenfs'] = '/dev/sda1=:10:true:gp2,/dev/sdd=:180:true:gp2'
devMap['min-bionic-x64-zenfs'] = '/dev/sda1=:10:true:gp2,/dev/sdd=:180:true:gp2'

devMap['docker-32gb-aarch64'] = devMap['docker']

labelMap = [:]
labelMap['docker']            = ''
labelMap['docker-32gb']       = ''
labelMap['docker2']           = ''
labelMap['micro-amazon']      = 'master'
labelMap['min-bionic-x64']    = 'asan'
labelMap['min-focal-x64']     = ''
labelMap['min-jammy-x64']     = ''
labelMap['min-centos-7-x64']  = ''
labelMap['fips-centos-7-x64'] = ''
labelMap['min-centos-8-x64']  = ''
labelMap['min-ol-8-x64']      = ''
labelMap['min-ol-9-x64']      = ''
labelMap['min-buster-x64']    = ''
labelMap['min-bullseye-x64']  = ''
labelMap['min-hirsute-x64-zenfs']  = ''
labelMap['min-focal-x64-zenfs']    = ''
labelMap['min-bionic-x64-zenfs']   = ''

labelMap['docker-32gb-aarch64'] = ''

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
            getTemplate('min-jammy-x64',         "${region}${it}"),
            getTemplate('min-focal-x64',         "${region}${it}"),
            getTemplate('min-bionic-x64',        "${region}${it}"),
            getTemplate('min-buster-x64',        "${region}${it}"),
            getTemplate('min-ol-8-x64',          "${region}${it}"),
            getTemplate('min-ol-9-x64',          "${region}${it}"),
            getTemplate('min-centos-8-x64',      "${region}${it}"),
            getTemplate('min-centos-7-x64',      "${region}${it}"),
            getTemplate('min-bullseye-x64',      "${region}${it}"),
            getTemplate('min-hirsute-x64-zenfs', "${region}${it}"),
            getTemplate('min-focal-x64-zenfs',   "${region}${it}"),
            getTemplate('min-bionic-x64-zenfs',  "${region}${it}"),
            getTemplate('docker-32gb-aarch64',   "${region}${it}"),
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

