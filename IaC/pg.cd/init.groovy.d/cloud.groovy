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
netMap['eu-central-1b'] = 'subnet-0fad4db6fdd8025b6'
netMap['eu-central-1c'] = 'subnet-0802c1d4746b8c35a'

imageMap = [:]
imageMap['eu-central-1a.micro-amazon'] = 'ami-01fd08d7b0955d6d5'
imageMap['eu-central-1b.micro-amazon'] = imageMap['eu-central-1a.micro-amazon']
imageMap['eu-central-1c.micro-amazon'] = imageMap['eu-central-1a.micro-amazon']

imageMap['eu-central-1a.min-centos-7-x64'] = 'ami-0afcbcee3dfbce929'
imageMap['eu-central-1b.min-centos-7-x64'] = imageMap['eu-central-1a.min-centos-7-x64']
imageMap['eu-central-1c.min-centos-7-x64'] = imageMap['eu-central-1a.min-centos-7-x64']

imageMap['eu-central-1a.min-ol-8-x64'] = 'ami-065e2293a3df4c870'
imageMap['eu-central-1b.min-ol-8-x64'] = imageMap['eu-central-1a.min-ol-8-x64']
imageMap['eu-central-1c.min-ol-8-x64'] = imageMap['eu-central-1a.min-ol-8-x64']

imageMap['eu-central-1a.min-ol-9-x64'] = 'ami-02952e732e6126584'
imageMap['eu-central-1b.min-ol-9-x64'] = imageMap['eu-central-1a.min-ol-9-x64']
imageMap['eu-central-1c.min-ol-9-x64'] = imageMap['eu-central-1a.min-ol-9-x64']

priceMap = [:]
priceMap['c5.large']     = '0.08' // type=c5.large, vCPU=2, memory=4GiB, saving=49%, interruption='<5%', price=0.053400
priceMap['d3.2xlarge']   = '0.47' // type=d3.2xlarge, vCPU=8, memory=64GiB, saving=70%, interruption='<5%', price=0.324000

userMap = [:]
userMap['micro-amazon']     = 'ec2-user'
userMap['min-centos-7-x64'] = 'centos'
userMap['min-ol-8-x64']     = userMap['micro-amazon']
userMap['min-ol-9-x64']     = userMap['micro-amazon']

initMap = [:]
initMap['micro-amazon'] = '''
    set -o xtrace
    RHVER=$(rpm --eval %rhel)
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

    if [[ ${RHVER} -eq 8 ]] || [[ ${RHVER} -eq 7 ]]; then
        sudo sed -i 's/mirrorlist/#mirrorlist/g' /etc/yum.repos.d/CentOS-*
        sudo sed -i 's|#baseurl=http://mirror.centos.org|baseurl=http://vault.centos.org|g' /etc/yum.repos.d/CentOS-*
    fi

    until sudo yum makecache; do
        sleep 1
        echo try again
    done
    sudo amazon-linux-extras install epel -y
    sudo amazon-linux-extras install java-openjdk11 -y || :
    sudo yum -y install java-11-openjdk tzdata-java git || :
    sudo yum -y install git || :
    sudo yum -y install awscli || :
    sudo yum -y remove java-1.7.0-openjdk || :
    sudo install -o $(id -u -n) -g $(id -g -n) -d /mnt/jenkins
'''

initMap['min-centos-7-x64'] = initMap['micro-amazon']
initMap['min-ol-8-x64']     = initMap['micro-amazon']
initMap['min-ol-9-x64']     = initMap['micro-amazon']

capMap = [:]
capMap['c5.large']     = '20'
capMap['d3.2xlarge']   = '40'

typeMap = [:]
typeMap['micro-amazon']     = 'c5.large'
typeMap['min-centos-7-x64'] = 'c5.large'
typeMap['min-ol-8-x64']     = 'c5.large'
typeMap['min-ol-9-x64']     = 'd3.2xlarge'

execMap = [:]
execMap['micro-amazon']     = '30'
execMap['min-centos-7-x64'] = '1'
execMap['min-ol-8-x64']     = '1'
execMap['min-ol-9-x64']     = '1'

devMap = [:]
devMap['micro-amazon']     = '/dev/xvda=:8:true:gp2,/dev/xvdd=:80:true:gp2'
devMap['min-centos-7-x64'] = '/dev/sda1=:8:true:gp2,/dev/sdd=:80:true:gp2'
devMap['min-ol-8-x64']     = '/dev/sda1=:8:true:gp2,/dev/sdd=:80:true:gp2'
devMap['min-ol-9-x64']     = '/dev/sda1=:10:true:gp2,/dev/sdd=:80:true:gp2'

labelMap = [:]
labelMap['micro-amazon']     = 'master'
labelMap['min-centos-7-x64'] = 'min-centos-7-x64'
labelMap['min-ol-8-x64']     = 'min-ol-8-x64'
labelMap['min-ol-9-x64']     = 'min-ol-9-x64'

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
            new EC2Tag('Name', 'jenkins-pg-' + OSType),
            new EC2Tag('iit-billing-tag', 'jenkins-pg-worker')
        ],                                          // List<EC2Tag> tags
        '3',                                        // String idleTerminationMinutes
        0,                                          // Init minimumNumberOfInstances
        0,                                          // minimumNumberOfSpareInstances
        capMap[typeMap[OSType]],                    // String instanceCapStr
        'arn:aws:iam::119175775298:instance-profile/jenkins-pg-worker', // String iamInstanceProfile
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

String sshKeysCredentialsId = 'aws-jenkins'

String region = 'eu-central-1'
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
            getTemplate('micro-amazon',      "${region}${it}"),
            getTemplate('min-centos-7-x64',  "${region}${it}"),
            getTemplate('min-ol-8-x64',      "${region}${it}"),
            getTemplate('min-ol-9-x64',      "${region}${it}"),
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
