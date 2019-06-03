import com.amazonaws.services.ec2.model.InstanceType
import hudson.model.*
import hudson.plugins.ec2.AmazonEC2Cloud
import hudson.plugins.ec2.EC2Tag
import hudson.plugins.ec2.SlaveTemplate
import hudson.plugins.ec2.SpotConfiguration
import hudson.plugins.ec2.ConnectionStrategy
import hudson.plugins.ec2.UnixData
import java.util.logging.Logger
import jenkins.model.Jenkins

def logger = Logger.getLogger("")
logger.info("Cloud init started")

// get Jenkins instance
Jenkins jenkins = Jenkins.getInstance()

netMap = [:]
netMap['eu-west-1b'] = 'subnet-05f58e38549072404'
netMap['eu-west-1c'] = 'subnet-0b5ee1ef341aca9db'

imageMap = [:]
imageMap['eu-west-1a.docker'] = 'ami-0bfe21f21a54b82f9'
imageMap['eu-west-1a.docker-32gb'] = 'ami-0bfe21f21a54b82f9'
imageMap['eu-west-1a.docker2'] = 'ami-0bfe21f21a54b82f9'
imageMap['eu-west-1a.micro-amazon'] = 'ami-0bfe21f21a54b82f9'
imageMap['eu-west-1a.min-centos-7-x64'] = 'ami-0ff760d16d9497662'
imageMap['eu-west-1a.fips-centos-7-x64'] = 'ami-0ff760d16d9497662'

imageMap['eu-west-1b.docker'] = imageMap['eu-west-1a.docker']
imageMap['eu-west-1b.docker-32gb'] = imageMap['eu-west-1a.docker-32gb']
imageMap['eu-west-1b.docker2'] = imageMap['eu-west-1a.docker2']
imageMap['eu-west-1b.micro-amazon'] = imageMap['eu-west-1a.micro-amazon']
imageMap['eu-west-1b.min-centos-7-x64'] = imageMap['eu-west-1a.min-centos-7-x64']
imageMap['eu-west-1b.fips-centos-7-x64'] = imageMap['eu-west-1a.fips-centos-7-x64']

imageMap['eu-west-1c.docker'] = imageMap['eu-west-1a.docker']
imageMap['eu-west-1c.docker-32gb'] = imageMap['eu-west-1a.docker-32gb']
imageMap['eu-west-1c.docker2'] = imageMap['eu-west-1a.docker2']
imageMap['eu-west-1c.micro-amazon'] = imageMap['eu-west-1a.micro-amazon']
imageMap['eu-west-1c.min-centos-7-x64'] = imageMap['eu-west-1a.min-centos-7-x64']
imageMap['eu-west-1c.fips-centos-7-x64'] = imageMap['eu-west-1a.fips-centos-7-x64']

/*
imageMap['min-artful-x64'] = 'ami-db2919be'
imageMap['min-centos-6-x64'] = 'ami-ff48629a'
imageMap['min-jessie-x64'] = 'ami-c5ba9fa0'
imageMap['min-stretch-x64'] = 'ami-79c0f01c'
imageMap['min-trusty-x64'] = 'ami-2ddeee48'
imageMap['min-xenial-x64'] = 'ami-e82a1a8d'
*/

priceMap = [:]
priceMap['t2.small'] = '0.01'
priceMap['m1.medium'] = '0.05'
priceMap['c4.xlarge'] = '0.10'
priceMap['m4.xlarge'] = '0.10'
priceMap['m4.2xlarge'] = '0.20'
priceMap['r4.4xlarge'] = '0.38'
priceMap['m5d.2xlarge'] = '0.20'
priceMap['c5d.xlarge'] = '0.20'

userMap = [:]
userMap['docker'] = 'ec2-user'
userMap['docker-32gb'] = userMap['docker']
userMap['docker2'] = userMap['docker']
userMap['micro-amazon'] = userMap['docker']
userMap['min-artful-x64'] = 'ubuntu'
userMap['min-bionic-x64'] = 'ubuntu'
userMap['min-centos-6-x32'] = 'root'
userMap['min-centos-6-x64'] = 'centos'
userMap['min-centos-7-x64'] = 'centos'
userMap['fips-centos-7-x64'] = 'centos'
userMap['min-jessie-x64'] = 'admin'
userMap['min-stretch-x64'] = 'admin'
userMap['min-trusty-x64'] = 'ubuntu'
userMap['min-xenial-x64'] = 'ubuntu'
userMap['psmdb'] = userMap['min-xenial-x64']

