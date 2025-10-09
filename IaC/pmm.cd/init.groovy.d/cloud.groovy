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
imageMap['us-east-2a.min-rhel-8-x64']     = 'ami-0eeed66f8f65afdba'                 // oraclelinux 8.9
imageMap['us-east-2a.min-ol-8-x64']       = imageMap['us-east-2a.min-rhel-8-x64']   // oraclelinux 8.9
imageMap['us-east-2a.min-rhel-9-x64']     = 'ami-0b5a1d936f517ad3e'                 // oraclelinux 9.3
imageMap['us-east-2a.min-ol-9-x64']       = imageMap['us-east-2a.min-rhel-9-x64']   // oraclelinux 9.3
imageMap['us-east-2a.min-alma-10-x64']    = 'ami-06be7a8dff9965741'                 // almalinux 10
imageMap['us-east-2a.min-jammy-x64']      = 'ami-085438ce84ab3ac76'                 // ubuntu 22
imageMap['us-east-2a.min-noble-x64']      = 'ami-0d1b5a8c13042c939'                 // ubuntu 24
imageMap['us-east-2a.min-bullseye-x64']   = 'ami-0434754593ce7b895'                 // debian 11
imageMap['us-east-2a.min-bookworm-x64']   = 'ami-065eb7eeb82248b49'                 // debian 12
imageMap['us-east-2a.min-trixie-x64']     = 'ami-0e4a9a71af7af46ff'                 // debian 13

imageMap['us-east-2b.min-rhel-8-x64']     = imageMap['us-east-2a.min-rhel-8-x64']
imageMap['us-east-2b.min-ol-8-x64']       = imageMap['us-east-2a.min-ol-8-x64']     // oraclelinux 8
imageMap['us-east-2b.min-rhel-9-x64']     = imageMap['us-east-2a.min-rhel-9-x64']
imageMap['us-east-2b.min-ol-9-x64']       = imageMap['us-east-2a.min-rhel-9-x64']   // oraclelinux 9
imageMap['us-east-2b.min-alma-10-x64']    = imageMap['us-east-2a.min-alma-10-x64']  // almalinux 10
imageMap['us-east-2b.min-jammy-x64']      = imageMap['us-east-2a.min-jammy-x64']
imageMap['us-east-2b.min-noble-x64']      = imageMap['us-east-2a.min-noble-x64']
imageMap['us-east-2b.min-bullseye-x64']   = imageMap['us-east-2a.min-bullseye-x64']
imageMap['us-east-2b.min-bookworm-x64']   = imageMap['us-east-2a.min-bookworm-x64']
imageMap['us-east-2b.min-trixie-x64']     = imageMap['us-east-2a.min-trixie-x64']

imageMap['us-east-2c.min-rhel-8-x64']     = imageMap['us-east-2a.min-rhel-8-x64']
imageMap['us-east-2c.min-ol-8-x64']       = imageMap['us-east-2a.min-ol-8-x64']     // oraclelinux 8
imageMap['us-east-2c.min-rhel-9-x64']     = imageMap['us-east-2a.min-rhel-9-x64']
imageMap['us-east-2c.min-ol-9-x64']       = imageMap['us-east-2a.min-rhel-9-x64']   // oraclelinux 9
imageMap['us-east-2c.min-alma-10-x64']    = imageMap['us-east-2a.min-alma-10-x64']  // almalinux 10
imageMap['us-east-2c.min-jammy-x64']      = imageMap['us-east-2a.min-jammy-x64']
imageMap['us-east-2c.min-noble-x64']      = imageMap['us-east-2a.min-noble-x64']
imageMap['us-east-2c.min-bullseye-x64']   = imageMap['us-east-2a.min-bullseye-x64']
imageMap['us-east-2c.min-bookworm-x64']   = imageMap['us-east-2a.min-bookworm-x64']
imageMap['us-east-2c.min-trixie-x64']     = imageMap['us-east-2a.min-trixie-x64']

