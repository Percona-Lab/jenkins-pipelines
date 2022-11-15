library changelog: false, identifier: 'lib@master', retriever: modernSCM([
    $class: 'GitSCMSource',
    remote: 'https://github.com/Percona-Lab/jenkins-pipelines.git'
]) _

void installDependencies() {
    sh '''
        export PATH=${PATH}:~/.local/bin
        sudo yum install -y git python3-pip jq
        sudo amazon-linux-extras install ansible2
        python3 -m venv venv
        source venv/bin/activate
        python3 -m pip install setuptools wheel
        python3 -m pip install molecule==2.22 boto boto3 paramiko
    '''
    
    sh '''
        rm -rf package-testing
        git clone https://github.com/Percona-QA/package-testing --branch master
    '''

}

void runMoleculeAction(String action, String version, String scenario) {
    def awsCredentials = [
        sshUserPrivateKey(
            credentialsId: 'MOLECULE_AWS_PRIVATE_KEY',
            keyFileVariable: 'MOLECULE_AWS_PRIVATE_KEY',
            passphraseVariable: '',
            usernameVariable: ''
        ),
        aws(
            accessKeyVariable: 'AWS_ACCESS_KEY_ID',
            credentialsId: '5d78d9c7-2188-4b16-8e31-4d5782c6ceaa',
            secretKeyVariable: 'AWS_SECRET_ACCESS_KEY'
        )
    ]

    withCredentials(awsCredentials) {
        sh """
            source venv/bin/activate
            export MOLECULE_DEBUG=0
            export install_repo=${params.INSTALL_REPO}
            cd package-testing/molecule/pxc

            cd ${version}-bootstrap
            export INSTANCE_PRIVATE_IP=\${BOOTSTRAP_INSTANCE_PRIVATE_IP}
            export INSTANCE_PUBLIC_IP=\${BOOTSTRAP_INSTANCE_PUBLIC_IP}            
            molecule ${action} -s ${scenario}
            cd -

            cd ${version}-common
            export INSTANCE_PRIVATE_IP=\${COMMON_INSTANCE_PRIVATE_IP}
            export INSTANCE_PUBLIC_IP=\${COMMON_INSTANCE_PUBLIC_IP}        
            molecule ${action} -s ${scenario}
            cd -
        """
    }
}

