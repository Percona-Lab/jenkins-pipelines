library changelog: false, identifier: 'lib@hetzner', retriever: modernSCM([
    $class: 'GitSCMSource',
    remote: 'https://github.com/Percona-Lab/jenkins-pipelines.git'
]) _

pipeline {
    agent {
        label params.CLOUD == 'AWS' ? 'micro-amazon' : 'launcher-x64'
    }
    parameters {
        choice(
            choices: ['Hetzner','AWS'],
            description: 'Cloud infra for build',
            name: 'CLOUD')
        string(
            defaultValue: 'https://github.com/percona/percona-server-mongodb.git',
            description: 'URL for percona-server-for-mongodb repository',
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
                String S3_STASH = (params.CLOUD == 'AWS') ? 'AWS_STASH' : 'HTZ_STASH'
                String S3_ENDPOINT = (params.CLOUD == 'AWS') ? '--endpoint-url https://s3.amazonaws.com' : '--endpoint-url https://fsn1.your-objectstorage.com'
                withCredentials([[$class: 'AmazonWebServicesCredentialsBinding', accessKeyVariable: 'AWS_ACCESS_KEY_ID', credentialsId: S3_STASH, secretKeyVariable: 'AWS_SECRET_ACCESS_KEY']]) {
                    sh """
                        set -euo pipefail

                        # PSMDB-2055: detect latest release-8.3.x branch first; abort if none
                        # found (prevents the previous bug where an empty BRANCH_NAME produced
                        # a curl against \${LINK}//MONGO_TOOLS_TAG_VERSION and stored a 307
                        # redirect HTML body into the properties file).
                        LATEST_RELEASE_BRANCH=\$(git -c 'versionsort.suffix=-' ls-remote --heads --sort='v:refname' ${GIT_REPO} release-8.3\\* | tail -1)
                        if [ -z "\${LATEST_RELEASE_BRANCH}" ]; then
                            echo "WARN: no release-8.3.* branch found on ${GIT_REPO}; skipping build trigger"
                            echo "START_NEW_BUILD=NO" > startBuild
                            # Touch an empty .properties so downstream sh(returnStdout) calls
                            # in the groovy block don't fail with "No such file" — they'll
                            # just return empty strings, which is fine because the "Build needed"
                            # stage is gated on START_NEW_BUILD=YES.
                            : > branch_commit_id_83.properties
                            exit 0
                        fi
                        LATEST_BRANCH_NAME=\$(echo \${LATEST_RELEASE_BRANCH} | cut -d "/" -f 3)
                        LATEST_COMMIT_ID=\$(echo \${LATEST_RELEASE_BRANCH} | cut -d " " -f 1)
                        MONGO_TOOLS_TAG_LINK=\$(echo ${GIT_REPO} | sed -re 's|github.com|raw.githubusercontent.com|; s|\\.git\$||')
                        # -fsSL: fail on HTTP errors, silent, show errors, follow redirects.
                        # Bare `curl` previously stored 307 HTML bodies into the properties file.
                        MONGO_TOOLS_TAG=\$(curl -fsSL \${MONGO_TOOLS_TAG_LINK}/\${LATEST_BRANCH_NAME}/MONGO_TOOLS_TAG_VERSION)

                        # PSMDB-2055: defense-in-depth via branch_commit_id_83.last_successful.
                        # Written by hetzner-psmdb83-autobuild-RELEASE's post.success block on
                        # every green run. If detected == last_successful, we KNOW we already
                        # built that branch/commit and SKIP regardless of branch_commit_id_83.properties
                        # state. This makes a corrupt or manually nulled .properties file (e.g. operator
                        # recovery scenario) harmless: no spurious rebuilds.
                        LAST_SUCCESSFUL_BRANCH=""
                        LAST_SUCCESSFUL_COMMIT_ID=""
                        LS_EC=0
                        AWS_RETRY_MODE=standard AWS_MAX_ATTEMPTS=10 aws s3 cp s3://percona-jenkins-artifactory/percona-server-mongodb/branch_commit_id_83.last_successful . ${S3_ENDPOINT} --cli-connect-timeout 60 --cli-read-timeout 120 || LS_EC=\$?
                        if [ \${LS_EC} = 0 ] && [ -s branch_commit_id_83.last_successful ]; then
                            LAST_SUCCESSFUL_BRANCH=\$(awk -F= '\$1=="BRANCH_NAME"{print \$2}' branch_commit_id_83.last_successful)
                            LAST_SUCCESSFUL_COMMIT_ID=\$(awk -F= '\$1=="COMMIT_ID"{print \$2}' branch_commit_id_83.last_successful)
                        fi
                        if [ -n "\${LAST_SUCCESSFUL_BRANCH}" ] \\
                           && [ "\${LAST_SUCCESSFUL_BRANCH}" = "\${LATEST_BRANCH_NAME}" ] \\
                           && [ "\${LAST_SUCCESSFUL_COMMIT_ID}" = "\${LATEST_COMMIT_ID}" ]; then
                            echo "INFO: last_successful matches detected (\${LATEST_BRANCH_NAME}@\${LATEST_COMMIT_ID}); skipping"
                            echo "START_NEW_BUILD=NO" > startBuild
                        else
                            # No last_successful baseline yet, or detected differs.
                            # Fall through to legacy .properties compare for backward compat —
                            # gives operators a single-source-of-truth view via the cached
                            # detected state even before the first green build.
                            EC=0
                            AWS_RETRY_MODE=standard AWS_MAX_ATTEMPTS=10 aws s3 ls s3://percona-jenkins-artifactory/percona-server-mongodb/branch_commit_id_83.properties ${S3_ENDPOINT} --cli-connect-timeout 60 --cli-read-timeout 120 || EC=\$?
                            if [ \${EC} = 1 ]; then
                                # First-time bootstrap: write fresh detected state, skip build.
                                echo "START_NEW_BUILD=NO" > startBuild
                            else
                                AWS_RETRY_MODE=standard AWS_MAX_ATTEMPTS=10 aws s3 cp s3://percona-jenkins-artifactory/percona-server-mongodb/branch_commit_id_83.properties . ${S3_ENDPOINT} --cli-connect-timeout 60 --cli-read-timeout 120
                                CACHED_BRANCH=\$(awk -F= '\$1=="BRANCH_NAME"{print \$2}' branch_commit_id_83.properties)
                                CACHED_COMMIT=\$(awk -F= '\$1=="COMMIT_ID"{print \$2}' branch_commit_id_83.properties)
                                if [ "\${CACHED_COMMIT}" != "\${LATEST_COMMIT_ID}" ] || [ "\${CACHED_BRANCH}" != "\${LATEST_BRANCH_NAME}" ]; then
                                    echo "START_NEW_BUILD=YES" > startBuild
                                else
                                    echo "START_NEW_BUILD=NO" > startBuild
                                fi
                            fi
                        fi

                        # Always refresh the detected-state file for observability.
                        echo "BRANCH_NAME=\${LATEST_BRANCH_NAME}" > branch_commit_id_83.properties
                        echo "COMMIT_ID=\${LATEST_COMMIT_ID}" >> branch_commit_id_83.properties
                        echo "MONGO_TOOLS_TAG=\${MONGO_TOOLS_TAG}" >> branch_commit_id_83.properties
                        AWS_RETRY_MODE=standard AWS_MAX_ATTEMPTS=10 aws s3 cp branch_commit_id_83.properties s3://percona-jenkins-artifactory/percona-server-mongodb/ ${S3_ENDPOINT} --cli-connect-timeout 60 --cli-read-timeout 120
                    """
                }
                    START_NEW_BUILD = sh(returnStdout: true, script: "source startBuild; echo \${START_NEW_BUILD}").trim()
                    BRANCH_NAME = sh(returnStdout: true, script: "source branch_commit_id_83.properties; echo \${BRANCH_NAME}").trim()
                    COMMIT_ID = sh(returnStdout: true, script: "source branch_commit_id_83.properties; echo \${COMMIT_ID}").trim()
                    VERSION = sh(returnStdout: true, script: "source branch_commit_id_83.properties; echo \${BRANCH_NAME} | cut -d - -f 2 ").trim()
                    RELEASE = sh(returnStdout: true, script: "source branch_commit_id_83.properties; echo \${BRANCH_NAME} | cut -d - -f 3 ").trim()
                    MONGO_TOOLS_TAG = sh(returnStdout: true, script: "source branch_commit_id_83.properties; echo \${MONGO_TOOLS_TAG}").trim()
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
                build job: 'hetzner-psmdb83-autobuild-RELEASE', parameters: [string(name: 'CLOUD', value: CLOUD), string(name: 'GIT_BRANCH', value: BRANCH_NAME), string(name: 'PSMDB_VERSION', value: VERSION), string(name: 'PSMDB_RELEASE', value: RELEASE), string(name: 'MONGO_TOOLS_TAG', value: MONGO_TOOLS_TAG), string(name: 'COMPONENT', value: 'testing')]
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