// ARM64 based AMIs
imageMap['us-east-2a.min-ol-8-arm64']       = 'ami-0f77cbbab56907b6c'               // oraclelinux 8.9 arm64
imageMap['us-east-2a.min-ol-9-arm64']       = 'ami-0ffbdb6ee492c2cd5'               // oraclelinux 9.3 arm64
imageMap['us-east-2a.min-alma-10-arm64']    = 'ami-0f5ead2bc98a63a65'               // almalinux 10 arm64
imageMap['us-east-2a.min-jammy-arm64']      = 'ami-0f732d76e7fad24ca'               // ubuntu 22.04 arm64
imageMap['us-east-2a.min-noble-arm64']      = 'ami-019eeff96c2865995'               // ubuntu 24.04 arm64
imageMap['us-east-2a.min-bullseye-arm64']   = 'ami-0d0902423ff93b14f'               // debian 11 arm64
imageMap['us-east-2a.min-bookworm-arm64']   = 'ami-0de4c77901001cfe7'               // debian 12 arm64
imageMap['us-east-2a.min-trixie-arm64']     = 'ami-0e80837acd137142b'               // debian 13 arm64

imageMap['us-east-2b.min-ol-8-arm64']       = imageMap['us-east-2a.min-ol-8-arm64']
imageMap['us-east-2b.min-ol-9-arm64']       = imageMap['us-east-2a.min-ol-9-arm64']
imageMap['us-east-2b.min-alma-10-arm64']    = imageMap['us-east-2a.min-alma-10-arm64']
imageMap['us-east-2b.min-jammy-arm64']      = imageMap['us-east-2a.min-jammy-arm64']
imageMap['us-east-2b.min-noble-arm64']      = imageMap['us-east-2a.min-noble-arm64']
imageMap['us-east-2b.min-bullseye-arm64']   = imageMap['us-east-2a.min-bullseye-arm64']
imageMap['us-east-2b.min-bookworm-arm64']   = imageMap['us-east-2a.min-bookworm-arm64']
imageMap['us-east-2b.min-trixie-arm64']     = imageMap['us-east-2a.min-trixie-arm64']

imageMap['us-east-2c.min-ol-8-arm64']       = imageMap['us-east-2a.min-ol-8-arm64']
imageMap['us-east-2c.min-ol-9-arm64']       = imageMap['us-east-2a.min-ol-9-arm64']
imageMap['us-east-2c.min-alma-10-arm64']    = imageMap['us-east-2a.min-alma-10-arm64']
imageMap['us-east-2c.min-jammy-arm64']      = imageMap['us-east-2a.min-jammy-arm64']
imageMap['us-east-2c.min-noble-arm64']      = imageMap['us-east-2a.min-noble-arm64']
imageMap['us-east-2c.min-bullseye-arm64']   = imageMap['us-east-2a.min-bullseye-arm64']
imageMap['us-east-2c.min-bookworm-arm64']   = imageMap['us-east-2a.min-bookworm-arm64']
imageMap['us-east-2c.min-trixie-arm64']     = imageMap['us-east-2a.min-trixie-arm64']

priceMap = [:]
priceMap['t2.large']   = '0.045'
priceMap['t3.xlarge']  = '0.065'
priceMap['t3.large']   = '0.035'
priceMap['m4.large']   = '0.060'
priceMap['m7a.large']  = '0.044' // amd64 instance type - vCPU=2, memory=8GiB, saving=73%, interruption='<5%', price=0.03
priceMap['m7g.large']  = '0.042' // arm64 instance type - vCPU=2, memory=8GiB, saving=63%, interruption='<5%', price=0.03

userMap = [:]
userMap['min-rhel-8-x64']      = 'ec2-user'
userMap['min-ol-8-x64']        = 'ec2-user'
userMap['min-rhel-9-x64']      = 'ec2-user'
userMap['min-ol-9-x64']        = 'ec2-user'
userMap['min-alma-10-x64']     = 'ec2-user'
userMap['min-jammy-x64']       = 'ubuntu'
userMap['min-noble-x64']       = 'ubuntu'
userMap['min-bullseye-x64']    = 'admin'
userMap['min-bookworm-x64']    = 'admin'
userMap['min-trixie-x64']      = 'admin'

