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
netMap['us-west-2a'] = 'subnet-086a759af174241ac'
netMap['us-west-2b'] = 'subnet-03136d8c244f56036'
netMap['us-west-2c'] = 'subnet-09103aa8678a054f7'

imageMap = [:]
imageMap['micro-amazon']     = 'ami-0f9f005c313373218'
imageMap['min-centos-7-x64'] = 'ami-0686851c4e7b1a8e1'
imageMap['min-centos-8-x64'] = 'ami-0155c31ea13d4abd2'
imageMap['min-ol-8-x64']     = 'ami-000b99c02c2b64925'
imageMap['min-ol-9-x64']     = 'ami-00a5d5bcea31bb02c'
imageMap['min-bullseye-x64'] = 'ami-0d0f7602aa5c2425d'
imageMap['min-buster-x64']   = 'ami-013e2c587714af230'
imageMap['min-stretch-x64']  = 'ami-01bc069bbdca81d56'
imageMap['min-xenial-x64']   = 'ami-079e7a3f57cc8e0d0'
imageMap['min-bionic-x64']   = 'ami-074251216af698218'
imageMap['min-focal-x64']    = 'ami-0892d3c7ee96c0bf7'
imageMap['min-jammy-x64']    = 'ami-0ee8244746ec5d6d4'
imageMap['psmdb']            = imageMap['min-xenial-x64']
imageMap['psmdb-bionic']     = imageMap['min-bionic-x64']
imageMap['docker']           = imageMap['micro-amazon']
imageMap['docker-32gb']      = imageMap['micro-amazon']
imageMap['docker-64gb']      = imageMap['micro-amazon']

imageMap['docker-64gb-aarch64'] = 'ami-0710985fd660e8917'

priceMap = [:]
priceMap['t2.medium']   = '0.03'
priceMap['c5ad.2xlarge']  = '0.18'
priceMap['m5zn.2xlarge'] = '0.22'
priceMap['c5ad.4xlarge'] = '0.40'
priceMap['g4ad.4xlarge'] = '0.40' // type=g4ad.4xlarge, vCPU=16, memory=64GiB, saving=70%, interruption='<5%'
priceMap['m6gd.4xlarge'] = '0.35' // aarch64 type=m6gd.4xlarge, vCPU=16, memory=64GiB, saving=61%, interruption='<5%', price=0.284000

userMap = [:]
userMap['docker']           = 'ec2-user'
userMap['docker-32gb']      = userMap['docker']
userMap['docker-64gb']      = userMap['docker']
userMap['micro-amazon']     = userMap['docker']
userMap['min-centos-7-x64'] = 'centos'
userMap['min-centos-8-x64'] = 'centos'
userMap['min-ol-8-x64']     = 'ec2-user'
userMap['min-ol-9-x64']     = 'ec2-user'
userMap['min-stretch-x64']  = 'admin'
userMap['min-buster-x64']   = 'admin'
userMap['min-xenial-x64']   = 'ubuntu'
userMap['min-bionic-x64']   = 'ubuntu'
userMap['min-focal-x64']    = 'ubuntu'
userMap['min-jammy-x64']    = 'ubuntu'
userMap['min-bullseye-x64'] = 'admin'
userMap['psmdb']            = userMap['min-xenial-x64']
userMap['psmdb-bionic']     = userMap['min-xenial-x64']

userMap['docker-64gb-aarch64'] = userMap['docker']

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

    sudo amazon-linux-extras install epel -y
    sudo amazon-linux-extras install java-openjdk11 -y || :
    sudo yum -y install git aws-cli docker
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

    sudo amazon-linux-extras install epel -y
    sudo amazon-linux-extras install java-openjdk11 -y || :
    sudo yum -y install git aws-cli docker
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

    until sudo yum makecache; do
        sleep 1
        echo try again
    done
    sudo amazon-linux-extras install epel -y || :
    sudo amazon-linux-extras install java-openjdk11 -y || :
    sudo yum -y install java-11-openjdk || :
    sudo yum -y install git || :
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
            sudo DEBIAN_FRONTEND=noninteractive apt-get -y install xfsprogs
            sudo mkfs.xfs ${DEVICE}
            sudo mount ${DEVICE} /mnt
        fi
    fi

    echo '10.30.6.9 repo.ci.percona.com' | sudo tee -a /etc/hosts

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


