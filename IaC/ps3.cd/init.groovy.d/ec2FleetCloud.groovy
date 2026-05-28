// ec2FleetCloud.groovy  (PS-11179)
//
// Registers the diversified Graviton EC2 Fleet (ASG-backed via the ec2-fleet
// plugin) as a Jenkins Cloud serving the `docker-32gb-aarch64` label, replacing
// the classic single-SKU `r6g.2xlarge` SlaveTemplate that previously served the
// same label (retired from cloud.groovy in the same commit).
//
// Multi-SKU only: this is the uniform fallback path. AWS chooses from
// `m8g/m7g/m6g.2xlarge` per the capacity-optimized MixedInstancesPolicy on the
// `jenkins-ps3-arm-graviton` ASG (Terraform module: jenkins-arm-fleet).
//
// IAM: the `Ec2FleetPluginAutoScaling` policy is attached to this master's role
// by the TF module, so `awsCredentialsId = ""` (use the master IAM instance
// profile, not a stored AWS credential).
//
// SSH: the `percona-jenkins` private-key credential must be present in
// Jenkins; it must match the AWS key pair set on the Launch Template
// (`key_name = "percona-jenkins"`).
//
// Idempotent: re-applying via `jenkins iac deploy` removes the prior cloud
// instance with the same name before adding the fresh one.

import com.amazon.jenkins.ec2fleet.EC2FleetCloud
import hudson.plugins.sshslaves.SSHConnector
import hudson.plugins.sshslaves.verifiers.NonVerifyingKeyVerificationStrategy
import jenkins.model.Jenkins
import java.util.logging.Logger

final Logger LOG = Logger.getLogger('ec2FleetCloud')
final String CLOUD_NAME   = 'arm-graviton-fleet'
final String REGION       = 'eu-west-1'
final String ASG_NAME     = 'jenkins-ps3-arm-graviton'
final String LABEL        = 'docker-32gb-aarch64'
final String SSH_CRED_ID  = 'percona-jenkins'

Jenkins.instance.clouds.findAll { it.name == CLOUD_NAME }.each {
    Jenkins.instance.clouds.remove(it)
}

def sshConn = new SSHConnector(
    22, SSH_CRED_ID,
    '', '', '', '',
    null, null, null,
    new NonVerifyingKeyVerificationStrategy()
)

def fleet = new EC2FleetCloud(
    CLOUD_NAME,
    '',                          // awsCredentialsId (empty -> master IAM instance profile)
    '',                          // credentialsId (legacy field, kept empty)
    REGION,
    '',                          // endpoint
    ASG_NAME,
    LABEL,
    '/mnt/jenkins',              // fsRoot
    sshConn,                     // computerConnector
    true,                        // privateIpUsed -- master + worker share the master VPC; public-IP SSH egress is not guaranteed across regions (ps57 smoke confirmed this)
    false,                       // alwaysReconnect
    (Integer) 10,                // idleMinutes (NON-zero: 0 = never scale down)
    0,                           // minSize
    16,                          // maxSize (matches TF)
    0,                           // minSpareSize
    1,                           // numExecutors
    true,                        // addNodeOnlyIfRunning
    false,                       // restrictUsage
    '-1',                        // maxTotalUses (-1 = unlimited)
    false,                       // disableTaskResubmit
    (Integer) 600,               // initOnlineTimeoutSec
    (Integer) 15,                // initOnlineCheckIntervalSec
    (Integer) 10,                // cloudStatusIntervalSec
    false,                       // noDelayProvision
    false,                       // scaleExecutorsByWeight
    new EC2FleetCloud.NoScaler()
)

Jenkins.instance.clouds.add(fleet)
LOG.info("ec2FleetCloud: registered cloud='${CLOUD_NAME}' label='${LABEL}' fleet='${ASG_NAME}' region='${REGION}'")
