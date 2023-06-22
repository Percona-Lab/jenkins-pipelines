library changelog: false, identifier: 'lib@master', retriever: modernSCM([
    $class: 'GitSCMSource',
    remote: 'https://github.com/Percona-Lab/jenkins-pipelines.git'
]) _

def runMoleculeAction(String action, String product_to_test, String scenario, String param_test_type, String test_repo, String version_check) {
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

            sh """
            mkdir -p "${WORKSPACE}/${product_to_test}/${params.node_to_test}/${param_test_type}/"
            """
                def install_repo="${test_repo}"

                if(action != "create" && action != "destroy"){
                    def IN_PXC1_IP = sh(
                        script: """cat ${INSTALL_COMMON_INSTANCE_PRIVATE_IP} | jq -r .[0] | jq [.private_ip] | jq -r .[]""",
                        returnStdout: true
                    ).trim()

                    def IN_PXC2_IP = sh(
                        script: """cat ${INSTALL_COMMON_INSTANCE_PRIVATE_IP} | jq -r .[1] | jq [.private_ip] | jq -r .[]""",
                        returnStdout: true
                    ).trim()

                    def IN_PXC3_IP = sh(
                        script: """cat ${INSTALL_COMMON_INSTANCE_PRIVATE_IP} | jq -r .[2] | jq [.private_ip] | jq -r .[]""",
                        returnStdout: true
                    ).trim()

                    sh """
                        echo 'install_repo: "${install_repo}"' > "${WORKSPACE}/${product_to_test}/${params.node_to_test}/install/envfile"
                        echo 'PXC1_IP: "${IN_PXC1_IP}"' >> "${WORKSPACE}/${product_to_test}/${params.node_to_test}/${param_test_type}/envfile"
                        echo 'PXC2_IP: "${IN_PXC2_IP}"' >> "${WORKSPACE}/${product_to_test}/${params.node_to_test}/${param_test_type}/envfile"
                        echo 'PXC3_IP: "${IN_PXC3_IP}"' >> "${WORKSPACE}/${product_to_test}/${params.node_to_test}/${param_test_type}/envfile"
                    """
                }

    withCredentials(awsCredentials) {

            if(action == "create" || action == "destroy"){
                sh"""
                    . virtenv/bin/activate
                    
                    
                    mkdir -p ${WORKSPACE}/install
                    
                    cd package-testing/molecule/pxc-keyring-test
                    
                    echo "param_test_type is ${param_test_type}"

                    cd pxc-80-setup-pkgs
                    molecule ${action} -s ${scenario}
                    cd -

                """
            }else{

                sh"""
                    . virtenv/bin/activate
                    cd package-testing/molecule/pxc-keyring-test

                    echo "param_test_type is ${param_test_type}"

                    cd pxc-80-setup-pkgs
                    molecule -e ${WORKSPACE}/${product_to_test}/${params.node_to_test}/${param_test_type}/envfile  ${action} -s ${scenario}
                    cd -
                """
            }
    }
}


