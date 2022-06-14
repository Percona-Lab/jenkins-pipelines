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
netMap['eu-central-1b'] = 'subnet-085deaca8c1c59a4f'
netMap['eu-central-1c'] = 'subnet-0643c0784b4e3cedd'

imageMap = [:]
imageMap['eu-central-1a.micro-amazon'] = 'ami-099ccc441b2ef41ec'
imageMap['eu-central-1b.micro-amazon'] = imageMap['eu-central-1a.micro-amazon']
imageMap['eu-central-1c.micro-amazon'] = imageMap['eu-central-1a.micro-amazon']

imageMap['eu-central-1a.min-centos-7-x64'] = 'ami-08b6d44b4f6f7b279'
imageMap['eu-central-1b.min-centos-7-x64'] = imageMap['eu-central-1a.min-centos-7-x64']
imageMap['eu-central-1c.min-centos-7-x64'] = imageMap['eu-central-1a.min-centos-7-x64']

imageMap['eu-central-1a.min-ol-8-x64'] = 'ami-07e51b655b107cd9b'
imageMap['eu-central-1b.min-ol-8-x64'] = imageMap['eu-central-1a.min-ol-8-x64']
imageMap['eu-central-1c.min-ol-8-x64'] = imageMap['eu-central-1a.min-ol-8-x64']

imageMap['eu-central-1a.min-rhel-9-x64'] = 'ami-025d24108be0a614c'
imageMap['eu-central-1b.min-rhel-9-x64'] = imageMap['eu-central-1a.min-rhel-9-x64']
imageMap['eu-central-1c.min-rhel-9-x64'] = imageMap['eu-central-1a.min-rhel-9-x64']

priceMap = [:]
priceMap['t2.medium']   = '0.05'
priceMap['m1.medium']   = '0.05'
priceMap['c4.xlarge']   = '0.10'
priceMap['m4.xlarge']   = '0.10'
priceMap['m4.2xlarge']  = '0.20'
priceMap['r4.4xlarge']  = '0.35'
priceMap['m5d.2xlarge'] = '0.20'
priceMap['c5d.xlarge']  = '0.20'
priceMap['r5b.2xlarge'] = '0.40'

userMap = [:]
userMap['micro-amazon']     = 'ec2-user'
userMap['min-centos-7-x64'] = 'centos'
userMap['min-ol-8-x64']     = userMap['micro-amazon']
userMap['min-rhel-9-x64']   = userMap['micro-amazon']

initMap = [:]
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
    sudo yum -y install java-1.8.0-openjdk git || :
    sudo yum -y install awscli || :
    sudo yum -y remove java-1.7.0-openjdk || :
    sudo install -o $(id -u -n) -g $(id -g -n) -d /mnt/jenkins
'''

initMap['min-centos-7-x64'] = initMap['micro-amazon']
initMap['min-ol-8-x64']     = initMap['micro-amazon']
initMap['min-rhel-9-x64']   = initMap['micro-amazon']

capMap = [:]
capMap['c4.xlarge']   = '60'
capMap['m4.xlarge']   = '5'
capMap['m4.2xlarge']  = '40'
capMap['r4.4xlarge']  = '40'
capMap['c5d.xlarge']  = '10'
capMap['r5b.2xlarge'] = '40'

typeMap = [:]
typeMap['micro-amazon']     = 't2.medium'
typeMap['min-centos-7-x64'] = 't2.medium'
typeMap['min-ol-8-x64']     = 't2.medium'
typeMap['min-rhel-9-x64']   = 'r5b.2xlarge'

execMap = [:]
execMap['micro-amazon']     = '30'
execMap['min-centos-7-x64'] = '1'
execMap['min-ol-8-x64']     = '1'
execMap['min-rhel-9-x64']   = '1'

devMap = [:]
devMap['micro-amazon']     = '/dev/xvda=:8:true:gp2,/dev/xvdd=:80:true:gp2'
devMap['min-centos-7-x64'] = '/dev/sda1=:8:true:gp2,/dev/sdd=:80:true:gp2'
devMap['min-ol-8-x64']     = '/dev/sda1=:8:true:gp2,/dev/sdd=:80:true:gp2'
devMap['min-rhel-9-x64']   = '/dev/sda1=:10:true:gp2,/dev/sdd=:80:true:gp2'

labelMap = [:]
labelMap['micro-amazon']     = 'master'
labelMap['min-centos-7-x64'] = 'min-centos-7-x64'
labelMap['min-ol-8-x64']     = 'min-ol-8-x64'
labelMap['min-rhel-9-x64']   = 'min-rhel-9-x64'

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
            getTemplate('min-rhel-9-x64',    "${region}${it}"),
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
