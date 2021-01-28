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
imageMap['us-east-2a.micro-amazon']     = 'ami-0a0ad6b70e61be944'
imageMap['us-east-2a.large-amazon']     = 'ami-0a0ad6b70e61be944'

imageMap['us-east-2b.min-centos-6-x64'] = imageMap['us-east-2a.min-centos-6-x64']
imageMap['us-east-2b.min-centos-7-x64'] = imageMap['us-east-2a.min-centos-7-x64']
imageMap['us-east-2b.micro-amazon']     = imageMap['us-east-2a.micro-amazon']
imageMap['us-east-2b.large-amazon']     = imageMap['us-east-2a.large-amazon']

imageMap['us-east-2c.min-centos-6-x64'] = imageMap['us-east-2a.min-centos-6-x64']
imageMap['us-east-2c.min-centos-7-x64'] = imageMap['us-east-2a.min-centos-7-x64']
imageMap['us-east-2c.micro-amazon']     = imageMap['us-east-2a.micro-amazon']
imageMap['us-east-2c.large-amazon']     = imageMap['us-east-2a.large-amazon']

priceMap = [:]
priceMap['t2.large']  = '0.04'
priceMap['t2.xlarge'] = '0.06'
priceMap['t3.large']  = '0.03'
priceMap['m4.large']  = '0.03'

userMap = [:]
userMap['min-centos-6-x64']  = 'centos'
userMap['min-centos-7-x64']  = userMap['min-centos-6-x64']
userMap['micro-amazon']      = 'ec2-user'
userMap['large-amazon']      = userMap['micro-amazon']

initMap = [:]
initMap['min-centos-6-x64'] = '''
    set -o xtrace
    RHVER="$(rpm --eval %rhel)"

    if ! mountpoint -q /mnt; then
        DEVICE=$(ls /dev/xvdd /dev/xvdh /dev/nvme1n1 | head -1)
        sudo mkfs.ext2 ${DEVICE}
        sudo mount ${DEVICE} /mnt
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
initMap['micro-amazon']     = '''
    set -o xtrace
    if ! mountpoint -q /mnt; then
        DEVICE=$(ls /dev/xvdd /dev/xvdh /dev/nvme1n1 | head -1)
        sudo mkfs.ext4 ${DEVICE}
        sudo mount ${DEVICE} /mnt
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
initMap['large-amazon']     = initMap['micro-amazon']

capMap = [:]
capMap['t2.large']   = '20'
capMap['t2.xlarge']  = '20'
capMap['t3.large']   = '20'
capMap['m4.large']   = '10'

typeMap = [:]
typeMap['min-centos-6-x64']  = 'm4.large'
typeMap['min-centos-7-x64']  = 'm4.large'
typeMap['micro-amazon']      = 't3.large'
typeMap['large-amazon']      = 't2.xlarge'

execMap = [:]
execMap['min-centos-6-x64']  = '1'
execMap['min-centos-7-x64']  = '1'
execMap['micro-amazon']      = '4'
execMap['large-amazon']      = '1'

devMap = [:]
devMap['min-centos-6-x64']  = '/dev/sda1=:8:true:gp2,/dev/sdd=:20:true:gp2'
devMap['min-centos-7-x64']  = devMap['min-centos-6-x64']
devMap['micro-amazon']      = '/dev/xvda=:8:true:gp2,/dev/xvdd=:120:true:gp2'
devMap['large-amazon']      = devMap['micro-amazon']

labelMap = [:]
labelMap['min-centos-6-x64']  = 'min-centos-6-x64'
labelMap['min-centos-7-x64']  = 'min-centos-7-x64'
labelMap['micro-amazon']      = 'micro-amazon nodejs master awscli'
labelMap['large-amazon']      = 'large-amazon'

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
            getTemplate('micro-amazon',         "${region}${it}"),
            getTemplate('large-amazon',         "${region}${it}"),
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

