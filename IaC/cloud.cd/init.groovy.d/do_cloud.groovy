import java.util.logging.Logger
import com.dubture.jenkins.digitalocean.DigitalOceanCloud
import com.dubture.jenkins.digitalocean.SlaveTemplate
import jenkins.model.Jenkins


def logger = Logger.getLogger("")
logger.info("Cloud init started")

// get Jenkins instance
Jenkins jenkins = Jenkins.getInstance()

def cloudParameters = [
  authToken:            '',
  connectionRetryWait:  '30',
  instanceCap:          '5',
  name:                 'DO01',
  sshKeyId:             '24803143',
  timeoutMinutes:       '3',
  usePrivateNetworking: false,
  privateKey:           ''''''
]

def slaveTemplateParameters = [
  idleTerminationInMinutes: '3',
  imageId:                  'ubuntu-18-04-x64',
  initScript:               '''set -o xtrace
    if ! mountpoint -q /mnt; then
        DEVICE=$(ls /dev/xvdd /dev/xvdh /dev/nvme1n1 | head -1)
        sudo mkfs.ext2 ${DEVICE}
        sudo mount ${DEVICE} /mnt
    fi
    until sudo apt-get update; do
        sleep 1
        echo try again
    done
    sudo apt-get -y install openjdk-8-jre-headless git virtualbox virtualbox-ext-pack
    sudo install -o $(id -u -n) -g $(id -g -n) -d /mnt/jenkins''',
  installMonitoring:        false,
  instanceCap:              '5',
  labellessJobsAllowed:     false,
  labelString:              'virtualbox',
  name:                     'Ubuntu1804',
  numExecutors:             '1',
  regionId:                 'ams3',
  sizeId:                   'c-4',
  sshPort:                  22,
  tags:                     'jenkins-cloud',
  userData:                 '',
  username:                 'root',
  workspacePath:            '/mnt/jenkins',
  setupPrivateNetworking:    false
]

// https://github.com/jenkinsci/digitalocean-plugin/blob/digitalocean-plugin-0.17/src/main/java/com/dubture/jenkins/digitalocean/SlaveTemplate.java
SlaveTemplate DoSlaveTemplate = new SlaveTemplate(
  slaveTemplateParameters.name,
  slaveTemplateParameters.imageId,
  slaveTemplateParameters.sizeId,
  slaveTemplateParameters.regionId,
  slaveTemplateParameters.username,
  slaveTemplateParameters.workspacePath,
  slaveTemplateParameters.sshPort,
  slaveTemplateParameters.setupPrivateNetworking,
  slaveTemplateParameters.idleTerminationInMinutes,
  slaveTemplateParameters.numExecutors,
  slaveTemplateParameters.labelString,
  slaveTemplateParameters.labellessJobsAllowed,
  slaveTemplateParameters.instanceCap,
  slaveTemplateParameters.installMonitoring,
  slaveTemplateParameters.tags,
  slaveTemplateParameters.userData,
  slaveTemplateParameters.initScript
)
 
// https://github.com/jenkinsci/digitalocean-plugin/blob/digitalocean-plugin-0.17/src/main/java/com/dubture/jenkins/digitalocean/DigitalOceanCloud.java
DigitalOceanCloud digitalOceanCloud = new DigitalOceanCloud(
  cloudParameters.name,
  cloudParameters.authToken,
  cloudParameters.privateKey,
  cloudParameters.sshKeyId,
  cloudParameters.instanceCap,
  cloudParameters.usePrivateNetworking,
  cloudParameters.timeoutMinutes,
  cloudParameters.connectionRetryWait,
  [DoSlaveTemplate]
)

// add cloud configuration to Jenkins
jenkins.clouds.add(digitalOceanCloud)

// save current Jenkins state to disk
jenkins.save()

logger.info("Cloud init finished")
