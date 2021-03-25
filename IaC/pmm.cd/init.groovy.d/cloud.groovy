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
netMap['us-east-2b'] = 'subnet-04356480646777b55'
netMap['us-east-2c'] = 'subnet-00b3df129e7d8c658'

imageMap = [:]
imageMap['us-east-2a.min-centos-6-x64'] = 'ami-ff48629a'
imageMap['us-east-2a.min-centos-7-x64'] = 'ami-00f8e2c955f7ffa9b'
imageMap['us-east-2a.min-centos-8-x64'] = 'ami-0ac6967966621d983'
imageMap['us-east-2a.min-focal-x64']    = 'ami-0996d3051b72b5b2c'
imageMap['us-east-2a.min-bionic-x64']   = 'ami-02aa7f3de34db391a'
imageMap['us-east-2a.min-xenial-x64']   = 'ami-0c9c2203357e2568a'
imageMap['us-east-2a.min-buster-x64']   = 'ami-0eec7e5aeb20f40ce'
imageMap['us-east-2a.min-stretch-x64']  = 'ami-0e24a11c7e7dd6435'
imageMap['us-east-2a.micro-amazon']     = 'ami-01aab85a5e4a5a0fe'
imageMap['us-east-2a.large-amazon']     = 'ami-01aab85a5e4a5a0fe'
imageMap['us-east-2a.docker']           = 'ami-01aab85a5e4a5a0fe'

imageMap['us-east-2b.min-centos-6-x64'] = imageMap['us-east-2a.min-centos-6-x64']
imageMap['us-east-2b.min-centos-7-x64'] = imageMap['us-east-2a.min-centos-7-x64']
imageMap['us-east-2b.min-centos-8-x64'] = imageMap['us-east-2a.min-centos-8-x64'] 
imageMap['us-east-2b.min-focal-x64']    = imageMap['us-east-2a.min-focal-x64']
imageMap['us-east-2b.min-bionic-x64']   = imageMap['us-east-2a.min-bionic-x64']
imageMap['us-east-2b.min-xenial-x64']   = imageMap['us-east-2a.min-xenial-x64']
imageMap['us-east-2b.min-buster-x64']   = imageMap['us-east-2a.min-buster-x64']
imageMap['us-east-2b.min-stretch-x64']  = imageMap['us-east-2a.min-stretch-x64']
imageMap['us-east-2b.micro-amazon']     = imageMap['us-east-2a.micro-amazon']
imageMap['us-east-2b.large-amazon']     = imageMap['us-east-2a.large-amazon']
imageMap['us-east-2b.docker']           = imageMap['us-east-2a.docker']

imageMap['us-east-2c.min-centos-6-x64'] = imageMap['us-east-2a.min-centos-6-x64']
imageMap['us-east-2c.min-centos-7-x64'] = imageMap['us-east-2a.min-centos-7-x64']
imageMap['us-east-2c.min-centos-8-x64'] = imageMap['us-east-2a.min-centos-8-x64'] 
imageMap['us-east-2c.min-focal-x64']    = imageMap['us-east-2a.min-focal-x64']
imageMap['us-east-2c.min-bionic-x64']   = imageMap['us-east-2a.min-bionic-x64']
imageMap['us-east-2c.min-xenial-x64']   = imageMap['us-east-2a.min-xenial-x64']
imageMap['us-east-2c.min-buster-x64']   = imageMap['us-east-2a.min-buster-x64']
imageMap['us-east-2c.min-stretch-x64']  = imageMap['us-east-2a.min-stretch-x64']
imageMap['us-east-2c.micro-amazon']     = imageMap['us-east-2a.micro-amazon']
imageMap['us-east-2c.large-amazon']     = imageMap['us-east-2a.large-amazon']
imageMap['us-east-2c.docker']           = imageMap['us-east-2a.docker']

priceMap = [:]
priceMap['t2.large']  = '0.04'
priceMap['t3.xlarge'] = '0.06'
priceMap['t3.large']  = '0.03'
priceMap['m4.large']  = '0.03'

userMap = [:]
userMap['min-centos-6-x64']  = 'centos'
userMap['min-centos-7-x64']  = userMap['min-centos-6-x64']
userMap['min-centos-8-x64']  = 'centos'
userMap['min-focal-x64']     = 'ubuntu'
userMap['min-bionic-x64']    = 'ubuntu'
userMap['min-xenial-x64']    = 'ubuntu'
userMap['min-buster-x64']    = 'admin'
userMap['min-stretch-x64']   = 'admin'
userMap['micro-amazon']      = 'ec2-user'
userMap['large-amazon']      = userMap['micro-amazon']
userMap['docker']            = userMap['micro-amazon']

