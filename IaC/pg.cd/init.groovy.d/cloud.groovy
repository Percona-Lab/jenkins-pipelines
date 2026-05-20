import com.amazonaws.services.ec2.model.InstanceType
import hudson.model.*
import hudson.plugins.ec2.EC2Cloud
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

// Subnet configuration for Jenkins PG workers
// PKG-1246: Expanded from /24 (251 IPs) to /22 (1024 IPs) subnets for 1200-1500 spot instances
// Old subnets: subnet-0fad4db6fdd8025b6 (B), subnet-0802c1d4746b8c35a (C)
netMap = [:]
netMap['eu-central-1b'] = 'subnet-0775d65ad1e9703bc'  // JSubnetB2: 10.145.0.0/22 (1024 IPs)
netMap['eu-central-1c'] = 'subnet-09947b46d69590c50'  // JSubnetC2: 10.145.4.0/22 (1024 IPs)

imageMap = [:]
imageMap['eu-central-1a.micro-amazon'] = 'ami-0444794b421ec32e4'
imageMap['eu-central-1b.micro-amazon'] = imageMap['eu-central-1a.micro-amazon']
imageMap['eu-central-1c.micro-amazon'] = imageMap['eu-central-1a.micro-amazon']

imageMap['eu-central-1a.min-centos-7-x64'] = 'ami-0afcbcee3dfbce929'
imageMap['eu-central-1b.min-centos-7-x64'] = imageMap['eu-central-1a.min-centos-7-x64']
imageMap['eu-central-1c.min-centos-7-x64'] = imageMap['eu-central-1a.min-centos-7-x64']

imageMap['eu-central-1a.min-ol-8-x64'] = 'ami-0f18276190490b6e3'
imageMap['eu-central-1b.min-ol-8-x64'] = imageMap['eu-central-1a.min-ol-8-x64']
imageMap['eu-central-1c.min-ol-8-x64'] = imageMap['eu-central-1a.min-ol-8-x64']

imageMap['eu-central-1a.min-ol-9-x64'] = 'ami-08b280891de0645a1'
imageMap['eu-central-1b.min-ol-9-x64'] = imageMap['eu-central-1a.min-ol-9-x64']
imageMap['eu-central-1c.min-ol-9-x64'] = imageMap['eu-central-1a.min-ol-9-x64']

imageMap['eu-central-1a.min-al2023-x64'] = 'ami-0444794b421ec32e4'
imageMap['eu-central-1b.min-al2023-x64'] = imageMap['eu-central-1a.min-al2023-x64']
imageMap['eu-central-1c.min-al2023-x64'] = imageMap['eu-central-1a.min-al2023-x64']

imageMap['eu-central-1a.min-al2023-aarch64'] = 'ami-0b7c9879f1e078eb1'
imageMap['eu-central-1b.min-al2023-aarch64'] = imageMap['eu-central-1a.min-al2023-aarch64']
imageMap['eu-central-1c.min-al2023-aarch64'] = imageMap['eu-central-1a.min-al2023-aarch64']

priceMap = [:]
priceMap['c5.large']     = '0.08' // type=c5.large, vCPU=2, memory=4GiB, saving=49%, interruption='<5%', price=0.053400
priceMap['c6g.large']    = '0.07' // type=c6g.large, vCPU=2, memory=4GiB (ARM)
priceMap['d3.2xlarge']   = '0.47' // type=d3.2xlarge, vCPU=8, memory=64GiB, saving=70%, interruption='<5%', price=0.324000
// PKG-1333: alt SKUs for min-ol-9-x64 to diversify the worker pool. Bids verified to fulfill at $0.47 across 1a/1b/1c on 2026-05-20.
priceMap['m5d.2xlarge']  = '0.47' // type=m5d.2xlarge,  vCPU=8, memory=32GiB, recent spot peak ~$0.27 (OD ~$0.45)
priceMap['m5dn.2xlarge'] = '0.47' // type=m5dn.2xlarge, vCPU=8, memory=32GiB, OD ~$0.54
priceMap['i3en.2xlarge'] = '0.47' // type=i3en.2xlarge, vCPU=8, memory=64GiB, recent spot peak ~$0.44 (OD ~$0.82)

