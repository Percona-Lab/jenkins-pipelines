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

void runMoleculeAction(String action, String product_to_test, String scenario) {
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
            export install_repo=${params.install_repo}
            
            if [[ ${product_to_test} = "pxc57" ]];
            then
                export pxc57repo=${params.pxc57_repo}
            else
                echo "Product is not pxc57 so skipping value assignment to it"
            fi
            
            cd package-testing/molecule/pxc

            cd ${product_to_test}-bootstrap
            export INSTANCE_PRIVATE_IP=\${BOOTSTRAP_INSTANCE_PRIVATE_IP}
            export INSTANCE_PUBLIC_IP=\${BOOTSTRAP_INSTANCE_PUBLIC_IP}            
            molecule ${action} -s ${scenario}
            cd -

            cd ${product_to_test}-common
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

            if [[ (${params.node_to_test} == "ubuntu-focal")  ||  (${params.node_to_test} == "ubuntu-bionic") || (${params.node_to_test} == "ubuntu-jammy") ]];
            then
                SSH_USER="ubuntu"            
                KEYPATH_BOOTSTRAP="/home/ec2-user/.cache/molecule/${product_to_test}-bootstrap/${params.node_to_test}/ssh_key-us-west-2"
                KEYPATH_COMMON="/home/ec2-user/.cache/molecule/${product_to_test}-common/${params.node_to_test}/ssh_key-us-west-2"
            elif [[ (${params.node_to_test} == "debian-11") ||  (${params.node_to_test} == "debian-10") ]];
            then
                SSH_USER="admin"            
                KEYPATH_BOOTSTRAP="/home/ec2-user/.cache/molecule/${product_to_test}-bootstrap/${params.node_to_test}/ssh_key-us-west-2"
                KEYPATH_COMMON="/home/ec2-user/.cache/molecule/${product_to_test}-common/${params.node_to_test}/ssh_key-us-west-2"
            elif [[ (${params.node_to_test} == "ol-8") || (${params.node_to_test} == "ol-9") || (${params.node_to_test} == "min-amazon-2") ]];
            then
                SSH_USER="ec2-user"
                KEYPATH_BOOTSTRAP="/home/ec2-user/.cache/molecule/${product_to_test}-bootstrap/${params.node_to_test}/ssh_key-us-west-2"
                KEYPATH_COMMON="/home/ec2-user/.cache/molecule/${product_to_test}-common/${params.node_to_test}/ssh_key-us-west-2"
            elif [[ (${params.node_to_test} == "centos-7") ]];
            then
                SSH_USER="centos"
                KEYPATH_BOOTSTRAP="/home/ec2-user/.cache/molecule/${product_to_test}-bootstrap/${params.node_to_test}/ssh_key-us-west-2"
                KEYPATH_COMMON="/home/ec2-user/.cache/molecule/${product_to_test}-common/${params.node_to_test}/ssh_key-us-west-2"
            else
                echo "OS Not yet in list of Keypath setup"
            fi

            echo \"printing path of bootstrap \$KEYPATH_BOOTSTRAP\"
            echo \"printing path of common  \$KEYPATH_COMMON\"
            echo \"printing user \$SSH_USER\"

            Bootstrap_Instance=\$(cat \${BOOTSTRAP_INSTANCE_PUBLIC_IP} | jq -r .[0] | jq [.instance] | jq -r .[])
            Bootstrap_Instance_Public_IP=\$(cat \${BOOTSTRAP_INSTANCE_PUBLIC_IP} | jq -r .[0] | jq [.public_ip] | jq -r .[])
            
            export ip_env=\$Bootstrap_Instance
            echo "\n \$Bootstrap_Instance ansible_host=\$Bootstrap_Instance_Public_IP  ansible_ssh_user=\$SSH_USER ansible_ssh_private_key_file=\$KEYPATH_BOOTSTRAP ansible_ssh_common_args='-o StrictHostKeyChecking=no' ip_env=\$Bootstrap_Instance" > ${WORKSPACE}/package-testing/molecule/pxc/${product_to_test}-bootstrap/playbooks/inventory

            export ip_env=\$Common_Instance_PXC2
            Common_Instance_PXC2=\$(cat \${COMMON_INSTANCE_PUBLIC_IP} | jq -r .[0] | jq [.instance] | jq -r .[])
            Common_Instance_PXC2_Public_IP=\$(cat \${COMMON_INSTANCE_PUBLIC_IP} | jq -r .[0] | jq [.public_ip] | jq -r .[])

            echo "\n \$Common_Instance_PXC2 ansible_host=\$Common_Instance_PXC2_Public_IP   ansible_ssh_user=\$SSH_USER ansible_ssh_private_key_file=\$KEYPATH_COMMON ansible_ssh_common_args='-o StrictHostKeyChecking=no'  ip_env=\$Common_Instance_PXC2" > ${WORKSPACE}/package-testing/molecule/pxc/${product_to_test}-common/playbooks/inventory

            export ip_env=\$Common_Instance_PXC3
            Common_Instance_PXC3=\$(cat \${COMMON_INSTANCE_PUBLIC_IP} | jq -r .[1] | jq [.instance] | jq -r .[])
            Common_Instance_PXC3_Public_IP=\$(cat \${COMMON_INSTANCE_PUBLIC_IP} | jq -r .[1] | jq [.public_ip] | jq -r .[])

            echo "\n \$Common_Instance_PXC3 ansible_host=\$Common_Instance_PXC3_Public_IP   ansible_ssh_user=\$SSH_USER ansible_ssh_private_key_file=\$KEYPATH_COMMON ansible_ssh_common_args='-o StrictHostKeyChecking=no'  ip_env=\$Common_Instance_PXC3" >> ${WORKSPACE}/package-testing/molecule/pxc/${product_to_test}-common/playbooks/inventory
            """

}

void runlogsbackup(String product_to_test) {
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
            ansible-playbook ${WORKSPACE}/package-testing/molecule/pxc/playbooks/logsbackup.yml -i ${WORKSPACE}/package-testing/molecule/pxc/${product_to_test}-bootstrap/playbooks/inventory

            echo "Running the logs backup task for pxc common node"
            ansible-playbook ${WORKSPACE}/package-testing/molecule/pxc/playbooks/logsbackup.yml -i ${WORKSPACE}/package-testing/molecule/pxc/${product_to_test}-common/playbooks/inventory
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
            name: 'product_to_test',
            choices: [
                'pxc80',
                'pxc57'
            ],
            description: 'PXC product_to_test to test'
        )
        choice(
            name: 'node_to_test',
            choices: [
                'ubuntu-jammy',
                'ubuntu-focal',
                'ubuntu-bionic',
                'debian-11',
                'debian-10',
                'centos-7',
                'ol-8',
                'ol-9',
                'min-amazon-2'
            ],
            description: 'Distribution to run test'
        )
        choice(
            name: 'install_repo',
            choices: [
                'testing',
                'main',
                'experimental'
            ],
            description: 'Repo to install packages from'
        )
        choice(
            name: "pxc57_repo",
            choices: ["original","pxc57" ],
            description: "PXC-5.7 packages are located in 2 repos: pxc-57 and original and both should be tested. Choose which repo to use for test."
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
                    currentBuild.displayName = "${env.BUILD_NUMBER}-${params.product_to_test}-${params.node_to_test}-${params.install_repo}"                    
                }   
                echo "${JENWORKSPACE}"
                installDependencies()
            }
        }

        stage("Create") {
            steps {
                runMoleculeAction("create", params.product_to_test, params.node_to_test)
                setInstancePrivateIPEnvironment()
            }
        }

        stage("Converge") {
            steps {
                script{
                    catchError(buildResult: 'UNSTABLE', stageResult: 'FAILURE'){
                        runMoleculeAction("converge", params.product_to_test, params.node_to_test)
                    }
                }
            }
        }

        stage("Logs Backup ansible playbook") {
            steps {
                setInventories()
                runlogsbackup(params.product_to_test)
            }
        }

    }

    post {
        always {
            script {
                runMoleculeAction("destroy", params.product_to_test, params.node_to_test)
            }
            archiveArtifacts artifacts: 'PXC/**/*.tar.gz' , followSymlinks: false
        }
        unstable {
            slackSend channel: '#dev-server-qa', color: '#DEFF13', message: "[${env.JOB_NAME}]: Failed during the Package testing (Unstable Build) [${env.BUILD_URL}] Parameters: product_to_test: ${params.product_to_test} , node_to_test: ${params.node_to_test} , install_repo: ${params.install_repo}"
        }

        failure {
            slackSend channel: '#dev-server-qa', color: '#FF0000', message: "[${env.JOB_NAME}]: Failed during the Package testing (Build Failed) [${env.BUILD_URL}] Parameters: product_to_test: ${params.product_to_test} , node_to_test: ${params.node_to_test} , install_repo: ${params.install_repo}"
        }


    }
   
}
