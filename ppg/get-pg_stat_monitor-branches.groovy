library changelog: false, identifier: 'lib@master', retriever: modernSCM([
    $class: 'GitSCMSource',
    remote: 'https://github.com/adivinho/jenkins-pipelines.git'
]) _

pipeline {
    agent {
        label 'docker'
    }
    parameters {
        string(
            defaultValue: 'https://github.com/percona/pg_stat_monitor.git',
            description: 'URL for pg_stat_monitor repository',
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
                withCredentials([[$class: 'AmazonWebServicesCredentialsBinding', accessKeyVariable: 'AWS_ACCESS_KEY_ID', credentialsId: 'AWS_STASH', secretKeyVariable: 'AWS_SECRET_ACCESS_KEY']]) {
                    sh """
                        EC=0
                        aws s3 ls s3://percona-jenkins-artifactory/pg_stat_monitor/branch_commit_id.properties || EC=\$?

                        if [ \${EC} = 1 ]; then
                            LATEST_RELEASE_BRANCH=\$(git -c 'versionsort.suffix=-' ls-remote --heads --sort='v:refname' ${GIT_REPO} main | tail -1)
                            BRANCH_NAME=\$(echo \${LATEST_RELEASE_BRANCH} | cut -d "/" -f 3)
                            COMMIT_ID=\$(echo \${LATEST_RELEASE_BRANCH} | cut -d " " -f 1)
                            GIT_SHORT_COMMIT_ID=\$(echo \${COMMIT_ID} | cut -c 1-7)

                            echo "BRANCH_NAME=\${BRANCH_NAME}" > branch_commit_id.properties
                            echo "COMMIT_ID=\${COMMIT_ID}" >> branch_commit_id.properties
                            echo "GIT_SHORT_COMMIT_ID=\${GIT_SHORT_COMMIT_ID}" >> branch_commit_id.properties

                            aws s3 cp branch_commit_id.properties s3://percona-jenkins-artifactory/pg_stat_monitor/
                            echo "START_NEW_BUILD=NO" > startBuild
                        else
                            aws s3 cp s3://percona-jenkins-artifactory/pg_stat_monitor/branch_commit_id.properties .
                            source ./branch_commit_id.properties
                            cat ./branch_commit_id.properties

                            LATEST_RELEASE_BRANCH=\$(git -c 'versionsort.suffix=-' ls-remote --heads --sort='v:refname' ${GIT_REPO} main | tail -1)
                            LATEST_BRANCH_NAME=\$(echo \${LATEST_RELEASE_BRANCH} | cut -d "/" -f 3)
                            LATEST_COMMIT_ID=\$(echo \${LATEST_RELEASE_BRANCH} | cut -d " " -f 1)
                            LATEST_GIT_SHORT_COMMIT_ID=\$(echo \${LATEST_COMMIT_ID} | cut -c 1-7)
                            VERSION=\$(echo \${LATEST_RELEASE_BRANCH} | cut -d - -f 2-3)
                            LATEST_TAG_VERSION=\$(git ls-remote --tags ${GIT_REPO} ?.?.?-* | cut -d / -f 3 | tail -1 | cut -d - -f 1)

                            VERSION=\$(echo \${LATEST_TAG_VERSION})

                            MAX_RPM_DEB_RELEASE=\$(for i in 11 12 13 14; do curl -sL https://repo.percona.com/ppg-$i/yum/testing/8/RPMS/x86_64/; curl -s curl -sL https://repo.percona.com/ppg-\$i/apt/pool/testing/p/percona-pg-stat-monitor/; done | egrep 'percona-pg-stat-monitor[0-9]|percona-pg_stat_monitor[0-9]' | awk -Fhref=\"percona- '{print \$2}' | awk '{print \$1}' | egrep -o '[0-9]*[0-9].[0-9]*[0-9].[0-9]*[0-9]-[0-9]+' | sort -r -u | awk -F- '{print \$2}' | head -1)

                            if [ "x\${COMMIT_ID}" != "x\${LATEST_COMMIT_ID}" ] || [ "x\${BRANCH_NAME}" != "x\${LATEST_BRANCH_NAME}" ]; then
                                echo "START_NEW_BUILD=YES" > startBuild
                            else
                                echo "START_NEW_BUILD=NO" > startBuild
                            fi

                            echo "BRANCH_NAME=\${LATEST_BRANCH_NAME}" > branch_commit_id.properties
                            echo "COMMIT_ID=\${LATEST_COMMIT_ID}" >> branch_commit_id.properties
                            echo "VERSION=\${VERSION}" >> branch_commit_id.properties
                            echo "MAX_RPM_DEB_RELEASE=\${MAX_RPM_DEB_RELEASE}" >> branch_commit_id.properties
                            aws s3 cp branch_commit_id.properties s3://percona-jenkins-artifactory/pg_stat_monitor/
                        fi
                    """
                }
                script {
                    START_NEW_BUILD = sh(returnStdout: true, script: "source ./startBuild; echo \${START_NEW_BUILD}").trim()
                    BRANCH_NAME = sh(returnStdout: true, script: "source ./branch_commit_id.properties; echo \${BRANCH_NAME}").trim()
                    COMMIT_ID = sh(returnStdout: true, script: "source ./branch_commit_id.properties; echo \${COMMIT_ID}").trim()
                    VERSION = sh(returnStdout: true, script: "source ./branch_commit_id.properties; echo \${VERSION}").trim()
                    MAX_RPM_DEB_RELEASE = sh(returnStdout: true, script: "source ./branch_commit_id.properties; echo \${MAX_RPM_DEB_RELEASE}").trim()
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
                        echo ${START_NEW_BUILD}: build is required
                    """
                }
                slackNotify("#releases-ci", "#00FF00", "[${JOB_NAME}]: new changes for branch ${BRANCH_NAME}[commit id: ${COMMIT_ID}] were detected, build will be started soon")
                build job: 'pg_stat_monitor-autobuild-RELEASE-test', parameters: [string(name: 'BRANCH', value: BRANCH_NAME), string(name: 'RPM_RELEASE', value: MAX_RPM_DEB_RELEASE), string(name: 'DEB_RELEASE', value: MAX_RPM_DEB_RELEASE), string(name: 'VERSION', value: VERSION), string(name: 'PG_RELEASE', value: '11'), string(name: 'COMPONENT', value: 'testing')]
                build job: 'pg_stat_monitor-autobuild-RELEASE-test', parameters: [string(name: 'BRANCH', value: BRANCH_NAME), string(name: 'RPM_RELEASE', value: MAX_RPM_DEB_RELEASE), string(name: 'DEB_RELEASE', value: MAX_RPM_DEB_RELEASE), string(name: 'VERSION', value: VERSION), string(name: 'PG_RELEASE', value: '12'), string(name: 'COMPONENT', value: 'testing')]
                build job: 'pg_stat_monitor-autobuild-RELEASE-test', parameters: [string(name: 'BRANCH', value: BRANCH_NAME), string(name: 'RPM_RELEASE', value: MAX_RPM_DEB_RELEASE), string(name: 'DEB_RELEASE', value: MAX_RPM_DEB_RELEASE), string(name: 'VERSION', value: VERSION), string(name: 'PG_RELEASE', value: '13'), string(name: 'COMPONENT', value: 'testing')]
                build job: 'pg_stat_monitor-autobuild-RELEASE-test', parameters: [string(name: 'BRANCH', value: BRANCH_NAME), string(name: 'RPM_RELEASE', value: MAX_RPM_DEB_RELEASE), string(name: 'DEB_RELEASE', value: MAX_RPM_DEB_RELEASE), string(name: 'VERSION', value: VERSION), string(name: 'PG_RELEASE', value: '14'), string(name: 'COMPONENT', value: 'testing')]
            }
        }
        stage('Build skipped') {
            when {
                expression { START_NEW_BUILD == 'NO' }
            }
            steps {
                withCredentials([[$class: 'AmazonWebServicesCredentialsBinding', accessKeyVariable: 'AWS_ACCESS_KEY_ID', credentialsId: 'AWS_STASH', secretKeyVariable: 'AWS_SECRET_ACCESS_KEY']]) {
                    sh """
                        echo ${START_NEW_BUILD}: build is NOT required
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
