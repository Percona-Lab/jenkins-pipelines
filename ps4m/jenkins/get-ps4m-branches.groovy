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
            defaultValue: 'https://github.com/percona/percona-mongot.git',
            description: 'URL for Percona Search for MongoDB (mongot) repository',
            name: 'GIT_REPO')
        string(
            defaultValue: 'release-1.70.1-1',
            description: 'Glob for release branches to watch. The latest one (by version sort) is built.',
            name: 'RELEASE_BRANCH_PATTERN')
        string(
            defaultValue: '1.70.1',
            description: 'mongot release version passed to the build job (mongot does not encode the version in the branch name).',
            name: 'VERSION')
        string(
            defaultValue: '1',
            description: 'Package release/revision number (same for rpm and deb)',
            name: 'PS4M_RELEASE')
        string(
            defaultValue: 'ps4m',
            description: 'Target repo name for the build job (mongot is shipped under the ps4m repo)',
            name: 'MONGOT_REPO')
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
                        # `aws s3 ls <key>` is a prefix match; the marker name is unique so no
                        # stray object shares this prefix. Exit code: 0=present, 1=absent.
                        AWS_RETRY_MODE=standard AWS_MAX_ATTEMPTS=10 aws s3 ls s3://percona-jenkins-artifactory/percona-search-mongodb/branch_commit_id_ps4m.properties ${S3_ENDPOINT} --cli-connect-timeout 60 --cli-read-timeout 120 || EC=\$?

                        if [ "\${EC}" != "0" ] && [ "\${EC}" != "1" ]; then
                          echo "ERROR: 'aws s3 ls' for the marker failed with exit code \${EC}; aborting to avoid a wrong build decision."
                          exit \${EC}
                        fi

                        # Capture git's own exit status separately: a `| tail` pipe would
                        # mask a git/network failure and the empty result would be misread
                        # as "no branches" -> silent skip of a real build.
                        LATEST_RELEASE_BRANCH=\$(git -c 'versionsort.suffix=-' ls-remote --heads --sort='v:refname' ${GIT_REPO} '${RELEASE_BRANCH_PATTERN}') || {
                          echo "ERROR: 'git ls-remote' against ${GIT_REPO} failed; aborting to avoid a wrong build decision."
                          exit 1
                        }
                        LATEST_RELEASE_BRANCH=\$(echo "\${LATEST_RELEASE_BRANCH}" | tail -1)
                        LATEST_BRANCH_NAME=\$(echo \${LATEST_RELEASE_BRANCH} | cut -d "/" -f 3)
                        LATEST_COMMIT_ID=\$(echo \${LATEST_RELEASE_BRANCH} | cut -d " " -f 1)

                        if [ -z "\${LATEST_BRANCH_NAME}" ]; then
                          echo "No branches matching ${RELEASE_BRANCH_PATTERN} found in ${GIT_REPO} - nothing to build yet."
                          echo "START_NEW_BUILD=NO" > startBuild
                          echo "BRANCH_NAME=" > branch_commit_id_ps4m.properties
                          echo "COMMIT_ID=" >> branch_commit_id_ps4m.properties
                        elif [ "\${EC}" = "1" ]; then
                          # First watcher run for a never-built product: no marker in S3 yet.
                          # Build the latest release branch; the S3 marker is written only
                          # after the downstream build succeeds (see 'Build needed' stage).
                          echo "BRANCH_NAME=\${LATEST_BRANCH_NAME}" > branch_commit_id_ps4m.properties
                          echo "COMMIT_ID=\${LATEST_COMMIT_ID}" >> branch_commit_id_ps4m.properties
                          echo "START_NEW_BUILD=YES" > startBuild
                        else
                          AWS_RETRY_MODE=standard AWS_MAX_ATTEMPTS=10 aws s3 cp s3://percona-jenkins-artifactory/percona-search-mongodb/branch_commit_id_ps4m.properties . ${S3_ENDPOINT} --cli-connect-timeout 60 --cli-read-timeout 120
                          source branch_commit_id_ps4m.properties

                          if [ "x\${COMMIT_ID}" != "x\${LATEST_COMMIT_ID}" ] || [ "x\${BRANCH_NAME}" != "x\${LATEST_BRANCH_NAME}" ]; then
                            echo "START_NEW_BUILD=YES" > startBuild
                          else
                            echo "START_NEW_BUILD=NO" > startBuild
                          fi

                          # Persist LATEST values locally for the downstream build params;
                          # the S3 marker is refreshed only after a successful build.
                          echo "BRANCH_NAME=\${LATEST_BRANCH_NAME}" > branch_commit_id_ps4m.properties
                          echo "COMMIT_ID=\${LATEST_COMMIT_ID}" >> branch_commit_id_ps4m.properties
                        fi
                    """
                }
                    START_NEW_BUILD = sh(returnStdout: true, script: "source startBuild; echo \${START_NEW_BUILD}").trim()
                    BRANCH_NAME = sh(returnStdout: true, script: "source branch_commit_id_ps4m.properties; echo \${BRANCH_NAME}").trim()
                    COMMIT_ID = sh(returnStdout: true, script: "source branch_commit_id_ps4m.properties; echo \${COMMIT_ID}").trim()
                }
            }
        }
        stage('Build needed') {
            when {
                expression { START_NEW_BUILD == 'YES' }
            }
            steps {
                slackNotify("#releases-ci", "#00FF00", "[${JOB_NAME}]: new changes for branch ${BRANCH_NAME}[commit id: ${COMMIT_ID}] were detected, build will be started soon")
                build job: 'hetzner-ps4m-autobuild-RELEASE', parameters: [
                    string(name: 'CLOUD',       value: params.CLOUD),
                    string(name: 'GIT_REPO',    value: params.GIT_REPO),
                    string(name: 'GIT_BRANCH',  value: BRANCH_NAME),
                    string(name: 'VERSION',      value: params.VERSION),
                    string(name: 'PS4M_RELEASE', value: params.PS4M_RELEASE),
                    string(name: 'MONGOT_REPO',  value: params.MONGOT_REPO),
                    string(name: 'COMPONENT',   value: 'testing')
                ]
                // build job propagates downstream failure, so we only reach here on
                // success: record the built branch/commit in S3 as the new baseline.
                // If the build had failed the marker stays unchanged and the next
                // watcher run retries the same commit.
                script {
                    String S3_STASH = (params.CLOUD == 'Hetzner') ? 'HTZ_STASH' : 'AWS_STASH'
                    String S3_ENDPOINT = (params.CLOUD == 'Hetzner') ? '--endpoint-url https://fsn1.your-objectstorage.com' : '--endpoint-url https://s3.amazonaws.com'
                    withCredentials([[$class: 'AmazonWebServicesCredentialsBinding', accessKeyVariable: 'AWS_ACCESS_KEY_ID', credentialsId: S3_STASH, secretKeyVariable: 'AWS_SECRET_ACCESS_KEY']]) {
                        sh """
                            echo "BRANCH_NAME=${BRANCH_NAME}" > branch_commit_id_ps4m.properties
                            echo "COMMIT_ID=${COMMIT_ID}" >> branch_commit_id_ps4m.properties
                            AWS_RETRY_MODE=standard AWS_MAX_ATTEMPTS=10 aws s3 cp branch_commit_id_ps4m.properties s3://percona-jenkins-artifactory/percona-search-mongodb/ ${S3_ENDPOINT} --cli-connect-timeout 60 --cli-read-timeout 120
                        """
                    }
                }
            }
        }
        stage('Build skipped') {
            when {
                expression { START_NEW_BUILD == 'NO' }
            }
            steps {
                sh """
                    echo "No new build required (START_NEW_BUILD=${START_NEW_BUILD})"
                """
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