initMap['docker-64gb']       = initMap['docker-32gb']
initMap['micro-amazon']      = initMap['rpmMap']
initMap['min-centos-7-x64']  = initMap['rpmMap']
initMap['min-centos-8-x64']  = initMap['rpmMap']
initMap['min-ol-8-x64']      = initMap['rpmMap']
initMap['min-ol-9-x64']      = initMap['rpmMap']

initMap['min-bullseye-x64'] = initMap['debMap']
initMap['min-stretch-x64']  = initMap['debMap']
initMap['min-buster-x64']   = initMap['debMap']

initMap['min-jammy-x64']   = initMap['debMap']
initMap['min-focal-x64']   = initMap['debMap']
initMap['min-bionic-x64']  = initMap['debMap']
initMap['min-xenial-x64']  = initMap['debMap']
initMap['psmdb']           = initMap['debMap']
initMap['psmdb-bionic']    = initMap['debMap']

initMap['docker-64gb-aarch64'] = initMap['docker-32gb']

capMap = [:]
capMap['c5ad.2xlarge'] = '60'
capMap['m5zn.2xlarge'] = '60'
capMap['c5ad.4xlarge'] = '80'
capMap['g4ad.4xlarge'] = '20'
capMap['m6gd.4xlarge'] = '20'

typeMap = [:]
typeMap['micro-amazon']      = 't2.medium'
typeMap['docker']            = 'c5ad.2xlarge'
typeMap['docker-32gb']       = 'c5ad.4xlarge'
typeMap['docker-64gb']       = 'g4ad.4xlarge'
typeMap['min-centos-7-x64']  = typeMap['docker-32gb']
typeMap['min-centos-8-x64']  = typeMap['docker-32gb']
typeMap['min-ol-8-x64']      = typeMap['docker-32gb']
typeMap['min-ol-9-x64']      = typeMap['docker-32gb']
typeMap['min-bullseye-x64']  = typeMap['docker-32gb']
typeMap['min-stretch-x64']   = typeMap['docker-32gb']
typeMap['min-buster-x64']    = typeMap['docker-32gb']
typeMap['min-xenial-x64']    = typeMap['docker-32gb']
typeMap['min-bionic-x64']    = typeMap['docker-32gb']
typeMap['min-focal-x64']     = typeMap['docker-32gb']
typeMap['min-jammy-x64']     = typeMap['docker-32gb']
typeMap['psmdb']             = typeMap['docker-32gb']
typeMap['psmdb-bionic']      = typeMap['docker-32gb']

typeMap['docker-64gb-aarch64'] = 'm6gd.4xlarge'

execMap = [:]
execMap['docker']           = '1'
execMap['docker-32gb']      = execMap['docker']
execMap['docker-64gb']      = execMap['docker']
execMap['micro-amazon']     = '30'
execMap['min-centos-7-x64'] = '1'
execMap['min-centos-8-x64'] = '1'
execMap['min-ol-8-x64']     = '1'
execMap['min-ol-9-x64']     = '1'
execMap['min-bullseye-x64'] = '1'
execMap['min-stretch-x64']  = '1'
execMap['min-buster-x64']   = '1'
execMap['min-xenial-x64']   = '1'
execMap['min-bionic-x64']   = '1'
execMap['min-focal-x64']    = '1'
execMap['min-jammy-x64']    = '1'
execMap['psmdb']            = '1'
execMap['psmdb-bionic']     = '1'

execMap['docker-64gb-aarch64'] = execMap['docker']

