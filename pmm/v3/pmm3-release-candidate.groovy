library changelog: false, identifier: 'lib@master', retriever: modernSCM([
    $class: 'GitSCMSource',
    remote: 'https://github.com/Percona-Lab/jenkins-pipelines.git'
]) _

def pmm_submodules() {
    return [
        "pmm",
        "grafana-dashboards",
        "grafana",
        "pmm-ui-tests",
        "pmm-qa",
        "mysqld_exporter",
        "node_exporter",
        "postgres_exporter",
        "proxysql_exporter",
        "rds_exporter",
        "azure_metrics_exporter",
        "percona-toolkit",
        "pmm-dump"
    ]
}

void deleteReleaseBranches(String VERSION) {

    pmm_submodules().each { submodule ->
        println "Deleting Release branch for : $submodule"
        deleteBranch(submodule, 'pmm-' + VERSION)
    }
    withCredentials([sshUserPrivateKey(credentialsId: 'GitHub SSH Key', keyFileVariable: 'SSHKEY', passphraseVariable: '', usernameVariable: '')]) {
        sh '''
            set -o errexit
            set -o xtrace

            # Configure git to push using ssh
            export GIT_SSH_COMMAND="/usr/bin/ssh -i ${SSHKEY} -o StrictHostKeyChecking=no -o UserKnownHostsFile=/dev/null"

            git push origin --delete ${RELEASE_BRANCH}
        '''
    }
}

void setupReleaseBranches(String VERSION) {
    git branch: SUBMODULES_GIT_BRANCH, credentialsId: 'GitHub SSH Key', poll: false, url: 'git@github.com:Percona-Lab/pmm-submodules'
    sh '''
        git config --global user.email "noreply@percona.com"
        git config --global user.name "PMM Jenkins"

        git branch ${RELEASE_BRANCH}
        git checkout ${RELEASE_BRANCH}
    '''
    pmm_submodules().each { submodule ->
        println "Preparing Release branch for Submodule: $submodule"
        createBranch(submodule, 'pmm-' + VERSION)
    }
    withCredentials([sshUserPrivateKey(credentialsId: 'GitHub SSH Key', keyFileVariable: 'SSHKEY', passphraseVariable: '', usernameVariable: '')]) {
        sh '''
            set -o errexit
            set -o xtrace

            # Configure git to push using ssh
            export GIT_SSH_COMMAND="/usr/bin/ssh -i ${SSHKEY} -o StrictHostKeyChecking=no -o UserKnownHostsFile=/dev/null"

            git commit -a -m "Prepare Release Branch Submodules"
            git branch
            git push --set-upstream origin ${RELEASE_BRANCH}
        '''
    }
}

void createBranch(String SUBMODULE, String BRANCH) {
    withCredentials([sshUserPrivateKey(credentialsId: 'GitHub SSH Key', keyFileVariable: 'SSHKEY', passphraseVariable: '', usernameVariable: '')]) {
        sh '''
            set -o errexit
            set -o xtrace

            # Configure git to push using ssh
            export GIT_SSH_COMMAND="/usr/bin/ssh -i ${SSHKEY} -o StrictHostKeyChecking=no -o UserKnownHostsFile=/dev/null"
            export SUBMODULE=''' + SUBMODULE + '''
            export BRANCH=''' + BRANCH + '''
            export submodule_url=\$(git config --file=.gitmodules submodule.${SUBMODULE}.url)
            export submodule_branch=\$(git config --file=.gitmodules submodule.${SUBMODULE}.branch)
            export ssh_submodule_url=\$(echo $submodule_url | sed "s^https://github.com/^git@github.com:^g")
            git config --file=.gitmodules submodule.${SUBMODULE}.branch ${BRANCH}
            cd /tmp/
            export submodule_branch_exist=\$(git ls-remote --heads ${submodule_url} ${BRANCH} | wc -l)
            if [[ ${submodule_branch_exist} != 1 ]]; then
                git clone --branch ${submodule_branch} ${ssh_submodule_url}
                cd ${SUBMODULE}
                git branch ${BRANCH}
                git checkout ${BRANCH}
                git push --set-upstream origin ${BRANCH}
            fi
        '''
    }
}

void deleteBranch(String SUBMODULE, String BRANCH) {
    withCredentials([sshUserPrivateKey(credentialsId: 'GitHub SSH Key', keyFileVariable: 'SSHKEY', passphraseVariable: '', usernameVariable: '')]) {
        sh '''
            set -o errexit
            set -o xtrace

            # Configure git to push using ssh
            export GIT_SSH_COMMAND="/usr/bin/ssh -i ${SSHKEY} -o StrictHostKeyChecking=no -o UserKnownHostsFile=/dev/null"
            export SUBMODULE=''' + SUBMODULE + '''
            export BRANCH=''' + BRANCH + '''
            export submodule_url=$(git config --file=.gitmodules submodule.${SUBMODULE}.url)
            export submodule_branch=\$(git config --file=.gitmodules submodule.${SUBMODULE}.branch)
            export ssh_submodule_url=\$(echo $submodule_url | sed "s^https://github.com/^git@github.com:^g")
            cd /tmp/
            export submodule_branch_exist=\$(git ls-remote --heads ${submodule_url} ${submodule_branch} | wc -l)
            if [[ ${submodule_branch_exist} != 0 ]]; then
                git clone --branch ${submodule_branch} ${ssh_submodule_url}
                cd ${SUBMODULE}
                git push origin --delete ${submodule_branch}
            fi
        '''
    }
}