userMap = [:]
userMap['micro-amazon']       = 'ec2-user'
userMap['min-centos-7-x64']   = 'centos'
userMap['min-ol-8-x64']       = userMap['micro-amazon']
userMap['min-ol-9-x64']       = userMap['micro-amazon']
userMap['min-al2023-x64']     = userMap['micro-amazon']
userMap['min-al2023-aarch64'] = userMap['micro-amazon']

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
        sudo sed -i 's/mirrorlist/#mirrorlist/g' /etc/yum.repos.d/CentOS-* || :
        sudo sed -i 's|#baseurl=http://mirror.centos.org|baseurl=http://vault.centos.org|g' /etc/yum.repos.d/CentOS-* || :
    fi

    until sudo yum makecache; do
        sleep 1
        echo try again
    done

    sudo yum -y remove java-1.8.0-openjdk || :
    sudo yum -y remove java-1.8.0-openjdk-headless || :

    SYSREL=$(cat /etc/system-release | tr -dc '0-9.' | awk -F'.' '{print $1}')
    if [ "${SYSREL}" = "2023" ]; then
        JAVA_PKG="java-17-amazon-corretto"
    else
        JAVA_PKG="java-17-openjdk-headless"
    fi
    sudo yum -y install ${JAVA_PKG} tzdata-java || :
    sudo yum -y install git || :

    echo '10.30.6.9 repo.ci.percona.com' | sudo tee -a /etc/hosts

    sudo install -o $(id -u -n) -g $(id -g -n) -d /mnt/jenkins