userMap['min-ol-8-arm64']      = 'ec2-user'
userMap['min-ol-9-arm64']      = 'ec2-user'
userMap['min-alma-10-arm64']   = 'ec2-user'
userMap['min-jammy-arm64']     = 'ubuntu'
userMap['min-noble-arm64']     = 'ubuntu'
userMap['min-bullseye-arm64']  = 'admin'
userMap['min-bookworm-arm64']  = 'admin'
userMap['min-trixie-arm64']    = 'admin'

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

    if [[ $SYSREL -ge 10 ]]; then
        PKGLIST="tar coreutils java-21-openjdk-headless tzdata-java"
    elif [[ $SYSREL -ge 8 ]]; then
        PKGLIST="tar coreutils java-11-openjdk tzdata-java"
    fi

    if [[ ${RHVER} -eq 8 ]]; then
        sudo sed -i 's/mirrorlist/#mirrorlist/g' /etc/yum.repos.d/CentOS-*
        sudo sed -i 's|#baseurl=http://mirror.centos.org|baseurl=http://vault.centos.org|g' /etc/yum.repos.d/CentOS-*
    fi

    until sudo yum makecache; do
        sleep 1
        echo try again
    done

    sudo yum -y install git ${PKGLIST} || :
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

    DEB_VERSION=$(lsb_release -sc)

    # Remove bullseye-backports entries from sources.list
    if [[ ${DEB_VERSION} == "bullseye" ]]; then
        sudo sed -i '/bullseye-backports/d' /etc/apt/sources.list
    fi

    until sudo apt-get update; do
        sleep 1
        echo try again
    done

    until sudo apt-get install -y lsb-release; do
        sleep 1
        echo try again
    done

    if [ "${DEB_VERSION}" == "trixie" ]; then
        JDK_PACKAGE="openjdk-21-jdk-headless"
    elif [ "${DEB_VERSION}" == "bookworm" ]; then
        JDK_PACKAGE="openjdk-17-jre-headless"
    else
        JDK_PACKAGE="openjdk-11-jre-headless"
    fi

    if [ "${DEB_VERSION}" = "bookworm" ] || [ "${DEB_VERSION}" = "trixie" ]; then
        sudo DEBIAN_FRONTEND=noninteractive apt-get -y install ${JDK_PACKAGE} git
        sudo mv /etc/ssl /etc/ssl_old
        sudo DEBIAN_FRONTEND=noninteractive apt-get -y install ${JDK_PACKAGE}
        sudo cp -r /etc/ssl_old /etc/ssl
        sudo DEBIAN_FRONTEND=noninteractive apt-get -y install ${JDK_PACKAGE}
    else
        sudo DEBIAN_FRONTEND=noninteractive apt-get -y install ${JDK_PACKAGE} git
    fi

    sudo install -o $(id -u -n) -g $(id -g -n) -d /mnt/jenkins

