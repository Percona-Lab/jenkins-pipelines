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
imageMap['micro-amazon']     = 'ami-083ac7c7ecf9bb9b0'
imageMap['min-centos-6-x64'] = 'ami-0362922178e02e9f3'
imageMap['min-centos-7-x64'] = 'ami-0686851c4e7b1a8e1'
imageMap['min-bullseye-x64'] = 'ami-05f9dcaa9ddb9a15e'
imageMap['min-stretch-x64']  = 'ami-0c729632334a74b05'
imageMap['min-xenial-x64']   = 'ami-079e7a3f57cc8e0d0'
imageMap['min-bionic-x64']   = 'ami-0864290505bf6b170'
imageMap['psmdb']            = imageMap['min-xenial-x64']
imageMap['psmdb-bionic']     = imageMap['min-bionic-x64']
imageMap['docker']           = imageMap['micro-amazon']
imageMap['docker-32gb']      = imageMap['micro-amazon']

priceMap = [:]
priceMap['t2.small']   = '0.01'
priceMap['c5.xlarge']  = '0.10'
priceMap['m4.xlarge']  = '0.10'
priceMap['m4.2xlarge'] = '0.20'
priceMap['m5a.2xlarge'] = '0.25'

userMap = [:]
userMap['docker']           = 'ec2-user'
userMap['docker-32gb']      = userMap['docker']
userMap['micro-amazon']     = userMap['docker']
userMap['min-centos-6-x64'] = 'centos'
userMap['min-centos-7-x64'] = 'centos'
userMap['min-stretch-x64']  = 'admin'
userMap['min-xenial-x64']   = 'ubuntu'
userMap['min-bionic-x64']   = 'ubuntu'
userMap['min-bullseye-x64'] = 'admin'
userMap['psmdb']            = userMap['min-xenial-x64']
userMap['psmdb-bionic']     = userMap['min-xenial-x64']

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

    sudo yum -y install java-1.8.0-openjdk git aws-cli docker
    sudo yum -y remove java-1.7.0-openjdk
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

    sudo yum -y install java-1.8.0-openjdk git aws-cli docker
    sudo yum -y remove java-1.7.0-openjdk
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
    until sudo yum makecache; do
        sleep 1
        echo try again
    done
    sudo yum -y install java-1.8.0-openjdk git ${PKGLIST} || :
    sudo yum -y install aws-cli || :
    sudo yum -y remove java-1.7.0-openjdk || :
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
            sudo apt-get -y install xfsprogs
            sudo mkfs.xfs ${DEVICE}
            sudo mount ${DEVICE} /mnt
        fi
    fi

    echo '10.30.6.9 repo.ci.percona.com' | sudo tee -a /etc/hosts

    until sudo apt-get update; do
        sleep 1
        echo try again
    done
    until sudo apt-get install -y lsb-release; do
        sleep 1
        echo try again
    done
    DEB_VER=$(lsb_release -sc)
    if [[ ${DEB_VER} == "buster" ]] || [[ ${DEB_VER} == "bullseye" ]]; then
        JAVA_VER="openjdk-11-jre-headless"
    else
        JAVA_VER="openjdk-8-jre-headless"
    fi
    sudo apt-get -y install ${JAVA_VER} git
    sudo install -o $(id -u -n) -g $(id -g -n) -d /mnt/jenkins
