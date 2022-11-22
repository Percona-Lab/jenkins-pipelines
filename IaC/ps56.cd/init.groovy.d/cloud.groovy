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
netMap['us-west-1b'] = 'subnet-016104ddcdfbf521b'
netMap['us-west-1c'] = 'subnet-08c73ba89640dfa60'

imageMap = [:]
imageMap['us-west-1a.docker']            = 'ami-061352bb71c4724b2'
imageMap['us-west-1a.docker-32gb']       = 'ami-061352bb71c4724b2'
imageMap['us-west-1a.micro-amazon']      = 'ami-061352bb71c4724b2'
imageMap['us-west-1a.min-ol-8-x64']      = 'ami-01c58ced87b56372e'
imageMap['us-west-1a.min-ol-9-x64']      = 'ami-0d1958c85fb6a7b3e'
imageMap['us-west-1a.min-centos-7-x64']  = 'ami-08d2d8b00f270d03b'
imageMap['us-west-1a.fips-centos-7-x64'] = 'ami-0f472ecc4a3e9620c'
imageMap['us-west-1a.min-centos-6-x64']  = 'ami-ade6e5cd'
imageMap['us-west-1a.min-bullseye-x64']  = 'ami-09b4378b1d3387f81'
imageMap['us-west-1a.min-buster-x64']    = 'ami-0462fab5ea5a8f709'
imageMap['us-west-1a.min-stretch-x64']   = 'ami-0e6b87a6c1ea9add1'
imageMap['us-west-1a.min-jammy-x64']     = 'ami-0dc5e9ff792ec08e3'
imageMap['us-west-1a.min-bionic-x64']    = 'ami-0558dde970ca91ee5'
imageMap['us-west-1a.min-xenial-x64']    = 'ami-0ce448b1704085256'

imageMap['us-west-1b.docker']            = imageMap['us-west-1a.docker']
imageMap['us-west-1b.docker-32gb']       = imageMap['us-west-1a.docker-32gb']
imageMap['us-west-1b.micro-amazon']      = imageMap['us-west-1a.micro-amazon']
imageMap['us-west-1b.min-ol-8-x64']      = imageMap['us-west-1a.min-ol-8-x64']
imageMap['us-west-1b.min-ol-9-x64']      = imageMap['us-west-1a.min-ol-9-x64']
imageMap['us-west-1b.min-centos-7-x64']  = imageMap['us-west-1a.min-centos-7-x64']
imageMap['us-west-1b.fips-centos-7-x64'] = imageMap['us-west-1a.fips-centos-7-x64']
imageMap['us-west-1b.min-centos-6-x64']  = imageMap['us-west-1a.min-centos-6-x64']
imageMap['us-west-1b.min-bullseye-x64']  = imageMap['us-west-1a.min-bullseye-x64']
imageMap['us-west-1b.min-buster-x64']    = imageMap['us-west-1a.min-buster-x64']
imageMap['us-west-1b.min-jammy-x64']     = imageMap['us-west-1a.min-jammy-x64']
imageMap['us-west-1b.min-bionic-x64']    = imageMap['us-west-1a.min-bionic-x64']
imageMap['us-west-1b.min-stretch-x64']   = imageMap['us-west-1a.min-stretch-x64']
imageMap['us-west-1b.min-xenial-x64']    = imageMap['us-west-1a.min-xenial-x64']

imageMap['us-west-1c.docker']            = imageMap['us-west-1a.docker']
imageMap['us-west-1c.docker-32gb']       = imageMap['us-west-1a.docker-32gb']
imageMap['us-west-1c.micro-amazon']      = imageMap['us-west-1a.micro-amazon']
imageMap['us-west-1c.min-ol-8-x64']      = imageMap['us-west-1a.min-ol-8-x64']
imageMap['us-west-1c.min-ol-9-x64']      = imageMap['us-west-1a.min-ol-9-x64']
imageMap['us-west-1c.min-centos-7-x64']  = imageMap['us-west-1a.min-centos-7-x64']
imageMap['us-west-1c.fips-centos-7-x64'] = imageMap['us-west-1a.fips-centos-7-x64']
imageMap['us-west-1c.min-centos-6-x64']  = imageMap['us-west-1a.min-centos-6-x64']
imageMap['us-west-1c.min-bullseye-x64']  = imageMap['us-west-1a.min-bullseye-x64']
imageMap['us-west-1c.min-buster-x64']    = imageMap['us-west-1a.min-buster-x64']
imageMap['us-west-1c.min-jammy-x64']     = imageMap['us-west-1a.min-jammy-x64']
imageMap['us-west-1c.min-bionic-x64']    = imageMap['us-west-1a.min-bionic-x64']
imageMap['us-west-1c.min-stretch-x64']   = imageMap['us-west-1a.min-stretch-x64']
imageMap['us-west-1c.min-xenial-x64']    = imageMap['us-west-1a.min-xenial-x64']

