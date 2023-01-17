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


// TODO We use rhel label here, in reality it's a RHEL-compatible derivative.
imageMap = [:]
imageMap['us-east-2a.min-rhel-7-x64']   = 'ami-00f8e2c955f7ffa9b' //centos 7
imageMap['us-east-2a.min-rhel-8-x64']   = 'ami-0ce24f7d9f52a2d88' // oracle linux 8
imageMap['us-east-2a.min-rhel-9-x64']   = 'ami-06a4a255271d0d6fa' // oraclelinux 9
imageMap['us-east-2a.min-focal-x64']    = 'ami-025aee7d3922c2191' // ubuntu 18
imageMap['us-east-2a.min-bionic-x64']   = 'ami-07e3ebaa49bb2df97' // ubuntu 20
imageMap['us-east-2a.min-jammy-x64']    = 'ami-01ef8c100091ba054' // ubuntu 22
imageMap['us-east-2a.min-stretch-x64']  = 'ami-0a694f67ea86df8a7' // debian 9
imageMap['us-east-2a.min-buster-x64']   = 'ami-0f89775886144ea25' // debian 10
imageMap['us-east-2a.min-bullseye-x64'] = 'ami-0d89c8b5893c02db4' // debian 11

imageMap['us-east-2b.min-rhel-7-x64']   = imageMap['us-east-2a.min-rhel-7-x64']
imageMap['us-east-2b.min-rhel-8-x64']   = imageMap['us-east-2a.min-rhel-8-x64']
imageMap['us-east-2b.min-rhel-9-x64']   = imageMap['us-east-2a.min-rhel-9-x64']
imageMap['us-east-2b.min-focal-x64']    = imageMap['us-east-2a.min-focal-x64']
imageMap['us-east-2b.min-bionic-x64']   = imageMap['us-east-2a.min-bionic-x64']
imageMap['us-east-2b.min-jammy-x64']    = imageMap['us-east-2a.min-jammy-x64']
imageMap['us-east-2b.min-stretch-x64']  = imageMap['us-east-2a.min-stretch-x64']
imageMap['us-east-2b.min-buster-x64']   = imageMap['us-east-2a.min-buster-x64']
imageMap['us-east-2b.min-bullseye-x64'] = imageMap['us-east-2a.min-bullseye-x64']

imageMap['us-east-2c.min-rhel-7-x64']   = imageMap['us-east-2a.min-rhel-7-x64']
imageMap['us-east-2c.min-rhel-8-x64']   = imageMap['us-east-2a.min-rhel-8-x64']
imageMap['us-east-2c.min-rhel-9-x64']   = imageMap['us-east-2a.min-rhel-9-x64']
imageMap['us-east-2c.min-focal-x64']    = imageMap['us-east-2a.min-focal-x64']
imageMap['us-east-2c.min-bionic-x64']   = imageMap['us-east-2a.min-bionic-x64']
imageMap['us-east-2c.min-jammy-x64']    = imageMap['us-east-2a.min-jammy-x64']
imageMap['us-east-2c.min-stretch-x64']  = imageMap['us-east-2a.min-stretch-x64']
imageMap['us-east-2c.min-buster-x64']   = imageMap['us-east-2a.min-buster-x64']
imageMap['us-east-2b.min-bullseye-x64'] = imageMap['us-east-2a.min-bullseye-x64']


priceMap = [:]
priceMap['t2.large']  = '0.045'
priceMap['t3.xlarge'] = '0.065'
priceMap['t3.large']  = '0.035'
priceMap['m4.large']  = '0.035'

userMap = [:]
userMap['min-rhel-7-x64']    = 'centos'
userMap['min-rhel-8-x64']    = 'rocky'
userMap['min-rhel-9-x64']    = 'ec2-user'
userMap['min-focal-x64']     = 'ubuntu'
userMap['min-bionic-x64']    = 'ubuntu'
userMap['min-jammy-x64']     = 'ubuntu'
userMap['min-stretch-x64']   = 'admin'
userMap['min-buster-x64']    = 'admin'
userMap['min-bullseye-x64']  = 'admin'


initMap = [:]

initMap['rpmMap'] = '''
    set -o xtrace
    RHVER=$(rpm --eval %rhel)
    SYSREL=$(cat /etc/system-release | tr -dc '0-9.' | awk -F'.' {'print $1'})

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

    printf "127.0.0.1 $(hostname) $(hostname -A)
    10.30.6.9 repo.ci.percona.com
    "     | sudo tee -a /etc/hosts

    if [[ $SYSREL -eq 2 ]]; then
        sudo sysctl -w fs.inotify.max_user_watches=10000000 || true
        sudo sysctl -w fs.aio-max-nr=1048576 || true
        sudo sysctl -w fs.file-max=6815744 || true
        echo "*  soft  core  unlimited" | sudo tee -a /etc/security/limits.conf
        sudo amazon-linux-extras install epel java-openjdk11 -y
        PKGLIST="tar coreutils p7zip"
    elif [[ $SYSREL -eq 7 ]]; then
        PKGLIST="tar coreutils java-11-openjdk"
    elif [[ $SYSREL -ge 8 ]]; then
        PKGLIST="tar coreutils java-11-openjdk"
    fi

    until sudo yum makecache; do
        sleep 1
        echo try again
    done

    sudo yum -y install git ${PKGLIST} || :
    aws --version || :
    sudo yum -y remove aws-cli || :

    if [[ $SYSREL -eq 2 ]]; then
        if ! $(aws --version | grep -q 'aws-cli/2'); then
            find /tmp -maxdepth 1 -name "*aws*" | xargs sudo rm -rf

            until curl "https://awscli.amazonaws.com/awscli-exe-linux-x86_64.zip" -o "/tmp/awscliv2.zip"; do
                sleep 1
                echo try again
            done

            7za -o/tmp x /tmp/awscliv2.zip
            cd /tmp/aws && sudo ./install
            aws --version || :
        fi
    fi

    sudo install -o $(id -u -n) -g $(id -g -n) -d /mnt/jenkins

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

    echo '10.30.6.9 repo.ci.percona.com' | sudo tee -a /etc/hosts

    until sudo apt-get update; do
        sleep 1
        echo try again
    done

    until sudo apt-get install -y lsb-release; do
        sleep 1
        echo try again
    done


    DEB_VERSION=$(lsb_release -sc)
    JDK_PACKAGE=openjdk-11-jre-headless

    if [ "$DEB_VERSION" == "stretch" ]; then
        echo 'deb http://ftp.debian.org/debian stretch-backports main' | sudo tee /etc/apt/sources.list.d/stretch-backports.list
        until sudo apt-get update; do
            sleep 1
            echo try again
        done
        JDK_PACKAGE=openjdk-11-jdk
    fi

    sudo apt-get -y install ${JDK_PACKAGE} git
    sudo install -o $(id -u -n) -g $(id -g -n) -d /mnt/jenkins

'''

