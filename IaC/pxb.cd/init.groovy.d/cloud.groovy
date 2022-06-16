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
netMap['us-west-2a'] = 'subnet-07ce3c65ffeabf876'
netMap['us-west-2b'] = 'subnet-011f09cf273aeef73'
netMap['us-west-2c'] = 'subnet-00b0d1d8bd8af5c07'

imageMap = [:]
imageMap['docker'] = 'ami-0e21d4d9303512b8e'
imageMap['docker-32gb'] = imageMap['docker']
imageMap['micro-amazon'] = imageMap['docker']
imageMap['min-centos-6-x32'] = 'ami-cb1382fb'
imageMap['min-centos-6-x64'] = 'ami-0e025868cef8764d8'
imageMap['min-centos-7-x64'] = 'ami-0686851c4e7b1a8e1'
imageMap['min-centos-8-x64'] = 'ami-0155c31ea13d4abd2'
imageMap['min-ol-8-x64']     = 'ami-000b99c02c2b64925'
imageMap['min-bionic-x64']   = 'ami-0bdef2eb518663879'
imageMap['min-stretch-x64']  = 'ami-025f3bcb64ebe6e83'
imageMap['min-buster-x64']   = 'ami-090cd3aed687b1ee1'
imageMap['min-xenial-x64']   = 'ami-079e7a3f57cc8e0d0'
imageMap['min-xenial-x32']   = 'ami-0697ba3ee1b641c90'
imageMap['min-focal-x64']    = 'ami-01773ce53581acf22'
imageMap['min-jammy-x64']    = 'ami-0ee8244746ec5d6d4'
imageMap['min-bullseye-x64'] = 'ami-0d0f7602aa5c2425d'

priceMap = [:]
priceMap['t3a.medium'] = '0.015'
priceMap['m1.medium'] = '0.05'
priceMap['c5ad.2xlarge'] = '0.18'
priceMap['m3.2xlarge'] = '0.23'
priceMap['m5zn.2xlarge'] = '0.23'
priceMap['m5zn.3xlarge'] = '0.27'

userMap = [:]
userMap['docker'] = 'ec2-user'
userMap['docker-32gb'] = userMap['docker']
userMap['micro-amazon'] = userMap['docker']
userMap['min-bionic-x64'] = 'ubuntu'
userMap['min-centos-6-x32'] = 'root'
userMap['min-centos-6-x64'] = 'centos'
userMap['min-centos-7-x64'] = 'centos'
userMap['fips-centos-7-x64'] = 'centos'
userMap['min-centos-8-x64'] = 'centos'
userMap['min-ol-8-x64'] = 'ec2-user'
userMap['min-stretch-x64'] = 'admin'
userMap['min-buster-x64'] = 'admin'
userMap['min-focal-x64'] = 'ubuntu'
userMap['min-jammy-x64'] = 'ubuntu'
userMap['min-xenial-x64'] = 'ubuntu'
userMap['min-xenial-x32'] = 'ubuntu'
userMap['min-bullseye-x64'] = 'admin'

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
    echo '{"experimental": true}' | sudo tee /etc/docker/daemon.json
    sudo systemctl status docker || sudo systemctl start docker
    sudo service docker status || sudo service docker start
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
    sudo yum -y install java-1.8.0-openjdk git ${PKGLIST} || :
    sudo yum -y remove java-1.7.0-openjdk || :
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
        JAVA_VER="openjdk-8-jre-headless"
    fi
    sudo DEBIAN_FRONTEND=noninteractive apt-get -y install ${JAVA_VER} git
    sudo install -o $(id -u -n) -g $(id -g -n) -d /mnt/jenkins
