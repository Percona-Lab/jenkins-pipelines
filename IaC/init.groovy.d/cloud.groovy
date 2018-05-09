import com.amazonaws.services.ec2.model.InstanceType
import com.cloudbees.jenkins.plugins.awscredentials.AWSCredentialsImpl
import com.cloudbees.plugins.credentials.*
import com.cloudbees.plugins.credentials.domains.Domain
import hudson.model.*
import hudson.plugins.ec2.AmazonEC2Cloud
import hudson.plugins.ec2.AMITypeData
import hudson.plugins.ec2.EC2Tag
import hudson.plugins.ec2.SlaveTemplate
import hudson.plugins.ec2.SpotConfiguration
import hudson.plugins.ec2.UnixData
import java.util.logging.Logger
import jenkins.model.Jenkins

def logger = Logger.getLogger("")
logger.info("Cloud init started")

// get Jenkins instance
Jenkins jenkins = Jenkins.getInstance()


imageMap = [:]
imageMap['docker'] = 'ami-976152f2'
imageMap['micro-amazon'] = 'ami-25615740'
imageMap['min-artful-x64'] = 'ami-db2919be'
imageMap['min-centos-6-x64'] = 'ami-ff48629a'
imageMap['min-centos-7-x64'] = 'ami-994575fc'
imageMap['min-jessie-x64'] = 'ami-c5ba9fa0'
imageMap['min-stretch-x64'] = 'ami-79c0f01c'
imageMap['min-trusty-x64'] = 'ami-2ddeee48'
imageMap['min-xenial-x64'] = 'ami-e82a1a8d'

userMap = [:]
userMap['docker'] = 'ec2-user'
userMap['micro-amazon'] = 'ec2-user'
userMap['min-artful-x64'] = 'ubuntu'
userMap['min-centos-6-x64'] = 'centos'
userMap['min-centos-7-x64'] = 'centos'
userMap['min-jessie-x64'] = 'admin'
userMap['min-stretch-x64'] = 'admin'
userMap['min-trusty-x64'] = 'ubuntu'
userMap['min-xenial-x64'] = 'ubuntu'