void setInventories(){

        sh """

            echo \"Setting up Key path based on the selection\"

            if [[ (${params.TEST_DIST} == "ubuntu-focal")  ||  (${params.TEST_DIST} == "ubuntu-bionic") ]];
            then
                SSH_USER="ubuntu"            
                KEYPATH_BOOTSTRAP="/home/ec2-user/.cache/molecule/pxc80-bootstrap/${params.TEST_DIST}/ssh_key-us-west-2"
                KEYPATH_COMMON="/home/ec2-user/.cache/molecule/pxc80-common/${params.TEST_DIST}/ssh_key-us-west-2"
            elif [[ (${params.TEST_DIST} == "debian-11") ||  (${params.TEST_DIST} == "debian-10") ]];
            then
                SSH_USER="admin"            
                KEYPATH_BOOTSTRAP="/home/ec2-user/.cache/molecule/pxc80-bootstrap/${params.TEST_DIST}/ssh_key-us-west-2"
                KEYPATH_COMMON="/home/ec2-user/.cache/molecule/pxc80-common/${params.TEST_DIST}/ssh_key-us-west-2"
            elif [[ (${params.TEST_DIST} == "ol-8") ]];
            then
                SSH_USER="ec2-user"
                KEYPATH_BOOTSTRAP="/home/ec2-user/.cache/molecule/pxc80-bootstrap/${params.TEST_DIST}/ssh_key-us-west-2"
                KEYPATH_COMMON="/home/ec2-user/.cache/molecule/pxc80-common/${params.TEST_DIST}/ssh_key-us-west-2"
            elif [[ (${params.TEST_DIST} == "centos-7") ]];
            then
                SSH_USER="centos"
                KEYPATH_BOOTSTRAP="/home/ec2-user/.cache/molecule/pxc80-bootstrap/${params.TEST_DIST}/ssh_key-us-west-2"
                KEYPATH_COMMON="/home/ec2-user/.cache/molecule/pxc80-common/${params.TEST_DIST}/ssh_key-us-west-2"
            else
                echo "OS Not yet in list of Keypath setup"
            fi

            echo \"printing path of bootstrap \$KEYPATH_BOOTSTRAP\"
            echo \"printing path of common  \$KEYPATH_COMMON\"
            echo \"printing user \$SSH_USER\"

            Bootstrap_Instance=\$(cat \${BOOTSTRAP_INSTANCE_PUBLIC_IP} | jq -r .[0] | jq [.instance] | jq -r .[])
            Bootstrap_Instance_Public_IP=\$(cat \${BOOTSTRAP_INSTANCE_PUBLIC_IP} | jq -r .[0] | jq [.public_ip] | jq -r .[])
            
            export ip_env=\$Bootstrap_Instance
            echo "\n \$Bootstrap_Instance ansible_host=\$Bootstrap_Instance_Public_IP  ansible_ssh_user=\$SSH_USER ansible_ssh_private_key_file=\$KEYPATH_BOOTSTRAP ansible_ssh_common_args='-o StrictHostKeyChecking=no' ip_env=\$Bootstrap_Instance" > ${WORKSPACE}/package-testing/molecule/pxc/${version}-bootstrap/playbooks/inventory

            export ip_env=\$Common_Instance_PXC2
            Common_Instance_PXC2=\$(cat \${COMMON_INSTANCE_PUBLIC_IP} | jq -r .[0] | jq [.instance] | jq -r .[])
            Common_Instance_PXC2_Public_IP=\$(cat \${COMMON_INSTANCE_PUBLIC_IP} | jq -r .[0] | jq [.public_ip] | jq -r .[])

            echo "\n \$Common_Instance_PXC2 ansible_host=\$Common_Instance_PXC2_Public_IP   ansible_ssh_user=\$SSH_USER ansible_ssh_private_key_file=\$KEYPATH_COMMON ansible_ssh_common_args='-o StrictHostKeyChecking=no'  ip_env=\$Common_Instance_PXC2" > ${WORKSPACE}/package-testing/molecule/pxc/${version}-common/playbooks/inventory

            export ip_env=\$Common_Instance_PXC3
            Common_Instance_PXC3=\$(cat \${COMMON_INSTANCE_PUBLIC_IP} | jq -r .[1] | jq [.instance] | jq -r .[])
            Common_Instance_PXC3_Public_IP=\$(cat \${COMMON_INSTANCE_PUBLIC_IP} | jq -r .[1] | jq [.public_ip] | jq -r .[])

            echo "\n \$Common_Instance_PXC3 ansible_host=\$Common_Instance_PXC3_Public_IP   ansible_ssh_user=\$SSH_USER ansible_ssh_private_key_file=\$KEYPATH_COMMON ansible_ssh_common_args='-o StrictHostKeyChecking=no'  ip_env=\$Common_Instance_PXC3" >> ${WORKSPACE}/package-testing/molecule/pxc/${version}-common/playbooks/inventory
            """

}

void runlogsbackup(String version) {
    def awsCredentials = [
        sshUserPrivateKey(
            credentialsId: 'MOLECULE_AWS_PRIVATE_KEY',
            keyFileVariable: 'MOLECULE_AWS_PRIVATE_KEY',
            passphraseVariable: '',
            usernameVariable: ''
        ),
        aws(
            accessKeyVariable: 'AWS_ACCESS_KEY_ID',
            credentialsId: '5d78d9c7-2188-4b16-8e31-4d5782c6ceaa',
            secretKeyVariable: 'AWS_SECRET_ACCESS_KEY'
        )
    ]

    withCredentials(awsCredentials) {
        sh """
            source venv/bin/activate

            echo "Running the logs backup task for pxc bootstrap node"
            ansible-playbook ${WORKSPACE}/package-testing/molecule/pxc/playbooks/logsbackup.yml -i ${WORKSPACE}/package-testing/molecule/pxc/${version}-bootstrap/playbooks/inventory

            echo "Running the logs backup task for pxc common node"
            ansible-playbook ${WORKSPACE}/package-testing/molecule/pxc/playbooks/logsbackup.yml -i ${WORKSPACE}/package-testing/molecule/pxc/${version}-common/playbooks/inventory
        """
    }
    

    
}


