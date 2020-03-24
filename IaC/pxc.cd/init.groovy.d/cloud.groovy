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

System.setProperty("hudson.model.DirectoryBrowserSupport.CSP", "sandbox allow-scripts; script-src 'unsafe-eval' 'unsafe-inline' https://www.google.com;")

def logger = Logger.getLogger("")
logger.info("Cloud init started")

// get Jenkins instance
Jenkins jenkins = Jenkins.getInstance()

netMap = [:]
netMap['us-west-1b'] = 'subnet-01d9a7d6b4722eb43'
netMap['us-west-1c'] = 'subnet-0550c1d2ffd688021'

imageMap = [:]
imageMap['micro-amazon']     = 'ami-0bdb828fd58c52235'
imageMap['micro-amazon2']    = 'ami-0782017a917e973e7'
imageMap['min-artful-x64']   = 'ami-20bb5d43'
imageMap['min-bionic-x64']   = 'ami-063aa838bd7631e0b'
imageMap['min-centos-6-x32'] = 'ami-67e3cd22'
imageMap['min-centos-6-x64'] = 'ami-8adb3fe9'
imageMap['min-centos-7-x64'] = 'ami-4826c22b'
imageMap['min-jessie-x64']   = 'ami-9899c7f8'
imageMap['min-stretch-x64']  = 'ami-0bcaff0e3bf791af1'
imageMap['min-trusty-x64']   = 'ami-00048435fed26a8d1'
imageMap['min-xenial-x64']   = 'ami-0ad16744583f21877'
imageMap['min-buster-x64']   = 'ami-09e03f9fdef632722'
imageMap['docker']           = 'ami-0b2d8d1abb76a53d8'
imageMap['docker-32gb']      = imageMap['docker']
imageMap['psmdb']            = imageMap['min-xenial-x64']

imageMap['ramdisk-centos-6-x64'] = imageMap['min-centos-6-x64']
imageMap['ramdisk-centos-7-x64'] = imageMap['min-centos-7-x64']
imageMap['ramdisk-stretch-x64']  = imageMap['min-stretch-x64']
imageMap['ramdisk-xenial-x64']   = imageMap['min-xenial-x64']
imageMap['ramdisk-bionic-x64']   = imageMap['min-bionic-x64']
imageMap['ramdisk-buster-x64']   = imageMap['min-buster-x64']
imageMap['ramdisk-jessie-x64']   = imageMap['min-jessie-x64']

imageMap['performance-centos-6-x64']   = imageMap['min-centos-7-x64']

priceMap = [:]
priceMap['t2.small'] = '0.01'
priceMap['m1.medium'] = '0.05'
priceMap['c4.xlarge'] = '0.10'
priceMap['m4.xlarge'] = '0.10'
priceMap['m4.2xlarge'] = '0.20'
priceMap['m5d.2xlarge'] = '0.20'
priceMap['r3.2xlarge'] = '0.20'

userMap = [:]
userMap['docker']            = 'ec2-user'
userMap['docker-32gb']       = userMap['docker']
userMap['micro-amazon']      = userMap['docker']
userMap['min-artful-x64']    = 'ubuntu'
userMap['min-bionic-x64']    = 'ubuntu'
userMap['min-centos-6-x32']  = 'root'
userMap['min-centos-6-x64']  = 'centos'
userMap['min-centos-7-x64']  = 'centos'
userMap['fips-centos-7-x64'] = 'centos'
userMap['min-jessie-x64']    = 'admin'
userMap['min-stretch-x64']   = 'admin'
userMap['min-buster-x64']    = 'admin'
userMap['min-trusty-x64']    = 'ubuntu'
userMap['min-xenial-x64']    = 'ubuntu'
userMap['psmdb']             = userMap['min-xenial-x64']

userMap['ramdisk-centos-6-x64'] = userMap['min-centos-6-x64']
userMap['ramdisk-centos-7-x64'] = userMap['min-centos-7-x64']
userMap['ramdisk-stretch-x64']  = userMap['min-stretch-x64']
userMap['ramdisk-xenial-x64']   = userMap['min-xenial-x64']
userMap['ramdisk-bionic-x64']   = userMap['min-bionic-x64']
userMap['ramdisk-buster-x64']   = userMap['min-buster-x64']
userMap['ramdisk-jessie-x64']   = userMap['min-jessie-x64']

userMap['performance-centos-6-x64'] = userMap['min-centos-6-x64']

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
    echo sudo service docker status || sudo service docker start
    echo "* * * * * root /usr/sbin/route add default gw 10.177.1.1 eth0" | sudo tee /etc/cron.d/fix-default-route