initMap = [:]
initMap['docker'] = '''
    set -o xtrace

    if ! mountpoint -q /mnt; then
        DEVICE=$(ls /dev/xvdd /dev/nvme1n1 | head -1)
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

    sudo sysctl -w fs.inotify.max_user_watches=10000000 || true
    sudo sysctl -w fs.aio-max-nr=1048576 || true
    sudo sysctl -w fs.file-max=6815744 || true
    sudo sed -i.bak -e 's/nofile=1024:4096/nofile=900000:900000/; s/DAEMON_MAXFILES=.*/DAEMON_MAXFILES=990000/' /etc/sysconfig/docker
    echo 'DOCKER_STORAGE_OPTIONS="--data-root=/mnt/docker"' | sudo tee -a /etc/sysconfig/docker-storage
    sudo install -o root -g root -d /mnt/docker
    sudo usermod -aG docker $(id -u -n)
    sudo mkdir -p /etc/docker
    echo '{"experimental": true}' | sudo tee /etc/docker/daemon.json
    sudo service docker status || sudo service docker start
'''
initMap['micro-amazon'] = '''
    set -o xtrace
    if ! mountpoint -q /mnt; then
        DEVICE=$(ls /dev/xvdd /dev/nvme1n1 | head -1)
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
initMap['min-artful-x64'] = '''
    set -o xtrace
    if ! mountpoint -q /mnt; then
        DEVICE=$(ls /dev/xvdd /dev/nvme1n1 | head -1)
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
initMap['min-centos-6-x64'] = initMap['micro-amazon']
initMap['min-centos-7-x64'] = initMap['micro-amazon']
initMap['min-jessie-x64'] = '''
    set -o xtrace
    if ! mountpoint -q /mnt; then
        DEVICE=$(ls /dev/xvdd /dev/nvme1n1 | head -1)
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
initMap['min-stretch-x64'] = initMap['min-artful-x64']
initMap['min-trusty-x64'] = initMap['min-jessie-x64']
initMap['min-xenial-x64'] = initMap['min-artful-x64']

typeMap = [:]
typeMap['micro-amazon'] = 't2.small'
typeMap['min-centos-7-x64'] = 'm5.large'
typeMap['docker'] = 'c5.2xlarge'
typeMap['min-artful-x64'] = typeMap['min-centos-7-x64']
typeMap['min-centos-6-x64'] = 'm4.large'
typeMap['min-jessie-x64'] = typeMap['min-centos-6-x64']
typeMap['min-stretch-x64'] = typeMap['min-centos-7-x64']
typeMap['min-trusty-x64'] = typeMap['min-centos-7-x64']
typeMap['min-xenial-x64'] = typeMap['min-centos-7-x64']

execMap = [:]
execMap['docker'] = '1'
execMap['micro-amazon'] = '4'
execMap['min-artful-x64'] = '1'
execMap['min-centos-6-x64'] = '1'
execMap['min-centos-7-x64'] = '1'
execMap['min-jessie-x64'] = '1'
execMap['min-stretch-x64'] = '1'
execMap['min-trusty-x64'] = '1'
execMap['min-xenial-x64'] = '1'

devMap = [:]
devMap['micro-amazon'] = '/dev/xvda=:8:true:gp2,/dev/xvdd=:80:true:gp2'
devMap['docker'] = devMap['micro-amazon']
devMap['min-artful-x64'] = '/dev/sda1=:8:true:gp2,/dev/sdd=:80:true:gp2'
devMap['min-centos-6-x64'] = devMap['min-artful-x64']
devMap['min-centos-7-x64'] = devMap['min-artful-x64']
devMap['min-jessie-x64'] = devMap['micro-amazon']
devMap['min-stretch-x64'] = 'xvda=:8:true:gp2,xvdd=:80:true:gp2'
devMap['min-trusty-x64'] = devMap['min-artful-x64']
devMap['min-xenial-x64'] = devMap['min-artful-x64']

labelMap = [:]
labelMap['docker'] = ''
labelMap['micro-amazon'] = 'master'
labelMap['min-artful-x64'] = ''
labelMap['min-centos-6-x64'] = ''
labelMap['min-centos-7-x64'] = ''
labelMap['min-jessie-x64'] = ''
labelMap['min-stretch-x64'] = ''
labelMap['min-trusty-x64'] = ''
labelMap['min-xenial-x64'] = ''

// https://github.com/jenkinsci/ec2-plugin/blob/ec2-1.39/src/main/java/hudson/plugins/ec2/SlaveTemplate.java
SlaveTemplate getTemplate(String OSType) {
    return new SlaveTemplate(
        imageMap[OSType],                           // String ami
        '',                                         // String zone
        new SpotConfiguration('0.43'),              // SpotConfiguration spotConfig
        'default',                                  // String securityGroups
        '/mnt/jenkins',                             // String remoteFS
        InstanceType.fromValue(typeMap[OSType]),    // InstanceType type
        ( typeMap[OSType] == 'm5.large' ),          // boolean ebsOptimized
        OSType + ' ' + labelMap[OSType],            // String labelString
        Node.Mode.NORMAL,                           // Node.Mode mode
        OSType,                                     // String description
        initMap[OSType],                            // String initScript
        '',                                         // String tmpDir
        '',                                         // String userData
        execMap[OSType],                            // String numExecutors
        userMap[OSType],                            // String remoteAdmin
        new UnixData('', '', '22'),                 // AMITypeData amiType
        '',                                         // String jvmopts
        false,                                      // boolean stopOnTerminate
        'subnet-f2db0688',                          // String subnetId
        [
            new EC2Tag('Name', OSType),
            new EC2Tag('iit-billing-tag', 'jenkins-ps-slave')
        ],                                          // List<EC2Tag> tags
        '15',                                       // String idleTerminationMinutes
        false,                                      // boolean usePrivateDnsName
        '100',                                       // String instanceCapStr
        'arn:aws:iam::119175775298:instance-profile/jenkins-ps-slave', // String iamInstanceProfile
        true,                                       // boolean deleteRootOnTermination
        false,                                      // boolean useEphemeralDevices
        false,                                      // boolean useDedicatedTenancy
        '',                                         // String launchTimeoutStr
        true,                                       // boolean associatePublicIp
        devMap[OSType],                             // String customDeviceMapping
        false,                                      // boolean connectBySSHProcess
        false                                       // boolean connectUsingPublicIp
    )
}

String privateKey = ''
jenkins.clouds.each {
    if (it['cloudName'] == 'AWS-Dev') {
        privateKey = it['privateKey']
    }
}
// https://github.com/jenkinsci/ec2-plugin/blob/ec2-1.39/src/main/java/hudson/plugins/ec2/AmazonEC2Cloud.java
AmazonEC2Cloud amazonEC2Cloud = new AmazonEC2Cloud(
    'AWS-Dev',                              // String cloudName
    true,                                   // boolean useInstanceProfileForCredentials
    '',                                     // String credentialsId
    'us-east-2',                            // String region
    privateKey,                             // String privateKey
    '100',                                  // String instanceCapStr
    [
        getTemplate('docker'),
        getTemplate('micro-amazon'),
        getTemplate('min-artful-x64'),
        getTemplate('min-centos-6-x64'),
        getTemplate('min-centos-7-x64'),
        getTemplate('min-jessie-x64'),
        getTemplate('min-stretch-x64'),
        getTemplate('min-trusty-x64'),
        getTemplate('min-xenial-x64'),
    ]                                       // List<? extends SlaveTemplate> templates
)

// add cloud configuration to Jenkins
jenkins.clouds.replace(amazonEC2Cloud)

// save current Jenkins state to disk
jenkins.save()

logger.info("Cloud init finished")
