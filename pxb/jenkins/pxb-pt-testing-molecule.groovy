
    library changelog: false, identifier: "lib@master", retriever: modernSCM([
        $class: 'GitSCMSource',
        remote: 'https://github.com/Percona-Lab/jenkins-pipelines.git'
    ])

    properties([
        parameters([

            [
                $class: 'ChoiceParameter',
                choiceType: 'PT_SINGLE_SELECT',
                description: 'Choose the product version to test: PXB8.0, PXB8.4 OR pxb_innovation_lts',
                name: 'product_to_test',
                script: [
                    $class: 'GroovyScript',
                    script: [
                        classpath: [],
                        sandbox: true,
                        script: 'return ["pxb_80", "pxb_innovation_lts", "pxb_84"]'
                    ]
                ]
            ],
            [
                $class: 'CascadeChoiceParameter',
                choiceType: 'PT_SINGLE_SELECT',
                description: 'Server to test (filtered by product version)',
                name: 'server_to_test',
                referencedParameters: 'product_to_test',
                script: [
                    $class: 'GroovyScript',
                    script: [
                        classpath: [],
                        sandbox: true,
                        script: '''
                            if (product_to_test == "pxb_80") {
                                return ["ps-80", "ms-80"]
                            }
                            else if (product_to_test == "pxb_84") {
                                return ["ps-84", "ms-84"]
                            }
                            else if (product_to_test == "pxb_innovation_lts") {
                                return ["ps_innovation_lts", "ms_innovation_lts"]
                            }
                            else {
                                return ["ps_innovation_lts", "ms_innovation_lts", "ps-80", "ms-80", "ps-84", "ms-84"]
                            }
                        '''
                    ]
                ]
            ],
            choice(
                choices: ['testing', 'main', 'experimental'],
                description: 'Choose the repo to install packages and run the tests',
                name: 'install_repo'
            ),
            string(
                defaultValue: 'https://github.com/Percona-QA/package-testing.git',
                description: 'repo name',
                name: 'git_repo',
                trim: false
            ),
            string(
                defaultValue: 'master',
                description: 'Branch for package-testing repository',
                name: 'TESTING_BRANCH'
            ),
            choice(
                choices: ['install', 'upgrade', 'kms'],
                description: 'Scenario To Test',
                name: 'scenario_to_test'
            ),
            choice(
                choices: ['NORMAL', 'PRO'],
                description: 'Choose the product to test',
                name: 'REPO_TYPE'
            )
        ])
    ])

    pipeline {
    agent {
        label 'min-bookworm-x64'
    }
    environment {
        product_to_test = "${params.product_to_test}"
        node_to_test = "${params.server_to_test}"
        install_repo = "${params.install_repo}"
        server_to_test  = "${params.server_to_test}"
        scenario_to_test = "${params.scenario_to_test}"
        REPO_TYPE = "${params.REPO_TYPE}"
        TESTING_BRANCH = "${params.TESTING_BRANCH}"
    }
    options {
        withCredentials(moleculepxbJenkinsCreds())
    }

        stages {
            
            stage('Set Build Name'){
                steps {
                    script {
                        currentBuild.displayName = "${env.BUILD_NUMBER}-${product_to_test}-${server_to_test}-${scenario_to_test}-${REPO_TYPE}"
                    }
                }
            }

            stage('Checkout') {
                steps {
                    deleteDir()
                    git poll: false, branch: "${params.TESTING_BRANCH}", url: "${params.git_repo}"
                }
            }

            stage('Prepare') {
                steps {
                    script {
                        installMoleculeBookworm()
                    }
                }
            }
            stage('RUN TESTS') {
                        steps {
                            script {
                                if (scenario_to_test == 'install') {
                                    sh """
                                        echo PLAYBOOK_VAR="${product_to_test}" > .env.ENV_VARS
                                        echo WORKSPACE_VAR=${WORKSPACE} >> .env.ENV_VARS
                                    """
                                } else {
                                    sh """
                                        echo PLAYBOOK_VAR="${product_to_test}_${scenario_to_test}" > .env.ENV_VARS
                                        echo WORKSPACE_VAR=${WORKSPACE} >> .env.ENV_VARS
                                    """
                                }

                                def envMap = loadEnvFile('.env.ENV_VARS')
                                
                                withEnv(envMap) {
                                    
                                if (REPO_TYPE == 'PRO') {
                                        withCredentials([usernamePassword(credentialsId: 'PS_PRIVATE_REPO_ACCESS', passwordVariable: 'PASSWORD', usernameVariable: 'USERNAME')]) {
                                            script {
                                                moleculeParallelTestPXB(pxbPackageTesting(), "molecule/pxb-package-testing/")
                                            }
                                        }
                                }
                                else {
                                        moleculeParallelTestPXB(pxbPackageTesting(), "molecule/pxb-package-testing/")
                                }

                                }

                            }
                        }

                        post {
                            always {

                                script{
                                    //sh "ls -la ."
                                    //sh "mkdir ARTIFACTS && cp *.zip ARTIFACTS/"
                                    //sh "ls -la ARTIFACTS/"
                                    //sh "zip -r ${env.BUILD_NUMBER}-ARTIFACTS.zip ARTIFACTS"
                                    archiveArtifacts artifacts: '*.zip', allowEmptyArchive: true


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
                    credentialsId: 'c42456e5-c28d-4962-b32c-b75d161bff27',
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

def loadEnvFile(envFilePath) {
    def envMap = []
    def envFileContent = readFile(file: envFilePath).trim().split('\n')
    envFileContent.each { line ->
        if (line && !line.startsWith('#')) {
            def parts = line.split('=')
            if (parts.length == 2) {
                envMap << "${parts[0].trim()}=${parts[1].trim()}"
            }
        }
    }
    return envMap
}