'''
initMap['docker-32gb'] = initMap['docker']
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
initMap['min-buster-x64'] = '''
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
    sudo apt-get -y install openjdk-11-jre-headless git
    sudo install -o $(id -u -n) -g $(id -g -n) -d /mnt/jenkins
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
initMap['min-trusty-x64'] = '''
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
initMap['min-jessie-x64'] = '''
    set -o xtrace
    if ! mountpoint -q /mnt; then
        DEVICE=$(ls /dev/xvdd /dev/xvdh /dev/nvme1n1 | head -1)
        sudo mkfs.ext2 ${DEVICE}
        sudo mount ${DEVICE} /mnt
    fi

    sudo rm -rf  /etc/apt/sources.list.d/backports.list
    echo "deb http://httpredir.debian.org/debian jessie main" > sources.list
    echo "deb-src http://httpredir.debian.org/debian jessie main" >> sources.list
    echo "deb http://security.debian.org/ jessie/updates main" >> sources.list
    echo "deb-src http://security.debian.org/ jessie/updates main" >> sources.list
    sudo mv sources.list /etc/apt/sources.list

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

initMap['ramdisk-jessie-x64'] = '''
    set -o xtrace
    set -o xtrace
    if ! mountpoint -q /mnt; then
        sudo mount -t tmpfs -o size=20G tmpfs /mnt
    fi
    sudo rm -rf  /etc/apt/sources.list.d/backports.list
    echo "deb http://httpredir.debian.org/debian jessie main" > sources.list
    echo "deb-src http://httpredir.debian.org/debian jessie main" >> sources.list
    echo "deb http://security.debian.org/ jessie/updates main" >> sources.list
    echo "deb-src http://security.debian.org/ jessie/updates main" >> sources.list
    sudo mv sources.list /etc/apt/sources.list

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
initMap['ramdisk-centos-6-x64'] = '''
    set -o xtrace
    if ! mountpoint -q /mnt; then
        sudo mount -t tmpfs -o size=20G tmpfs /mnt
    fi
    until sudo yum makecache; do
        sleep 1
        echo try again
    done
    sudo yum -y install java-1.8.0-openjdk git aws-cli || :
    sudo yum -y remove java-1.7.0-openjdk || :
    sudo install -o $(id -u -n) -g $(id -g -n) -d /mnt/jenkins
'''
initMap['ramdisk-centos-7-x64'] = initMap['ramdisk-centos-6-x64']

initMap['ramdisk-buster-x64'] = '''
    set -o xtrace
    if ! mountpoint -q /mnt; then
        sudo mount -t tmpfs -o size=20G tmpfs /mnt
    fi
    until sudo apt-get update; do
        sleep 1
        echo try again
    done
    sudo apt-get -y install openjdk-11-jre-headless git
    sudo install -o $(id -u -n) -g $(id -g -n) -d /mnt/jenkins
'''
initMap['ramdisk-bionic-x64'] = '''
    set -o xtrace
    if ! mountpoint -q /mnt; then
        sudo mount -t tmpfs -o size=20G tmpfs /mnt
    fi
    until sudo apt-get update; do
        sleep 1
        echo try again
    done
    sudo apt-get -y install openjdk-8-jre-headless git
    sudo install -o $(id -u -n) -g $(id -g -n) -d /mnt/jenkins
'''
initMap['ramdisk-stretch-x64']       = initMap['ramdisk-bionic-x64']
initMap['ramdisk-xenial-x64']        = initMap['ramdisk-bionic-x64']
initMap['performance-centos-6-x64']  = '''
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

    echo "*  soft  nofile  1048575" | sudo tee -a /etc/security/limits.conf
    echo "*  hard  nofile  1048575" | sudo tee -a /etc/security/limits.conf
    echo "*  soft  nproc  1048575"  | sudo tee -a /etc/security/limits.conf
    echo "*  hard  nproc  1048575"  | sudo tee -a /etc/security/limits.conf
