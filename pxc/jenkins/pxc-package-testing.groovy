library changelog: false, identifier: 'lib@master', retriever: modernSCM([
    $class: 'GitSCMSource',
    remote: 'https://github.com/Percona-Lab/jenkins-pipelines.git'
]) _

import groovy.transform.Field

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
            credentialsId: '7e252458-7ef8-4d0e-a4d5-5773edcbfa5e',
            secretKeyVariable: 'AWS_SECRET_ACCESS_KEY'
        )
    ]


            
            echo "check var param_test_type ${param_test_type}"

            sh """
            mkdir -p "${WORKSPACE}/${product_to_test}/${params.node_to_test}/${param_test_type}/"
            """
            echo "action is ${action}!!!!!!!!!!!"
            if(param_test_type == "install"){   
                def install_repo="${test_repo}"
                def check_version="${version_check}"
                def pxc57repo = "${params.pxc57_repo}"

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
                    
                    if ("${product_to_test}" == "pxc57"){
                        if ("${pxc57repo}" == "EOL"){

                            sh """
                                echo 'pxc57repo: "EOL"' >> "${WORKSPACE}/${product_to_test}/${params.node_to_test}/install/envfile"
                                echo 'install_repo_eol: "testing"' >> "${WORKSPACE}/${product_to_test}/${params.node_to_test}/install/envfile"
                            """

                            echo "Setting the secret variables for molecule use"
                            withCredentials([usernamePassword(credentialsId: 'PS_PRIVATE_REPO_ACCESS', passwordVariable: 'PASSWORD', usernameVariable: 'USERNAME')]){
                                sh """
                                    echo 'USERNAME: "${USERNAME}"' >> "${WORKSPACE}/${product_to_test}/${params.node_to_test}/install/envfile"
                                    echo 'PASSWORD: "${PASSWORD}"' >> "${WORKSPACE}/${product_to_test}/${params.node_to_test}/install/envfile"
                                """
                            }
                        } else{
                            echo "Not setting the secret variables for molecule use as EOL not selected"
                        }
                    }else{
                        echo "Product is not pxc57 so skipping value assignment to it"
                    }

                }else{
                    echo "Not setting up VARS as in create or destroy stage"
                    sh """
                        echo 'install_repo: "${install_repo}"' > "${WORKSPACE}/${product_to_test}/${params.node_to_test}/install/envfile"
                        echo 'check_version: "${check_version}"' >> "${WORKSPACE}/${product_to_test}/${params.node_to_test}/install/envfile"
                    """
                }
            }else if(param_test_type == "min_upgrade"){
                    
                def install_repo="main"
                def upgrade_repo="testing"
                def check_version="${version_check}"
                def pxc57repo = "${params.pxc57_repo}"
                def MIN_UPGRADE_TEST = env.MIN_UPGRADE_TEST
                echo "MIN UPGRADE TYPE: ${MIN_UPGRADE_TEST}"
                

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
                        echo 'PXC1_IP: "${UP_PXC1_IP}"' >> "${WORKSPACE}/${product_to_test}/${params.node_to_test}/min_upgrade/envfile"
                        echo 'PXC2_IP: "${UP_PXC2_IP}"' >> "${WORKSPACE}/${product_to_test}/${params.node_to_test}/min_upgrade/envfile"
                        echo 'PXC3_IP: "${UP_PXC3_IP}"' >> "${WORKSPACE}/${product_to_test}/${params.node_to_test}/min_upgrade/envfile"
                    """

                    if(action == "converge"){
                        sh """
                            echo 'check_version: "no"' >> "${WORKSPACE}/${product_to_test}/${params.node_to_test}/min_upgrade/envfile"
                        """
                    } else if(action == "side-effect"){
                        sh """ # This will check version during side effect
                            echo 'install_repo: "${upgrade_repo}"' >> "${WORKSPACE}/${product_to_test}/${params.node_to_test}/min_upgrade/envfile"
                            echo 'check_version: "yes"' >> "${WORKSPACE}/${product_to_test}/${params.node_to_test}/min_upgrade/envfile"
                        """
                    }

                    if ("${product_to_test}" == "pxc57"){
                            if(action == "converge" && "${MIN_UPGRADE_TEST}" == "EOL_MAIN_TO_EOL_TESTING"){
                                sh """
                                    echo 'pxc57repo: "EOL"' >> "${WORKSPACE}/${product_to_test}/${params.node_to_test}/min_upgrade/envfile"
                                    echo 'install_repo_eol: "main"' >> "${WORKSPACE}/${product_to_test}/${params.node_to_test}/min_upgrade/envfile"
                                    echo 'check_version: "no"' >> "${WORKSPACE}/${product_to_test}/${params.node_to_test}/min_upgrade/envfile"
                                """
                                echo "INSIDE PXC CONVERGE AND EOL_MAIN_TO_EOL_TESTING"
                            }
                            else if(action == "converge" && "${MIN_UPGRADE_TEST}" == "PXC57_MAIN_TO_EOL_TESTING"){
                                sh """
                                    echo 'pxc57repo: "pxc57"' >> "${WORKSPACE}/${product_to_test}/${params.node_to_test}/min_upgrade/envfile"
                                    echo 'check_version: "no"' >> "${WORKSPACE}/${product_to_test}/${params.node_to_test}/min_upgrade/envfile"
                                """
                                echo "INSIDE PXC CONVERGE AND PXC57_MAIN_TO_EOL_TESTING"

                            }
                            else {
                                sh """
                                    echo 'pxc57repo: "${pxc57repo}"' >> "${WORKSPACE}/${product_to_test}/${params.node_to_test}/min_upgrade/envfile"
                                    echo 'check_version: "yes"' >> "${WORKSPACE}/${product_to_test}/${params.node_to_test}/min_upgrade/envfile"
                                    echo 'install_repo_eol: "testing"' >> "${WORKSPACE}/${product_to_test}/${params.node_to_test}/min_upgrade/envfile"
                                """
                                echo "NOT INSIDE PXC CONVERGE"
                            }


                        if ("${pxc57repo}" == "EOL" ){

                            echo "Setting the secret variables for molecule use"
                            withCredentials([usernamePassword(credentialsId: 'PS_PRIVATE_REPO_ACCESS', passwordVariable: 'PASSWORD', usernameVariable: 'USERNAME')]){
                                sh """
                                    echo 'USERNAME: "${USERNAME}"' >> "${WORKSPACE}/${product_to_test}/${params.node_to_test}/min_upgrade/envfile"
                                    echo 'PASSWORD: "${PASSWORD}"' >> "${WORKSPACE}/${product_to_test}/${params.node_to_test}/min_upgrade/envfile"
                                """
                            }
                        } else{
                            echo "Not setting the secret variables for molecule use as EOL not selected"
                        }
                    }else{
                        echo "Product is not pxc57 so skipping value assignment to it"
                    }
                    
                }else{
                    echo "Not setting up VARS as in create or destroy stage"
                }
            }else if(param_test_type == "maj_upgrade"){
                def install_repo="testing"
                def check_version="${version_check}"
                def upgrade_repo="main"
                def pxc57repo = "${params.pxc57_repo}"

                if(action != "create" && action != "destroy"){
                    def UPMaj_PXC1_IP = sh(
                        script: """cat ${UPGRADE_MAJ_BOOTSTRAP_INSTANCE_PRIVATE_IP} | jq -r .[0] | jq [.private_ip] | jq -r .[]""",
                        returnStdout: true
                    ).trim()

                    def UPMaj_PXC2_IP = sh(
                        script: """cat ${UPGRADE_MAJ_COMMON_INSTANCE_PRIVATE_IP} | jq -r .[0] | jq [.private_ip] | jq -r .[]""",
                        returnStdout: true
                    ).trim()

                    def UPMaj_PXC3_IP = sh(
                        script: """cat ${UPGRADE_MAJ_COMMON_INSTANCE_PRIVATE_IP} | jq -r .[1] | jq [.private_ip] | jq -r .[]""",
                        returnStdout: true
                    ).trim()
                    sh """
                        echo 'install_repo: "${install_repo}"' > "${WORKSPACE}/${product_to_test}/${params.node_to_test}/maj_upgrade/envfile"
                        echo 'check_version: "${check_version}"' >> "${WORKSPACE}/${product_to_test}/${params.node_to_test}/maj_upgrade/envfile"
                        echo 'upgrade_repo: "${upgrade_repo}"' >> "${WORKSPACE}/${product_to_test}/${params.node_to_test}/maj_upgrade/envfile"
                        echo 'PXC1_IP: "${UPMaj_PXC1_IP}"' >> "${WORKSPACE}/${product_to_test}/${params.node_to_test}/maj_upgrade/envfile"
                        echo 'PXC2_IP: "${UPMaj_PXC2_IP}"' >> "${WORKSPACE}/${product_to_test}/${params.node_to_test}/maj_upgrade/envfile"
                        echo 'PXC3_IP: "${UPMaj_PXC3_IP}"' >> "${WORKSPACE}/${product_to_test}/${params.node_to_test}/maj_upgrade/envfile"
                    """

                    if ("${product_to_test}" == "pxc57"){

                        if(action == "converge"){
                            sh """
                                echo 'pxc57repo: "pxc57"' >> "${WORKSPACE}/${product_to_test}/${params.node_to_test}/maj_upgrade/envfile"
                            """
                        }
                        else {
                                sh """
                                    echo 'pxc57repo: "${pxc57repo}"' >> "${WORKSPACE}/${product_to_test}/${params.node_to_test}/maj_upgrade/envfile"
                                """
                        }
                        if ("${pxc57repo}" == "EOL"){
                            echo "Setting the secret variables for molecule use"
                            withCredentials([usernamePassword(credentialsId: 'PS_PRIVATE_REPO_ACCESS', passwordVariable: 'PASSWORD', usernameVariable: 'USERNAME')]){
                                sh """
                                    echo 'USERNAME: "${USERNAME}"' >> "${WORKSPACE}/${product_to_test}/${params.node_to_test}/maj_upgrade/envfile"
                                    echo 'PASSWORD: "${PASSWORD}"' >> "${WORKSPACE}/${product_to_test}/${params.node_to_test}/maj_upgrade/envfile"
                                """
                            }
                        } else{
                            echo "Not setting the secret variables for molecule use as EOL not selected"
                        }
                    }else{
                        echo "Product is not pxc57 so skipping value assignment to it"
                    }
                    
                }else{
                    echo "Not setting up VARS as in create or destroy stage"
                    sh """
                    echo 'install_repo: "${install_repo}"' > "${WORKSPACE}/${product_to_test}/${params.node_to_test}/maj_upgrade/envfile"
                    echo 'check_version: "${check_version}"' >> "${WORKSPACE}/${product_to_test}/${params.node_to_test}/maj_upgrade/envfile"
                    echo 'upgrade_repo: "${upgrade_repo}"' >> "${WORKSPACE}/${product_to_test}/${params.node_to_test}/maj_upgrade/envfile"
                    """
                }
            }else{
                echo "Unknown condition"
            }

    withCredentials(awsCredentials) {

            if(action == "create" || action == "destroy"){
                sh"""
                    . virtenv/bin/activate
                    #export MOLECULE_DEBUG=1
                    #export DESTROY_ENV=no
                    export INSTALLTYPE="nonpro"
                    
                    mkdir -p ${WORKSPACE}/install
                    mkdir -p ${WORKSPACE}/min_upgrade
                    mkdir -p ${WORKSPACE}/maj_upgrade
                    
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
                    #export MOLECULE_DEBUG=1
                    #export DESTROY_ENV=no
                    export INSTALLTYPE="nonpro"
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

def install(){
    script {
        def param_test_type = "install"
        echo "1. CREATE"
        try {
            runMoleculeAction("create", params.product_to_test, params.node_to_test, "install", params.test_repo, "yes")
        } catch (Exception e) {
            echo "Failed during Molecule create step: ${e.message}"
            throw e
        }

        echo "2. CONVERGE"
        catchError(buildResult: 'UNSTABLE', stageResult: 'FAILURE') {
            try {
                runMoleculeAction("converge", params.product_to_test, params.node_to_test, "install", params.test_repo, "yes")
            } catch (Exception e) {
                echo "Failed during Molecule converge step: ${e.message}"
                throw e
            }
        }
    }
}

def post_install(){

        script {
            try {
                echo "3. BACKUP LOGS"
                setInventories("install")
                runlogsbackup(params.product_to_test, "install")
            } catch (Exception e) {
                echo "Failed during logs backup: ${e.message}"
            }

            echo "4. DESTROY"
            try {
                runMoleculeAction("destroy", params.product_to_test, params.node_to_test, "install", params.test_repo, "yes")
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

def upgrade(String upgrade_type){

    script{

        echo "1 CREATE INSTANCES"

        catchError(buildResult: 'UNSTABLE', stageResult: 'FAILURE'){
            runMoleculeAction("create", params.product_to_test, params.node_to_test, upgrade_type, "main", "no")
        }

        echo "2 SET INVENTORIES FOR CONVERGE AND SIDE EFFECT"

        catchError(buildResult: 'UNSTABLE', stageResult: 'FAILURE'){
            setInventories("min_upgrade")
        }

        echo "3 CONVERGE"

            catchError(buildResult: 'UNSTABLE', stageResult: 'FAILURE'){
                runMoleculeAction("converge", params.product_to_test, params.node_to_test, upgrade_type, "main", "no")
            }

        echo "4 SIDE_EFFECT"

            catchError(buildResult: 'UNSTABLE', stageResult: 'FAILURE'){
                runMoleculeAction("side-effect", params.product_to_test, params.node_to_test, upgrade_type, params.test_repo, "yes")
            }
    }

}

def post_upgrade(String upgrade_type){

        script{
            echo "5. Backup logs"
            setInventories(upgrade_type)
            runlogsbackup(params.product_to_test, upgrade_type)
            echo "6. Destroy"
            runMoleculeAction("destroy", params.product_to_test, params.node_to_test, upgrade_type, params.test_repo, "yes")
        }
        catchError(buildResult: 'UNSTABLE', stageResult: 'FAILURE'){
            echo "7. Archive Logs and Artifacts"
            archiveArtifacts artifacts: 'PXC/**/*.tar.gz' , followSymlinks: false
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
                    }else if(("${params.node_to_test}" == "debian-11") ||("${params.node_to_test}" == "debian-12") || ("${params.node_to_test}" == "debian-11-arm") || ("${params.node_to_test}" == "debian-12-arm") || ("${params.node_to_test}" == "debian-10")){
                        SSH_USER="admin"
                    }else if(("${params.node_to_test}" == "amazon-linux-2023-arm") || ("${params.node_to_test}" == "amazon-linux-2023") || ("${params.node_to_test}" == "ol-8") || ("${params.node_to_test}" == "ol-9") || ("${params.node_to_test}" == "min-amazon-2") || ("${params.node_to_test}" == "rhel-8") || ("${params.node_to_test}" == "rhel-9") ("${params.node_to_test}" == "rhel-8-arm") || ("${params.node_to_test}" == "rhel-9-arm")){
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

                    echo "Inside min_upgrade setting inventories"

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
                    
                }else if(param_test_type == "maj_upgrade"){

                    def UPGRADE_MAJ_Bootstrap_Instance = sh(
                        script: """cat ${UPGRADE_MAJ_BOOTSTRAP_INSTANCE_PUBLIC_IP} | jq -r .[0] | jq [.instance] | jq -r .[]""",
                        returnStdout: true
                    ).trim()

                    def UPGRADE_MAJ_Bootstrap_Instance_Public_IP = sh(
                        script: """cat ${UPGRADE_MAJ_BOOTSTRAP_INSTANCE_PUBLIC_IP} | jq -r .[0] | jq [.public_ip] | jq -r .[]""",
                        returnStdout: true
                    ).trim()

                    def UPGRADE_MAJ_Common_Instance_PXC2 = sh(
                        script: """cat ${UPGRADE_MAJ_COMMON_INSTANCE_PUBLIC_IP} | jq -r .[0] | jq [.instance] | jq -r .[]""",
                        returnStdout: true
                    ).trim()

                    def UPGRADE_MAJ_Common_Instance_PXC2_Public_IP = sh(
                        script: """cat ${UPGRADE_MAJ_COMMON_INSTANCE_PUBLIC_IP} | jq -r .[0] | jq [.public_ip] | jq -r .[]""",
                        returnStdout: true
                    ).trim()

                    def UPGRADE_MAJ_Common_Instance_PXC3 = sh(
                        script: """cat ${UPGRADE_MAJ_COMMON_INSTANCE_PUBLIC_IP} | jq -r .[1] | jq [.instance] | jq -r .[]""",
                        returnStdout: true
                    ).trim()

                    def UPGRADE_MAJ_Common_Instance_PXC3_Public_IP = sh(
                        script: """cat ${UPGRADE_MAJ_COMMON_INSTANCE_PUBLIC_IP} | jq -r .[1] | jq [.public_ip] | jq -r .[]""",
                        returnStdout: true
                    ).trim()

                    sh """
                        echo \"printing path of bootstrap ${KEYPATH_BOOTSTRAP}"
                        echo \"printing path of common  ${KEYPATH_COMMON}"
                        echo \"printing user ${SSH_USER}"
                        mkdir -p "${WORKSPACE}/${product_to_test}-bootstrap/${params.node_to_test}/maj_upgrade/"
                        mkdir -p "${WORKSPACE}/${product_to_test}-common/${params.node_to_test}/maj_upgrade/"
                        echo "\n ${UPGRADE_MAJ_Bootstrap_Instance} ansible_host=${UPGRADE_MAJ_Bootstrap_Instance_Public_IP}  ansible_ssh_user=${SSH_USER} ansible_ssh_private_key_file=${KEYPATH_BOOTSTRAP} ansible_ssh_common_args='-o StrictHostKeyChecking=no' ip_env=${UPGRADE_MAJ_Bootstrap_Instance}" > ${WORKSPACE}/${product_to_test}-bootstrap/${params.node_to_test}/maj_upgrade/inventory            
                        echo "\n ${UPGRADE_MAJ_Common_Instance_PXC2} ansible_host=${UPGRADE_MAJ_Common_Instance_PXC2_Public_IP}   ansible_ssh_user=${SSH_USER} ansible_ssh_private_key_file=${KEYPATH_COMMON} ansible_ssh_common_args='-o StrictHostKeyChecking=no'  ip_env=${UPGRADE_MAJ_Common_Instance_PXC2}" > ${WORKSPACE}/${product_to_test}-common/${params.node_to_test}/maj_upgrade/inventory
                        echo "\n ${UPGRADE_MAJ_Common_Instance_PXC3} ansible_host=${UPGRADE_MAJ_Common_Instance_PXC3_Public_IP}   ansible_ssh_user=${SSH_USER} ansible_ssh_private_key_file=${KEYPATH_COMMON} ansible_ssh_common_args='-o StrictHostKeyChecking=no'  ip_env=${UPGRADE_MAJ_Common_Instance_PXC3}" >> ${WORKSPACE}/${product_to_test}-common/${params.node_to_test}/maj_upgrade/inventory
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

def deleteBuildInstances(){
            script {
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
                            def BUILD_NUMBER = env.BUILD_NUMBER
                            jobName.trim()

                            echo "Fetched JOB_TO_RUN from environment: '${jobName}'"

                            echo "Listing EC2 instances with job-name tag: ${jobName}"
                            sh """
                            aws ec2 describe-instances --region us-west-1 --filters "Name=tag:job-name,Values=${jobName}" "Name=tag:build-number,Values=${BUILD_NUMBER}"  --query "Reservations[].Instances[].InstanceId" --output text
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

def setup(){

                echo "${JENWORKSPACE}"
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



properties([
    parameters([
        [
            $class: 'ChoiceParameter',
            choiceType: 'PT_SINGLE_SELECT',
            description: 'PXC product to test',
            name: 'product_to_test',
            script: [
                $class: 'GroovyScript',
                script: [
                    classpath: [],
                    sandbox: true,
                    script: 'return ["pxc84", "pxc80", "pxc57", "pxc-innovation-lts"]'
                ]
            ]
        ],
        [
            $class: 'CascadeChoiceParameter',
            choiceType: 'PT_SINGLE_SELECT',
            description: 'Distribution to run test (filtered by product)',
            name: 'node_to_test',
            referencedParameters: 'product_to_test',
            script: [
                $class: 'GroovyScript',
                script: [
                    classpath: [],
                    sandbox: true,
                    script: '''
                        def non_pro_pxc80 = [
                                        'ubuntu-noble',
                                        'ubuntu-jammy',
                                        'ubuntu-noble-arm',
                                        'ubuntu-jammy-arm',
                                        'ubuntu-focal',
                                        'debian-12',
                                        'debian-11',
                                        'debian-12-arm',
                                        'debian-11-arm',
                                        'ol-8',
                                        'ol-9',
                                        'rhel-8',
                                        'rhel-9',
                                        'rhel-8-arm',
                                        'rhel-9-arm'
                        ]

                        def non_pro_pxc84 = [
                                        'ubuntu-noble',
                                        'ubuntu-jammy',
                                        'ubuntu-noble-arm',
                                        'ubuntu-jammy-arm',
                                        'ubuntu-focal',
                                        'debian-12',
                                        'debian-11',
                                        'debian-12-arm',
                                        'debian-11-arm',
                                        'ol-8',
                                        'ol-9',
                                        'rhel-8',
                                        'rhel-9',
                                        'rhel-10',
                                        'rhel-8-arm',
                                        'rhel-9-arm',
                                        'rhel-10-arm'
                        ]

                        def pxc_innovation_lts = [
                                        'ubuntu-noble',
                                        'ubuntu-jammy',
                                        'ubuntu-noble-arm',
                                        'ubuntu-jammy-arm'
                        ]

                        def pxc57_nodes = [
                                        'ubuntu-jammy',
                                        'ubuntu-focal',
                                        'debian-12',
                                        'debian-11',
                                        'ol-8',
                                        'ol-9'
                        ]

                        if (product_to_test == "pxc57") {
                            return pxc57_nodes
                        } else if (product_to_test == "pxc80") {
                            return non_pro_pxc80
                        } else if (product_to_test == "pxc84") {
                            return non_pro_pxc84
                        } else if (product_to_test == "pxc-innovation-lts") {
                            return pxc_innovation_lts
                        } else {
                            return ["N/A"]
                        }
                    '''
                ]
            ]
        ],
        choice(
            name: 'test_repo',
            choices: ['testing', 'main', 'experimental'],
            description: 'Repo to install packages from'
        ),
        [
            $class: 'CascadeChoiceParameter',
            choiceType: 'PT_SINGLE_SELECT',
            description: 'PXC-5.7 repo selection',
            name: 'pxc57_repo',
            referencedParameters: 'product_to_test',
            script: [
                $class: 'GroovyScript',
                script: [
                    classpath: [],
                    sandbox: true,
                    script: '''
                        if (product_to_test == "pxc57") {
                            return ["EOL", "original", "pxc57"]
                        }
                        return ["N/A"]
                    '''
                ]
            ]
        ],
        [
            $class: 'CascadeChoiceParameter',
            choiceType: 'PT_SINGLE_SELECT',
            description: 'Test type based on product selection',
            name: 'test_type',
            referencedParameters: 'product_to_test,pxc57_repo',
            script: [
                $class: 'GroovyScript',
                script: [
                    classpath: [],
                    sandbox: true,
                    script: '''
                        def result = ["install"]
                        
                        if (product_to_test == "pxc57") {
                            if (pxc57_repo == "EOL") {
                                result.add("min_upgrade_pxc57_eol_main_to_eol_testing")
                            }
                        } 
                        else if (product_to_test == "pxc80") {
                            result.add("min_upgrade_pxc_80")
                        } 
                        else if (product_to_test == "pxc84") {
                            result.add("min_upgrade_pxc_84")
                        } 
                        else if (product_to_test == "pxc-innovation-lts") {
                            result.add("min_upgrade_pxc_innovation")
                        }
                        
                        return result
                    '''
                ]
            ]
        ],
        string(
            name: 'git_repo',
            defaultValue: 'Percona-QA/package-testing',
            description: 'Git repository to use for testing'
        ),
        string(
            name: 'BRANCH',
            defaultValue: 'master',
            description: 'Git branch to use for testing'
        )
    ])
])

pipeline {
    agent {
        label 'min-bookworm-x64'
    }

    options {
        buildDiscarder(logRotator(numToKeepStr: '100'))
    }

    environment {
        pro = "no"
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
                        expression { params.test_type == "install" }
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

                stage("MIN_UPGRADE INNOVATION ") {
                            when {
                                allOf{
                                    expression{params.test_type == "min_upgrade_pxc_innovation"}
                                    expression{params.test_repo != "main"}
                                    expression{params.product_to_test == "pxc-innovation-lts"}                
                                }
                            }

                            //agent {
                                //label 'min-bookworm-x64'
                            //}


                            environment {

                                UPGRADE_BOOTSTRAP_INSTANCE_PRIVATE_IP = "${WORKSPACE}/min_upgrade/bootstrap_instance_private_ip.json"
                                UPGRADE_COMMON_INSTANCE_PRIVATE_IP = "${WORKSPACE}/min_upgrade/common_instance_private_ip.json"
                                
                                UPGRADE_BOOTSTRAP_INSTANCE_PUBLIC_IP = "${WORKSPACE}/min_upgrade/bootstrap_instance_public_ip.json"
                                UPGRADE_COMMON_INSTANCE_PUBLIC_IP  = "${WORKSPACE}/min_upgrade/common_instance_public_ip.json"

                                JENWORKSPACE = "${env.WORKSPACE}"

                                MIN_UPGRADE_TEST = "PXC_INNOVATION_LTS_MINOR_UPGRADE"
                            }

                            options {
                                skipDefaultCheckout()
                            }


                            steps {
                                setup()
                                upgrade("min_upgrade")
                            }
                            post{
                                always {
                                    post_upgrade("min_upgrade")
                                }
                            }
                }

                stage("MIN_UPGRADE_PXC80") {
                            when {
                                allOf{
                                    expression{params.test_type == "min_upgrade_pxc_80"}
                                    expression{params.test_repo != "main"}
                                    expression{params.product_to_test == "pxc80" }
                                }
                            }



                            //agent {
                                //label 'min-bookworm-x64'
                            //}


                            environment {

                                UPGRADE_BOOTSTRAP_INSTANCE_PRIVATE_IP = "${WORKSPACE}/min_upgrade/bootstrap_instance_private_ip.json"
                                UPGRADE_COMMON_INSTANCE_PRIVATE_IP = "${WORKSPACE}/min_upgrade/common_instance_private_ip.json"
                                
                                UPGRADE_BOOTSTRAP_INSTANCE_PUBLIC_IP = "${WORKSPACE}/min_upgrade/bootstrap_instance_public_ip.json"
                                UPGRADE_COMMON_INSTANCE_PUBLIC_IP  = "${WORKSPACE}/min_upgrade/common_instance_public_ip.json"

                                JENWORKSPACE = "${env.WORKSPACE}"

                                MIN_UPGRADE_TEST = "PXC80_MINOR_UPGRADE"
                            }

                            options {
                                skipDefaultCheckout()
                            }


                            steps {
                                setup()
                                upgrade("min_upgrade")
                            }
                            post{
                                always {
                                    post_upgrade("min_upgrade")
                                }
                            }
                }

                stage("MIN_UPGRADE_PXC84") {
                            when {
                                allOf{
                                    expression{params.test_type == "min_upgrade_pxc_84"}
                                    expression{params.test_repo != "main"}
                                    expression{params.product_to_test == "pxc84" }
                                }
                            }

                            //agent {
                                //label 'min-bookworm-x64'
                            //}

                            environment {

                                UPGRADE_BOOTSTRAP_INSTANCE_PRIVATE_IP = "${WORKSPACE}/min_upgrade/bootstrap_instance_private_ip.json"
                                UPGRADE_COMMON_INSTANCE_PRIVATE_IP = "${WORKSPACE}/min_upgrade/common_instance_private_ip.json"
                                
                                UPGRADE_BOOTSTRAP_INSTANCE_PUBLIC_IP = "${WORKSPACE}/min_upgrade/bootstrap_instance_public_ip.json"
                                UPGRADE_COMMON_INSTANCE_PUBLIC_IP  = "${WORKSPACE}/min_upgrade/common_instance_public_ip.json"

                                JENWORKSPACE = "${env.WORKSPACE}"

                                MIN_UPGRADE_TEST = "PXC84_MINOR_UPGRADE"
                            }

                            options {
                                skipDefaultCheckout()
                            }


                            steps {
                                setup()
                                upgrade("min_upgrade")
                            }
                            post{
                                always {
                                    post_upgrade("min_upgrade")
                                }
                            }
                }

                stage("MIN_UPGRADE_PXC57_EOL_MAIN_TO_EOL_TESTING") {
                            when {
                                allOf{
                                    expression{params.test_type == "min_upgrade_pxc57_eol_main_to_eol_testing"}
                                    expression{params.test_repo != "main"}
                                    expression{params.product_to_test == "pxc57"}
                                }
                            }

                            //agent {
                                //label 'min-bookworm-x64'
                            //}

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
                                upgrade("min_upgrade")
                            }
                            post{
                                always {
                                    post_upgrade("min_upgrade")
                                }   
                            }
                }

                stage("MAJOR UPGRADE pxc57") {
                            when {
                                allOf{
                                    expression{params.test_type == "install_and_upgrade" || params.test_type == "maj_upgrade" }
                                    expression{params.test_repo != "main"}
                                    expression{params.product_to_test == "pxc57"}                
                                }
                            }


                            //agent {
                                //label 'min-bookworm-x64'
                            //}

                            environment {

                                UPGRADE_MAJ_BOOTSTRAP_INSTANCE_PRIVATE_IP = "${WORKSPACE}/maj_upgrade/bootstrap_instance_private_ip.json"
                                UPGRADE_MAJ_COMMON_INSTANCE_PRIVATE_IP = "${WORKSPACE}/maj_upgrade/common_instance_private_ip.json"
                                
                                UPGRADE_MAJ_BOOTSTRAP_INSTANCE_PUBLIC_IP = "${WORKSPACE}/maj_upgrade/bootstrap_instance_public_ip.json"
                                UPGRADE_MAJ_COMMON_INSTANCE_PUBLIC_IP  = "${WORKSPACE}/maj_upgrade/common_instance_public_ip.json"

                                JENWORKSPACE = "${env.WORKSPACE}"

                            }

                            options {
                                skipDefaultCheckout()
                            }

                            steps {
                                setup()

                                script{
                                    echo "maj_upgrade STAGE INSIDE"
                                    def param_test_type = "maj_upgrade"   
                                    echo "1. Creating Molecule Instances for running PXC UPGRADE tests.. Molecule create step"
                                    runMoleculeAction("create", params.product_to_test, params.node_to_test, "maj_upgrade", "main", "no")
                                    setInventories("maj_upgrade")
                                    echo "2. Run Install scripts and tests for running PXC maj_upgrade tests.. Molecule converge step"
                                        catchError(buildResult: 'UNSTABLE', stageResult: 'FAILURE'){
                                            runMoleculeAction("converge", params.product_to_test, params.node_to_test, "maj_upgrade", "main", "no")
                                        }
                                    echo "3. Run maj_upgrade scripts and playbooks for running PXC maj_upgrade tests.. Molecule side-effect step"
                                        catchError(buildResult: 'UNSTABLE', stageResult: 'FAILURE'){
                                            runMoleculeAction("side-effect", params.product_to_test, params.node_to_test, "maj_upgrade", params.test_repo, "yes")
                                        }
                                }
                            }
                            post{
                                always{
                                    script{
                                        def param_test_type = "maj_upgrade"
                                        echo "4. Take Backups of the Logs.. for PXC maj_upgrade tests"
                                        setInventories("maj_upgrade")
                                        runlogsbackup(params.product_to_test, "maj_upgrade")
                                        echo "5. Destroy the Molecule instances for PXC maj_upgrade tests.."
                                        runMoleculeAction("destroy", params.product_to_test, params.node_to_test, "maj_upgrade", params.test_repo, "yes")
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
        always {
            deleteBuildInstances()
        }
    }
}

