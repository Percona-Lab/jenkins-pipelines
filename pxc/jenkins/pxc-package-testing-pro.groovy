library changelog: false, identifier: 'lib@master', retriever: modernSCM([
    $class: 'GitSCMSource',
    remote: 'https://github.com/Percona-Lab/jenkins-pipelines.git'
]) _

import groovy.transform.Field

def runMoleculeActionPro(String action, String product_to_test, String scenario, String param_test_type, String test_repo, String version_check, String pro) {
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
            }else if(param_test_type == "min_upgrade"){
                    
                def check_version="${version_check}"
                def install_repo="main"
                def upgrade_repo="${test_repo}"

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
                    echo 'pro: "${pro}"' > "${WORKSPACE}/${product_to_test}/${params.node_to_test}/min_upgrade/envfile"
                    echo 'PXC1_IP: "${UP_PXC1_IP}"' >> "${WORKSPACE}/${product_to_test}/${params.node_to_test}/min_upgrade/envfile"
                    echo 'PXC2_IP: "${UP_PXC2_IP}"' >> "${WORKSPACE}/${product_to_test}/${params.node_to_test}/min_upgrade/envfile"
                    echo 'PXC3_IP: "${UP_PXC3_IP}"' >> "${WORKSPACE}/${product_to_test}/${params.node_to_test}/min_upgrade/envfile"
                """

                if(action == "converge"){
                    sh """
                        echo 'install_repo: "${install_repo}"' >> "${WORKSPACE}/${product_to_test}/${params.node_to_test}/min_upgrade/envfile"
                        echo 'check_version: "no"' >> "${WORKSPACE}/${product_to_test}/${params.node_to_test}/min_upgrade/envfile"
                    """
                } else if(action == "side-effect") {
                    sh """
                        echo 'install_repo: "${upgrade_repo}"' >> "${WORKSPACE}/${product_to_test}/${params.node_to_test}/min_upgrade/envfile"
                        echo 'check_version: "yes"' >> "${WORKSPACE}/${product_to_test}/${params.node_to_test}/min_upgrade/envfile"
                    """
                }
                echo "Not setting up VARS as in create or destroy stage"
            }


    withCredentials(awsCredentials) {

            if(action == "create" || action == "destroy"){
                sh"""
                    . virtenv/bin/activate
                    #export MOLECULE_DEBUG=1
                    #export DESTROY_ENV=no
                    export INSTALLTYPE="pro"
                    
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
                    export INSTALLTYPE="pro"
                
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
                    }else if(("${params.node_to_test}" == "debian-11") ||("${params.node_to_test}" == "debian-12") || ("${params.node_to_test}" == "debian-11-arm") || ("${params.node_to_test}" == "debian-12-arm")){
                        SSH_USER="admin"
                    }else if(("${params.node_to_test}" == "ol-8") || ("${params.node_to_test}" == "ol-9") || ("${params.node_to_test}" == "min-amazon-2") || ("${params.node_to_test}" == "amazon-linux-2023") || ("${params.node_to_test}" == "amazon-linux-2023-arm") || ("${params.node_to_test}" == "rhel-8") || ("${params.node_to_test}" == "rhel-9") || ("${params.node_to_test}" == "rhel-8-arm") || ("${params.node_to_test}" == "rhel-9-arm")){
                        SSH_USER="ec2-user"
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

                script {
                    try {
                        echo "Installing Molecule Bookworm..."
                        installMoleculeBookworm()
                        echo "Installation completed successfully"
                    } catch (Exception e) {
                        echo "First attempt failed: ${e.getMessage()}"
                        echo "Retrying installation..."
                        try {
                            installMoleculeBookworm()
                            echo "Installation completed successfully on retry"
                        } catch (Exception retryException) {
                            echo "Retry failed: ${retryException.getMessage()}"
                            error("Failed to install Molecule Bookworm after 2 attempts")
                        }
                    }
                }


                
                sh '''
                    rm -rf package-testing
                    git clone https://github.com/${git_repo} --branch ${BRANCH}
                '''
}

