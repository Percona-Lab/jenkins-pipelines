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
import jenkins.model.Jenkins

imageMap = [:]
imageMap['min-centos-7-x64'] = 'ami-994575fc'
imageMap['min-artful-x64'] = 'ami-db2919be'
imageMap['min-trusty-x64'] = 'ami-2ddeee48'
imageMap['min-wheezy-x64'] = 'ami-f4510b91'
imageMap['min-stretch-x64'] = 'ami-79c0f01c'
imageMap['min-jessie-x64'] = 'ami-c5ba9fa0'
imageMap['min-zesty-x64'] = 'ami-167f5773'
imageMap['min-centos-6-x64'] = 'ami-ff48629a'
imageMap['min-xenial-x64'] = 'ami-e82a1a8d'

userMap = [:]
userMap['min-centos-7-x64'] = 'centos'
userMap['min-centos-6-x64'] = 'centos'

initMap = [:]
initMap['min-centos-7-x64'] = '''
    until sudo yum makecache; do
        sleep 1
        echo try again
    done
    sudo yum -y install java-1.8.0-openjdk git
    sudo install -o centos -g centos -d /mnt
'''
initMap['min-centos-6-x64'] = initMap['min-centos-7-x64']

// https://github.com/jenkinsci/ec2-plugin/blob/ec2-1.39/src/main/java/hudson/plugins/ec2/SlaveTemplate.java
SlaveTemplate getTemplate(String OSType) {
    return new SlaveTemplate(
        imageMap[OSType],                       // String ami
        '',                                     // String zone
        new SpotConfiguration('0.03'),          // SpotConfiguration spotConfig
        'default',                              // String securityGroups
        '/mnt',                                 // String remoteFS
        InstanceType.fromValue('m4.large'),     // InstanceType type
        false,                                  // boolean ebsOptimized
        OSType,                                 // String labelString
        Node.Mode.NORMAL,                       // Node.Mode mode
        OSType,                                 // String description
        initMap[OSType],                        // String initScript
        '',                                     // String tmpDir
        '',                                     // String userData
        '1',                                    // String numExecutors
        'centos',                               // String remoteAdmin
        new UnixData('', '', '22'),             // AMITypeData amiType
        '',                                     // String jvmopts
        false,                                  // boolean stopOnTerminate
        'subnet-f2db0688',                      // String subnetId
        [
            new EC2Tag('Name', OSType),
            new EC2Tag('iit-billing-tag', 'jenkins-ps-slave')
        ],                                      // List<EC2Tag> tags
        '15',                                   // String idleTerminationMinutes
        false,                                  // boolean usePrivateDnsName
        '10',                                   // String instanceCapStr
        'arn:aws:iam::119175775298:instance-profile/jenkins-ps-slave', // String iamInstanceProfile
        true,                                   // boolean deleteRootOnTermination
        false,                                  // boolean useEphemeralDevices
        false,                                  // boolean useDedicatedTenancy
        '',                                     // String launchTimeoutStr
        true,                                   // boolean associatePublicIp
        '/dev/sda1=:12:true:gp2',               // String customDeviceMapping
        false,                                  // boolean connectBySSHProcess
        false                                   // boolean connectUsingPublicIp
    )
}

// https://github.com/jenkinsci/ec2-plugin/blob/ec2-1.39/src/main/java/hudson/plugins/ec2/AmazonEC2Cloud.java
AmazonEC2Cloud amazonEC2Cloud = new AmazonEC2Cloud(
    'AWS-Dev',                              // String cloudName
    true,                                   // boolean useInstanceProfileForCredentials
    '',                                     // String credentialsId
    'us-east-2',                            // String region
    '''
-----BEGIN RSA PRIVATE KEY-----
-----END RSA PRIVATE KEY-----
''',                                        // String privateKey
    '100',                                  // String instanceCapStr
    [
        getTemplate('min-centos-7-x64'),
        getTemplate('min-centos-6-x64')
    ]                                       // List<? extends SlaveTemplate> templates
)

// get Jenkins instance
Jenkins jenkins = Jenkins.getInstance()

// add cloud configuration to Jenkins
jenkins.clouds.replace(amazonEC2Cloud)

// save current Jenkins state to disk
jenkins.save()