'''

capMap = [:]
capMap['c4.xlarge'] = '60'
capMap['m4.xlarge'] = '60'
capMap['m4.2xlarge'] = '10'
capMap['r3.2xlarge'] = '10'

typeMap = [:]
typeMap['micro-amazon'] = 't2.small'
typeMap['docker']       = 'c4.xlarge'
typeMap['docker-32gb']  = 'm4.2xlarge'

typeMap['performance-centos-6-x64'] = typeMap['docker-32gb']

typeMap['min-centos-7-x64'] = typeMap['docker-32gb']
typeMap['fips-centos-7-x64'] = typeMap['min-centos-7-x64']
typeMap['min-artful-x64'] = typeMap['min-centos-7-x64']
typeMap['min-bionic-x64'] = typeMap['min-centos-7-x64']
typeMap['min-centos-6-x32'] = 'm1.medium'
typeMap['min-centos-6-x64'] = typeMap['min-centos-7-x64']
typeMap['min-jessie-x64'] = typeMap['min-centos-7-x64']
typeMap['min-buster-x64'] = typeMap['min-centos-7-x64']
typeMap['min-stretch-x64'] = typeMap['min-centos-7-x64']
typeMap['min-trusty-x64'] = typeMap['min-centos-7-x64']
typeMap['min-xenial-x64'] = typeMap['min-centos-7-x64']
typeMap['psmdb'] = typeMap['docker-32gb']

typeMap['ramdisk-centos-6-x64'] = typeMap['docker-32gb']
typeMap['ramdisk-centos-7-x64'] = typeMap['docker-32gb']
typeMap['ramdisk-stretch-x64']  = typeMap['docker-32gb']
typeMap['ramdisk-xenial-x64']   = typeMap['docker-32gb']
typeMap['ramdisk-bionic-x64']   = typeMap['docker-32gb']
typeMap['ramdisk-buster-x64']   = typeMap['docker-32gb']
typeMap['ramdisk-jessie-x64']   = typeMap['docker-32gb']

execMap = [:]
execMap['docker'] = '1'
execMap['docker-32gb'] = execMap['docker']
execMap['micro-amazon'] = '30'
execMap['min-artful-x64'] = '1'
execMap['min-bionic-x64'] = '1'
execMap['min-centos-6-x32'] = '1'
execMap['min-centos-6-x64'] = '1'
execMap['min-centos-7-x64'] = '1'
execMap['fips-centos-7-x64'] = '1'
execMap['min-jessie-x64'] = '1'
execMap['min-buster-x64'] = '1'
execMap['min-stretch-x64'] = '1'
execMap['min-trusty-x64'] = '1'
execMap['min-xenial-x64'] = '1'
execMap['psmdb'] = '1'

execMap['ramdisk-centos-6-x64'] = execMap['docker-32gb']
execMap['ramdisk-centos-7-x64'] = execMap['docker-32gb']
execMap['ramdisk-stretch-x64']  = execMap['docker-32gb']
execMap['ramdisk-xenial-x64']   = execMap['docker-32gb']
execMap['ramdisk-bionic-x64']   = execMap['docker-32gb']
execMap['ramdisk-buster-x64']   = execMap['docker-32gb']
execMap['ramdisk-jessie-x64']   = execMap['docker-32gb']

execMap['performance-centos-6-x64']   = execMap['docker-32gb']

devMap = [:]
devMap['docker']            = '/dev/xvda=:8:true:gp2,/dev/xvdd=:80:true:gp2'
devMap['docker-32gb']       = devMap['docker']
devMap['micro-amazon']      = devMap['docker']
devMap['min-artful-x64']    = '/dev/sda1=:8:true:gp2,/dev/sdd=:80:true:gp2'
devMap['min-bionic-x64']    = devMap['min-artful-x64']
devMap['min-centos-6-x64']  = devMap['min-artful-x64']
devMap['min-centos-7-x64']  = devMap['min-artful-x64']
devMap['fips-centos-7-x64'] = devMap['min-artful-x64']
devMap['min-jessie-x64']    = devMap['micro-amazon']
devMap['min-stretch-x64']   = 'xvda=:8:true:gp2,xvdd=:80:true:gp2'
devMap['min-buster-x64']    = devMap['min-stretch-x64']
devMap['min-trusty-x64']    = devMap['min-artful-x64']
devMap['min-xenial-x64']    = devMap['min-artful-x64']
devMap['min-centos-6-x32']  = '/dev/sda=:8:true:gp2,/dev/sdd=:80:true:gp2'
devMap['psmdb']             = '/dev/sda1=:8:true:gp2,/dev/sdd=:160:true:gp2'

devMap['ramdisk-centos-6-x64'] = '/dev/sda1=:8:true:gp2'
devMap['ramdisk-centos-7-x64'] = devMap['ramdisk-centos-6-x64']
devMap['ramdisk-bionic-x64']   = devMap['ramdisk-centos-6-x64']
devMap['ramdisk-xenial-x64']   = devMap['ramdisk-centos-6-x64']
devMap['ramdisk-stretch-x64']  = 'xvda=:8:true:gp2'
devMap['ramdisk-buster-x64']   = devMap['ramdisk-stretch-x64']
devMap['ramdisk-jessie-x64']   = '/dev/xvda=:8:true:gp2'

devMap['performance-centos-6-x64'] = '/dev/sda1=:8:true:gp2,/dev/sdd=:120:true:gp2'

labelMap = [:]
labelMap['docker']            = ''
labelMap['docker-32gb']       = ''
labelMap['micro-amazon']      = 'master'
labelMap['min-artful-x64']    = ''
labelMap['min-bionic-x64']    = 'asan'
labelMap['min-centos-6-x32']  = ''
labelMap['min-centos-6-x64']  = ''
labelMap['min-centos-7-x64']  = ''
labelMap['fips-centos-7-x64'] = ''
labelMap['min-jessie-x64']    = ''
labelMap['min-stretch-x64']   = ''
labelMap['min-buster-x64']    = ''
labelMap['min-trusty-x64']    = ''
labelMap['min-xenial-x64']    = ''
labelMap['psmdb']             = ''

labelMap['ramdisk-centos-6-x64'] = ''
labelMap['ramdisk-centos-7-x64'] = ''
labelMap['ramdisk-bionic-x64']   = ''
labelMap['ramdisk-xenial-x64']   = ''
labelMap['ramdisk-stretch-x64']  = ''
labelMap['ramdisk-buster-x64']   = ''
labelMap['ramdisk-jessie-x64']   = ''

labelMap['performance-centos-6-x64'] = 'perf-centos-6-x64'

// https://github.com/jenkinsci/ec2-plugin/blob/ec2-1.39/src/main/java/hudson/plugins/ec2/SlaveTemplate.java
SlaveTemplate getTemplate(String OSType, String AZ) {
    return new SlaveTemplate(
        imageMap[OSType],                           // String ami
        '',                                         // String zone
        new SpotConfiguration(true, priceMap[typeMap[OSType]], false, '0'), // SpotConfiguration spotConfig
        'default',                                  // String securityGroups
        '/mnt/jenkins',                             // String remoteFS
        InstanceType.fromValue(typeMap[OSType]),    // InstanceType type
        ( typeMap[OSType].startsWith("c4") || typeMap[OSType].startsWith("m4") || typeMap[OSType].startsWith("c5") || typeMap[OSType].startsWith("m5") || typeMap[OSType].startsWith("r3") ), // boolean ebsOptimized
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
            new EC2Tag('Name', 'jenkins-pxc-' + OSType),
            new EC2Tag('iit-billing-tag', 'jenkins-pxc-worker')
        ],                                          // List<EC2Tag> tags
        '3',                                        // String idleTerminationMinutes
        capMap[typeMap[OSType]],                    // String instanceCapStr
        'arn:aws:iam::119175775298:instance-profile/jenkins-pxc-worker', // String iamInstanceProfile
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
    )
}

String privateKey = ''
jenkins.clouds.each {
    if (it.hasProperty('cloudName') && it['cloudName'] == 'AWS-Dev b') {
        privateKey = it['privateKey']
    }
}

String region = 'us-west-1'
('b'..'b').each {
    // https://github.com/jenkinsci/ec2-plugin/blob/ec2-1.39/src/main/java/hudson/plugins/ec2/AmazonEC2Cloud.java
    AmazonEC2Cloud ec2Cloud = new AmazonEC2Cloud(
        "AWS-Dev ${it}",                        // String cloudName
        true,                                   // boolean useInstanceProfileForCredentials
        '',                                     // String credentialsId
        region,                                 // String region
        privateKey,                             // String privateKey
        '240',                                   // String instanceCapStr
        [
            getTemplate('micro-amazon',     "${region}${it}"),
            getTemplate('docker',           "${region}${it}"),
            getTemplate('docker-32gb',       "${region}${it}"),
            getTemplate('min-centos-6-x32', "${region}${it}"),
            getTemplate('min-centos-6-x64', "${region}${it}"),
            getTemplate('min-centos-7-x64', "${region}${it}"),
            getTemplate('min-jessie-x64',   "${region}${it}"),
            getTemplate('min-stretch-x64',  "${region}${it}"),
            getTemplate('min-buster-x64',   "${region}${it}"),
            getTemplate('min-trusty-x64',   "${region}${it}"),
            getTemplate('min-xenial-x64',   "${region}${it}"),
            getTemplate('min-bionic-x64',   "${region}${it}"),
            getTemplate('ramdisk-centos-6-x64', "${region}${it}"),
            getTemplate('ramdisk-centos-7-x64', "${region}${it}"),
            getTemplate('ramdisk-stretch-x64',  "${region}${it}"),
            getTemplate('ramdisk-xenial-x64',   "${region}${it}"),
            getTemplate('ramdisk-bionic-x64',   "${region}${it}"),
            getTemplate('ramdisk-buster-x64',   "${region}${it}"),
            getTemplate('ramdisk-jessie-x64',   "${region}${it}"),
            getTemplate('performance-centos-6-x64', "${region}${it}"),
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