def pro_post_steps(){
    script {
            currentBuild.description = "action: ${params.action_to_test} node: ${params.node_to_test}"
            echo "All tests completed"

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

                def jobName = env.JOB_NAME
                jobName.trim()

                echo "Fetched JOB_NAME from environment: '${jobName}'"

                echo "Listing EC2 instances with job-name tag: ${jobName}"
                sh """
                aws ec2 describe-instances --region us-west-1 --filters "Name=tag:job-name,Values=${jobName}" "Name=tag:build-number,Values=${BUILD_NUMBER}" --query "Reservations[].Instances[].InstanceId" --output text
                """

                sh """
                echo "=== EC2 Instances to be cleaned up ==="
                aws ec2 describe-instances --region us-west-1 \\
                --filters "Name=tag:job-name,Values=${jobName}" "Name=tag:build-number,Values=${BUILD_NUMBER}" \\
                --query "Reservations[].Instances[].[InstanceId,Tags[?Key=='Name'].Value|[0],State.Name]" \\
                --output table || echo "No instances found with job-name tag: ${jobName}"
                """

                def instanceIds = sh(
                    script: """
                    aws ec2 describe-instances --region us-west-1 \\
                    --filters "Name=tag:job-name,Values=${jobName}" "Name=tag:build-number,Values=${BUILD_NUMBER}" "Name=instance-state-name,Values=running" \\
                    --query "Reservations[].Instances[].InstanceId" \\
                    --output text
                    """,
                    returnStdout: true
                ).trim()
                if (instanceIds != null && !instanceIds.trim().isEmpty()) {
                    echo "Found instances to terminate: ${instanceIds.trim()}"


                    sh """
                    echo "${instanceIds.trim()}" | xargs -r aws ec2 terminate-instances --instance-ids
                    """
                
                    sleep(30)
                    
                    echo "Terminated instances: ${instanceIds.trim()}"
                    
                    echo "==========================================="


                    echo "Verification: Status of terminated instances:"

                    sh """
                    sleep 5 && aws ec2 describe-instances --instance-ids ${instanceIds} --query "Reservations[].Instances[].[InstanceId,Tags[?Key=='Name'].Value|[0],State.Name]" --output table
                    """
                
                } else {
                    echo "No instances found to terminate"
                }
            

            }
    }
}

def install(){

    script {
        def param_test_type = "install"
        echo "1. Creating Molecule Instances for running INSTALL PXC tests.. Molecule create step"
        try {
            runMoleculeActionPro("create", params.product_to_test, params.node_to_test, "install", params.test_repo, "yes", "yes")
        } catch (Exception e) {
            echo "Failed during Molecule create step: ${e.message}"
            throw e
        }

        echo "2. Run Install scripts and tests for PXC INSTALL PXC tests.. Molecule converge step"
        catchError(buildResult: 'UNSTABLE', stageResult: 'FAILURE') {
            try {
                runMoleculeActionPro("converge", params.product_to_test, params.node_to_test, "install", params.test_repo, "yes", "yes")
            } catch (Exception e) {
                echo "Failed during Molecule converge step: ${e.message}"
                throw e
            }
        }
    }

}

def post_install(){

    script {
        def param_test_type = "install"

        // Back up logs if possible, log failure if any
        try {
            echo "3. BACKUP"
            setInventories("install")
            runlogsbackup(params.product_to_test, "install")
        } catch (Exception e) {
            echo "Failed during logs backup: ${e.message}"
        }

        // Ensure Molecule instances are destroyed no matter what
        echo "4. DESTROY"
        try {
            runMoleculeActionPro("destroy", params.product_to_test, params.node_to_test, "install", params.test_repo, "yes","yes")
        } catch (Exception e) {
            echo "Failed during Molecule destroy step: ${e.message}"
        }

        catchError(buildResult: 'UNSTABLE', stageResult: 'FAILURE') {
            try {
                archiveArtifacts artifacts: 'PXC/**/*.tar.gz', followSymlinks: false
            } catch (Exception e) {
                echo "Failed to archive artifacts: ${e.message}"
            }
        }
    }

}

def min_upgrade_nonpro_pro(String upgrade_type){
    script{
        echo "UPGRADE STAGE INSIDE"
        def param_test_type = upgrade_type   
        echo "1. CREATE"
        runMoleculeActionPro("create", params.product_to_test, params.node_to_test, upgrade_type, "main", "no","no")
        setInventories(upgrade_type)
        echo "2. INSTALL"
            catchError(buildResult: 'UNSTABLE', stageResult: 'FAILURE'){
                runMoleculeActionPro("converge", params.product_to_test, params.node_to_test, upgrade_type, "main", "no", "no")
            }
        echo "3. UPGRADE"
            catchError(buildResult: 'UNSTABLE', stageResult: 'FAILURE'){
                runMoleculeActionPro("side-effect", params.product_to_test, params.node_to_test, upgrade_type, params.test_repo, "yes","yes")
            }
    }
}