'''

initMap['min-centos-7-x64']   = initMap['micro-amazon']
initMap['min-ol-8-x64']       = initMap['micro-amazon']
initMap['min-ol-9-x64']       = initMap['micro-amazon']
initMap['min-al2023-x64']     = initMap['micro-amazon']
initMap['min-al2023-aarch64'] = initMap['micro-amazon']

capMap = [:]
capMap['c5.large']     = '20'
capMap['c6g.large']    = '20'
capMap['d3.2xlarge']   = '40'
// PKG-1333: per-SKU caps for diversified min-ol-9-x64 templates.
capMap['m5d.2xlarge']  = '40'
capMap['m5dn.2xlarge'] = '40'
capMap['i3en.2xlarge'] = '40'

typeMap = [:]
typeMap['micro-amazon']       = 'c5.large'
typeMap['min-centos-7-x64']   = 'c5.large'
typeMap['min-ol-8-x64']       = 'c5.large'
typeMap['min-ol-9-x64']       = 'd3.2xlarge'
typeMap['min-al2023-x64']     = 'c5.large'
typeMap['min-al2023-aarch64'] = 'c6g.large'

execMap = [:]
execMap['micro-amazon']       = '30'
execMap['min-centos-7-x64']   = '1'
execMap['min-ol-8-x64']       = '1'
execMap['min-ol-9-x64']       = '1'
execMap['min-al2023-x64']     = '1'
execMap['min-al2023-aarch64'] = '1'

devMap = [:]
devMap['micro-amazon']       = '/dev/xvda=:20:true:gp2,/dev/xvdd=:80:true:gp2'
devMap['min-centos-7-x64']   = '/dev/sda1=:8:true:gp2,/dev/sdd=:80:true:gp2'
devMap['min-ol-8-x64']       = '/dev/sda1=:30:true:gp2,/dev/sdd=:80:true:gp2'
devMap['min-ol-9-x64']       = '/dev/sda1=:30:true:gp2,/dev/sdd=:80:true:gp2'
devMap['min-al2023-x64']     = '/dev/xvda=:30:true:gp2,/dev/xvdd=:80:true:gp2'
devMap['min-al2023-aarch64'] = '/dev/xvda=:30:true:gp2,/dev/xvdd=:80:true:gp2'

labelMap = [:]
labelMap['micro-amazon']       = 'master'
labelMap['min-centos-7-x64']   = 'min-centos-7-x64'
labelMap['min-ol-8-x64']       = 'min-ol-8-x64'
labelMap['min-ol-9-x64']       = 'min-ol-9-x64'
labelMap['min-al2023-x64']     = 'min-al2023-x64'
labelMap['min-al2023-aarch64'] = 'min-al2023-aarch64'

// https://github.com/jenkinsci/ec2-plugin/blob/ec2-1.41/src/main/java/hudson/plugins/ec2/SlaveTemplate.java
// PKG-1333: optional overrideType lets one OSType (label) be backed by multiple instance types.
// When overrideType is set, the template uses that SKU instead of typeMap[OSType] while keeping the same label.
SlaveTemplate getTemplate(String OSType, String AZ, String overrideType = null) {
    String actualType = overrideType ?: typeMap[OSType]
    String tagSuffix  = overrideType ? '-' + overrideType.split(/\./)[0] : ''
    String descSuffix = overrideType ? ' (' + overrideType + ')' : ''
    return new SlaveTemplate(
        imageMap[AZ + '.' + OSType],                // String ami
        '',                                         // String zone
        new SpotConfiguration(true, priceMap[actualType], false, '0'), // SpotConfiguration spotConfig
        'default',                                  // String securityGroups
        '/mnt/jenkins',                             // String remoteFS
        InstanceType.fromValue(actualType),         // InstanceType type
        ( actualType.startsWith("c4") || actualType.startsWith("m4") || actualType.startsWith("c5") || actualType.startsWith("m5") ), // boolean ebsOptimized
        OSType + ' ' + labelMap[OSType],            // String labelString (shared across SKUs so jobs find any of them)
        Node.Mode.NORMAL,                           // Node.Mode mode
        OSType + descSuffix,                        // String description
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
            new EC2Tag('Name', 'jenkins-pg-' + OSType + tagSuffix),
            new EC2Tag('iit-billing-tag', 'jenkins-pg-worker')
        ],                                          // List<EC2Tag> tags
        '15',                                       // String idleTerminationMinutes
        0,                                          // Init minimumNumberOfInstances
        0,                                          // minimumNumberOfSpareInstances
        capMap[actualType],                         // String instanceCapStr
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
    EC2Cloud ec2Cloud = new EC2Cloud(
        "AWS-Dev ${it}",                        // String cloudName
        true,                                   // boolean useInstanceProfileForCredentials
        '',                                     // String credentialsId
        region,                                 // String region
        privateKey,                             // String privateKey
        sshKeysCredentialsId,                   // String sshKeysCredentialsId
        '240',                                   // String instanceCapStr
        [
            getTemplate('micro-amazon',        "${region}${it}"),
            getTemplate('min-centos-7-x64',    "${region}${it}"),
            getTemplate('min-ol-8-x64',        "${region}${it}"),
            getTemplate('min-ol-9-x64',        "${region}${it}"),
            // PKG-1333: alt SKUs sharing the min-ol-9-x64 label so Jenkins fulfills from whichever spot pool has capacity.
            getTemplate('min-ol-9-x64',        "${region}${it}", 'm5d.2xlarge'),
            getTemplate('min-ol-9-x64',        "${region}${it}", 'm5dn.2xlarge'),
            getTemplate('min-ol-9-x64',        "${region}${it}", 'i3en.2xlarge'),
            getTemplate('min-al2023-x64',      "${region}${it}"),
            getTemplate('min-al2023-aarch64',  "${region}${it}"),
        ],                                       // List<? extends SlaveTemplate> templates
        '',
        ''
    )

    // add cloud configuration to Jenkins
    jenkins.clouds.each {
        if (it.hasProperty('name') && it.name == ec2Cloud.name) {
            jenkins.clouds.remove(it)
        }
    }
    jenkins.clouds.add(ec2Cloud)
}

// save current Jenkins state to disk
jenkins.save()

logger.info("Cloud init finished")
