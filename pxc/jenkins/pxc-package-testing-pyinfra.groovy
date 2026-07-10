import groovy.transform.Field

def getAwsCredentials() {
    return [
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
}

def setup() {
    sh '''
        sudo apt update -y
        sudo apt install -y python3 python3-pip python3-dev python3-venv git jq unzip
        rm -rf package-testing
        git clone https://github.com/${git_repo} package-testing --branch ${BRANCH}
        python3 -m venv virtenv
        . virtenv/bin/activate
        python3 --version
        python3 -m pip install --upgrade pip
        python3 -m pip install -r package-testing/pyinfra/pxc/requirements.txt
        pyinfra --version
    '''
}

def provisionInstances() {
    withCredentials(getAwsCredentials()) {
        sh '''
            . ${WORKSPACE}/virtenv/bin/activate
            cd package-testing/pyinfra/pxc
            python3 provision.py \
                --os ${node_to_test} \
                --product ${product_to_test} \
                --job-name ${JOB_NAME} \
                --build-number ${BUILD_NUMBER} \
                --iit-billing-tag ${IIT_BILLING_TAG} \
                --state-file ${PXC_STATE_FILE}
        '''
    }
    archiveArtifacts artifacts: 'pxc-state.json', allowEmptyArchive: true
}

def runPyinfraDeploy(String deployFile, String limitGroup, Boolean serial) {
    def gitAccount = params.git_repo.tokenize('/')[0]
    def serialFlag = serial ? '--serial' : ''
    withCredentials(getAwsCredentials()) {
        sh """
            . \${WORKSPACE}/virtenv/bin/activate
            install -m 600 "\${MOLECULE_AWS_PRIVATE_KEY}" "\${WORKSPACE}/.pxc_ssh_key"
            export PXC_SSH_KEY_PATH="\${WORKSPACE}/.pxc_ssh_key"
            cd package-testing/pyinfra/pxc
            pyinfra -y -v --limit ${limitGroup} ${serialFlag} inventory.py ${deployFile} \
                --data product=${params.product_to_test} \
                --data install_repo=${params.test_repo} \
                --data check_version=yes \
                --data git_account=${gitAccount} \
                --data testing_branch=${params.BRANCH}
        """
    }
}

def backupLogs() {
    withCredentials(getAwsCredentials()) {
        sh """
            . \${WORKSPACE}/virtenv/bin/activate
            install -m 600 "\${MOLECULE_AWS_PRIVATE_KEY}" "\${WORKSPACE}/.pxc_ssh_key"
            export PXC_SSH_KEY_PATH="\${WORKSPACE}/.pxc_ssh_key"
            cd package-testing/pyinfra/pxc
            pyinfra -y inventory.py deploy_logsbackup.py \
                --data workspace=\${WORKSPACE} \
                --data test_phase=install
        """
    }
}

def destroyInstances() {
    withCredentials(getAwsCredentials()) {
        sh '''
            . ${WORKSPACE}/virtenv/bin/activate
            cd package-testing/pyinfra/pxc
            python3 destroy.py --state-file ${PXC_STATE_FILE}
        '''
    }
}

def deleteBuildInstances() {
    // Safety net: terminate anything tagged with this job/build that the
    // state-file based destroy missed. rocky-linux instances live in
    // us-west-2, everything else in us-west-1 (see os_config.py).
    script {
        withCredentials(getAwsCredentials()) {
            def jobName = env.JOB_NAME.trim()
            def buildNumber = env.BUILD_NUMBER

            ['us-west-1', 'us-west-2'].each { region ->
                echo "Checking for leftover EC2 instances in ${region} (job-name=${jobName}, build-number=${buildNumber})"

                sh """
                    echo "=== EC2 Instances to be cleaned up in ${region} ==="
                    aws ec2 describe-instances --region ${region} \\
                    --filters "Name=tag:job-name,Values=${jobName}" "Name=tag:build-number,Values=${buildNumber}" \\
                    --query "Reservations[].Instances[].[InstanceId,Tags[?Key=='Name'].Value|[0],State.Name]" \\
                    --output table || echo "No instances found with job-name tag: ${jobName}"
                """

                def instanceIds = sh(
                    script: """
                        aws ec2 describe-instances --region ${region} \\
                        --filters "Name=tag:job-name,Values=${jobName}" "Name=tag:build-number,Values=${buildNumber}" "Name=instance-state-name,Values=running" \\
                        --query "Reservations[].Instances[].InstanceId" \\
                        --output text
                    """,
                    returnStdout: true
                ).trim()

                if (instanceIds) {
                    echo "Found instances to terminate in ${region}: ${instanceIds}"
                    sh """
                        echo "${instanceIds}" | xargs -r aws ec2 terminate-instances --region ${region} --instance-ids
                    """
                    sleep(30)
                    sh """
                        aws ec2 describe-instances --region ${region} --instance-ids ${instanceIds} \\
                        --query "Reservations[].Instances[].[InstanceId,Tags[?Key=='Name'].Value|[0],State.Name]" --output table
                    """
                } else {
                    echo "No instances found to terminate in ${region}"
                }
            }
        }
    }
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
                    script: 'return ["pxc84", "pxc80"]'
                ]
            ]
        ],
        [
            $class: 'CascadeChoiceParameter',
            choiceType: 'PT_SINGLE_SELECT',
            description: 'OS to test on',
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
                                        'debian-12',
                                        'debian-11',
                                        'debian-12-arm',
                                        'debian-11-arm',
                                        'ol-8',
                                        'ol-9',
                                        'rhel-8',
                                        'rhel-9',
                                        'rhel-8-arm',
                                        'rhel-9-arm',
                                        'rocky-linux-8',
                                        'rocky-linux-8-arm',
                                        'rocky-linux-9',
                                        'rocky-linux-9-arm',
                                        'amazon-linux-2023',
                                        'amazon-linux-2023-arm'
                        ]

                        def non_pro_pxc84 = [
                                        'ubuntu-noble',
                                        'ubuntu-jammy',
                                        'ubuntu-noble-arm',
                                        'ubuntu-jammy-arm',
                                        'debian-13',
                                        'debian-12',
                                        'debian-11',
                                        'debian-13-arm',
                                        'debian-12-arm',
                                        'debian-11-arm',
                                        'ol-8',
                                        'ol-9',
                                        'rhel-8',
                                        'rhel-9',
                                        'rhel-10',
                                        'rhel-8-arm',
                                        'rhel-9-arm',
                                        'rhel-10-arm',
                                        'rocky-linux-8',
                                        'rocky-linux-8-arm',
                                        'rocky-linux-9',
                                        'rocky-linux-9-arm',
                                        'amazon-linux-2023',
                                        'amazon-linux-2023-arm'
                        ]

                        if (product_to_test == "pxc80") {
                            return non_pro_pxc80
                        } else if (product_to_test == "pxc84") {
                            return non_pro_pxc84
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
        choice(
            name: 'test_type',
            choices: ['install'],
            description: 'Test type to run (only install is supported by the pyinfra job for now)'
        ),
        string(
            defaultValue: 'Percona-QA/package-testing',
            description: 'Git repository to use for testing',
            name: 'git_repo',
            trim: false
        ),
        string(
            defaultValue: 'master',
            description: 'Git branch to use for testing',
            name: 'BRANCH',
            trim: false
        )
    ])
])