def min_upgrade_pro_pro(String upgrade_type){
    script{
        echo "UPGRADE STAGE INSIDE"
        def param_test_type = upgrade_type   
        echo "1. CREATE"
        runMoleculeActionPro("create", params.product_to_test, params.node_to_test, upgrade_type, "main", "no","yes")
        setInventories(upgrade_type)
        echo "2. INSTALL"
            catchError(buildResult: 'UNSTABLE', stageResult: 'FAILURE'){
                runMoleculeActionPro("converge", params.product_to_test, params.node_to_test, upgrade_type, "main", "no", "yes")
            }
        echo "3. UPGRADE"
            catchError(buildResult: 'UNSTABLE', stageResult: 'FAILURE'){
                runMoleculeActionPro("side-effect", params.product_to_test, params.node_to_test, upgrade_type, params.test_repo, "yes","yes")
            }
    }
}

def min_upgrade_pro_nonpro(String upgrade_type){
    script{
        echo "UPGRADE STAGE INSIDE"
        def param_test_type = upgrade_type   
        echo "1. CREATE"
        runMoleculeActionPro("create", params.product_to_test, params.node_to_test, upgrade_type, params.test_repo, "no","yes")
        setInventories(upgrade_type)
        echo "2. INSTALL"
            catchError(buildResult: 'UNSTABLE', stageResult: 'FAILURE'){
                runMoleculeActionPro("converge", params.product_to_test, params.node_to_test, upgrade_type, params.test_repo, "no", "yes")
            }
        echo "3. UPGRADE"
            catchError(buildResult: 'UNSTABLE', stageResult: 'FAILURE'){
                runMoleculeActionPro("side-effect", params.product_to_test, params.node_to_test, upgrade_type, "main", "yes","no")
            }
    }
}

def post_upgrade_pro(String upgrade_type){

    script{
        def param_test_type = upgrade_type
        try{
            echo "4. BACKUP"
            setInventories(upgrade_type)
            runlogsbackup(params.product_to_test, upgrade_type)
        }catch(Exception e){
            echo "Failed during logs backup"
        }
        echo "5. DESTROY"
        runMoleculeActionPro("destroy", params.product_to_test, params.node_to_test, upgrade_type, params.test_repo, "yes","yes")
    }
    catchError(buildResult: 'UNSTABLE', stageResult: 'FAILURE'){
        archiveArtifacts artifacts: 'PXC/**/*.tar.gz' , followSymlinks: false
    }

}

pipeline {
    agent {
        label 'micro-amazon'
    }

    options {
        buildDiscarder(logRotator(numToKeepStr: '100'))
    }

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
                'amazon-linux-2023',
                'amazon-linux-2023-arm',
                'ol-8',
                'ol-9',
                'rhel-8',
                'rhel-9',
                'rhel-8-arm',
                'rhel-9-arm',
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
                'install',
                'min_upgrade_pro_pro',
                'min_upgrade_nonpro_pro',
                'min_upgrade_pro_nonpro'
            ],
            description: 'Set test type for testing'
        )
        string(
            name: 'git_repo',
            defaultValue: "Percona-QA/package-testing",
            description: 'Git repository to use for testing'
        )
        string(
            name: 'BRANCH',
            defaultValue: 'master',
            description: 'Git branch to use for testing'
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
                        expression { params.test_type == "install"}
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
                        install()
                    }
                    post {
                        always {
                         post_install()
                        }
                    }
                }

                stage("NONPRO_PRO") {
                            when {
                                allOf{
                                    expression{params.test_type == "min_upgrade_nonpro_pro" }
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
                                min_upgrade_nonpro_pro("min_upgrade")
                            }
                            post{
                                always{
                                post_upgrade_pro("min_upgrade")
                                }
                            }
                }

                stage("PRO_PRO" ) {
                            when {
                                allOf{
                                    expression{params.test_type == "min_upgrade_pro_pro" }
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
                                MIN_UPGRADE_TEST = "PRO_PRO"
                            }
                            options {
                                skipDefaultCheckout()
                            }
                            steps {
                                setup()
                                min_upgrade_pro_pro("min_upgrade")
                            }
                            post{
                                always{
                                    post_upgrade_pro("min_upgrade") 
                                }
                            }
                }

                stage("PRO_NONPRO") {
                            when {
                                allOf{
                                    expression{params.test_type == "min_upgrade_pro_nonpro"}
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
                                MIN_UPGRADE_TEST = "PRO_NONPRO"
                            }
                            options {
                                skipDefaultCheckout()
                            }
                            steps {
                                setup()
                                min_upgrade_pro_nonpro("min_upgrade")
                            }
                            post{
                                always{
                                    post_upgrade_pro("min_upgrade")
                                }

                            }
                }
            }
        }
    }

    post {
        always {
            pro_post_steps()
    }
}

}