devMap = [:]
devMap['docker']           = '/dev/xvda=:8:true:gp2,/dev/xvdd=:500:true:gp2'
devMap['docker-32gb']      = devMap['docker']
devMap['docker-64gb']      = devMap['docker']
devMap['psmdb']            = '/dev/sda1=:8:true:gp2,/dev/sdd=:500:true:gp2'
devMap['psmdb-bionic']     = '/dev/sda1=:8:true:gp2,/dev/sdd=:500:true:gp2'
devMap['micro-amazon']     = '/dev/xvda=:8:true:gp2,/dev/xvdd=:160:true:gp2'
devMap['min-centos-7-x64'] = '/dev/xvda=:8:true:gp2,/dev/xvdd=:500:true:gp2'
devMap['min-centos-8-x64'] = '/dev/xvda=:8:true:gp2,/dev/xvdd=:500:true:gp2'
devMap['min-ol-8-x64']     = '/dev/xvda=:8:true:gp2,/dev/xvdd=:500:true:gp2'
devMap['min-ol-9-x64']     = '/dev/xvda=:8:true:gp2,/dev/xvdd=:500:true:gp2'
devMap['min-bullseye-x64'] = '/dev/xvda=:8:true:gp2,/dev/xvdd=:500:true:gp2'
devMap['min-buster-x64']   = '/dev/xvda=:8:true:gp2,/dev/xvdd=:500:true:gp2'
devMap['min-stretch-x64']  = 'xvda=:8:true:gp2,xvdd=:500:true:gp2'
devMap['min-xenial-x64']   = '/dev/sda1=:8:true:gp2,/dev/sdd=:500:true:gp2'
devMap['min-bionic-x64']   = '/dev/sda1=:8:true:gp2,/dev/sdd=:500:true:gp2'
devMap['min-focal-x64']    = '/dev/sda1=:8:true:gp2,/dev/sdd=:500:true:gp2'
devMap['min-jammy-x64']    = '/dev/sda1=:8:true:gp2,/dev/sdd=:500:true:gp2'

devMap['docker-64gb-aarch64'] = devMap['docker']

labelMap = [:]
labelMap['docker']           = ''
labelMap['docker-32gb']      = ''
labelMap['docker-64gb']      = ''
labelMap['micro-amazon']     = 'master'
labelMap['min-centos-7-x64'] = ''
labelMap['min-centos-8-x64'] = ''
labelMap['min-ol-8-x64']     = ''
labelMap['min-ol-9-x64']     = ''
labelMap['min-bullseye-x64'] = ''
labelMap['min-stretch-x64']  = ''
labelMap['min-buster-x64']   = ''
labelMap['min-xenial-x64']   = ''
labelMap['min-bionic-x64']   = ''
labelMap['min-focal-x64']    = ''
labelMap['min-jammy-x64']    = ''
labelMap['psmdb']            = ''
labelMap['psmdb-bionic']     = ''

labelMap['docker-64gb-aarch64'] = ''

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
        '-Xmx512m -Xms512m',                        // String jvmopts
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
    AmazonEC2Cloud ec2Cloud = new AmazonEC2Cloud(
        "AWS-Dev ${it}",                        // String cloudName
        true,                                   // boolean useInstanceProfileForCredentials
        '',                                     // String credentialsId
        region,                                 // String region
        privateKey,                             // String privateKey
        sshKeysCredentialsId,                   // String sshKeysCredentialsId
        '240',                                   // String instanceCapStr
        [
            getTemplate('docker',           "${region}${it}"),
            getTemplate('docker-32gb',      "${region}${it}"),
            getTemplate('docker-64gb',      "${region}${it}"),
            getTemplate('psmdb',            "${region}${it}"),
            getTemplate('psmdb-bionic',     "${region}${it}"),
            getTemplate('min-centos-7-x64', "${region}${it}"),
            getTemplate('min-centos-8-x64', "${region}${it}"),
            getTemplate('min-ol-8-x64',     "${region}${it}"),
            getTemplate('min-ol-9-x64',     "${region}${it}"),
            getTemplate('min-buster-x64',   "${region}${it}"),
            getTemplate('min-stretch-x64',  "${region}${it}"),
            getTemplate('min-bullseye-x64', "${region}${it}"),
            getTemplate('min-jammy-x64',    "${region}${it}"),
            getTemplate('min-focal-x64',    "${region}${it}"),
            getTemplate('min-bionic-x64',   "${region}${it}"),
            getTemplate('min-xenial-x64',   "${region}${it}"),
            getTemplate('micro-amazon',     "${region}${it}"),
            getTemplate('docker-64gb-aarch64', "${region}${it}"),
        ],
        '',
        ''                                    // List<? extends SlaveTemplate> templates
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
