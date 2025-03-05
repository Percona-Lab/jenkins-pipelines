library changelog: false, identifier: 'lib@master', retriever: modernSCM([
    $class: 'GitSCMSource',
    remote: 'https://github.com/Percona-Lab/jenkins-pipelines.git'
]) _

import groovy.transform.Field

def runMoleculeAction(String action, String product_to_test, String scenario, String param_test_type, String install_repo, String test_repo, String version_check, String pro) {
    def awsCredentials = [
        sshUserPrivateKey(
            credentialsId: 'MOLECULE_AWS_PRIVATE_KEY',
            keyFileVariable: 'MOLECULE_AWS_PRIVATE_KEY',
            passphraseVariable: '',
            usernameVariable: ''
        ),
        aws(
            accessKeyVariable: 'AWS_ACCESS_KEY_ID',
            credentialsId: '7e252458-7ef8-4d0e-a4d5-5773edcbfa5e',
            secretKeyVariable: 'AWS_SECRET_ACCESS_KEY'
        )
    ]


            
            echo "check var param_test_type ${param_test_type}"

            sh """
            mkdir -p "${WORKSPACE}/${product_to_test}/${params.node_to_test}/${param_test_type}/"
            """

            if(param_test_type == "install"){   
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
                        echo 'install_repo: "${test_repo}"' > "${WORKSPACE}/${product_to_test}/${params.node_to_test}/install/envfile"
                        echo 'check_version: "${check_version}"' >> "${WORKSPACE}/${product_to_test}/${params.node_to_test}/install/envfile"
                        echo 'pro: "${pro}"' >> "${WORKSPACE}/${product_to_test}/${params.node_to_test}/install/envfile"
                        echo 'PXC1_IP: "${IN_PXC1_IP}"' >> "${WORKSPACE}/${product_to_test}/${params.node_to_test}/install/envfile"
                        echo 'PXC2_IP: "${IN_PXC2_IP}"' >> "${WORKSPACE}/${product_to_test}/${params.node_to_test}/install/envfile"
                        echo 'PXC3_IP: "${IN_PXC3_IP}"' >> "${WORKSPACE}/${product_to_test}/${params.node_to_test}/install/envfile"                    
                    """
                }else{
                    echo "Not setting up VARS as in create or destroy stage"
                    sh """
                        echo 'install_repo: "${test_repo}"' > "${WORKSPACE}/${product_to_test}/${params.node_to_test}/install/envfile"
                        echo 'check_version: "${check_version}"' >> "${WORKSPACE}/${product_to_test}/${params.node_to_test}/install/envfile"
                    """
                }
            }else if(param_test_type == "min_upgrade"){
                    
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
                        echo 'install_repo: "${install_repo}"' > "${WORKSPACE}/${product_to_test}/${params.node_to_test}/min_upgrade/envfile"
                        echo 'check_version: "${check_version}"' >> "${WORKSPACE}/${product_to_test}/${params.node_to_test}/min_upgrade/envfile"
                        echo 'pro: "${pro}"' >> "${WORKSPACE}/${product_to_test}/${params.node_to_test}/min_upgrade/envfile"
                        echo 'PXC1_IP: "${UP_PXC1_IP}"' >> "${WORKSPACE}/${product_to_test}/${params.node_to_test}/min_upgrade/envfile"
                        echo 'PXC2_IP: "${UP_PXC2_IP}"' >> "${WORKSPACE}/${product_to_test}/${params.node_to_test}/min_upgrade/envfile"
                        echo 'PXC3_IP: "${UP_PXC3_IP}"' >> "${WORKSPACE}/${product_to_test}/${params.node_to_test}/min_upgrade/envfile"
                    """

                    if(action == "converge"){
                        sh """
                            echo 'check_version: "no"' >> "${WORKSPACE}/${product_to_test}/${params.node_to_test}/min_upgrade/envfile"
                        """
                    } else {
                        sh """
                            echo 'check_version: "yes"' >> "${WORKSPACE}/${product_to_test}/${params.node_to_test}/min_upgrade/envfile"
                        """
                    }
                }else{
                    echo "Not setting up VARS as in create or destroy stage"
                    sh """
                    echo 'install_repo: "${install_repo}"' > "${WORKSPACE}/${product_to_test}/${params.node_to_test}/min_upgrade/envfile"
                    echo 'upgrade_repo: "${upgrade_repo}"' >> "${WORKSPACE}/${product_to_test}/${params.node_to_test}/min_upgrade/envfile"
                    """
                }
            }


    withCredentials(awsCredentials) {

            if(action == "create" || action == "destroy"){
                sh"""
                    . virtenv/bin/activate
                    export MOLECULE_DEBUG=1
                    #export DESTROY_ENV=no
                    
                    mkdir -p ${WORKSPACE}/install
                    mkdir -p ${WORKSPACE}/min_upgrade
                    
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
                withCredentials([usernamePassword(credentialsId: 'PS_PRIVATE_REPO_ACCESS', passwordVariable: 'PASSWORD', usernameVariable: 'USERNAME')]) {
                sh"""
                    . virtenv/bin/activate
                    export MOLECULE_DEBUG=1
                    #export DESTROY_ENV=no

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
}



void setInventories(String param_test_type){

                    def KEYPATH_BOOTSTRAP
                    def KEYPATH_COMMON
                    def SSH_USER

                    KEYPATH_BOOTSTRAP="/home/admin/.cache/molecule/${product_to_test}-bootstrap-${param_test_type}/${params.node_to_test}/ssh_key-us-west-1"
                    KEYPATH_COMMON="/home/admin/.cache/molecule/${product_to_test}-common-${param_test_type}/${params.node_to_test}/ssh_key-us-west-1"


                    if(("${params.node_to_test}" == "ubuntu-noble") || ("${params.node_to_test}" == "ubuntu-focal") || ("${params.node_to_test}" == "ubuntu-jammy") || ("${params.node_to_test}" == "ubuntu-noble-arm") || ("${params.node_to_test}" == "ubuntu-jammy-arm")){
                        SSH_USER="ubuntu"            
                    }else if(("${params.node_to_test}" == "debian-11") || ("${params.node_to_test}" == "debian-10") ||("${params.node_to_test}" == "debian-12") || ("${params.node_to_test}" == "debian-11-arm") || ("${params.node_to_test}" == "debian-12-arm")){
                        SSH_USER="admin"
                    }else if(("${params.node_to_test}" == "ol-8") || ("${params.node_to_test}" == "ol-9") || ("${params.node_to_test}" == "min-amazon-2") || ("${params.node_to_test}" == "rhel-8") || ("${params.node_to_test}" == "rhel-9") ("${params.node_to_test}" == "rhel-8-arm") || ("${params.node_to_test}" == "rhel-9-arm")){
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

                }else if(param_test_type == "min_upgrade"){

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
                        mkdir -p "${WORKSPACE}/${product_to_test}-bootstrap/${params.node_to_test}/min_upgrade/"
                        mkdir -p "${WORKSPACE}/${product_to_test}-common/${params.node_to_test}/min_upgrade/"
                        echo "\n ${UPGRADE_Bootstrap_Instance} ansible_host=${UPGRADE_Bootstrap_Instance_Public_IP}  ansible_ssh_user=${SSH_USER} ansible_ssh_private_key_file=${KEYPATH_BOOTSTRAP} ansible_ssh_common_args='-o StrictHostKeyChecking=no' ip_env=${UPGRADE_Bootstrap_Instance}" > ${WORKSPACE}/${product_to_test}-bootstrap/${params.node_to_test}/min_upgrade/inventory            
                        echo "\n ${UPGRADE_Common_Instance_PXC2} ansible_host=${UPGRADE_Common_Instance_PXC2_Public_IP}   ansible_ssh_user=${SSH_USER} ansible_ssh_private_key_file=${KEYPATH_COMMON} ansible_ssh_common_args='-o StrictHostKeyChecking=no'  ip_env=${UPGRADE_Common_Instance_PXC2}" > ${WORKSPACE}/${product_to_test}-common/${params.node_to_test}/min_upgrade/inventory
                        echo "\n ${UPGRADE_Common_Instance_PXC3} ansible_host=${UPGRADE_Common_Instance_PXC3_Public_IP}   ansible_ssh_user=${SSH_USER} ansible_ssh_private_key_file=${KEYPATH_COMMON} ansible_ssh_common_args='-o StrictHostKeyChecking=no'  ip_env=${UPGRADE_Common_Instance_PXC3}" >> ${WORKSPACE}/${product_to_test}-common/${params.node_to_test}/min_upgrade/inventory
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
            credentialsId: '7e252458-7ef8-4d0e-a4d5-5773edcbfa5e',
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

def setup(){

                script{                              
                    if (( params.test_type == "min_upgrade" ) && ( params.test_repo == "main" )) {
                         echo "Skipping as the min_upgrade and main are not supported together."
                         echo "Exiting the Stage as the inputs are invalid."
                         currentBuild.result = 'UNSTABLE'
                    } else {
                         echo "Continue with the package tests"
                    }                
                }   
                echo "${JENWORKSPACE}"
                installMoleculeBookworm()
                    sh '''
                        rm -rf package-testing                    
                        git clone https://github.com/Percona-QA/package-testing --branch pxc-pt-pro-support
                    '''
}


pipeline {
    agent none

    parameters {
        choice(
            name: 'product_to_test',
            choices: [
                'pxc84',
                'pxc80'
            ],
            description: 'PXC product_to_test to test'
        )
        choice(
            name: 'node_to_test',
            choices: [
                'ubuntu-noble',
                'ubuntu-jammy',
                'ubuntu-noble-arm',
                'ubuntu-jammy-arm',
                'ubuntu-focal',
                'debian-12',
                'debian-11',
                'debian-12-arm',
                'debian-11-arm',
                'debian-10',
                'centos-7',
                'ol-8',
                'ol-9',
                'rhel-8',
                'rhel-9',
                'rhel-8-arm',
                'rhel-9-arm'
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
                'min_upgrade_pro_pro',
                'min_upgrade_nonpro_pro',
                'min_upgrade_pro_nonpro'
            ],
            description: 'Set test type for testing'
        )
    }

    stages {
        stage("Starting Build"){
            steps{
                script{
                    currentBuild.displayName = "${env.BUILD_NUMBER}-${params.product_to_test}-${params.node_to_test}-${params.test_repo}-${params.test_type}"       
                }
            }
        }   
        stage("Run parallel Install and UPGRADE"){
            parallel{


                stage("INSTALL") {
                    when {
                        expression { params.test_type == "install" || params.test_type == "install_and_upgrade" }
                    }

                    agent {
                        label 'min-bookworm-x64'
                    }
                    environment {
                        INSTALL_BOOTSTRAP_INSTANCE_PRIVATE_IP = "${WORKSPACE}/install/bootstrap_instance_private_ip.json"
                        INSTALL_COMMON_INSTANCE_PRIVATE_IP = "${WORKSPACE}/install/common_instance_private_ip.json"

                        INSTALL_BOOTSTRAP_INSTANCE_PUBLIC_IP = "${WORKSPACE}/install/bootstrap_instance_public_ip.json"
                        INSTALL_COMMON_INSTANCE_PUBLIC_IP = "${WORKSPACE}/install/common_instance_public_ip.json"

                        JENWORKSPACE = "${env.WORKSPACE}"
                    }
                    options {
                        skipDefaultCheckout()
                    }

                    steps {
                        setup()
                        script {
                            def param_test_type = "install"
                            echo "1. Creating Molecule Instances for running INSTALL PXC tests.. Molecule create step"
                            try {
                                runMoleculeAction("create", params.product_to_test, params.node_to_test, "install", params.test_repo, params.test_repo, "yes", "yes")
                            } catch (Exception e) {
                                echo "Failed during Molecule create step: ${e.message}"
                                throw e
                            }

                            echo "2. Run Install scripts and tests for PXC INSTALL PXC tests.. Molecule converge step"
                            catchError(buildResult: 'UNSTABLE', stageResult: 'FAILURE') {
                                try {
                                    runMoleculeAction("converge", params.product_to_test, params.node_to_test, "install", params.test_repo, params.test_repo, "yes", "yes")
                                } catch (Exception e) {
                                    echo "Failed during Molecule converge step: ${e.message}"
                                    throw e
                                }
                            }
                        }
                    }

                    post {
                        always {
                            script {
                                def param_test_type = "install"
                                echo "Always INSTALL"

                                // Back up logs if possible, log failure if any
                                try {
                                    echo "3. Take Backups of the Logs.. PXC INSTALL tests.."
                                    setInventories("install")
                                    runlogsbackup(params.product_to_test, "install")
                                } catch (Exception e) {
                                    echo "Failed during logs backup: ${e.message}"
                                }

                                // Ensure Molecule instances are destroyed no matter what
                                echo "4. Destroy the Molecule instances for the PXC INSTALL tests.."
                                try {
                                    runMoleculeAction("destroy", params.product_to_test, params.node_to_test, "install", params.test_repo, params.test_repo, "yes","yes")
                                } catch (Exception e) {
                                    echo "Failed during Molecule destroy step: ${e.message}"
                                }
                            }

                            // Always try to archive artifacts even if tests fail
                            script {
                                catchError(buildResult: 'UNSTABLE', stageResult: 'FAILURE') {
                                    try {
                                        archiveArtifacts artifacts: 'PXC/**/*.tar.gz', followSymlinks: false
                                    } catch (Exception e) {
                                        echo "Failed to archive artifacts: ${e.message}"
                                    }
                                }
                            }
                        }
                    }
                }

                stage("MIN UPGRADE PXC 80/84 NONPRO_PRO") {
                            when {
                                allOf{
                                    expression{params.test_type == "min_upgrade_nonpro_pro" || params.test_type == "install_and_upgrade"}
                                    expression{params.test_repo != "main"}
                                    expression{params.product_to_test == "pxc80" || params.product_to_test == "pxc84"}                
                                }
                            }

                            agent {
                                label 'min-bookworm-x64'
                            }


                            environment {

                                UPGRADE_BOOTSTRAP_INSTANCE_PRIVATE_IP = "${WORKSPACE}/min_upgrade/bootstrap_instance_private_ip.json"
                                UPGRADE_COMMON_INSTANCE_PRIVATE_IP = "${WORKSPACE}/min_upgrade/common_instance_private_ip.json"
                                
                                UPGRADE_BOOTSTRAP_INSTANCE_PUBLIC_IP = "${WORKSPACE}/min_upgrade/bootstrap_instance_public_ip.json"
                                UPGRADE_COMMON_INSTANCE_PUBLIC_IP  = "${WORKSPACE}/min_upgrade/common_instance_public_ip.json"

                                JENWORKSPACE = "${env.WORKSPACE}"

                            }

                            options {
                                skipDefaultCheckout()
                            }


                            steps {
                                setup()
                                script{

                                    echo "UPGRADE STAGE INSIDE"
                                    def param_test_type = "min_upgrade"   
                                    echo "1. Creating Molecule Instances for running PXC UPGRADE tests.. Molecule create step"
                                    runMoleculeAction("create", params.product_to_test, params.node_to_test, "min_upgrade", "main", "testing", "no","no")
                                    setInventories("min_upgrade")
                                    echo "2. Run Install scripts and tests for running PXC UPGRADE tests.. Molecule converge step"
                                        catchError(buildResult: 'UNSTABLE', stageResult: 'FAILURE'){
                                            runMoleculeAction("converge", params.product_to_test, params.node_to_test, "min_upgrade", "main", "testing", "no", "no")
                                        }
                                    echo "3. Run UPGRADE scripts and playbooks for running PXC UPGRADE tests.. Molecule side-effect step"
                                        catchError(buildResult: 'UNSTABLE', stageResult: 'FAILURE'){
                                            runMoleculeAction("side-effect", params.product_to_test, params.node_to_test, "min_upgrade", "main", params.test_repo, "yes","yes")
                                        }
                                }
                            }
                            post{
                                always{
                                    script{
                                        def param_test_type = "min_upgrade"
                                        try{
                                            echo "4. Take Backups of the Logs.. for PXC UPGRADE tests"
                                            setInventories("min_upgrade")
                                            runlogsbackup(params.product_to_test, "min_upgrade")
                                        }catch(Exception e){
                                            echo "Failed during logs backup"
                                        }
                                        echo "5. Destroy the Molecule instances for PXC UPGRADE tests.."
                                        runMoleculeAction("destroy", params.product_to_test, params.node_to_test, "min_upgrade", "main", params.test_repo, "yes","no")
                                    }
                                    catchError(buildResult: 'UNSTABLE', stageResult: 'FAILURE'){
                                        archiveArtifacts artifacts: 'PXC/**/*.tar.gz' , followSymlinks: false
                                    }
                                }

                            }
                }

                stage("MIN UPGRADE PXC 80/84 PRO_PRO" ) {
                            when {
                                allOf{
                                    expression{params.test_type == "min_upgrade_pro_pro" || params.test_type == "install_and_upgrade"}
                                    expression{params.test_repo != "main"}
                                    expression{params.product_to_test == "pxc80" || params.product_to_test == "pxc84"}
                                }
                            }



                            agent {
                                label 'min-bookworm-x64'
                            }


                            environment {

                                UPGRADE_BOOTSTRAP_INSTANCE_PRIVATE_IP = "${WORKSPACE}/min_upgrade/bootstrap_instance_private_ip.json"
                                UPGRADE_COMMON_INSTANCE_PRIVATE_IP = "${WORKSPACE}/min_upgrade/common_instance_private_ip.json"
                                
                                UPGRADE_BOOTSTRAP_INSTANCE_PUBLIC_IP = "${WORKSPACE}/min_upgrade/bootstrap_instance_public_ip.json"
                                UPGRADE_COMMON_INSTANCE_PUBLIC_IP  = "${WORKSPACE}/min_upgrade/common_instance_public_ip.json"

                                JENWORKSPACE = "${env.WORKSPACE}"

                            }

                            options {
                                skipDefaultCheckout()
                            }


                            steps {
                                setup()
                                script{

                                    echo "UPGRADE STAGE INSIDE"
                                    def param_test_type = "min_upgrade"   
                                    echo "1. Creating Molecule Instances for running PXC UPGRADE tests.. Molecule create step"
                                    runMoleculeAction("create", params.product_to_test, params.node_to_test, "min_upgrade", "main", params.test_repo, "no", "yes")
                                    setInventories("min_upgrade")
                                    echo "2. Run Install scripts and tests for running PXC UPGRADE tests.. Molecule converge step"
                                        catchError(buildResult: 'UNSTABLE', stageResult: 'FAILURE'){
                                            runMoleculeAction("converge", params.product_to_test, params.node_to_test, "min_upgrade", "main", params.test_repo, "no", "yes")
                                        }
                                    echo "3. Run UPGRADE scripts and playbooks for running PXC UPGRADE tests.. Molecule side-effect step"
                                        catchError(buildResult: 'UNSTABLE', stageResult: 'FAILURE'){
                                            runMoleculeAction("side-effect", params.product_to_test, params.node_to_test, "min_upgrade", "main", params.test_repo, "yes")
                                        }
                                }
                            }
                            post{
                                always{
                                    script{
                                        def param_test_type = "min_upgrade"
                                        try{
                                            echo "4. Take Backups of the Logs.. for PXC UPGRADE tests"
                                            setInventories("min_upgrade")
                                            runlogsbackup(params.product_to_test, "min_upgrade")
                                        }catch(Exception e){
                                            echo "Failed during logs backup"
                                        }
                                        echo "5. Destroy the Molecule instances for PXC UPGRADE tests.."
                                        runMoleculeAction("destroy", params.product_to_test, params.node_to_test, "min_upgrade", "main", params.test_repo, "yes", "yes")

                                    }
                                    catchError(buildResult: 'UNSTABLE', stageResult: 'FAILURE'){
                                        archiveArtifacts artifacts: 'PXC/**/*.tar.gz' , followSymlinks: false
                                    }
                                }

                            }
                }

                stage("MIN UPGRADE PXC 80/84 PRO_NONPRO") {
                            when {
                                allOf{
                                    expression{params.test_type == "min_upgrade_pro_nonpro" || params.test_type == "install_and_upgrade"}
                                    expression{params.test_repo != "main"}
                                    expression{params.product_to_test == "pxc80" || params.product_to_test == "pxc84"}
                                }
                            }

                            agent {
                                label 'min-bookworm-x64'
                            }

                            environment {

                                UPGRADE_BOOTSTRAP_INSTANCE_PRIVATE_IP = "${WORKSPACE}/min_upgrade/bootstrap_instance_private_ip.json"
                                UPGRADE_COMMON_INSTANCE_PRIVATE_IP = "${WORKSPACE}/min_upgrade/common_instance_private_ip.json"
                                
                                UPGRADE_BOOTSTRAP_INSTANCE_PUBLIC_IP = "${WORKSPACE}/min_upgrade/bootstrap_instance_public_ip.json"
                                UPGRADE_COMMON_INSTANCE_PUBLIC_IP  = "${WORKSPACE}/min_upgrade/common_instance_public_ip.json"

                                JENWORKSPACE = "${env.WORKSPACE}"

                                MIN_UPGRADE_TEST = "EOL_MAIN_TO_EOL_TESTING"

                            }

                            options {
                                skipDefaultCheckout()
                            }


                            steps {
                                setup()
                                script {
                                    echo "UPGRADE STAGE INSIDE"
                                    def param_test_type = "min_upgrade"
                                    echo "1. Creating Molecule Instances for running PXC UPGRADE tests.. Molecule create step"
                                    runMoleculeAction("create", params.product_to_test, params.node_to_test, "min_upgrade",  params.test_repo, "main", "no", "yes")
                                    setInventories("min_upgrade")
                                    
                                    echo "2. Run Install scripts and tests for running PXC UPGRADE tests.. Molecule converge step"
                                    def convergeSuccess = true // Flag to track the success of the converge step

                                    catchError(buildResult: 'UNSTABLE', stageResult: 'FAILURE') {
                                        runMoleculeAction("converge", params.product_to_test, params.node_to_test, "min_upgrade",  params.test_repo, "main", "no", "yes")
                                    }.catch {
                                        convergeSuccess = false // If the converge step fails, set the flag to false
                                    }

                                    if (convergeSuccess) {
                                        echo "3. Run UPGRADE scripts and playbooks for running PXC UPGRADE tests.. Molecule side-effect step"
                                        catchError(buildResult: 'UNSTABLE', stageResult: 'FAILURE') {
                                            runMoleculeAction("side-effect", params.product_to_test, params.node_to_test, "min_upgrade", params.test_repo, "main", "yes", "no")
                                        }
                                    } else {
                                        echo "Skipping side-effect step due to failure in converge step."
                                    }
                                }

                            }
                            post{
                                always{
                                    script{
                                        def param_test_type = "min_upgrade"
                                        echo "4. Take Backups of the Logs.. for PXC UPGRADE tests"
                                        setInventories("min_upgrade")
                                        runlogsbackup(params.product_to_test, "min_upgrade")
                                        echo "5. Destroy the Molecule instances for PXC UPGRADE tests.."
                                        runMoleculeAction("destroy", params.product_to_test, params.node_to_test, "min_upgrade",  params.test_repo, "main", "no", "yes")
                                    }
                                    catchError(buildResult: 'UNSTABLE', stageResult: 'FAILURE'){
                                        archiveArtifacts artifacts: 'PXC/**/*.tar.gz' , followSymlinks: false
                                    }
                                }

                            }
                }
            }
        }
    }


    post {

        aborted {
                slackSend channel: '#dev-server-qa', color: '#B2BEB5', message: "[${env.JOB_NAME}]: Aborted during the Package testing (Build Failed) [${env.BUILD_URL}] Parameters: product_to_test: ${params.product_to_test} , node_to_test: ${params.node_to_test} , test_repo: ${params.test_repo}, test_type: ${params.test_type}"
        }

        unstable {
                slackSend channel: '#dev-server-qa', color: '#DEFF13', message: "[${env.JOB_NAME}]: Failed during the Package testing (Unstable Build) [${env.BUILD_URL}] Parameters: product_to_test: ${params.product_to_test} , node_to_test: ${params.node_to_test} , test_repo: ${params.test_repo}, test_type: ${params.test_type}"
        }

        failure {
                slackSend channel: '#dev-server-qa', color: '#FF0000', message: "[${env.JOB_NAME}]: Failed during the Package testing (Build Failed) [${env.BUILD_URL}] Parameters: product_to_test: ${params.product_to_test} , node_to_test: ${params.node_to_test} , test_repo: ${params.test_repo}, test_type: ${params.test_type}"
        }


    }

}