priceMap = [:]
priceMap['t2.small'] = '0.02'
priceMap['m1.medium'] = '0.05'
priceMap['c4.xlarge'] = '0.11'
priceMap['m4.xlarge'] = '0.10'
priceMap['m4.2xlarge'] = '0.20'
priceMap['m5d.2xlarge'] = '0.20'

userMap = [:]
userMap['docker']            = 'ec2-user'
userMap['docker-32gb']       = userMap['docker']
userMap['micro-amazon']      = userMap['docker']
userMap['min-jammy-x64']     = 'ubuntu'
userMap['min-bionic-x64']    = 'ubuntu'
userMap['min-xenial-x64']    = 'ubuntu'
userMap['min-centos-6-x32']  = 'root'
userMap['min-centos-6-x64']  = 'centos'
userMap['min-centos-7-x64']  = 'centos'
userMap['fips-centos-7-x64'] = 'centos'
userMap['min-ol-8-x64']      = 'ec2-user'
userMap['min-ol-9-x64']      = 'ec2-user'
userMap['min-stretch-x64']   = 'admin'
userMap['min-buster-x64']    = 'admin'
userMap['min-bullseye-x64']  = 'admin'

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
    echo '{"experimental": true, "ipv6": true, "fixed-cidr-v6": "fd3c:a8b0:18eb:5c06::/64"}' | sudo tee /etc/docker/daemon.json
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
    else
        PKGLIST="aws-cli"
    fi

    until sudo yum makecache; do
        sleep 1
        echo try again
    done

    sudo yum -y install java-11-openjdk || :
    sudo yum -y install git || :
    sudo yum -y install ${PKGLIST} || :
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

            7za -aoa -o/tmp x /tmp/awscliv2.zip 
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
        JAVA_VER="openjdk-11-jre-headless"
    fi
    sudo apt-get -y install ${JAVA_VER} git
    sudo install -o $(id -u -n) -g $(id -g -n) -d /mnt/jenkins