'''


initMap['micro-amazon']      = initMap['rpmMap']
initMap['min-centos-6-x64']  = initMap['rpmMap']
initMap['min-centos-7-x64']  = initMap['rpmMap']

initMap['min-bullseye-x64'] = initMap['debMap']
initMap['min-stretch-x64'] = initMap['debMap']

initMap['min-bionic-x64']  = initMap['debMap']
initMap['min-xenial-x64']  = initMap['debMap']
initMap['psmdb']           = initMap['debMap']
initMap['psmdb-bionic']    = initMap['debMap']

capMap = [:]
capMap['c5.xlarge'] = '60'
capMap['m4.xlarge'] = '60'
capMap['m4.2xlarge'] = '10'
capMap['m5a.2xlarge'] = '10'

typeMap = [:]
typeMap['micro-amazon']      = 't2.small'
typeMap['docker']            = 'c5.xlarge'
typeMap['docker-32gb']       = 'm5a.2xlarge'
typeMap['min-centos-7-x64']  = typeMap['docker-32gb']
typeMap['min-centos-6-x64']  = 'm5a.2xlarge'
typeMap['min-bullseye-x64']  = typeMap['docker-32gb']
typeMap['min-stretch-x64']   = typeMap['docker-32gb']
typeMap['min-xenial-x64']    = typeMap['docker-32gb']
typeMap['min-bionic-x64']    = typeMap['docker-32gb']
typeMap['psmdb']             = typeMap['docker-32gb']
typeMap['psmdb-bionic']      = typeMap['docker-32gb']

execMap = [:]
execMap['docker']           = '1'
execMap['docker-32gb']      = execMap['docker']
execMap['micro-amazon']     = '30'
execMap['min-centos-6-x64'] = '1'
execMap['min-centos-7-x64'] = '1'
execMap['min-bullseye-x64'] = '1'
execMap['min-stretch-x64']  = '1'
execMap['min-xenial-x64']   = '1'
execMap['min-bionic-x64']   = '1'
execMap['psmdb']            = '1'
execMap['psmdb-bionic']     = '1'

devMap = [:]
devMap['docker']           = '/dev/xvda=:8:true:gp2,/dev/xvdd=:160:true:gp2'
devMap['psmdb']            = '/dev/sda1=:8:true:gp2,/dev/sdd=:160:true:gp2'
devMap['psmdb-bionic']     = '/dev/sda1=:8:true:gp2,/dev/sdd=:500:true:gp2'
devMap['docker-32gb']      = devMap['docker']
devMap['micro-amazon']     = devMap['docker']
devMap['min-centos-6-x64'] = '/dev/xvda=:8:true:gp2,/dev/xvdd=:400:true:gp2'
devMap['min-centos-7-x64'] = '/dev/xvda=:8:true:gp2,/dev/xvdd=:400:true:gp2'
devMap['min-bullseye-x64'] = '/dev/xvda=:8:true:gp2,/dev/xvdd=:400:true:gp2'
devMap['min-stretch-x64']  = 'xvda=:8:true:gp2,xvdd=:400:true:gp2'
devMap['min-xenial-x64']   = '/dev/sda1=:8:true:gp2,/dev/sdd=:400:true:gp2'
devMap['min-bionic-x64']   = '/dev/sda1=:8:true:gp2,/dev/sdd=:400:true:gp2'

labelMap = [:]
labelMap['docker']           = ''
labelMap['docker-32gb']      = ''
labelMap['micro-amazon']     = 'master'
labelMap['min-centos-6-x64'] = ''
labelMap['min-centos-7-x64'] = ''
labelMap['min-bullseye-x64'] = ''
labelMap['min-stretch-x64']  = ''
labelMap['min-xenial-x64']   = ''
labelMap['min-bionic-x64']   = ''
labelMap['psmdb']            = ''
labelMap['psmdb-bionic']     = ''

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
        new UnixData('', '', '', '22'),             // AMITypeData amiType
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
            getTemplate('docker', "${region}${it}"),
            getTemplate('docker-32gb', "${region}${it}"),
            getTemplate('psmdb',  "${region}${it}"),
            getTemplate('psmdb-bionic',  "${region}${it}"),
            getTemplate('min-centos-6-x64', "${region}${it}"),
            getTemplate('min-centos-7-x64', "${region}${it}"),
            getTemplate('min-stretch-x64',  "${region}${it}"),
            getTemplate('min-bullseye-x64', "${region}${it}"),
            getTemplate('min-bionic-x64',   "${region}${it}"),
            getTemplate('min-xenial-x64',   "${region}${it}"),
            getTemplate('micro-amazon',     "${region}${it}"),
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
