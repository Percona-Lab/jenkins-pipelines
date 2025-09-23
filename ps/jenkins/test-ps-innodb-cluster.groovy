library changelog: false, identifier: 'lib@master', retriever: modernSCM([
    $class: 'GitSCMSource',
    remote: 'https://github.com/Percona-Lab/jenkins-pipelines.git'
]) _

void installDependencies() {
    sh """
        sudo apt update -y
        sudo apt install -y python3 python3-pip python3-dev python3-venv jq tar unzip
        python3 -m venv virtenv
        . virtenv/bin/activate
        python3 --version
        python3 -m pip install --upgrade pip
        python3 -m pip install --upgrade setuptools
        python3 -m pip install --upgrade setuptools-rust
        python3 -m pip install --upgrade PyYaml==5.3.1 molecule==3.3.0 testinfra pytest molecule-ec2==0.3 molecule[ansible] "ansible<10.0.0" "ansible-lint>=5.1.1,<6.0.0" boto3 boto
       
       """

    sh '''
        rm -rf /tmp/package-testing
        git clone -b "${BRANCH}" https://github.com/${git_repo}.git /tmp/package-testing
    '''
}

void runMoleculeAction(String action, String scenario) {
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
            . virtenv/bin/activate
            cd /tmp/package-testing/molecule/ps-innodb-cluster
            cd server
            export INSTANCE_PRIVATE_IP=\${SERVER_INSTANCE_PRIVATE_IP}
            molecule ${action} -s ${scenario}
            cd -
            cd router
            export INSTANCE_PRIVATE_IP=\${ROUTER_INSTANCE_PRIVATE_IP}
            molecule ${action} -s ${scenario}
            cd -
        """
    }
}

void setInstancePrivateIPEnvironment() {
    env.PS_NODE1_IP = sh(
        script: 'jq -r \'.[] | select(.instance | startswith("ps-node1")).private_ip\' ${SERVER_INSTANCE_PRIVATE_IP}',
        returnStdout: true
    ).trim()
    env.PS_NODE2_IP = sh(
        script: 'jq -r \'.[] | select(.instance | startswith("ps-node2")).private_ip\' ${SERVER_INSTANCE_PRIVATE_IP}',
        returnStdout: true
    ).trim()
    env.PS_NODE3_IP = sh(
        script: 'jq -r \'.[] | select(.instance | startswith("ps-node3")).private_ip\' ${SERVER_INSTANCE_PRIVATE_IP}',
        returnStdout: true
    ).trim()
    env.MYSQL_ROUTER_IP = sh(
        script: 'jq -r \'.[] | select(.instance | startswith("mysql-router")).private_ip\' ${ROUTER_INSTANCE_PRIVATE_IP}',
        returnStdout: true
    ).trim()
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
                    credentialsId: '5d78d9c7-2188-4b16-8e31-4d5782c6ceaa',
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
            aws ec2 describe-instances --region us-west-2 --filters "Name=tag:job-name,Values=${jobName}" "Name=tag:build-number,Values=${BUILD_NUMBER}"  --query "Reservations[].Instances[].InstanceId" --output text
            """

            sh """
            echo "=== EC2 Instances to be cleaned up ==="
            aws ec2 describe-instances --region us-west-2 \\
            --filters "Name=tag:job-name,Values=${jobName}" "Name=tag:build-number,Values=${BUILD_NUMBER}" \\
            --query "Reservations[].Instances[].[InstanceId,Tags[?Key=='Name'].Value|[0],State.Name]" \\
            --output table || echo "No instances found with job-name tag: ${jobName}"
            """

            def instanceIds = sh(
                script: """
                aws ec2 describe-instances --region us-west-2 \\
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


pipeline {
    agent {
        label 'min-bookworm-x64'
    }

    options {
        skipDefaultCheckout()
    }

    environment {
        PRODUCT_TO_TEST = "${params.PRODUCT_TO_TEST}"
        SERVER_INSTANCE_PRIVATE_IP = "${WORKSPACE}/server_instance_private_ip.json"
        ROUTER_INSTANCE_PRIVATE_IP = "${WORKSPACE}/router_instance_private_ip.json"
    }

    parameters {
        choice(
            choices: ['PS80','PS84','PS_LTS_INN'],
            description: 'Product for which the packages will be tested',
            name: 'PRODUCT_TO_TEST'
        )
        choice(
            name: 'TEST_DIST',
            choices: [
                'ubuntu-noble',
                'ubuntu-jammy',
                'ubuntu-focal',  
                'debian-12',
                'debian-11',
                'oracle-8',
                'oracle-9',
                'rhel-8',
                'rhel-9',
                'rhel-10',
                'rhel-8-arm',
                'rhel-9-arm',
                'rhel-10-arm',
                'debian-11-arm',
                'debian-12-arm',
                'ubuntu-focal-arm',
                'ubuntu-jammy-arm',
                'ubuntu-noble-arm'
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
        stage('Install Dependencies') {
            steps {
                script {
                    installDependencies()
                }
            }
        }
        stage('SET UPSTREAM_VERSION,PS_VERSION and PS_REVISION') {
            steps {
                script {
                    echo "PRODUCT_TO_TEST is: ${env.PRODUCT_TO_TEST}"

                    def UPSTREAM_VERSION = sh(
                        script: ''' 
                            grep ${PRODUCT_TO_TEST}_VER /tmp/package-testing/VERSIONS | awk -F= '{print \$2}' | sed 's/"//g' | awk -F- '{print \$1}'
                         ''',
                        returnStdout: true
                        ).trim()

                    def PS_VERSION = sh(
                        script: ''' 
                            grep ${PRODUCT_TO_TEST}_VER /tmp/package-testing/VERSIONS | awk -F= '{print \$2}' | sed 's/"//g' | awk -F- '{print \$2}'
                        ''',
                        returnStdout: true
                        ).trim()

                    def PS_REVISION = sh(
                        script: '''
                             grep ${PRODUCT_TO_TEST}_REV /tmp/package-testing/VERSIONS | awk -F= '{print \$2}' | sed 's/"//g' 
                        ''',
                        returnStdout: true
                        ).trim()
                    
                    
                    env.UPSTREAM_VERSION = UPSTREAM_VERSION
                    env.PS_VERSION = PS_VERSION
                    env.PS_REVISION = PS_REVISION

                    echo "UPSTREAM_VERSION fetched: ${env.UPSTREAM_VERSION}"
                    echo "PS_VERSION fetched: ${env.PS_VERSION}"
                    echo "PS_REVISION fetched: ${env.PS_REVISION}"

                }
            }
        }
        stage('Set environmental variable'){
            steps{
                 script {
                    // Now, you can access these global environment variables
                    echo "Using UPSTREAM_VERSION: ${env.UPSTREAM_VERSION}"
                    echo "Using PS_VERSION: ${env.PS_VERSION}"
                    echo "Using PS_REVISION: ${env.PS_REVISION}"
                }
            }
        }
        stage("Set up") {
            steps {
            script {
                   currentBuild.displayName = "#${BUILD_NUMBER}-${UPSTREAM_VERSION}-${PS_VERSION}-${TEST_DIST}"
                   currentBuild.description = "${PS_REVISION}-${INSTALL_REPO}"
                }
            }
        }

        stage("Create") {
            steps {
                runMoleculeAction("create", params.TEST_DIST)
                setInstancePrivateIPEnvironment()
            }
        }

        stage("Converge") {
            steps {
                runMoleculeAction("converge", params.TEST_DIST)
            }
        }

        stage("Verify") {
            steps {
                runMoleculeAction("verify", params.TEST_DIST)
            }
        }
    }

    post {
        always {
            script {
                runMoleculeAction("destroy", params.TEST_DIST)
            }

            deleteBuildInstances()

        }
    }
}