initMap = [:]
initMap['min-centos-6-x64'] = '''
    set -o xtrace
    RHVER="$(rpm --eval %rhel)"

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

    if [[ $RHVER == 6 ]]; then
        sudo curl https://jenkins.percona.com/downloads/cent6/centos6-eol.repo --output /etc/yum.repos.d/CentOS-Base.repo
    fi

    until sudo yum makecache; do
        sleep 1
        echo try again
    done

    if [[ $RHVER == 6 ]]; then
        until sudo yum -y install epel-release centos-release-scl; do    
            sleep 1
            echo try again
        done
        sudo rm /etc/yum.repos.d/epel-testing.repo
        sudo curl https://jenkins.percona.com/downloads/cent6/centos6-epel-eol.repo --output /etc/yum.repos.d/epel.repo
        sudo curl https://jenkins.percona.com/downloads/cent6/centos6-scl-eol.repo --output /etc/yum.repos.d/CentOS-SCLo-scl.repo
        sudo curl https://jenkins.percona.com/downloads/cent6/centos6-scl-rh-eol.repo --output /etc/yum.repos.d/CentOS-SCLo-scl-rh.repo
    fi


    sudo yum -y install java-1.8.0-openjdk git wget curl || :
    sudo yum -y remove java-1.7.0-openjdk || :
    sudo install -o $(id -u -n) -g $(id -g -n) -d /mnt/jenkins
'''

initMap['min-centos-7-x64'] = initMap['min-centos-6-x64']
initMap['min-centos-8-x64'] = initMap['min-centos-6-x64']
initMap['min-focal-x64'] = '''
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
initMap['min-bionic-x64'] = initMap['min-focal-x64']
initMap['min-xenial-x64'] = initMap['min-focal-x64']
initMap['min-stretch-x64'] = '''
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
    sudo apt-get -y install openjdk-8-jre-headless git
    sudo install -o $(id -u -n) -g $(id -g -n) -d /mnt/jenkins
'''
initMap['min-buster-x64']   = '''
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
initMap['micro-amazon']     = '''
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

    printf "127.0.0.1 $(hostname) $(hostname -A)
    10.30.6.220 vbox-01.ci.percona.com
    10.30.6.9 repo.ci.percona.com
    "     | sudo tee -a /etc/hosts

    until sudo yum makecache; do
        sleep 1
        echo try again
    done

    sudo yum -y install java-1.8.0-openjdk git aws-cli tar coreutils || :
    sudo yum -y remove java-1.7.0-openjdk || :
    sudo install -o $(id -u -n) -g $(id -g -n) -d /mnt/jenkins
'''
initMap['large-amazon']     = '''
    set -o xtrace

    printf "127.0.0.1 $(hostname) $(hostname -A)
    10.30.6.220 vbox-01.ci.percona.com
    10.30.6.9 repo.ci.percona.com
    "     | sudo tee -a /etc/hosts

    until sudo yum makecache; do
        sleep 1
        echo try again
    done

    sudo yum -y install java-1.8.0-openjdk git aws-cli tar coreutils || :
    sudo yum -y remove java-1.7.0-openjdk || :
    sudo install -o $(id -u -n) -g $(id -g -n) -d /mnt/jenkins
'''
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
    printf "127.0.0.1 $(hostname) $(hostname -A)
    10.30.6.220 vbox-01.ci.percona.com
    10.30.6.9 repo.ci.percona.com
    "     | sudo tee -a /etc/hosts

    until sudo yum makecache; do
        sleep 1
        echo try again
    done
    sudo amazon-linux-extras install epel -y
    sudo yum -y install java-1.8.0-openjdk git docker p7zip
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
    echo '{"experimental": true, "ipv6": true, "fixed-cidr-v6": "fd3c:a8b0:18eb:5c06::/64"}' | sudo tee /etc/docker/daemon.json
    sudo systemctl status docker || sudo systemctl start docker
    sudo service docker status || sudo service docker start
    echo "* * * * * root /usr/sbin/route add default gw 10.199.1.1 eth0" | sudo tee /etc/cron.d/fix-default-route