void setInventories(String param_test_type){

                    def KEYPATH_BOOTSTRAP
                    def KEYPATH_COMMON
                    def SSH_USER

                    KEYPATH_COMMON="/home/centos/.cache/molecule/pxc-80-setup-pkgs/${params.node_to_test}/ssh_key-us-west-2"
                    

                    
                    if(("${params.node_to_test}" == "ubuntu-focal")  ||  ("${params.node_to_test}" == "ubuntu-bionic") || ("${params.node_to_test}" == "ubuntu-jammy")){
                        SSH_USER="ubuntu"            
                    }else if(("${params.node_to_test}" == "debian-11") ||  ("${params.node_to_test}" == "debian-10")){
                        SSH_USER="admin"
                    }else if(("${params.node_to_test}" == "ol-8") || ("${params.node_to_test}" == "ol-9") || ("${params.node_to_test}" == "min-amazon-2")){
                        SSH_USER="ec2-user"
                    }else if(("${params.node_to_test}" == "centos-7")){
                        SSH_USER="centos"
                    }else{
                        echo "OS Not yet in list of Keypath setup"
                    }


                    echo "${SSH_USER}"
                    echo "${KEYPATH_BOOTSTRAP}"
                    echo "${KEYPATH_COMMON}"

                    def INSTALL_Common_Instance_PXC1 = sh(
                        script: """cat ${INSTALL_COMMON_INSTANCE_PUBLIC_IP} | jq -r .[0] | jq [.instance] | jq -r .[]""",
                        returnStdout: true
                    ).trim()

                    def INSTALL_Common_Instance_PXC1_Public_IP = sh(
                        script: """cat ${INSTALL_COMMON_INSTANCE_PUBLIC_IP} | jq -r .[0] | jq [.public_ip] | jq -r .[]""",
                        returnStdout: true
                    ).trim()

                    def INSTALL_Common_Instance_PXC2 = sh(
                        script: """cat ${INSTALL_COMMON_INSTANCE_PUBLIC_IP} | jq -r .[1] | jq [.instance] | jq -r .[]""",
                        returnStdout: true
                    ).trim()

                    def INSTALL_Common_Instance_PXC2_Public_IP = sh(
                        script: """cat ${INSTALL_COMMON_INSTANCE_PUBLIC_IP} | jq -r .[1] | jq [.public_ip] | jq -r .[]""",
                        returnStdout: true
                    ).trim()

                    def INSTALL_Common_Instance_PXC3 = sh(
                        script: """cat ${INSTALL_COMMON_INSTANCE_PUBLIC_IP} | jq -r .[2] | jq [.instance] | jq -r .[]""",
                        returnStdout: true
                    ).trim()

                    def INSTALL_Common_Instance_PXC3_Public_IP = sh(
                        script: """cat ${INSTALL_COMMON_INSTANCE_PUBLIC_IP} | jq -r .[2] | jq [.public_ip] | jq -r .[]""",
                        returnStdout: true
                    ).trim()

                    sh """

                        mkdir -p "${WORKSPACE}/pxc-80-setup-pkgs/${params.node_to_test}/install/"
                        echo \"printing path of common  ${KEYPATH_COMMON}"
                        echo \"printing user ${SSH_USER}"
                        echo "\n ${INSTALL_Common_Instance_PXC1} ansible_host=${INSTALL_Common_Instance_PXC1_Public_IP}   ansible_ssh_user=${SSH_USER} ansible_ssh_private_key_file=${KEYPATH_COMMON} ansible_ssh_common_args='-o StrictHostKeyChecking=no'  ip_env=${INSTALL_Common_Instance_PXC1}" > ${WORKSPACE}/pxc-80-setup-pkgs/${params.node_to_test}/install/inventory            
                        echo "\n ${INSTALL_Common_Instance_PXC2} ansible_host=${INSTALL_Common_Instance_PXC2_Public_IP}   ansible_ssh_user=${SSH_USER} ansible_ssh_private_key_file=${KEYPATH_COMMON} ansible_ssh_common_args='-o StrictHostKeyChecking=no'  ip_env=${INSTALL_Common_Instance_PXC2}" >> ${WORKSPACE}/pxc-80-setup-pkgs/${params.node_to_test}/install/inventory
                        echo "\n ${INSTALL_Common_Instance_PXC3} ansible_host=${INSTALL_Common_Instance_PXC3_Public_IP}   ansible_ssh_user=${SSH_USER} ansible_ssh_private_key_file=${KEYPATH_COMMON} ansible_ssh_common_args='-o StrictHostKeyChecking=no'  ip_env=${INSTALL_Common_Instance_PXC3}" >> ${WORKSPACE}/pxc-80-setup-pkgs/${params.node_to_test}/install/inventory
                    """
}

void runlogsbackup(String product_to_test, String param_test_type) {
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

                    def IN_PXC1_IP = sh(
                        script: """cat ${INSTALL_COMMON_INSTANCE_PRIVATE_IP} | jq -r .[0] | jq [.private_ip] | jq -r .[]""",
                        returnStdout: true
                    ).trim()

                    def IN_PXC2_IP = sh(
                        script: """cat ${INSTALL_COMMON_INSTANCE_PRIVATE_IP} | jq -r .[1] | jq [.private_ip] | jq -r .[]""",
                        returnStdout: true
                    ).trim()

                    def IN_PXC3_IP = sh(
                        script: """cat ${INSTALL_COMMON_INSTANCE_PRIVATE_IP} | jq -r .[2] | jq [.private_ip] | jq -r .[]""",
                        returnStdout: true
                    ).trim()

                    sh """
                        echo 'PXC1_IP: "${IN_PXC1_IP}"' > "${WORKSPACE}/${product_to_test}/${params.node_to_test}/${param_test_type}/envfile"
                        echo 'PXC2_IP: "${IN_PXC2_IP}"' >> "${WORKSPACE}/${product_to_test}/${params.node_to_test}/${param_test_type}/envfile"
                        echo 'PXC3_IP: "${IN_PXC3_IP}"' >> "${WORKSPACE}/${product_to_test}/${params.node_to_test}/${param_test_type}/envfile"
                    """

    withCredentials(awsCredentials) {
        
        sh """
            . virtenv/bin/activate
            echo "Running the logs backup task for pxc common node"
            ansible-playbook ${WORKSPACE}/package-testing/molecule/pxc/playbooks/logsbackup.yml -i  ${WORKSPACE}/pxc-80-setup-pkgs/${params.node_to_test}/${param_test_type}/inventory -e @${WORKSPACE}/${product_to_test}/${params.node_to_test}/${param_test_type}/envfile
        """
    }
    

    
}