'''

initMap['micro-amazon']      = initMap['rpmMap']
initMap['min-centos-6-x64']  = initMap['rpmMap']
initMap['min-centos-7-x64']  = initMap['rpmMap']
initMap['fips-centos-7-x64'] = initMap['rpmMap']
initMap['min-ol-8-x64']      = initMap['rpmMap']
initMap['min-ol-9-x64']      = initMap['rpmMap']
initMap['min-centos-6-x32']  = initMap['rpmMap']

initMap['min-bullseye-x64'] = initMap['debMap']
initMap['min-buster-x64']   = initMap['debMap']
initMap['min-jammy-x64']    = initMap['debMap']
initMap['min-bionic-x64']   = initMap['debMap']
initMap['min-stretch-x64']  = initMap['debMap']
initMap['min-xenial-x64']   = initMap['debMap']

capMap = [:]
capMap['c4.xlarge']  = '60'
capMap['m4.xlarge']  = '60'
capMap['m4.2xlarge'] = '10'

typeMap = [:]
typeMap['micro-amazon']      = 't2.small'
typeMap['docker']            = 'c4.xlarge'
typeMap['docker-32gb']       = 'm4.2xlarge'

typeMap['min-centos-7-x64']  = typeMap['docker']
typeMap['fips-centos-7-x64'] = typeMap['min-centos-7-x64']
typeMap['min-ol-8-x64']      = typeMap['min-centos-7-x64']
typeMap['min-ol-9-x64']      = typeMap['min-centos-7-x64']
typeMap['min-jammy-x64']     = typeMap['min-centos-7-x64']
typeMap['min-bionic-x64']    = typeMap['min-centos-7-x64']
typeMap['min-buster-x64']    = typeMap['min-centos-7-x64']
typeMap['min-bullseye-x64']  = typeMap['min-centos-7-x64']
typeMap['min-centos-6-x32']  = 'm1.medium'
typeMap['min-centos-6-x64']  = 'm4.xlarge'
typeMap['min-stretch-x64']   = typeMap['docker']
typeMap['min-xenial-x64']    = typeMap['docker']

execMap = [:]
execMap['docker']            = '1'
execMap['docker-32gb']       = execMap['docker']

execMap['micro-amazon']      = '30'
execMap['min-jammy-x64']     = '1'
execMap['min-bionic-x64']    = '1'
execMap['min-centos-6-x32']  = '1'
execMap['min-centos-6-x64']  = '1'
execMap['min-centos-7-x64']  = '1'
execMap['fips-centos-7-x64'] = '1'
execMap['min-ol-8-x64']      = '1'
execMap['min-ol-9-x64']      = '1'
execMap['min-stretch-x64']   = '1'
execMap['min-xenial-x64']    = '1'
execMap['min-buster-x64']    = '1'
execMap['min-bullseye-x64']  = '1'

devMap = [:]
devMap['docker']            = '/dev/xvda=:8:true:gp2,/dev/xvdd=:80:true:gp2'

devMap['docker-32gb']       = devMap['docker']
devMap['micro-amazon']      = devMap['docker']
devMap['min-bionic-x64']    = '/dev/sda1=:8:true:gp2,/dev/sdd=:80:true:gp2'
devMap['min-jammy-x64']     = devMap['min-bionic-x64']
devMap['min-centos-6-x64']  = devMap['min-bionic-x64']
devMap['min-centos-7-x64']  = devMap['min-bionic-x64']
devMap['fips-centos-7-x64'] = devMap['min-bionic-x64']
devMap['min-ol-8-x64']      = devMap['min-bionic-x64']
devMap['min-ol-9-x64']      = devMap['min-bionic-x64']
devMap['min-stretch-x64']   = 'xvda=:8:true:gp2,xvdd=:80:true:gp2'
devMap['min-xenial-x64']    = devMap['min-bionic-x64']
devMap['min-centos-6-x32']  = '/dev/sda=:8:true:gp2,/dev/sdd=:80:true:gp2'
devMap['min-buster-x64']    = '/dev/xvda=:8:true:gp2,/dev/xvdd=:80:true:gp2'
devMap['min-bullseye-x64']  = '/dev/xvda=:8:true:gp2,/dev/xvdd=:80:true:gp2'

labelMap = [:]
labelMap['docker']            = ''
labelMap['docker-32gb']       = ''

labelMap['micro-amazon']      = 'master'
labelMap['min-bionic-x64']    = 'asan'
labelMap['min-jammy-x64']     = ''
labelMap['min-centos-6-x32']  = ''
labelMap['min-centos-6-x64']  = ''
labelMap['min-centos-7-x64']  = ''
labelMap['fips-centos-7-x64'] = ''
labelMap['min-ol-8-x64']      = ''
labelMap['min-ol-9-x64']      = ''
labelMap['min-stretch-x64']   = ''
labelMap['min-xenial-x64']    = ''
labelMap['min-buster-x64']    = ''
labelMap['min-bullseye-x64']  = ''

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
            new EC2Tag('Name', 'jenkins-ps56-' + OSType),
            new EC2Tag('iit-billing-tag', 'jenkins-ps56-worker')
        ],                                          // List<EC2Tag> tags
        '3',                                        // String idleTerminationMinutes
        0,                                          // Init minimumNumberOfInstances
        0,                                          // minimumNumberOfSpareInstances
        capMap[typeMap[OSType]],                    // String instanceCapStr
        'arn:aws:iam::119175775298:instance-profile/jenkins-ps56-worker', // String iamInstanceProfile
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
    if (it.hasProperty('cloudName') && it['cloudName'] == 'AWS-Dev c') {
        privateKey = it['privateKey']
    }
}

String sshKeysCredentialsId = 'b8757ec6-73d6-4062-93b4-acf7972681c1'

String region = 'us-west-1'
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
            getTemplate('docker',             "${region}${it}"),
            getTemplate('docker-32gb',        "${region}${it}"),
            getTemplate('micro-amazon',       "${region}${it}"),
            getTemplate('min-ol-8-x64',       "${region}${it}"),
            getTemplate('min-ol-9-x64',       "${region}${it}"),
            getTemplate('min-centos-7-x64',   "${region}${it}"),
            getTemplate('fips-centos-7-x64',  "${region}${it}"),
            getTemplate('min-centos-6-x64',   "${region}${it}"),
            getTemplate('min-jammy-x64' ,     "${region}${it}"),
            getTemplate('min-bionic-x64',     "${region}${it}"),
            getTemplate('min-bullseye-x64',   "${region}${it}"),
            getTemplate('min-buster-x64',     "${region}${it}"),
            getTemplate('min-stretch-x64',    "${region}${it}"),
            getTemplate('min-xenial-x64',     "${region}${it}"),
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