initMap = [:]
initMap['docker'] = '''
    set -o xtrace

    if ! mountpoint -q /mnt; then
        DEVICE=$(ls /dev/xvdd /dev/xvdh /dev/nvme1n1 | head -1)
        sudo mkfs.ext4 ${DEVICE}
        sudo mount -o noatime ${DEVICE} /mnt
    fi
    sudo ethtool -K eth0 sg off
    until sudo yum makecache; do
        sleep 1
        echo try again
    done
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
    echo '{"experimental": true, "ipv6": true, "fixed-cidr-v6": "fd3c:a8b0:18eb:5c06::/64"}' | sudo tee /etc/docker/daemon.json
    sudo systemctl status docker || sudo systemctl start docker
    sudo service docker status || sudo service docker start
    echo "* * * * * root /usr/sbin/route add default gw 10.177.1.1 eth0" | sudo tee /etc/cron.d/fix-default-route
'''
initMap['docker-32gb'] = initMap['docker']
initMap['docker2'] = initMap['docker']
initMap['micro-amazon'] = '''
    set -o xtrace
    if ! mountpoint -q /mnt; then
        DEVICE=$(ls /dev/xvdd /dev/xvdh /dev/nvme1n1 | head -1)
        sudo mkfs.ext2 ${DEVICE}
        sudo mount ${DEVICE} /mnt
    fi
    until sudo yum makecache; do
        sleep 1
        echo try again
    done
    sudo yum -y install java-1.8.0-openjdk git aws-cli || :
    sudo yum -y remove java-1.7.0-openjdk || :
    sudo install -o $(id -u -n) -g $(id -g -n) -d /mnt/jenkins
'''
initMap['min-centos-6-x64'] = initMap['micro-amazon']
initMap['min-centos-7-x64'] = initMap['micro-amazon']
initMap['fips-centos-7-x64'] = initMap['micro-amazon']
initMap['min-centos-6-x32'] = '''
    set -o xtrace
    if ! mountpoint -q /mnt; then
        DEVICE=$(ls /dev/xvdd /dev/xvdh /dev/nvme1n1 | head -1)
        sudo mkfs.ext2 ${DEVICE}
        sudo mount ${DEVICE} /mnt
    fi
    until sudo yum makecache; do
        sleep 1
        echo try again
    done
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
initMap['min-artful-x64'] = '''
    set -o xtrace
    if ! mountpoint -q /mnt; then
        DEVICE=$(ls /dev/xvdd /dev/xvdh /dev/nvme1n1 | head -1)
        sudo mkfs.ext2 ${DEVICE}
        sudo mount ${DEVICE} /mnt
    fi
    until sudo apt-get update; do
        sleep 1
        echo try again
    done
    sudo apt-get -y install openjdk-8-jre-headless git
    sudo install -o $(id -u -n) -g $(id -g -n) -d /mnt/jenkins
'''
initMap['min-bionic-x64'] = initMap['min-artful-x64']
initMap['min-stretch-x64'] = initMap['min-artful-x64']
initMap['min-xenial-x64'] = initMap['min-artful-x64']
initMap['psmdb'] = initMap['min-xenial-x64']
initMap['min-jessie-x64'] = '''
    set -o xtrace
    if ! mountpoint -q /mnt; then
        DEVICE=$(ls /dev/xvdd /dev/xvdh /dev/nvme1n1 | head -1)
        sudo mkfs.ext2 ${DEVICE}
        sudo mount ${DEVICE} /mnt
    fi
    until sudo apt-get update; do
        sleep 1
        echo try again
    done
    sudo apt-get -y install git wget
    wget https://jenkins.percona.com/downloads/jre/jre-8u152-linux-x64.tar.gz
    sudo tar -zxf jre-8u152-linux-x64.tar.gz -C /usr/local
    sudo ln -s /usr/local/jre1.8.0_152 /usr/local/java
    sudo ln -s /usr/local/jre1.8.0_152/bin/java /usr/bin/java
    rm -fv jre-8u152-linux-x64.tar.gz
    sudo install -o $(id -u -n) -g $(id -g -n) -d /mnt/jenkins