void setInstancePrivateIPEnvironment() {
    env.PXC1_IP = sh(
        script: 'jq -r \'.[] | select(.instance | startswith("pxc1")).private_ip\' ${BOOTSTRAP_INSTANCE_PRIVATE_IP}',
        returnStdout: true
    ).trim()
    env.PXC2_IP = sh(
        script: 'jq -r \'.[] | select(.instance | startswith("pxc2")).private_ip\' ${COMMON_INSTANCE_PRIVATE_IP}',
        returnStdout: true
    ).trim()
    env.PXC3_IP = sh(
        script: 'jq -r \'.[] | select(.instance | startswith("pxc3")).private_ip\' ${COMMON_INSTANCE_PRIVATE_IP}',
        returnStdout: true
    ).trim()
}

pipeline {
    agent {
        label 'micro-amazon'
    }

    options {
        skipDefaultCheckout()
    }

    environment {

        BOOTSTRAP_INSTANCE_PRIVATE_IP = "${WORKSPACE}/bootstrap_instance_private_ip.json"
        COMMON_INSTANCE_PRIVATE_IP = "${WORKSPACE}/common_instance_private_ip.json"

        BOOTSTRAP_INSTANCE_PUBLIC_IP = "${WORKSPACE}/bootstrap_instance_public_ip.json"
        COMMON_INSTANCE_PUBLIC_IP  = "${WORKSPACE}/common_instance_public_ip.json"

        JENWORKSPACE = "${env.WORKSPACE}"

    }

    parameters {
        choice(
            name: 'VERSION',
            choices: [
                'pxc80',
                'pxc57'
            ],
            description: 'PXC version to test'
        )
        choice(
            name: 'TEST_DIST',
            choices: [
                'ubuntu-focal',
                'ubuntu-bionic',
                'debian-11',
                'debian-10',
                'centos-7',
                'ol-8'               
            ],
            description: 'Distribution to run test'
        )
        choice(
            name: 'INSTALL_REPO',
            choices: [
                'testing',
                'main',
                'experimental'
            ],
            description: 'Repo to install packages from'
        )
    }

    stages {
        stage("Cleanup Workspace") {
            steps {                
                sh "sudo rm -rf ${WORKSPACE}/*"
            }
        }

        stage("Set up") {
            steps {             
                script{
                    currentBuild.displayName = "${env.BUILD_NUMBER}-${params.VERSION}-${params.VERSION}-${params.TEST_DIST}-${params.INSTALL_REPO}"                    
                }   
                echo "${JENWORKSPACE}"
                installDependencies()
            }
        }

        stage("Create") {
            steps {
                runMoleculeAction("create", params.VERSION, params.TEST_DIST)
                setInstancePrivateIPEnvironment()
            }
        }

        stage("Converge") {
            steps {
                script{
                    catchError(buildResult: 'UNSTABLE', stageResult: 'FAILURE'){
                        runMoleculeAction("converge", params.VERSION, params.TEST_DIST)
                    }
                }
            }
        }

        stage("Logs Backup ansible playbook") {
            steps {
                setInventories()
                runlogsbackup(params.VERSION)
            }
        }

    }

    post {
        always {
            script {
                runMoleculeAction("destroy", params.VERSION, params.TEST_DIST)
            }
            archiveArtifacts artifacts: 'PXC/**/*.tar.gz' , followSymlinks: false
        }
        unstable {
            slackSend channel: '#dev-server-qa', color: '#DEFF13', message: "[${env.JOB_NAME}]: Failed during the Package testing (Unstable Build) [${env.BUILD_URL}] Parameters: VERSION: ${params.VERSION} , TEST_DIST: ${params.TEST_DIST} , INSTALL_REPO: ${params.INSTALL_REPO}"
        }

        failure {
            slackSend channel: '#dev-server-qa', color: '#FF0000', message: "[${env.JOB_NAME}]: Failed during the Package testing (Build Failed) [${env.BUILD_URL}] Parameters: VERSION: ${params.VERSION} , TEST_DIST: ${params.TEST_DIST} , INSTALL_REPO: ${params.INSTALL_REPO}"
        }


    }
   
}