String DEFAULT_BRANCH = 'v3'

pipeline {
    agent {
        label 'agent-amd64-ol9'
    }
    parameters {
        string(
            defaultValue: DEFAULT_BRANCH,
            description: 'Prepare Submodules from pmm-submodules branch',
            name: 'SUBMODULES_GIT_BRANCH'
        )
        choice(
            choices: ['no', 'yes'],
            description: 'Recreate Release branches, Option to be used only to recreate release branches',
            name: 'REMOVE_RELEASE_BRANCH'
        )
        string(
            defaultValue: '#pmm-dev',
            description: 'Channel to send notifications to',
            name: 'NOTIFICATION_CHANNEL'
        )
    }
    stages {
        stage('Update API descriptors') {
            when {
                expression { env.REMOVE_RELEASE_BRANCH == 'no' }
            }
            steps {
                script {
                    env.TARGET_BRANCH = params.SUBMODULES_GIT_BRANCH

                    git branch: env.TARGET_BRANCH, credentialsId: 'GitHub SSH Key', poll: false, url: 'git@github.com:percona/pmm'

                    withCredentials([sshUserPrivateKey(credentialsId: 'GitHub SSH Key', keyFileVariable: 'SSHKEY', passphraseVariable: '', usernameVariable: '')]) {
                        sh '''
                            set -ex

                            # Configure git to push using ssh
                            git config --global user.email "noreply@percona.com"
                            git config --global user.name "PMM Jenkins"
                            export GIT_SSH_COMMAND="/usr/bin/ssh -i ${SSHKEY} -o StrictHostKeyChecking=no -o UserKnownHostsFile=/dev/null"

                            docker run --rm -v $PWD/.:/pmm public.ecr.aws/e7j3v3n0/rpmbuild:3 sh -c '
                                cd /pmm
                                make init
                                make descriptors
                            '

                            API_DESCRIPTOR=$(git diff --text | grep -q 'descriptor\\.bin' && echo "CHANGED" || echo "NOT_CHANGED")
                            if [[ $API_DESCRIPTOR == "CHANGED" ]]; then
                                git commit -a -m "Update descriptors"
                                git show
                                git push origin ${TARGET_BRANCH}
                            fi
                            echo "${API_DESCRIPTOR}" > descriptor_status.txt
                        '''
                    }

                    env.API_DESCRIPTOR = sh(script: 'cat descriptor_status.txt', returnStdout: true).trim()

                    deleteDir()
                }
            }
        }
        stage('Rewind Submodules') {
            when {
                expression { env.REMOVE_RELEASE_BRANCH == 'no' && env.TARGET_BRANCH == DEFAULT_BRANCH && env.API_DESCRIPTOR == 'CHANGED' }
            }
            steps {
                build job: 'pmm3-submodules-rewind', propagate: false, wait: true
            }
        }
        stage('Get version') {
            steps {
                script {
                    git branch: env.SUBMODULES_GIT_BRANCH,
                        credentialsId: 'GitHub SSH Key',
                        poll: false,
                        url: 'git@github.com:Percona-Lab/pmm-submodules'
                    env.VERSION = sh(returnStdout: true, script: "cat VERSION").trim()
                    env.RELEASE_BRANCH = 'pmm-' + VERSION
                }
            }
        }
        stage('Remove Release branches for submodules') {
            when {
                expression { env.REMOVE_RELEASE_BRANCH == "yes" && env.SUBMODULES_GIT_BRANCH != DEFAULT_BRANCH }
            }
            steps {
                deleteReleaseBranches(env.SUBMODULES_GIT_BRANCH)
                script {
                    currentBuild.description = "Release branches were deleted: ${env.SUBMODULES_GIT_BRANCH}"
                    return
                }
            }
        }
        stage('Check if Release Branch Exists') {
            steps {
                deleteDir()
                script {
                    currentBuild.description = "$VERSION"
                    slackSend botUser: true,
                        channel: env.NOTIFICATION_CHANNEL,
                        color: '#0892d0',
                        message: "Release candidate PMM $VERSION build has started. You can check progress at: ${BUILD_URL}"
                    env.EXIST = sh (
                        script: 'git ls-remote --heads https://github.com/Percona-Lab/pmm-submodules pmm-\${VERSION} | wc -l',
                        returnStdout: true
                    ).trim()
                }
            }
        }
        stage('Create Release branches') {
            when {
                expression { env.EXIST.toInteger() == 0 }
            }
            steps {
                setupReleaseBranches(VERSION)
            }
        }
        stage('Rewind Release Submodule') {
            when {
                expression { env.REMOVE_RELEASE_BRANCH == "no"}
            }
            steps {
                script {
                    rewindSubmodule = build job: 'pmm3-rewind-submodules-fb', propagate: false, parameters: [
                        string(name: 'GIT_BRANCH', value: RELEASE_BRANCH)
                    ]
                }
            }
        }
        stage('Run Server & Client RC Autobuilds') {
            when {
                expression { env.REMOVE_RELEASE_BRANCH == "no"}
            }
            parallel {
                stage('Start PMM3 Server Autobuild') {
                    steps {
                        script {
                            pmmServer = build job: 'pmm3-server-autobuild', parameters: [
                                string(name: 'GIT_BRANCH', value: RELEASE_BRANCH),
                                string(name: 'DESTINATION', value: 'testing')
                            ]
                        }
                    }
                }
                stage('Start PMM3 Client Autobuild') {
                    steps {
                        script {
                            pmmClient = build job: 'pmm3-client-autobuild', parameters: [
                                string(name: 'GIT_BRANCH', value: RELEASE_BRANCH),
                                string(name: 'DESTINATION', value: 'testing')
                            ]
                            env.TARBALL_URL = pmmClient.buildVariables.TARBALL_URL
                        }
                    }
                }
            }
        }
        stage('Run OVF & AMI RC builds') {
            when {
                expression { env.REMOVE_RELEASE_BRANCH == "no"}
            }
            parallel {
                stage('Start AMI RC Build') {
                    steps {
                        script {
                            pmmAMI = build job: 'pmm3-ami', parameters: [
                                string(name: 'PMM_BRANCH', value: "pmm-${VERSION}"),
                                string(name: 'RELEASE_CANDIDATE', value: "yes")
                            ]
                            env.AMI_ID = pmmAMI.buildVariables.AMI_ID
                        }
                    }
                }
                stage('Start OVF RC Build') {
                    steps {
                        script {
                            pmmOVF = build job: 'pmm3-ovf', parameters: [
                                string(name: 'PMM_BRANCH', value: "pmm-${VERSION}"),
                                string(name: 'RELEASE_CANDIDATE', value: 'yes')
                            ]
                        }
                    }
                }
            }
        }
        // This staging instance currently sees no use
        // stage('Launch a staging instance') {
        //     when {
        //         expression { env.REMOVE_RELEASE_BRANCH == "no"}
        //     }            
        //     steps {
        //         script {
        //             pmmStaging = build job: 'pmm3-aws-staging-start', propagate: false, parameters: [
        //                 string(name: 'DOCKER_VERSION', value: "perconalab/pmm-server:${VERSION}-rc"),
        //                 string(name: 'CLIENT_VERSION', value: "pmm-rc"),
        //                 string(name: 'ENABLE_TESTING_REPO', value: "yes"),
        //                 string(name: 'ENABLE_EXPERIMENTAL_REPO', value: "no"),
        //                 string(name: 'NOTIFY', value: "false"),
        //                 string(name: 'DAYS', value: "14")
        //             ]
        //             env.IP = pmmStaging.buildVariables.IP
        //             env.TEST_URL = env.IP ? "Testing environment (14d): https://${env.IP}" : ""
        //         }
        //     }
        // }
        stage('Scan image for vulnerabilities') {
            when {
                expression { env.REMOVE_RELEASE_BRANCH == "no"}
            }
            steps {
                script {
                    imageScan = build job: 'pmm3-image-scanning', propagate: false, parameters: [
                        string(name: 'IMAGE', value: "perconalab/pmm-server"),
                        string(name: 'TAG', value: "${VERSION}-rc")
                    ]

                    env.SCAN_REPORT_URL = ""
                    if (imageScan.result == 'SUCCESS') {
                        copyArtifacts filter: 'report.html', projectName: 'pmm3-image-scanning'
                        sh 'mv report.html report-${VERSION}-rc.html'
                        archiveArtifacts "report-${VERSION}-rc.html"
                        env.SCAN_REPORT_URL = "CVE Scan Report: ${BUILD_URL}artifact/report-${VERSION}-rc.html"

                        copyArtifacts filter: 'evaluations/**/evaluation_*.json', projectName: 'pmm3-image-scanning'
                        sh 'mv evaluations/*/*/*/evaluation_*.json ./report-${VERSION}-rc.json'
                        archiveArtifacts "report-${VERSION}-rc.json"
                    }
                }
            }
        }
    }
    post {
        success {
            slackSend botUser: true,
                      channel: env.NOTIFICATION_CHANNEL,
                      color: '#00FF00',
                      message: """New Release Candidate is out :rocket:
Server: perconalab/pmm-server:${VERSION}-rc
Client: perconalab/pmm-client:${VERSION}-rc
OVA: https://percona-vm.s3.amazonaws.com/PMM3-Server-${VERSION}.ova
AMI: ${env.AMI_ID}
Tarball: ${env.TARBALL_URL}
${env.SCAN_REPORT_URL}
                      """
        }
    }
}