'''
initMap['min-trusty-x64'] = initMap['min-jessie-x64']

capMap = [:]
capMap['c4.xlarge'] = '60'
capMap['m4.xlarge'] = '5'
capMap['m4.2xlarge'] = '40'
capMap['r4.4xlarge'] = '40'
capMap['c5d.xlarge'] = '10'

typeMap = [:]
typeMap['micro-amazon'] = 't2.small'
typeMap['docker'] = 'c4.xlarge'
typeMap['docker-32gb'] = 'm4.2xlarge'
typeMap['docker2'] = 'r4.4xlarge'
typeMap['min-centos-7-x64'] = typeMap['docker']
typeMap['fips-centos-7-x64'] = typeMap['min-centos-7-x64']
typeMap['min-artful-x64'] = typeMap['min-centos-7-x64']
typeMap['min-bionic-x64'] = typeMap['min-centos-7-x64']
typeMap['min-centos-6-x32'] = 'm1.medium'
typeMap['min-centos-6-x64'] = 'm4.xlarge'
typeMap['min-jessie-x64'] = typeMap['min-centos-6-x64']
typeMap['min-stretch-x64'] = typeMap['min-centos-7-x64']
typeMap['min-trusty-x64'] = typeMap['min-centos-7-x64']
typeMap['min-xenial-x64'] = typeMap['min-centos-7-x64']
typeMap['psmdb'] = typeMap['docker-32gb']

execMap = [:]
execMap['docker'] = '1'
execMap['docker-32gb'] = execMap['docker']
execMap['docker2'] = execMap['docker']
execMap['micro-amazon'] = '30'
execMap['min-artful-x64'] = '1'
execMap['min-bionic-x64'] = '1'
execMap['min-centos-6-x32'] = '1'
execMap['min-centos-6-x64'] = '1'
execMap['min-centos-7-x64'] = '1'
execMap['fips-centos-7-x64'] = '1'
execMap['min-jessie-x64'] = '1'
execMap['min-stretch-x64'] = '1'
execMap['min-trusty-x64'] = '1'
execMap['min-xenial-x64'] = '1'
execMap['psmdb'] = '1'

devMap = [:]
devMap['docker'] = '/dev/xvda=:8:true:gp2,/dev/xvdd=:80:true:gp2'
devMap['docker2'] = '/dev/xvda=:8:true:gp2,/dev/xvdd=:80:true:gp2'
devMap['docker-32gb'] = devMap['docker']
devMap['micro-amazon'] = devMap['docker']
devMap['min-artful-x64'] = '/dev/sda1=:8:true:gp2,/dev/sdd=:80:true:gp2'
devMap['min-bionic-x64'] = devMap['min-artful-x64']
devMap['min-centos-6-x64'] = devMap['min-artful-x64']
devMap['min-centos-7-x64'] = devMap['min-artful-x64']
devMap['fips-centos-7-x64'] = devMap['min-artful-x64']
devMap['min-jessie-x64'] = devMap['micro-amazon']
devMap['min-stretch-x64'] = 'xvda=:8:true:gp2,xvdd=:80:true:gp2'
devMap['min-trusty-x64'] = devMap['min-artful-x64']
devMap['min-xenial-x64'] = devMap['min-artful-x64']
devMap['min-centos-6-x32'] = '/dev/sda=:8:true:gp2,/dev/sdd=:80:true:gp2'
devMap['psmdb'] = '/dev/sda1=:8:true:gp2,/dev/sdd=:160:true:gp2'

labelMap = [:]
labelMap['docker'] = ''
labelMap['docker-32gb'] = ''
labelMap['docker2'] = 'docker-32gb'
labelMap['micro-amazon'] = 'master'
labelMap['min-artful-x64'] = ''
labelMap['min-bionic-x64'] = 'asan'
labelMap['min-centos-6-x32'] = ''
labelMap['min-centos-6-x64'] = ''
labelMap['min-centos-7-x64'] = ''
labelMap['fips-centos-7-x64'] = ''
labelMap['min-jessie-x64'] = ''
labelMap['min-stretch-x64'] = ''
labelMap['min-trusty-x64'] = ''
labelMap['min-xenial-x64'] = ''
labelMap['psmdb'] = ''

// https://github.com/jenkinsci/ec2-plugin/blob/ec2-1.41/src/main/java/hudson/plugins/ec2/SlaveTemplate.java
SlaveTemplate getTemplate(String OSType, String AZ) {
    return new SlaveTemplate(
        imageMap[AZ + '.' + OSType],                // String ami
        '',                                         // String zone
        new SpotConfiguration(true, priceMap[typeMap[OSType]], false), // SpotConfiguration spotConfig
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
            new EC2Tag('Name', 'jenkins-fb-' + OSType),
            new EC2Tag('iit-billing-tag', 'jenkins-fb-worker')
        ],                                          // List<EC2Tag> tags
        '3',                                        // String idleTerminationMinutes
        capMap[typeMap[OSType]],                    // String instanceCapStr
        'arn:aws:iam::119175775298:instance-profile/jenkins-fb-worker', // String iamInstanceProfile
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
    )}

String privateKey = ''
jenkins.clouds.each {
    if (it.hasProperty('cloudName') && it['cloudName'] == 'AWS-Dev b') {
        privateKey = it['privateKey']
    }
}

String region = 'eu-west-1'
('b'..'c').each {
    // https://github.com/jenkinsci/ec2-plugin/blob/ec2-1.41/src/main/java/hudson/plugins/ec2/AmazonEC2Cloud.java
    AmazonEC2Cloud ec2Cloud = new AmazonEC2Cloud(
        "AWS-Dev ${it}",                        // String cloudName
        true,                                   // boolean useInstanceProfileForCredentials
        '',                                     // String credentialsId
        region,                                 // String region
        privateKey,                             // String privateKey
        '240',                                   // String instanceCapStr
        [
            getTemplate('docker',           "${region}${it}"),
            getTemplate('docker-32gb',      "${region}${it}"),
            getTemplate('docker2',          "${region}${it}"),
            getTemplate('micro-amazon',     "${region}${it}"),
            getTemplate('min-centos-7-x64', "${region}${it}"),
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