'''

initMap['min-rhel-8-x64']     = initMap['rpmMap']
initMap['min-ol-8-x64']       = initMap['rpmMap']
initMap['min-rhel-9-x64']     = initMap['rpmMap']
initMap['min-ol-9-x64']       = initMap['rpmMap']
initMap['min-alma-10-x64']    = initMap['rpmMap']
initMap['min-jammy-x64']      = initMap['debMap']
initMap['min-noble-x64']      = initMap['debMap']
initMap['min-bullseye-x64']   = initMap['debMap']
initMap['min-bookworm-x64']   = initMap['debMap']
initMap['min-trixie-x64']     = initMap['debMap']

initMap['min-ol-8-arm64']     = initMap['rpmMap']
initMap['min-ol-9-arm64']     = initMap['rpmMap']
initMap['min-alma-10-arm64']  = initMap['rpmMap']
initMap['min-jammy-arm64']    = initMap['debMap']
initMap['min-noble-arm64']    = initMap['debMap']
initMap['min-bullseye-arm64'] = initMap['debMap']
initMap['min-bookworm-arm64'] = initMap['debMap']
initMap['min-trixie-arm64']   = initMap['debMap']

capMap = [:]
capMap['t2.large']   = '20'
capMap['t3.xlarge']  = '20'
capMap['t3.large']   = '20'
capMap['m4.large']   = '10'
capMap['m7a.large']  = '15' // amd64 instance type
capMap['m7g.large']  = '15' // arm64 instance type

typeMap = [:]
typeMap['min-rhel-8-x64']     = 'm7a.large'
typeMap['min-ol-8-x64']       = typeMap['min-rhel-8-x64']
typeMap['min-rhel-9-x64']     = typeMap['min-rhel-8-x64']
typeMap['min-ol-9-x64']       = typeMap['min-rhel-8-x64']
typeMap['min-alma-10-x64']    = typeMap['min-rhel-8-x64']
typeMap['min-jammy-x64']      = typeMap['min-rhel-8-x64']
typeMap['min-noble-x64']      = typeMap['min-rhel-8-x64']
typeMap['min-bullseye-x64']   = typeMap['min-rhel-8-x64']
typeMap['min-bookworm-x64']   = typeMap['min-rhel-8-x64']
typeMap['min-trixie-x64']     = typeMap['min-rhel-8-x64']

typeMap['min-ol-8-arm64']     = 'm7g.large'
typeMap['min-ol-9-arm64']     = typeMap['min-ol-8-arm64']
typeMap['min-alma-10-arm64']  = typeMap['min-ol-8-arm64']
typeMap['min-jammy-arm64']    = typeMap['min-ol-8-arm64']
typeMap['min-noble-arm64']    = typeMap['min-ol-8-arm64']
typeMap['min-bullseye-arm64'] = typeMap['min-ol-8-arm64']
typeMap['min-bookworm-arm64'] = typeMap['min-ol-8-arm64']
typeMap['min-trixie-arm64']   = typeMap['min-ol-8-arm64']

execMap = [:]
execMap['min-rhel-8-x64']     = '1'
execMap['min-ol-8-x64']       = '1'
execMap['min-rhel-9-x64']     = '1'
execMap['min-ol-9-x64']       = '1'
execMap['min-alma-10-x64']    = '1'
execMap['min-jammy-x64']      = '1'
execMap['min-noble-x64']      = '1'
execMap['min-bullseye-x64']   = '1'
execMap['min-bookworm-x64']   = '1'
execMap['min-trixie-x64']     = '1'

execMap['min-ol-8-arm64']     = '1'
execMap['min-ol-9-arm64']     = '1'
execMap['min-alma-10-arm64']  = '1'
execMap['min-jammy-arm64']    = '1'
execMap['min-noble-arm64']    = '1'
execMap['min-bullseye-arm64'] = '1'
execMap['min-bookworm-arm64'] = '1'
execMap['min-trixie-arm64']   = '1'

devMap = [:]
devMap['min-rhel-8-x64']     = '/dev/sda1=:80:true:gp3,/dev/sdd=:20:true:gp3'
devMap['min-ol-8-x64']       = devMap['min-rhel-8-x64']
devMap['min-rhel-9-x64']     = devMap['min-rhel-8-x64']
devMap['min-ol-9-x64']       = devMap['min-rhel-8-x64']
devMap['min-alma-10-x64']    = devMap['min-rhel-8-x64']
devMap['min-jammy-x64']      = devMap['min-rhel-8-x64']
devMap['min-noble-x64']      = devMap['min-rhel-8-x64']
devMap['min-bullseye-x64']   = '/dev/xvda=:80:true:gp3,/dev/xvdd=:20:true:gp3'
devMap['min-bookworm-x64']   = '/dev/xvda=:80:true:gp3,/dev/xvdd=:20:true:gp3'
devMap['min-trixie-x64']     = '/dev/xvda=:80:true:gp3,/dev/xvdd=:20:true:gp3'

devMap['min-ol-8-arm64']     = devMap['min-rhel-8-x64']
devMap['min-ol-9-arm64']     = devMap['min-rhel-8-x64']
devMap['min-alma-10-arm64']  = devMap['min-rhel-8-x64']
devMap['min-jammy-arm64']    = devMap['min-rhel-8-x64']
devMap['min-noble-arm64']    = devMap['min-rhel-8-x64']
devMap['min-bullseye-arm64'] = '/dev/xvda=:80:true:gp3,/dev/xvdd=:20:true:gp3'
devMap['min-bookworm-arm64'] = '/dev/xvda=:80:true:gp3,/dev/xvdd=:20:true:gp3'
devMap['min-trixie-arm64']   = '/dev/xvda=:80:true:gp3,/dev/xvdd=:20:true:gp3'

labelMap = [:]
labelMap['min-rhel-8-x64']     = 'min-rhel-8-x64'
labelMap['min-ol-8-x64']       = 'min-ol-8-x64'
labelMap['min-rhel-9-x64']     = 'min-rhel-9-x64'
labelMap['min-ol-9-x64']       = 'min-ol-9-x64'
labelMap['min-alma-10-x64']    = 'min-alma-10-x64'
labelMap['min-jammy-x64']      = 'min-jammy-x64'
labelMap['min-noble-x64']      = 'min-noble-x64'
labelMap['min-bullseye-x64']   = 'min-bullseye-x64'
labelMap['min-bookworm-x64']   = 'min-bookworm-x64'
labelMap['min-trixie-x64']     = 'min-trixie-x64'

labelMap['min-ol-8-arm64']     = 'min-ol-8-arm64'
labelMap['min-ol-9-arm64']     = 'min-ol-9-arm64'
labelMap['min-alma-10-arm64']  = 'min-alma-10-arm64'
labelMap['min-jammy-arm64']    = 'min-jammy-arm64'
labelMap['min-noble-arm64']    = 'min-noble-arm64'
labelMap['min-bullseye-arm64'] = 'min-bullseye-arm64'
labelMap['min-bookworm-arm64'] = 'min-bookworm-arm64'
labelMap['min-trixie-arm64']   = 'min-trixie-arm64'

jvmoptsMap = [:]
jvmoptsMap['min-rhel-8-x64']     = '-Xmx512m -Xms512m'
jvmoptsMap['min-ol-8-x64']       = jvmoptsMap['min-rhel-8-x64']
jvmoptsMap['min-rhel-9-x64']     = jvmoptsMap['min-rhel-8-x64']
jvmoptsMap['min-ol-9-x64']       = jvmoptsMap['min-rhel-8-x64']
jvmoptsMap['min-alma-10-x64']    = jvmoptsMap['min-rhel-8-x64']
jvmoptsMap['min-jammy-x64']      = jvmoptsMap['min-rhel-8-x64']
jvmoptsMap['min-noble-x64']      = jvmoptsMap['min-rhel-8-x64']
jvmoptsMap['min-bullseye-x64']   = '-Xmx512m -Xms512m --add-opens=java.base/java.lang=ALL-UNNAMED --add-opens=java.base/java.lang.reflect=ALL-UNNAMED'
jvmoptsMap['min-bookworm-x64']   = '-Xmx512m -Xms512m --add-opens=java.base/java.lang=ALL-UNNAMED --add-opens=java.base/java.lang.reflect=ALL-UNNAMED'
jvmoptsMap['min-trixie-x64']     = '-Xmx512m -Xms512m --add-opens=java.base/java.lang=ALL-UNNAMED --add-opens=java.base/java.lang.reflect=ALL-UNNAMED'

jvmoptsMap['min-ol-8-arm64']     = jvmoptsMap['min-rhel-8-x64']
jvmoptsMap['min-ol-9-arm64']     = jvmoptsMap['min-rhel-8-x64']
jvmoptsMap['min-alma-10-arm64']  = jvmoptsMap['min-rhel-8-x64']
jvmoptsMap['min-jammy-arm64']    = jvmoptsMap['min-rhel-8-x64']
jvmoptsMap['min-noble-arm64']    = jvmoptsMap['min-rhel-8-x64']
jvmoptsMap['min-bullseye-arm64'] = '-Xmx512m -Xms512m --add-opens=java.base/java.lang=ALL-UNNAMED --add-opens=java.base/java.lang.reflect=ALL-UNNAMED'
jvmoptsMap['min-bookworm-arm64'] = '-Xmx512m -Xms512m --add-opens=java.base/java.lang=ALL-UNNAMED --add-opens=java.base/java.lang.reflect=ALL-UNNAMED'
jvmoptsMap['min-trixie-arm64']   = '-Xmx512m -Xms512m --add-opens=java.base/java.lang=ALL-UNNAMED --add-opens=java.base/java.lang.reflect=ALL-UNNAMED'

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
        jvmoptsMap[OSType],                         // String jvmopts
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
            getTemplate('min-rhel-8-x64',      "${region}${it}"),
            getTemplate('min-ol-8-x64',        "${region}${it}"),
            getTemplate('min-rhel-9-x64',      "${region}${it}"),
            getTemplate('min-ol-9-x64',        "${region}${it}"),
            getTemplate('min-alma-10-x64',     "${region}${it}"),
            getTemplate('min-jammy-x64',       "${region}${it}"),
            getTemplate('min-noble-x64',       "${region}${it}"),
            getTemplate('min-bullseye-x64',    "${region}${it}"),
            getTemplate('min-bookworm-x64',    "${region}${it}"),
            getTemplate('min-trixie-x64',      "${region}${it}"),
            getTemplate('min-ol-8-arm64',      "${region}${it}"),
            getTemplate('min-ol-9-arm64',      "${region}${it}"),
            getTemplate('min-alma-10-arm64',   "${region}${it}"),
            getTemplate('min-jammy-arm64',     "${region}${it}"),
            getTemplate('min-noble-arm64',     "${region}${it}"),
            getTemplate('min-bullseye-arm64',  "${region}${it}"),
            getTemplate('min-bookworm-arm64',  "${region}${it}"),
            getTemplate('min-trixie-arm64',    "${region}${it}"),
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