initMap['min-rhel-7-x64']   = initMap['rpmMap']
initMap['min-rhel-8-x64']   = initMap['rpmMap']
initMap['min-rhel-9-x64']   = initMap['rpmMap']
initMap['min-focal-x64']    = initMap['debMap']
initMap['min-bionic-x64']   = initMap['debMap']
initMap['min-jammy-x64']    = initMap['debMap']
initMap['min-stretch-x64']  = initMap['debMap']
initMap['min-bullseye-x64'] = initMap['debMap']
initMap['min-buster-x64']   = initMap['debMap']

capMap = [:]
capMap['t2.large']   = '20'
capMap['t3.xlarge']  = '20'
capMap['t3.large']   = '20'
capMap['m4.large']   = '10'

typeMap = [:]
typeMap['min-rhel-7-x64']  = 'm4.large'
typeMap['min-rhel-8-x64']  = typeMap['min-rhel-7-x64']
typeMap['min-rhel-9-x64']  = typeMap['min-rhel-7-x64']
typeMap['min-focal-x64']     = typeMap['min-rhel-7-x64']
typeMap['min-bionic-x64']    = typeMap['min-rhel-7-x64']
typeMap['min-jammy-x64']     = typeMap['min-rhel-7-x64']
typeMap['min-stretch-x64']   = typeMap['min-rhel-7-x64']
typeMap['min-buster-x64']    = typeMap['min-rhel-7-x64']
typeMap['min-bullseye-x64']  = typeMap['min-rhel-7-x64']

execMap = [:]
execMap['min-rhel-7-x64']  = '1'
execMap['min-rhel-8-x64']  = '1'
execMap['min-rhel-9-x64']  = '1'
execMap['min-focal-x64']     = '1'
execMap['min-bionic-x64']    = '1'
execMap['min-jammy-x64']     = '1'
execMap['min-stretch-x64']   = '1'
execMap['min-buster-x64']    = '1'
execMap['min-bullseye-x64']  = '1'

devMap = [:]
devMap['min-rhel-7-x64']  = '/dev/sda1=:80:true:gp2,/dev/sdd=:20:true:gp2'
devMap['min-rhel-8-x64']  = devMap['min-rhel-7-x64']
devMap['min-rhel-9-x64']  = devMap['min-rhel-7-x64']
devMap['min-focal-x64']     = devMap['min-rhel-7-x64']
devMap['min-bionic-x64']    = devMap['min-rhel-7-x64']
devMap['min-jammy-x64']     = devMap['min-rhel-7-x64']
devMap['min-stretch-x64']   = '/dev/xvda=:80:true:gp2,/dev/xvdd=:20:true:gp2'
devMap['min-buster-x64']    = '/dev/xvda=:80:true:gp2,/dev/xvdd=:20:true:gp2'
devMap['min-bullseye-x64']  = '/dev/xvda=:80:true:gp2,/dev/xvdd=:20:true:gp2'

labelMap = [:]
labelMap['min-rhel-7-x64']  = 'min-rhel-7-x64'
labelMap['min-rhel-8-x64']  = 'min-rhel-8-x64'
labelMap['min-rhel-9-x64']  = 'min-rhel-9-x64'
labelMap['min-focal-x64']     = 'min-focal-x64'
labelMap['min-bionic-x64']    = 'min-bionic-x64'
labelMap['min-jammy-x64']     = 'min-jammy-x64'
labelMap['min-stretch-x64']   = 'min-stretch-x64'
labelMap['min-buster-x64']    = 'min-buster-x64'
labelMap['min-bullseye-x64']  = 'min-bullseye-x64'

// https://github.com/jenkinsci/ec2-plugin/blob/ec2-1.41/src/main/java/hudson/plugins/ec2/SlaveTemplate.java
// https://javadoc.jenkins.io/plugin/ec2/index.html?hudson/plugins/ec2/UnixData.html
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
            getTemplate('min-rhel-7-x64',     "${region}${it}"),
            getTemplate('min-rhel-8-x64',     "${region}${it}"),
            getTemplate('min-rhel-9-x64',     "${region}${it}"),
            getTemplate('min-focal-x64',        "${region}${it}"),
            getTemplate('min-bionic-x64',       "${region}${it}"),
            getTemplate('min-jammy-x64',        "${region}${it}"),
            getTemplate('min-stretch-x64',      "${region}${it}"),
            getTemplate('min-buster-x64',       "${region}${it}"),
            getTemplate('min-bullseye-x64',     "${region}${it}"),
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