pipeline {
    agent {
        label 'min-bookworm-x64'
    }

    options {
        buildDiscarder(logRotator(numToKeepStr: '100'))
        timeout(time: 4, unit: 'HOURS')
    }

    environment {
        IIT_BILLING_TAG = "${params.product_to_test}_package_testing"
        PXC_STATE_FILE = "${WORKSPACE}/pxc-state.json"
        PXC_SSH_KEY_PATH = "${WORKSPACE}/.pxc_ssh_key"
    }

    stages {
        stage('Set build name') {
            steps {
                script {
                    currentBuild.displayName = "${env.BUILD_NUMBER}-${params.product_to_test}-${params.node_to_test}-${params.test_repo}-install"
                }
            }
        }

        stage('Setup') {
            steps {
                script {
                    retry(2) {
                        setup()
                    }
                }
            }
        }

        stage('Provision instances') {
            steps {
                provisionInstances()
            }
        }

        stage('Deploy bootstrap node (pxc1)') {
            steps {
                catchError(buildResult: 'UNSTABLE', stageResult: 'FAILURE') {
                    runPyinfraDeploy('deploy_bootstrap.py', 'bootstrap', false)
                }
            }
        }

        stage('Deploy joiner nodes (pxc2, pxc3)') {
            when {
                expression { currentBuild.currentResult == 'SUCCESS' }
            }
            steps {
                catchError(buildResult: 'UNSTABLE', stageResult: 'FAILURE') {
                    runPyinfraDeploy('deploy_common.py', 'joiners', true)
                }
            }
        }
    }

    post {
        always {
            catchError(buildResult: 'UNSTABLE', stageResult: 'FAILURE') {
                backupLogs()
            }
            archiveArtifacts artifacts: 'PXC/**/*.tar.gz', followSymlinks: false, allowEmptyArchive: true
            catchError(buildResult: 'UNSTABLE', stageResult: 'FAILURE') {
                destroyInstances()
            }
            deleteBuildInstances()
        }
    }
}
