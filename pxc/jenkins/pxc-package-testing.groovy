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
            credentialsId: 'c42456e5-c28d-4962-b32c-b75d161bff27',
            secretKeyVariable: 'AWS_SECRET_ACCESS_KEY'
        )
    ]


            if("${product_to_test}" == "pxc57"){
                def pxc57repo="${params.pxc57_repo}"
            }else{
                echo "Product is not pxc57 so skipping value assignment to it"
            }
            
            echo "check var param_test_type ${param_test_type}"

            sh """
            mkdir -p "${WORKSPACE}/${product_to_test}/${params.node_to_test}/${param_test_type}/"
            """

	        if(param_test_type == "install"){   
                def install_repo="${test_repo}"
                def check_version="${version_check}"
                if(action != "create" && action != "destroy"){
                    def IN_PXC1_IP = sh(
                        script: """cat ${INSTALL_BOOTSTRAP_INSTANCE_PRIVATE_IP} | jq -r .[0] | jq [.private_ip] | jq -r .[]""",
                        returnStdout: true
                    ).trim()

                    def IN_PXC2_IP = sh(
                        script: """cat ${INSTALL_COMMON_INSTANCE_PRIVATE_IP} | jq -r .[0] | jq [.private_ip] | jq -r .[]""",
                        returnStdout: true
                    ).trim()

                    def IN_PXC3_IP = sh(
                        script: """cat ${INSTALL_COMMON_INSTANCE_PRIVATE_IP} | jq -r .[1] | jq [.private_ip] | jq -r .[]""",
                        returnStdout: true
                    ).trim()

                    sh """
                        echo 'install_repo: "${install_repo}"' > "${WORKSPACE}/${product_to_test}/${params.node_to_test}/install/envfile"
                        echo 'check_version: "${check_version}"' >> "${WORKSPACE}/${product_to_test}/${params.node_to_test}/install/envfile"
                        echo 'PXC1_IP: "${IN_PXC1_IP}"' >> "${WORKSPACE}/${product_to_test}/${params.node_to_test}/install/envfile"
                        echo 'PXC2_IP: "${IN_PXC2_IP}"' >> "${WORKSPACE}/${product_to_test}/${params.node_to_test}/install/envfile"
                        echo 'PXC3_IP: "${IN_PXC3_IP}"' >> "${WORKSPACE}/${product_to_test}/${params.node_to_test}/install/envfile"
                    """
                }else{
                    echo "Not setting up VARS as in create or destroy stage"
                    sh """
                        echo 'install_repo: "${install_repo}"' > "${WORKSPACE}/${product_to_test}/${params.node_to_test}/install/envfile"
                        echo 'check_version: "${check_version}"' >> "${WORKSPACE}/${product_to_test}/${params.node_to_test}/install/envfile"
                    """
                }
            }else if(param_test_type == "upgrade"){
                def install_repo="main"
                def check_version="${version_check}"
                def upgrade_repo="${test_repo}"

                if(action != "create" && action != "destroy"){
                    def UP_PXC1_IP = sh(
                        script: """cat ${UPGRADE_BOOTSTRAP_INSTANCE_PRIVATE_IP} | jq -r .[0] | jq [.private_ip] | jq -r .[]""",
                        returnStdout: true
                    ).trim()

                    def UP_PXC2_IP = sh(
                        script: """cat ${UPGRADE_COMMON_INSTANCE_PRIVATE_IP} | jq -r .[0] | jq [.private_ip] | jq -r .[]""",
                        returnStdout: true
                    ).trim()

                    def UP_PXC3_IP = sh(
                        script: """cat ${UPGRADE_COMMON_INSTANCE_PRIVATE_IP} | jq -r .[1] | jq [.private_ip] | jq -r .[]""",
                        returnStdout: true
                    ).trim()
                    sh """
                        echo 'install_repo: "${install_repo}"' > "${WORKSPACE}/${product_to_test}/${params.node_to_test}/upgrade/envfile"
                        echo 'check_version: "${check_version}"' >> "${WORKSPACE}/${product_to_test}/${params.node_to_test}/upgrade/envfile"
                        echo 'upgrade_repo: "${upgrade_repo}"' >> "${WORKSPACE}/${product_to_test}/${params.node_to_test}/upgrade/envfile"
                        echo 'PXC1_IP: "${UP_PXC1_IP}"' >> "${WORKSPACE}/${product_to_test}/${params.node_to_test}/upgrade/envfile"
                        echo 'PXC2_IP: "${UP_PXC2_IP}"' >> "${WORKSPACE}/${product_to_test}/${params.node_to_test}/upgrade/envfile"
                        echo 'PXC3_IP: "${UP_PXC3_IP}"' >> "${WORKSPACE}/${product_to_test}/${params.node_to_test}/upgrade/envfile"
                    """
                }else{
                    echo "Not setting up VARS as in create or destroy stage"
                    sh """
                    echo 'install_repo: "${install_repo}"' > "${WORKSPACE}/${product_to_test}/${params.node_to_test}/upgrade/envfile"
                    echo 'check_version: "${check_version}"' >> "${WORKSPACE}/${product_to_test}/${params.node_to_test}/upgrade/envfile"
                    echo 'upgrade_repo: "${upgrade_repo}"' >> "${WORKSPACE}/${product_to_test}/${params.node_to_test}/upgrade/envfile"
                    """
                }
            }else{
                echo "Unknown condition"
            }

    withCredentials(awsCredentials) {

            if(action == "create" || action == "destroy"){
                sh"""
                    . virtenv/bin/activate
                    
                    
                    mkdir -p ${WORKSPACE}/install
                    mkdir -p ${WORKSPACE}/upgrade
                    
                    cd package-testing/molecule/pxc
                    
                    echo "param_test_type is ${param_test_type}"

                    cd ${product_to_test}-bootstrap-${param_test_type}
                    molecule ${action} -s ${scenario}
                    cd -

                    cd ${product_to_test}-common-${param_test_type}
                    molecule ${action} -s ${scenario}
                    cd -
                """
            }else{

                sh"""
                    . virtenv/bin/activate
                    cd package-testing/molecule/pxc

                    echo "param_test_type is ${param_test_type}"

                    cd ${product_to_test}-bootstrap-${param_test_type}
                    molecule -e ${WORKSPACE}/${product_to_test}/${params.node_to_test}/${param_test_type}/envfile ${action} -s ${scenario}
                    cd -

                    cd ${product_to_test}-common-${param_test_type}
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

                    KEYPATH_BOOTSTRAP="/home/centos/.cache/molecule/${product_to_test}-bootstrap-${param_test_type}/${params.node_to_test}/ssh_key-us-west-1"
                    KEYPATH_COMMON="/home/centos/.cache/molecule/${product_to_test}-common-${param_test_type}/${params.node_to_test}/ssh_key-us-west-1"


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


                if(param_test_type == "install"){

                    def INSTALL_Bootstrap_Instance = sh(
                        script: """cat ${INSTALL_BOOTSTRAP_INSTANCE_PUBLIC_IP} | jq -r .[0] | jq [.instance] | jq -r .[]""",
                        returnStdout: true
                    ).trim()

                    def INSTALL_Bootstrap_Instance_Public_IP = sh(
                        script: """cat ${INSTALL_BOOTSTRAP_INSTANCE_PUBLIC_IP} | jq -r .[0] | jq [.public_ip] | jq -r .[]""",
                        returnStdout: true
                    ).trim()

                    def INSTALL_Common_Instance_PXC2 = sh(
                        script: """cat ${INSTALL_COMMON_INSTANCE_PUBLIC_IP} | jq -r .[0] | jq [.instance] | jq -r .[]""",
                        returnStdout: true
                    ).trim()

                    def INSTALL_Common_Instance_PXC2_Public_IP = sh(
                        script: """cat ${INSTALL_COMMON_INSTANCE_PUBLIC_IP} | jq -r .[0] | jq [.public_ip] | jq -r .[]""",
                        returnStdout: true
                    ).trim()

                    def INSTALL_Common_Instance_PXC3 = sh(
                        script: """cat ${INSTALL_COMMON_INSTANCE_PUBLIC_IP} | jq -r .[1] | jq [.instance] | jq -r .[]""",
                        returnStdout: true
                    ).trim()

                    def INSTALL_Common_Instance_PXC3_Public_IP = sh(
                        script: """cat ${INSTALL_COMMON_INSTANCE_PUBLIC_IP} | jq -r .[1] | jq [.public_ip] | jq -r .[]""",
                        returnStdout: true
                    ).trim()

                    sh """
                        mkdir -p "${WORKSPACE}/${product_to_test}-bootstrap/${params.node_to_test}/install/"
                        mkdir -p "${WORKSPACE}/${product_to_test}-common/${params.node_to_test}/install/"
                        echo \"printing path of bootstrap ${KEYPATH_BOOTSTRAP}"
                        echo \"printing path of common  ${KEYPATH_COMMON}"
                        echo \"printing user ${SSH_USER}"
                        echo "\n ${INSTALL_Bootstrap_Instance} ansible_host=${INSTALL_Bootstrap_Instance_Public_IP}  ansible_ssh_user=${SSH_USER} ansible_ssh_private_key_file=${KEYPATH_BOOTSTRAP} ansible_ssh_common_args='-o StrictHostKeyChecking=no' ip_env=${INSTALL_Bootstrap_Instance}" > ${WORKSPACE}/${product_to_test}-bootstrap/${params.node_to_test}/install/inventory            
                        echo "\n ${INSTALL_Common_Instance_PXC2} ansible_host=${INSTALL_Common_Instance_PXC2_Public_IP}   ansible_ssh_user=${SSH_USER} ansible_ssh_private_key_file=${KEYPATH_COMMON} ansible_ssh_common_args='-o StrictHostKeyChecking=no'  ip_env=${INSTALL_Common_Instance_PXC2}" > ${WORKSPACE}/${product_to_test}-common/${params.node_to_test}/install/inventory
                        echo "\n ${INSTALL_Common_Instance_PXC3} ansible_host=${INSTALL_Common_Instance_PXC3_Public_IP}   ansible_ssh_user=${SSH_USER} ansible_ssh_private_key_file=${KEYPATH_COMMON} ansible_ssh_common_args='-o StrictHostKeyChecking=no'  ip_env=${INSTALL_Common_Instance_PXC3}" >> ${WORKSPACE}/${product_to_test}-common/${params.node_to_test}/install/inventory
                    """

                }else if(param_test_type == "upgrade"){

                    def UPGRADE_Bootstrap_Instance = sh(
                        script: """cat ${UPGRADE_BOOTSTRAP_INSTANCE_PUBLIC_IP} | jq -r .[0] | jq [.instance] | jq -r .[]""",
                        returnStdout: true
                    ).trim()

                    def UPGRADE_Bootstrap_Instance_Public_IP = sh(
                        script: """cat ${UPGRADE_BOOTSTRAP_INSTANCE_PUBLIC_IP} | jq -r .[0] | jq [.public_ip] | jq -r .[]""",
                        returnStdout: true
                    ).trim()

                    def UPGRADE_Common_Instance_PXC2 = sh(
                        script: """cat ${UPGRADE_COMMON_INSTANCE_PUBLIC_IP} | jq -r .[0] | jq [.instance] | jq -r .[]""",
                        returnStdout: true
                    ).trim()

                    def UPGRADE_Common_Instance_PXC2_Public_IP = sh(
                        script: """cat ${UPGRADE_COMMON_INSTANCE_PUBLIC_IP} | jq -r .[0] | jq [.public_ip] | jq -r .[]""",
                        returnStdout: true
                    ).trim()

                    def UPGRADE_Common_Instance_PXC3 = sh(
                        script: """cat ${UPGRADE_COMMON_INSTANCE_PUBLIC_IP} | jq -r .[1] | jq [.instance] | jq -r .[]""",
                        returnStdout: true
                    ).trim()

                    def UPGRADE_Common_Instance_PXC3_Public_IP = sh(
                        script: """cat ${UPGRADE_COMMON_INSTANCE_PUBLIC_IP} | jq -r .[1] | jq [.public_ip] | jq -r .[]""",
                        returnStdout: true
                    ).trim()

                    sh """
                        echo \"printing path of bootstrap ${KEYPATH_BOOTSTRAP}"
                        echo \"printing path of common  ${KEYPATH_COMMON}"
                        echo \"printing user ${SSH_USER}"
                        mkdir -p "${WORKSPACE}/${product_to_test}-bootstrap/${params.node_to_test}/upgrade/"
                        mkdir -p "${WORKSPACE}/${product_to_test}-common/${params.node_to_test}/upgrade/"
                        echo "\n ${UPGRADE_Bootstrap_Instance} ansible_host=${UPGRADE_Bootstrap_Instance_Public_IP}  ansible_ssh_user=${SSH_USER} ansible_ssh_private_key_file=${KEYPATH_BOOTSTRAP} ansible_ssh_common_args='-o StrictHostKeyChecking=no' ip_env=${UPGRADE_Bootstrap_Instance}" > ${WORKSPACE}/${product_to_test}-bootstrap/${params.node_to_test}/upgrade/inventory            
                        echo "\n ${UPGRADE_Common_Instance_PXC2} ansible_host=${UPGRADE_Common_Instance_PXC2_Public_IP}   ansible_ssh_user=${SSH_USER} ansible_ssh_private_key_file=${KEYPATH_COMMON} ansible_ssh_common_args='-o StrictHostKeyChecking=no'  ip_env=${UPGRADE_Common_Instance_PXC2}" > ${WORKSPACE}/${product_to_test}-common/${params.node_to_test}/upgrade/inventory
                        echo "\n ${UPGRADE_Common_Instance_PXC3} ansible_host=${UPGRADE_Common_Instance_PXC3_Public_IP}   ansible_ssh_user=${SSH_USER} ansible_ssh_private_key_file=${KEYPATH_COMMON} ansible_ssh_common_args='-o StrictHostKeyChecking=no'  ip_env=${UPGRADE_Common_Instance_PXC3}" >> ${WORKSPACE}/${product_to_test}-common/${params.node_to_test}/upgrade/inventory
                    """
                    
                }


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
            credentialsId: 'c42456e5-c28d-4962-b32c-b75d161bff27',
            secretKeyVariable: 'AWS_SECRET_ACCESS_KEY'
        )
    ]

    withCredentials(awsCredentials) {
        sh """
            . virtenv/bin/activate

            echo "Running the logs backup task for pxc bootstrap node"
            ansible-playbook ${WORKSPACE}/package-testing/molecule/pxc/playbooks/logsbackup.yml -i  ${WORKSPACE}/${product_to_test}-bootstrap/${params.node_to_test}/${param_test_type}/inventory -e @${WORKSPACE}/${product_to_test}/${params.node_to_test}/${param_test_type}/envfile

            echo "Running the logs backup task for pxc common node"
            ansible-playbook ${WORKSPACE}/package-testing/molecule/pxc/playbooks/logsbackup.yml -i  ${WORKSPACE}/${product_to_test}-common/${params.node_to_test}/${param_test_type}/inventory -e @${WORKSPACE}/${product_to_test}/${params.node_to_test}/${param_test_type}/envfile
        """
    }
    

    
}


pipeline {
    agent {
        label 'min-centos-7-x64'
    }

    options {
        skipDefaultCheckout()
    }

    environment {

        INSTALL_BOOTSTRAP_INSTANCE_PRIVATE_IP = "${WORKSPACE}/install/bootstrap_instance_private_ip.json"
        INSTALL_COMMON_INSTANCE_PRIVATE_IP = "${WORKSPACE}/install/common_instance_private_ip.json"

        INSTALL_BOOTSTRAP_INSTANCE_PUBLIC_IP = "${WORKSPACE}/install/bootstrap_instance_public_ip.json"
        INSTALL_COMMON_INSTANCE_PUBLIC_IP  = "${WORKSPACE}/install/common_instance_public_ip.json"



        UPGRADE_BOOTSTRAP_INSTANCE_PRIVATE_IP = "${WORKSPACE}/upgrade/bootstrap_instance_private_ip.json"
        UPGRADE_COMMON_INSTANCE_PRIVATE_IP = "${WORKSPACE}/upgrade/common_instance_private_ip.json"
        
        UPGRADE_BOOTSTRAP_INSTANCE_PUBLIC_IP = "${WORKSPACE}/upgrade/bootstrap_instance_public_ip.json"
        UPGRADE_COMMON_INSTANCE_PUBLIC_IP  = "${WORKSPACE}/upgrade/common_instance_public_ip.json"

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
	        name: 'test_repo',
            choices: [
                'testing',
                'main',
                'experimental'
            ],
            description: 'Repo to install packages from'
        )
        choice(
            name: 'test_type',
            choices: [
                'install_and_upgrade',
                'install',
                'upgrade'

            ],
            description: 'Set test type for testing'
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
                    currentBuild.displayName = "${env.BUILD_NUMBER}-${params.product_to_test}-${params.node_to_test}-${params.test_repo}-${params.test_type}"                    
                    if (( params.test_type == "upgrade" ) && ( params.test_repo == "main" )) {
                         echo "Skipping as the upgrade and main are not supported together."
                         echo "Exiting the Stage as the inputs are invalid."
                         currentBuild.result = 'UNSTABLE'
                    } else {
                         echo "Continue with the package tests"
                    }                
                }   
                echo "${JENWORKSPACE}"
                installMolecule()
                    sh '''
                        sudo yum install -y epel-release 
                        sudo yum install -y git jq
                        rm -rf package-testing                    
                        git clone https://github.com/Percona-QA/package-testing --branch master
                    '''
            }
        }
        
        stage("Run parallel Install and UPGRADE"){
            parallel{
                stage("INSTALL") {
                            when {
                                expression{params.test_type == "install" || params.test_type == "install_and_upgrade"}
                            }
                             
                            steps {
                                script{
                                    def param_test_type = "install"   
                                    echo "1. Creating Molecule Instances for running INSTALL PXC tests.. Molecule create step"
                                    runMoleculeAction("create", params.product_to_test, params.node_to_test, "install", params.test_repo, "yes")
                                    echo "2. Run Install scripts and tests for PXC INSTALL PXC tests.. Molecule converge step"
                                    catchError(buildResult: 'UNSTABLE', stageResult: 'FAILURE'){
                                        runMoleculeAction("converge", params.product_to_test, params.node_to_test, "install", params.test_repo, "yes")
                                    }
                                }
                            }
                            post{
                                always{
                                    script{
                                        def param_test_type = "install" 
                                        echo "Always INSTALL"
                                        echo "3. Take Backups of the Logs.. PXC INSTALL tests.."
                                        setInventories("install")
                                        runlogsbackup(params.product_to_test, "install")
                                        echo "4. Destroy the Molecule instances for the PXC INSTALL tests.."
                                        runMoleculeAction("destroy", params.product_to_test, params.node_to_test, "install", params.test_repo, "yes")
                                    }
                                }
                            }
                }

                stage("UPGRADE") {
                            when {
                                allOf{
                                    expression{params.test_type == "upgrade" || params.test_type == "install_and_upgrade"}
                                    expression{params.test_repo != "main"}                
                                }
                            }
                            steps {
                                script{
                                    echo "UPGRADE STAGE INSIDE"
                                    def param_test_type = "upgrade"   
                                    echo "1. Creating Molecule Instances for running PXC UPGRADE tests.. Molecule create step"
                                    runMoleculeAction("create", params.product_to_test, params.node_to_test, "upgrade", "main", "no")
                                    setInventories("upgrade")
                                    echo "2. Run Install scripts and tests for running PXC UPGRADE tests.. Molecule converge step"
                                        catchError(buildResult: 'UNSTABLE', stageResult: 'FAILURE'){
                                            runMoleculeAction("converge", params.product_to_test, params.node_to_test, "upgrade", "main", "no")
                                        }
                                    echo "3. Run UPGRADE scripts and playbooks for running PXC UPGRADE tests.. Molecule side-effect step"
                                        catchError(buildResult: 'UNSTABLE', stageResult: 'FAILURE'){
                                            runMoleculeAction("side-effect", params.product_to_test, params.node_to_test, "upgrade", params.test_repo, "yes")
                                        }
                                }
                            }
                            post{
                                always{
                                    script{
                                        def param_test_type = "upgrade"
                                        echo "4. Take Backups of the Logs.. for PXC UPGRADE tests"
                                        setInventories("upgrade")
                                        runlogsbackup(params.product_to_test, "upgrade")
                                        echo "5. Destroy the Molecule instances for PXC UPGRADE tests.."
                                        runMoleculeAction("destroy", params.product_to_test, params.node_to_test, "upgrade", params.test_repo, "yes")
                                    }
                                }
                            }
                }
            }
        }

    }

    post {

        always {
             catchError(buildResult: 'UNSTABLE', stageResult: 'FAILURE'){
                archiveArtifacts artifacts: 'PXC/**/*.tar.gz' , followSymlinks: false
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