pipeline {

    agent {
        label 'min-centos-7-x64'
    }

    options {
        skipDefaultCheckout()
        timestamps()
    }

    environment {

        INSTALL_COMMON_INSTANCE_PRIVATE_IP = "${WORKSPACE}/install/common_instance_private_ip.json"
        INSTALL_COMMON_INSTANCE_PUBLIC_IP  = "${WORKSPACE}/install/common_instance_public_ip.json"

        JENWORKSPACE = "${env.WORKSPACE}"

        DESTROY_ENV = "no"
    }

    parameters {
        choice(
            name: 'product_to_test',
            choices: [
                'pxc80'
            ],
            description: 'PXC product_to_test to test'
        )
        choice(
            name: 'node_to_test',
            choices: [
                'ubuntu-focal',
                'ubuntu-bionic',
                'ubuntu-jammy',
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
            name: 'test_type',
            choices: [
                'install'
            ],
            description: 'Test type to run test'
        )
        choice(
            name: 'test_repo',
            choices: [
                'main',
                'testing'
            ],
            description: 'Test repo to run test'
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
                    currentBuild.displayName = "${env.BUILD_NUMBER}-${params.product_to_test}-${params.node_to_test}-${params.test_repo}-${params.test_type}"                              
                }   
                echo "${JENWORKSPACE}"
                installMolecule()
                    sh '''
                        sudo yum install -y epel-release 
                        sudo yum install -y git unzip jq ansible
                        ansible-galaxy install panchal_yash.percona_xtradb_cluster_role
                        rm -rf package-testing                    
                        git clone https://github.com/Percona-QA/package-testing --branch master
                    '''
            }
        }
        

        stage("1. Setup Molecule instances for Keyring tests") {

                    steps {
                        script{
                            def param_test_type = "install"   
                            runMoleculeAction("create", params.product_to_test, params.node_to_test, "install", params.test_repo, "yes")
                        }
                    }

        }

        stage("2. Config Molecule Instances") {
                        steps {                
                            script{
                                def param_test_type = "install" 
                                echo "Always INSTALL"
                                setInventories("install")


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

                                def IN_PXC1_IP = sh(
                                    script: """cat ${INSTALL_COMMON_INSTANCE_PRIVATE_IP} | jq -r .[0] | jq [.private_ip] | jq -r .[]""",
                                    returnStdout: true
                                ).trim()

                                def IN_PXC2_IP = sh(
                                    script: """cat ${INSTALL_COMMON_INSTANCE_PRIVATE_IP} | jq -r .[1] | jq [.private_ip] | jq -r .[]""",
                                    returnStdout: true
                                ).trim()

                                def IN_PXC3_IP = sh(
                                    script: """cat ${INSTALL_COMMON_INSTANCE_PRIVATE_IP} | jq -r .[2] | jq [.private_ip] | jq -r .[]""",
                                    returnStdout: true
                                ).trim()


                                def INSTALL_Common_Instance_PXC1_Public_IP = sh(
                                    script: """cat ${INSTALL_COMMON_INSTANCE_PUBLIC_IP} | jq -r .[0] | jq [.public_ip] | jq -r .[]""",
                                    returnStdout: true
                                ).trim()


                                def INSTALL_Common_Instance_PXC2_Public_IP = sh(
                                    script: """cat ${INSTALL_COMMON_INSTANCE_PUBLIC_IP} | jq -r .[1] | jq [.public_ip] | jq -r .[]""",
                                    returnStdout: true
                                ).trim()


                                def INSTALL_Common_Instance_PXC3_Public_IP = sh(
                                    script: """cat ${INSTALL_COMMON_INSTANCE_PUBLIC_IP} | jq -r .[2] | jq [.public_ip] | jq -r .[]""",
                                    returnStdout: true
                                ).trim()

                                sh """

                                    sudo yum install wget -y
                                    echo "run the keyring tests"
                                    wget https://raw.githubusercontent.com/Percona-QA/package-testing/master/scripts/pxc-keyring-test-pks.sh
                                    chmod +x pxc-keyring-test-pks.sh
                                    
                                    echo 'PXC1_IP: "${IN_PXC1_IP}"' > "${WORKSPACE}/${product_to_test}/${params.node_to_test}/${param_test_type}/envfile"
                                    echo 'PXC2_IP: "${IN_PXC2_IP}"' >> "${WORKSPACE}/${product_to_test}/${params.node_to_test}/${param_test_type}/envfile"
                                    echo 'PXC3_IP: "${IN_PXC3_IP}"' >> "${WORKSPACE}/${product_to_test}/${params.node_to_test}/${param_test_type}/envfile"

                                    sed -i 's/DB1_PRIV/${IN_PXC1_IP}/g' "pxc-keyring-test-pks.sh"
                                    sed -i 's/DB2_PRIV/${IN_PXC2_IP}/g' "pxc-keyring-test-pks.sh"
                                    sed -i 's/DB3_PRIV/${IN_PXC3_IP}/g' "pxc-keyring-test-pks.sh"

                                    sed -i 's/DB1_PUB/${INSTALL_Common_Instance_PXC1_Public_IP}/g' "pxc-keyring-test-pks.sh"
                                    sed -i 's/DB2_PUB/${INSTALL_Common_Instance_PXC2_Public_IP}/g' "pxc-keyring-test-pks.sh"
                                    sed -i 's/DB3_PUB/${INSTALL_Common_Instance_PXC3_Public_IP}/g' "pxc-keyring-test-pks.sh"


                                """

                                sh """
                                    echo "Cating the file after sed"
                                    cat pxc-keyring-test-pks.sh
                                """
                            
                                withCredentials(awsCredentials) {
                                
                                    sh """
                                        echo "ssh-rsa AAAAB3NzaC1yc2EAAAADAQABAAABgQD6iGdDs3A9vLPFPmJO3pE5TnKBT6grWis3YFcmrMCIj5RsnIdrRRg6Ull0h8ErP+4pyXEGvmwMgEWJ0NBPZL0KynQufLUTFstInEiujLpUsEfj8HpBK25w+/VukT2nX/7UagitH1cZRarAmObtU67cAtOFwBCyM1v2SoeYCKpPyxA2+MVeVVYJnkn3yUTCYDwt77XgeqS4qZ4VyuckiASLAD0/0A3wb81lm2hDB5tZOO50A6ZxdHw9SWGAgEA/i3O+4DPkJ2zd5OntaEIrSHHNT1I8D/BJyFYI9odVdnX+wwfKimqNMnn4Di3lZs2HYdiJ6CP12lp1JrksXH+zqQWvJVwM1rxJ/e638OS9rSfRlzL5TwlEKFTaE48KehpJFjXK0mpG3fbV7NU9K49Gi3gnxNKUEwINlJGLK8d04zlO2gnpkQcrq0HBIN6LAEcDsVRDrNZsERO5I9tw+bBxmmzEeMiU/2NEeLHqqoYPqO3Y27S9xZUvBoI86HcOQwlTwnk= root@yash-ThinkPad-P15-Gen-2i" >> ~/.ssh/authorized_keys
                                        sudo yum install wget -y
                                        curl "https://awscli.amazonaws.com/awscli-exe-linux-x86_64.zip" -o "/tmp/awscliv2.zip"
                                        unzip -o /tmp/awscliv2.zip -d /tmp 
                                        cd /tmp/aws && sudo ./install
                                        cd ~/
                                    """

                                    sh """
                                        . virtenv/bin/activate
                                        echo -e "\n\n\n\n" | ssh-keygen -t rsa
                                        export KEY=\$(cat ~/.ssh/id_rsa.pub)
                                        echo "KEY: \"\$KEY\"" > ENVFILE
                                        ansible-playbook ${WORKSPACE}/package-testing/molecule/pxc-keyring-test/pxc-80-setup-pkgs/playbooks/config-packages.yml -i  ${WORKSPACE}/pxc-80-setup-pkgs/${params.node_to_test}/${param_test_type}/inventory -e @ENVFILE

                                        echo "Save the ssh keys of all molecule nodes to a file."
                                        
                                        ssh mysql@${INSTALL_Common_Instance_PXC1_Public_IP} "cat ~/.ssh/id_rsa.pub" > FILE
                                        ssh mysql@${INSTALL_Common_Instance_PXC2_Public_IP} "cat ~/.ssh/id_rsa.pub" >> FILE                                              
                                        ssh mysql@${INSTALL_Common_Instance_PXC3_Public_IP} "cat ~/.ssh/id_rsa.pub" >> FILE
                                        ssh root@${INSTALL_Common_Instance_PXC1_Public_IP} "cat ~/.ssh/id_rsa.pub" >> FILE
                                        ssh root@${INSTALL_Common_Instance_PXC2_Public_IP} "cat ~/.ssh/id_rsa.pub" >> FILE                                              
                                        ssh root@${INSTALL_Common_Instance_PXC3_Public_IP} "cat ~/.ssh/id_rsa.pub" >> FILE

                                        cat FILE

                                        scp FILE mysql@${INSTALL_Common_Instance_PXC1_Public_IP}:~/
                                        scp FILE mysql@${INSTALL_Common_Instance_PXC2_Public_IP}:~/
                                        scp FILE mysql@${INSTALL_Common_Instance_PXC3_Public_IP}:~/


                                        ssh mysql@${INSTALL_Common_Instance_PXC1_Public_IP} 'cat ~/FILE >> ~/.ssh/authorized_keys'
                                        ssh mysql@${INSTALL_Common_Instance_PXC2_Public_IP} 'cat ~/FILE >> ~/.ssh/authorized_keys'
                                        ssh mysql@${INSTALL_Common_Instance_PXC3_Public_IP} 'cat ~/FILE >> ~/.ssh/authorized_keys'
                                        ssh root@${INSTALL_Common_Instance_PXC1_Public_IP} 'cat /home/mysql/FILE >> ~/.ssh/authorized_keys'
                                        ssh root@${INSTALL_Common_Instance_PXC2_Public_IP} 'cat /home/mysql/FILE >> ~/.ssh/authorized_keys'
                                        ssh root@${INSTALL_Common_Instance_PXC3_Public_IP} 'cat /home/mysql/FILE >> ~/.ssh/authorized_keys'

                                        echo "Moved the stuff successfully"
                                    """

                                        runMoleculeAction("converge", params.product_to_test, params.node_to_test, "install", params.test_repo, "yes")
                                        

                                }
                            }
                        }
        }

        stage("3. Running Keyring Scripts on the Molecule Instances") {
                        steps {                
                            script{
                                        sh """
                                        
                                        ./pxc-keyring-test-pks.sh || true

                                        """

                            }

                        }
        }
        stage("4. Take Logs Backup and Destroy Molecule Instances") {
                    
                    steps {
                        script{
                            echo "Take Log Backups of all the Molecule Nodes"
                            runlogsbackup(params.product_to_test, "install")
                        }
                    }
        }

        }

        post {

            always {
                catchError(buildResult: 'UNSTABLE', stageResult: 'FAILURE'){
                    archiveArtifacts artifacts: 'PXC/**/*.tar.gz' , followSymlinks: false
                    echo "Destroy Molecule Instances"
                    runMoleculeAction("destroy", params.product_to_test, params.node_to_test, "install", params.test_repo, "yes")
                            
                }
            }

            unstable {
                    slackSend channel: '#dev-server-qa', color: '#DEFF13', message: "[${env.JOB_NAME}]: Failed during the Package testing (Unstable Build) [${env.BUILD_URL}] Parameters: product_to_test: ${params.product_to_test} , node_to_test: ${params.node_to_test} , test_repo: ${params.test_repo}, test_type: ${params.test_type}"
            }

            failure {
                    slackSend channel: '#dev-server-qa', color: '#FF0000', message: "[${env.JOB_NAME}]: Failed during the Package testing (Build Failed) [${env.BUILD_URL}] Parameters: product_to_test: ${params.product_to_test} , node_to_test: ${params.node_to_test} , test_repo: ${params.test_repo}, test_type: ${params.test_type}"
            }


        }

    }


   


