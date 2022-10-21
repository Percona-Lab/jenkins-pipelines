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
netMap['eu-west-1b'] = 'subnet-05f58e38549072404'
netMap['eu-west-1c'] = 'subnet-0b5ee1ef341aca9db'

imageMap = [:]
imageMap['eu-west-1a.docker'] = 'ami-0af7a5885e3ff0439'
imageMap['eu-west-1a.docker-32gb'] = 'ami-0af7a5885e3ff0439'
imageMap['eu-west-1a.docker2'] = 'ami-0af7a5885e3ff0439'
imageMap['eu-west-1a.micro-amazon'] = 'ami-0af7a5885e3ff0439'
imageMap['eu-west-1a.min-centos-7-x64'] = 'ami-04f5641b0d178a27a'

imageMap['eu-west-1b.docker'] = imageMap['eu-west-1a.docker']
imageMap['eu-west-1b.docker-32gb'] = imageMap['eu-west-1a.docker-32gb']
imageMap['eu-west-1b.docker2'] = imageMap['eu-west-1a.docker2']
imageMap['eu-west-1b.micro-amazon'] = imageMap['eu-west-1a.micro-amazon']
imageMap['eu-west-1b.min-centos-7-x64'] = imageMap['eu-west-1a.min-centos-7-x64']

imageMap['eu-west-1c.docker'] = imageMap['eu-west-1a.docker']
imageMap['eu-west-1c.docker-32gb'] = imageMap['eu-west-1a.docker-32gb']
imageMap['eu-west-1c.docker2'] = imageMap['eu-west-1a.docker2']
imageMap['eu-west-1c.micro-amazon'] = imageMap['eu-west-1a.micro-amazon']
imageMap['eu-west-1c.min-centos-7-x64'] = imageMap['eu-west-1a.min-centos-7-x64']

priceMap = [:]
priceMap['t2.small'] = '0.01'
priceMap['m1.medium'] = '0.05'
priceMap['c4.xlarge'] = '0.10'
priceMap['m4.xlarge'] = '0.10'
priceMap['m5dn.2xlarge'] = '0.47'
priceMap['i4i.4xlarge'] = '0.57'
priceMap['m5d.2xlarge'] = '0.20'
priceMap['c5d.xlarge'] = '0.20'

userMap = [:]
userMap['docker'] = 'ec2-user'
userMap['docker-32gb'] = userMap['docker']
userMap['docker2'] = userMap['docker']
userMap['micro-amazon'] = userMap['docker']
userMap['min-centos-7-x64'] = 'centos'

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
    echo "* * * * * root /usr/sbin/route add default gw 10.177.1.1 eth0" | sudo tee /etc/cron.d/fix-default-route
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
    until sudo yum makecache; do
        sleep 1
        echo try again
    done
    sudo amazon-linux-extras install epel -y
    sudo amazon-linux-extras install java-openjdk11 -y || :
    sudo yum -y install git aws-cli || :
    sudo install -o $(id -u -n) -g $(id -g -n) -d /mnt/jenkins
'''

capMap = [:]
capMap['c4.xlarge'] = '60'
capMap['m4.xlarge'] = '5'
capMap['m5dn.2xlarge'] = '40'
capMap['i4i.4xlarge'] = '40'
capMap['c5d.xlarge'] = '10'

typeMap = [:]
typeMap['micro-amazon'] = 't2.small'
typeMap['docker'] = 'c4.xlarge'
typeMap['docker-32gb'] = 'm5dn.2xlarge'
typeMap['docker2'] = 'i4i.4xlarge'
typeMap['min-centos-7-x64'] = typeMap['docker']

execMap = [:]
execMap['docker'] = '1'
execMap['docker-32gb'] = execMap['docker']
execMap['docker2'] = execMap['docker']
execMap['micro-amazon'] = '30'
execMap['min-centos-7-x64'] = '1'

devMap = [:]
devMap['docker'] = '/dev/xvda=:8:true:gp2,/dev/xvdd=:80:true:gp2'
devMap['docker2'] = '/dev/xvda=:8:true:gp2,/dev/xvdd=:120:true:gp2'
devMap['docker-32gb'] = '/dev/xvda=:8:true:gp2,/dev/xvdd=:120:true:gp2'
devMap['micro-amazon'] = devMap['docker']
devMap['min-centos-7-x64'] = '/dev/sda1=:8:true:gp2,/dev/sdd=:80:true:gp2'

labelMap = [:]
labelMap['docker'] = ''
labelMap['docker-32gb'] = ''
labelMap['docker2'] = 'docker-32gb'
labelMap['micro-amazon'] = 'master'
labelMap['min-centos-7-x64'] = ''

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
            new EC2Tag('Name', 'jenkins-fb-' + OSType),
            new EC2Tag('iit-billing-tag', 'jenkins-fb-worker')
        ],                                          // List<EC2Tag> tags
        '3',                                        // String idleTerminationMinutes
        0,                                          // Init minimumNumberOfInstances
        0,                                          // minimumNumberOfSpareInstances
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

String sshKeysCredentialsId = '1b53b7b1-f4fb-4109-8080-28ac2ee46b41'

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