'''

initMap['micro-amazon'] = initMap['rpmMap']
initMap['min-centos-6-x64'] = initMap['rpmMap']
initMap['min-centos-7-x64'] = initMap['rpmMap']
initMap['fips-centos-7-x64'] = initMap['rpmMap']
initMap['min-centos-8-x64'] = initMap['rpmMap']
initMap['min-ol-8-x64'] = initMap['rpmMap']
initMap['min-centos-6-x32'] = initMap['rpmMap']

initMap['min-stretch-x64'] = initMap['debMap']
initMap['min-buster-x64'] = initMap['debMap']
initMap['min-bullseye-x64'] = initMap['debMap']

initMap['min-xenial-x64'] = initMap['debMap']
initMap['min-xenial-x32'] = initMap['debMap']
initMap['min-bionic-x64'] = initMap['debMap']
initMap['min-focal-x64']  = initMap['debMap']
initMap['min-jammy-x64']  = initMap['debMap']


capMap = [:]
capMap['c5ad.2xlarge'] = '80'
capMap['m3.2xlarge'] = '60'
capMap['m5zn.2xlarge'] = '80'
capMap['m5zn.3xlarge'] = '80'
capMap['m1.medium'] = '10'

typeMap = [:]
typeMap['micro-amazon'] = 't3a.medium'
typeMap['docker'] = 'm5zn.2xlarge'
typeMap['docker-32gb'] = 'm5zn.3xlarge'
typeMap['min-centos-7-x64'] = typeMap['docker']
typeMap['fips-centos-7-x64'] = typeMap['min-centos-7-x64']
typeMap['min-centos-8-x64'] = typeMap['min-centos-7-x64']
typeMap['min-ol-8-x64'] = typeMap['min-centos-7-x64']
typeMap['min-bionic-x64'] = typeMap['min-centos-7-x64']
typeMap['min-focal-x64'] = typeMap['min-centos-7-x64']
typeMap['min-jammy-x64'] = typeMap['min-centos-7-x64']
typeMap['min-centos-6-x32'] = 'm1.medium'
typeMap['min-centos-6-x64'] = 'm3.2xlarge'
typeMap['min-stretch-x64'] = typeMap['min-centos-7-x64']
typeMap['min-buster-x64'] = typeMap['min-centos-7-x64']
typeMap['min-xenial-x64'] = typeMap['min-centos-7-x64']
typeMap['min-xenial-x32'] = 'm1.medium'
typeMap['min-bullseye-x64'] = typeMap['min-centos-7-x64']

execMap = [:]
execMap['docker'] = '1'
execMap['docker-32gb'] = execMap['docker']
execMap['micro-amazon'] = '30'
execMap['min-bionic-x64'] = '1'
execMap['min-centos-6-x32'] = '1'
execMap['min-centos-6-x64'] = '1'
execMap['min-centos-7-x64'] = '1'
execMap['fips-centos-7-x64'] = '1'
execMap['min-centos-8-x64'] = '1'
execMap['min-ol-8-x64'] = '1'
execMap['min-stretch-x64'] = '1'
execMap['min-buster-x64'] = '1'
execMap['min-xenial-x64'] = '1'
execMap['min-xenial-x32'] = '1'
execMap['min-focal-x64'] = '1'
execMap['min-jammy-x64'] = '1'
execMap['min-bullseye-x64'] = '1'

devMap = [:]
devMap['docker'] = '/dev/xvda=:8:true:gp2,/dev/xvdd=:80:true:gp2'
devMap['docker-32gb'] = devMap['docker']
devMap['micro-amazon'] = devMap['docker']
devMap['min-bionic-x64'] = '/dev/sda1=:30:true:gp2,/dev/sdd=:80:true:gp2'
devMap['min-focal-x64'] = devMap['min-bionic-x64']
devMap['min-jammy-x64'] = devMap['min-bionic-x64']
devMap['min-centos-6-x64'] = devMap['min-bionic-x64']
devMap['min-centos-7-x64'] = devMap['min-bionic-x64']
devMap['fips-centos-7-x64'] = devMap['min-bionic-x64']
devMap['min-centos-8-x64'] = '/dev/sda1=:30:true:gp2,/dev/sdd=:80:true:gp2'
devMap['min-ol-8-x64'] = '/dev/sda1=:30:true:gp2,/dev/sdd=:80:true:gp2'
devMap['min-stretch-x64'] = 'xvda=:30:true:gp2,xvdd=:80:true:gp2'
devMap['min-buster-x64'] = '/dev/xvda=:30:true:gp2,/dev/xvdd=:80:true:gp2'
devMap['min-bullseye-x64'] = '/dev/xvda=:30:true:gp2,/dev/xvdd=:80:true:gp2'
devMap['min-xenial-x64'] = devMap['min-bionic-x64']
devMap['min-xenial-x32'] = '/dev/sda1=:10:false:gp2,/dev/sdd=:80:false:gp2'
devMap['min-centos-6-x32'] = '/dev/sda=:8:true:gp2,/dev/sdd=:80:true:gp2'

labelMap = [:]
labelMap['docker'] = ''
labelMap['docker-32gb'] = ''
labelMap['micro-amazon'] = 'master'
labelMap['min-bionic-x64'] = 'asan'
labelMap['min-focal-x64'] = ''
labelMap['min-jammy-x64'] = ''
labelMap['min-centos-6-x32'] = ''
labelMap['min-centos-6-x64'] = ''
labelMap['min-centos-7-x64'] = ''
labelMap['fips-centos-7-x64'] = ''
labelMap['min-centos-8-x64'] = ''
labelMap['min-ol-8-x64'] = ''
labelMap['min-stretch-x64'] = ''
labelMap['min-buster-x64'] = ''
labelMap['min-xenial-x64'] = ''
labelMap['min-xenial-x32'] = ''
labelMap['min-bullseye-x64'] = ''

// https://github.com/jenkinsci/ec2-plugin/blob/ec2-1.39/src/main/java/hudson/plugins/ec2/SlaveTemplate.java
SlaveTemplate getTemplate(String OSType, String AZ) {
    return new SlaveTemplate(
        imageMap[OSType],                           // String ami
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
            new EC2Tag('Name', 'jenkins-pxb-' + OSType),
            new EC2Tag('iit-billing-tag', 'jenkins-pxb-worker')
        ],                                          // List<EC2Tag> tags
        '3',                                        // String idleTerminationMinutes
        0,                                          // Init minimumNumberOfInstances
        0,                                          // minimumNumberOfSpareInstances
        capMap[typeMap[OSType]],                    // String instanceCapStr
        'arn:aws:iam::119175775298:instance-profile/jenkins-pxb-worker', // String iamInstanceProfile
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
    if (it.hasProperty('cloudName') && it['cloudName'] == 'AWS-Dev a') {
        privateKey = it['privateKey']
    }
}

String sshKeysCredentialsId = '48702e74-c282-45e2-a5a2-518ba58fedf6'

String region = 'us-west-2'
('a'..'a').each {
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
            getTemplate('micro-amazon', "${region}${it}"),
            getTemplate('min-centos-6-x32', "${region}${it}"),
            getTemplate('min-centos-6-x64', "${region}${it}"),
            getTemplate('min-centos-7-x64', "${region}${it}"),
            getTemplate('min-centos-8-x64', "${region}${it}"),
            getTemplate('min-ol-8-x64', "${region}${it}"),
            getTemplate('min-stretch-x64', "${region}${it}"),
            getTemplate('min-buster-x64', "${region}${it}"),
            getTemplate('min-xenial-x64', "${region}${it}"),
            getTemplate('min-xenial-x32', "${region}${it}"),
            getTemplate('min-bionic-x64', "${region}${it}"),
            getTemplate('min-focal-x64', "${region}${it}"),
            getTemplate('min-jammy-x64', "${region}${it}"),
            getTemplate('min-bullseye-x64', "${region}${it}"),
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
