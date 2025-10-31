library changelog: false, identifier: 'lib@hetzner', retriever: modernSCM([
    $class: 'GitSCMSource',
    remote: 'https://github.com/Percona-Lab/jenkins-pipelines.git'
]) _

pipeline {
    agent {
        label params.CLOUD == 'Hetzner' ? 'launcher-x64' : 'micro-amazon'
    }
    parameters {
        choice(
            choices: ['Hetzner','AWS'],
            description: 'Cloud infra for build',
            name: 'CLOUD')
        string(
            defaultValue: 'https://github.com/percona/percona-clustersync-mongodb.git',
            description: 'URL for percona-clustersync-mongodb repository',
            name: 'GIT_REPO')
    }
    options {
        skipDefaultCheckout()
        disableConcurrentBuilds()
        buildDiscarder(logRotator(numToKeepStr: '10', artifactNumToKeepStr: '10'))
        timestamps ()
    }
    stages {
        stage('Get release branches') {
            steps {
                script {
                    String S3_STASH = (params.CLOUD == 'Hetzner') ? 'HTZ_STASH' : 'AWS_STASH'
                    String S3_ENDPOINT = (params.CLOUD == 'Hetzner') ? '--endpoint-url https://fsn1.your-objectstorage.com' : '--endpoint-url https://s3.amazonaws.com'
                    withCredentials([[$class: 'AmazonWebServicesCredentialsBinding', accessKeyVariable: 'AWS_ACCESS_KEY_ID', credentialsId: S3_STASH, secretKeyVariable: 'AWS_SECRET_ACCESS_KEY']]) {
                        sh """
                            EC=0
                            AWS_RETRY_MODE=standard AWS_MAX_ATTEMPTS=10 aws s3 ls s3://percona-jenkins-artifactory/percona-clustersync-mongodb/branch_commit_id.properties ${S3_ENDPOINT} --cli-connect-timeout 60 --cli-read-timeout 120 || EC=\$?

                            if [ \${EC} = 1 ]; then
                                LATEST_RELEASE_BRANCH=\$(git -c 'versionsort.suffix=-' ls-remote --heads --sort='v:refname' ${GIT_REPO} release\\* | tail -1)
                                BRANCH_NAME=\$(echo \${LATEST_RELEASE_BRANCH} | cut -d "/" -f 3)
                                COMMIT_ID=\$(echo \${LATEST_RELEASE_BRANCH} | cut -d " " -f 1)

                                echo "BRANCH_NAME=\${BRANCH_NAME}" > branch_commit_id.properties
                                echo "COMMIT_ID=\${COMMIT_ID}" >> branch_commit_id.properties

                                AWS_RETRY_MODE=standard AWS_MAX_ATTEMPTS=10 aws s3 cp branch_commit_id.properties s3://percona-jenkins-artifactory/percona-clustersync-mongodb/ ${S3_ENDPOINT} --cli-connect-timeout 60 --cli-read-timeout 120
                                echo "START_NEW_BUILD=NO" > startBuild
                            else
                                AWS_RETRY_MODE=standard AWS_MAX_ATTEMPTS=10 aws s3 cp s3://percona-jenkins-artifactory/percona-clustersync-mongodb/branch_commit_id.properties . ${S3_ENDPOINT} --cli-connect-timeout 60 --cli-read-timeout 120
                                source branch_commit_id.properties

                                LATEST_RELEASE_BRANCH=\$(git -c 'versionsort.suffix=-' ls-remote --heads --sort='v:refname' ${GIT_REPO} release\\* | tail -1)
                                LATEST_BRANCH_NAME=\$(echo \${LATEST_RELEASE_BRANCH} | cut -d "/" -f 3)
                                LATEST_COMMIT_ID=\$(echo \${LATEST_RELEASE_BRANCH} | cut -d " " -f 1)

                                if [ "x\${COMMIT_ID}" != "x\${LATEST_COMMIT_ID}" ] || [ "x\${BRANCH_NAME}" != "x\${LATEST_BRANCH_NAME}" ]; then
                                    echo "START_NEW_BUILD=YES" > startBuild
                                else
                                    echo "START_NEW_BUILD=NO" > startBuild
                                fi

                                echo "BRANCH_NAME=\${LATEST_BRANCH_NAME}" > branch_commit_id.properties
                                echo "COMMIT_ID=\${LATEST_COMMIT_ID}" >> branch_commit_id.properties
                                AWS_RETRY_MODE=standard AWS_MAX_ATTEMPTS=10 aws s3 cp branch_commit_id.properties s3://percona-jenkins-artifactory/percona-clustersync-mongodb/ ${S3_ENDPOINT} --cli-connect-timeout 60 --cli-read-timeout 120
                            fi
                        """
                    }
                    START_NEW_BUILD = sh(returnStdout: true, script: "source startBuild; echo \${START_NEW_BUILD}").trim()
                    BRANCH_NAME = sh(returnStdout: true, script: "source branch_commit_id.properties; echo \${BRANCH_NAME}").trim()
                    COMMIT_ID = sh(returnStdout: true, script: "source branch_commit_id.properties; echo \${COMMIT_ID}").trim()
                    VERSION = sh(returnStdout: true, script: "source branch_commit_id.properties; echo \${BRANCH_NAME} | sed s:release\\-::").trim()
                }
            }
        }
        stage('Build needed') {
            when {
                expression { START_NEW_BUILD == 'YES' }
            }
            steps {
                withCredentials([[$class: 'AmazonWebServicesCredentialsBinding', accessKeyVariable: 'AWS_ACCESS_KEY_ID', credentialsId: 'AWS_STASH', secretKeyVariable: 'AWS_SECRET_ACCESS_KEY']]) {
                    sh """
                        echo ${START_NEW_BUILD}: build required
                    """
                }
                slackNotify("#releases-ci", "#00FF00", "[${JOB_NAME}]: new changes for branch ${BRANCH_NAME}[commit id: ${COMMIT_ID}] were detected, build will be started soon")
                build job: 'hetzner-pcsm-autobuild-RELEASE', parameters: [string(name: 'CLOUD', value: CLOUD), string(name: 'GIT_BRANCH', value: BRANCH_NAME), string(name: 'VERSION', value: VERSION), string(name: 'COMPONENT', value: 'testing')]
            }
        }
        stage('Build skipped') {
            when {
                expression { START_NEW_BUILD == 'NO' }
            }
            steps {
                withCredentials([[$class: 'AmazonWebServicesCredentialsBinding', accessKeyVariable: 'AWS_ACCESS_KEY_ID', credentialsId: 'AWS_STASH', secretKeyVariable: 'AWS_SECRET_ACCESS_KEY']]) {
                    sh """
                        echo ${START_NEW_BUILD} build required
                    """
                }
            }
        }
    }
    post {
        always {
            sh '''
                sudo rm -rf ./*
            '''
            deleteDir()
        }
    }
}