'''

capMap = [:]
capMap['t2.large']   = '20'
capMap['t3.xlarge']  = '20'
capMap['t3.large']   = '20'
capMap['m4.large']   = '10'

typeMap = [:]
typeMap['min-centos-6-x64']  = 'm4.large'
typeMap['min-centos-7-x64']  = typeMap['min-centos-6-x64']
typeMap['min-centos-8-x64']  = typeMap['min-centos-6-x64']
typeMap['min-focal-x64']     = typeMap['min-centos-6-x64']
typeMap['min-bionic-x64']    = typeMap['min-centos-6-x64']
typeMap['min-xenial-x64']    = typeMap['min-centos-6-x64']
typeMap['min-buster-x64']    = typeMap['min-centos-6-x64']
typeMap['min-stretch-x64']   = typeMap['min-centos-6-x64']
typeMap['micro-amazon']      = 't3.large'
typeMap['large-amazon']      = 't3.xlarge'
typeMap['docker']            = 't3.large'

execMap = [:]
execMap['min-centos-6-x64']  = '1'
execMap['min-centos-7-x64']  = '1'
execMap['min-centos-8-x64']  = '1'
execMap['min-focal-x64']     = '1'
execMap['min-bionic-x64']    = '1'
execMap['min-xenial-x64']    = '1'
execMap['min-buster-x64']    = '1'
execMap['min-stretch-x64']   = '1'
execMap['micro-amazon']      = '4'
execMap['large-amazon']      = '1'
execMap['docker']            = '1'

devMap = [:]
devMap['min-centos-6-x64']  = '/dev/sda1=:8:true:gp2,/dev/sdd=:80:true:gp2'
devMap['min-centos-7-x64']  = devMap['min-centos-6-x64']
devMap['min-centos-8-x64']  = '/dev/sda1=:10:true:gp2,/dev/sdd=:80:true:gp2'
devMap['min-focal-x64']     = '/dev/sda1=:8:true:gp2,/dev/sdd=:80:true:gp2'
devMap['min-bionic-x64']    = devMap['min-focal-x64']
devMap['min-xenial-x64']    = devMap['min-focal-x64']
devMap['min-buster-x64']    = '/dev/xvda=:8:true:gp2,/dev/xvdd=:80:true:gp2'
devMap['min-stretch-x64']   = '/dev/xvda=:8:true:gp2,/dev/xvdd=:80:true:gp2'
devMap['micro-amazon']      = '/dev/xvda=:8:true:gp2,/dev/xvdd=:120:true:gp2'
devMap['large-amazon']      = '/dev/xvda=:100:true:gp2'
devMap['docker']            = '/dev/xvda=:8:true:gp2,/dev/xvdd=:80:true:gp2'

labelMap = [:]
labelMap['min-centos-6-x64']  = 'min-centos-6-x64'
labelMap['min-centos-7-x64']  = 'min-centos-7-x64'
labelMap['min-centos-8-x64']  = 'min-centos-8-x64'
labelMap['min-focal-x64']     = 'min-focal-x64'
labelMap['min-bionic-x64']    = 'min-bionic-x64'
labelMap['min-xenial-x64']    = 'min-xenial-x64'
labelMap['min-buster-x64']    = 'min-buster-x64'
labelMap['min-stretch-x64']   = 'min-stretch-x64'
labelMap['micro-amazon']      = 'micro-amazon nodejs master awscli'
labelMap['large-amazon']      = 'large-amazon'
labelMap['docker']            = ''

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
            new EC2Tag('Name', 'jenkins-pmm-' + OSType),
            new EC2Tag('iit-billing-tag', 'jenkins-pmm-slave')
        ],                                          // List<EC2Tag> tags
        '10',                                        // String idleTerminationMinutes
        0,                                          // Init minimumNumberOfInstances
        0,                                          // minimumNumberOfSpareInstances
        capMap[typeMap[OSType]],                    // String instanceCapStr
        '',                                         // String iamInstanceProfile
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
        1,                                          // int maxTotalUses
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

String sshKeysCredentialsId = '9498028f-01d9-4066-b45c-6c813d51d11b'

String region = 'us-east-2'
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
            getTemplate('min-centos-6-x64',     "${region}${it}"),
            getTemplate('min-centos-7-x64',     "${region}${it}"),
            getTemplate('min-centos-8-x64',     "${region}${it}"),
            getTemplate('min-focal-x64',        "${region}${it}"),
            getTemplate('min-bionic-x64',       "${region}${it}"),
            getTemplate('min-xenial-x64',       "${region}${it}"),
            getTemplate('min-buster-x64',       "${region}${it}"),
            getTemplate('min-stretch-x64',      "${region}${it}"),
            getTemplate('micro-amazon',         "${region}${it}"),
            getTemplate('large-amazon',         "${region}${it}"),
            getTemplate('docker'),              "${region}${it}"),
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

