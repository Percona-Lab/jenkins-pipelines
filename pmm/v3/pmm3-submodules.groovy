import groovy.json.JsonOutput

library changelog: false, identifier: 'lib@master', retriever: modernSCM([
    $class: 'GitSCMSource',
    remote: 'https://github.com/Percona-Lab/jenkins-pipelines.git'
]) _

void addComment(String COMMENT) {
    withCredentials([string(credentialsId: 'GITHUB_API_TOKEN', variable: 'GITHUB_API_TOKEN')]) {
        payload = [
            body: "${COMMENT}",
        ]
        writeFile(file: 'body.json', text: JsonOutput.toJson(payload))

        sh '''
            REPO=$(echo $CHANGE_URL | cut -d '/' -f 4-5)
            curl -X POST \
                -H "Authorization: token ${GITHUB_API_TOKEN}" \
                -d @body.json \
                "https://api.github.com/repos/${REPO}/issues/${CHANGE_ID}/comments"
        '''
    }
}

pipeline {
    agent {
        label 'agent-amd64-ol9'
    }
    parameters {
        string(
            defaultValue: 'v3',
            description: 'Tag/Branch for pmm-submodules repository',
            name: 'PMM_BRANCH')
        string(
            defaultValue: '',
            description: 'URL for pmm-submodules repository PR',
            name: 'CHANGE_URL')
        string(
            defaultValue: '',
            description: 'ID for pmm-submodules repository PR',
            name: 'CHANGE_ID')
        string(
            // Starts with 'PR-', e.g., PR-2345
            defaultValue: '',
            description: 'Change Request Number for pmm-submodules repository PR',
            name: 'BRANCH_NAME')
        booleanParam(
            defaultValue: false,
            description: 'Build GSSAPI dynamic client tarballs for OL8 and OL9 (amd64)',
            name: 'GSSAPI_DYNAMIC_TARBALLS')
    }
    environment {
        PATH_TO_SCRIPTS = 'sources/pmm/src/github.com/percona/pmm/build/scripts'
    }
    stages {
        stage('Prepare') {
            steps {
                git poll: false,
                    branch: PMM_BRANCH,
                    url: 'http://github.com/Percona-Lab/pmm-submodules'

                withCredentials([string(credentialsId: 'GITHUB_API_TOKEN', variable: 'GITHUB_API_TOKEN')]) {
                sh '''
                    set -o errexit
                    git submodule update --init --jobs 10
                    git submodule status

                    ${PATH_TO_SCRIPTS}/build-submodules
                '''
                }
                script {
                    env.PMM_VERSION = sh(returnStdout: true, script: "cat VERSION").trim()
                    env.FB_COMMIT = sh(returnStdout: true, script: "cat fbCommitSha").trim()
                    env.SHORTENED_COMMIT = env.FB_COMMIT.substring(0, 7)
                }
                stash includes: 'apiBranch', name: 'apiBranch'
                stash includes: 'apiURL', name: 'apiURL'
                stash includes: 'pmmQABranch', name: 'pmmQABranch'
                stash includes: 'apiCommitSha', name: 'apiCommitSha'
                stash includes: 'pmmQACommitSha', name: 'pmmQACommitSha'
                stash includes: 'pmmUITestBranch', name: 'pmmUITestBranch'
                stash includes: 'pmmUITestsCommitSha', name: 'pmmUITestsCommitSha'
                stash includes: 'fbCommitSha', name: 'fbCommitSha'
                slackSend channel: '#pmm-notifications', color: '#0000FF', message: "[${JOB_NAME}]: v3 build started, URL: ${BUILD_URL}"
            }
        }
        stage('Build client source') {
            steps {
                withCredentials([[$class: 'AmazonWebServicesCredentialsBinding', accessKeyVariable: 'AWS_ACCESS_KEY_ID', credentialsId: 'AMI/OVF', secretKeyVariable: 'AWS_SECRET_ACCESS_KEY']]) {
                    sh '''
                        set -o errexit

                        aws ecr-public get-login-password --region us-east-1 | docker login -u AWS --password-stdin public.ecr.aws/e7j3v3n0

                        ${PATH_TO_SCRIPTS}/build-client-source
                    '''
                }
            }
        }
        stage('Build client binary') {
            steps {
                withCredentials([[$class: 'AmazonWebServicesCredentialsBinding', accessKeyVariable: 'AWS_ACCESS_KEY_ID', credentialsId: 'AMI/OVF', secretKeyVariable: 'AWS_SECRET_ACCESS_KEY']]) {
                    sh '''
                        set -o errexit

                        aws ecr-public get-login-password --region us-east-1 | docker login -u AWS --password-stdin public.ecr.aws/e7j3v3n0

                        ${PATH_TO_SCRIPTS}/build-client-binary
                        aws s3 cp \
                            --acl public-read \
                            results/tarball/pmm-client-*.tar.gz \
                            s3://pmm-build-cache/PR-BUILDS/pmm-client/pmm-client-${BRANCH_NAME}-${SHORTENED_COMMIT}.tar.gz
                    '''
                }
                script {
                    sh (script: 'echo "https://s3.us-east-2.amazonaws.com/pmm-build-cache/PR-BUILDS/pmm-client/pmm-client-${BRANCH_NAME}-${SHORTENED_COMMIT}.tar.gz" | tee CLIENT_URL')
                    env.CLIENT_URL = sh (script: "cat CLIENT_URL", returnStdout: true).trim()
                }
            }
        }
        stage('Build dynamic client binary for OL8 (GSSAPI)') {
            when { expression { return params.GSSAPI_DYNAMIC_TARBALLS } }
            steps {
                withCredentials([aws(credentialsId: 'AMI/OVF')]) {
                    sh '''
                        set -o errexit

                        aws ecr-public get-login-password --region us-east-1 | docker login -u AWS --password-stdin public.ecr.aws/e7j3v3n0

                        export RPMBUILD_DOCKER_IMAGE=public.ecr.aws/e7j3v3n0/rpmbuild:3-ol8
                        export BUILD_TYPE=dynamic
                        export OS_VARIANT=ol8

                        ${PATH_TO_SCRIPTS}/build-client-binary

                        aws s3 cp \
                            --acl public-read \
                            results/tarball/pmm-client-*-dynamic-ol8.tar.gz \
                            s3://pmm-build-cache/PR-BUILDS/pmm-client/pmm-client-dynamic-ol8-${BRANCH_NAME}-${SHORTENED_COMMIT}.tar.gz
                    '''
                }
                script {
                    env.CLIENT_URL_DYNAMIC_OL8 = sh (script: 'echo "https://s3.us-east-2.amazonaws.com/pmm-build-cache/PR-BUILDS/pmm-client/pmm-client-dynamic-ol8-${BRANCH_NAME}-${SHORTENED_COMMIT}.tar.gz" | tee CLIENT_URL_DYNAMIC_OL8', returnStdout: true).trim()
                }
            }
        }
        stage('Build dynamic client binary for OL9 (GSSAPI)') {
            when { expression { return params.GSSAPI_DYNAMIC_TARBALLS } }
            steps {
                withCredentials([aws(credentialsId: 'AMI/OVF')]) {
                    sh '''
                        set -o errexit

                        aws ecr-public get-login-password --region us-east-1 | docker login -u AWS --password-stdin public.ecr.aws/e7j3v3n0

                        export RPMBUILD_DOCKER_IMAGE=public.ecr.aws/e7j3v3n0/rpmbuild:3
                        export BUILD_TYPE=dynamic
                        export OS_VARIANT=ol9

                        ${PATH_TO_SCRIPTS}/build-client-binary

                        aws s3 cp \
                            --acl public-read \
                            results/tarball/pmm-client-*-dynamic-ol9.tar.gz \
                            s3://pmm-build-cache/PR-BUILDS/pmm-client/pmm-client-dynamic-ol9-${BRANCH_NAME}-${SHORTENED_COMMIT}.tar.gz
                    '''
                }
                script {
                    env.CLIENT_URL_DYNAMIC_OL9 = sh (script: 'echo "https://s3.us-east-2.amazonaws.com/pmm-build-cache/PR-BUILDS/pmm-client/pmm-client-dynamic-ol9-${BRANCH_NAME}-${SHORTENED_COMMIT}.tar.gz" | tee CLIENT_URL_DYNAMIC_OL9', returnStdout: true).trim()
                }
            }
        }
        stage('Build client docker') {
            steps {
                withCredentials([usernamePassword(credentialsId: 'hub.docker.com', passwordVariable: 'PASS', usernameVariable: 'USER')]) {
                    sh '''
                        docker login -u "${USER}" -p "${PASS}"
                    '''
                }
                withCredentials([[$class: 'AmazonWebServicesCredentialsBinding', accessKeyVariable: 'AWS_ACCESS_KEY_ID', credentialsId: 'AMI/OVF', secretKeyVariable: 'AWS_SECRET_ACCESS_KEY']]) {
                    sh '''
                        set -o errexit
                        export PUSH_DOCKER=1
                        export DOCKER_CLIENT_TAG=perconalab/pmm-client-fb:${BRANCH_NAME}-${SHORTENED_COMMIT}
                        ${PATH_TO_SCRIPTS}/build-client-docker
                    '''
                }
                stash includes: 'results/docker/CLIENT_TAG', name: 'CLIENT_IMAGE'
                archiveArtifacts 'results/docker/CLIENT_TAG'
            }
        }
        stage('Build server packages') {
            steps {
                withCredentials([[$class: 'AmazonWebServicesCredentialsBinding', accessKeyVariable: 'AWS_ACCESS_KEY_ID', credentialsId: 'AMI/OVF', secretKeyVariable: 'AWS_SECRET_ACCESS_KEY']]) {
                    sh '''

                        set -o errexit

                        aws ecr-public get-login-password --region us-east-1 | docker login -u AWS --password-stdin public.ecr.aws/e7j3v3n0

                        export RPM_EPOCH=1
                        export PATH=${PATH}:$(pwd -P)/${PATH_TO_SCRIPTS}

                        ${PATH_TO_SCRIPTS}/build-server-rpm-all
                    '''
                }
            }
        }
        stage('Build server docker') {
            steps {
                withCredentials([usernamePassword(credentialsId: 'hub.docker.com', passwordVariable: 'PASS', usernameVariable: 'USER')]) {
                    sh '''
                        echo "${PASS}" | docker login -u "${USER}" --password-stdin
                    '''
                }
                withCredentials([[$class: 'AmazonWebServicesCredentialsBinding', accessKeyVariable: 'AWS_ACCESS_KEY_ID', credentialsId: 'AMI/OVF', secretKeyVariable: 'AWS_SECRET_ACCESS_KEY']]) {
                    sh '''
                        set -o errexit

                        export PUSH_DOCKER=1
                        export DOCKER_TAG=perconalab/pmm-server-fb:${BRANCH_NAME}-${SHORTENED_COMMIT}

                        export DOCKERFILE=Dockerfile.el9

                        ${PATH_TO_SCRIPTS}/build-server-docker
                    '''
                }
                stash includes: 'results/docker/TAG', name: 'IMAGE'
                archiveArtifacts 'results/docker/TAG'
            }
        }
        stage('Build watchtower container') {
            steps {
                build job: 'pmm3-watchtower-autobuild', parameters: [
                    string(name: 'GIT_BRANCH', value: params.PMM_BRANCH),
                    string(name: 'TAG_TYPE', value: "perconalab/pmm-watchtower-fb:${BRANCH_NAME}-${SHORTENED_COMMIT}")
                ]
            }
        }
        stage('Trigger workflows in GH') {
            steps {
                script {
                    withCredentials([string(credentialsId: 'GITHUB_API_TOKEN', variable: 'GITHUB_API_TOKEN')]) {
                        unstash 'IMAGE'
                        unstash 'pmmQABranch'
                        unstash 'pmmUITestBranch'
                        unstash 'fbCommitSha'
                        def IMAGE = sh(returnStdout: true, script: "cat results/docker/TAG").trim()
                        def CLIENT_IMAGE = sh(returnStdout: true, script: "cat results/docker/CLIENT_TAG").trim()
                        def FB_COMMIT_HASH = sh(returnStdout: true, script: "cat fbCommitSha").trim()
                        def STAGING_URL = "https://pmm.cd.percona.com/job/pmm3-aws-staging-start/parambuild/"

                        def message = "Server docker: ${IMAGE}\nClient docker: ${CLIENT_IMAGE}\nWatchtower docker: perconalab/pmm-watchtower-fb:${BRANCH_NAME}-${SHORTENED_COMMIT}\nClient tarball: ${CLIENT_URL}"
                        if (params.GSSAPI_DYNAMIC_TARBALLS && env.CLIENT_URL_DYNAMIC_OL8) {
                          message += "\nClient dynamic OL8 tarball: ${CLIENT_URL_DYNAMIC_OL8}"
                        }
                        if (params.GSSAPI_DYNAMIC_TARBALLS && env.CLIENT_URL_DYNAMIC_OL9) {
                          message += "\nClient dynamic OL9 tarball: ${CLIENT_URL_DYNAMIC_OL9}"
                        }
                        message += "\nStaging instance: ${STAGING_URL}?DOCKER_VERSION=${IMAGE}&CLIENT_VERSION=${CLIENT_URL}"

                        def payload = [
                          body: message
                        ]
                        writeFile(file: 'body.json', text: JsonOutput.toJson(payload))
                        sh '''
                            REPO=$(echo $CHANGE_URL | cut -d '/' -f 4-5)
                            # https://docs.github.com/en/rest/issues/comments?apiVersion=2022-11-28#create-an-issue-comment
                            # Comment on PR with docker server, client and the staging link
                            curl -X POST \
                                -H "Accept: application/vnd.github+json" \
                                -H "Authorization: token ${GITHUB_API_TOKEN}" \
                                -H "X-GitHub-Api-Version: 2022-11-28" \
                                -d @body.json \
                                "https://api.github.com/repos/${REPO}/issues/${CHANGE_ID}/comments"
                        '''

                        def PMM_QA_GIT_BRANCH = sh(returnStdout: true, script: "cat pmmQABranch").trim()
                        def PMM_UI_TESTS_GIT_BRANCH = sh(returnStdout: true, script: "cat pmmUITestBranch").trim()
                        payload = [
                          ref: "${PMM_BRANCH}",
                          inputs: [
                            pmm_server_image: "${IMAGE}", pmm_client_image: "${CLIENT_IMAGE}", sha: "${FB_COMMIT_HASH}",
                            pmm_qa_branch: "${PMM_QA_GIT_BRANCH}", pmm_ui_tests_branch: "${PMM_UI_TESTS_GIT_BRANCH}",
                            pmm_client_version: "${CLIENT_URL}",
                            pmm_client_dynamic_ol8: params.GSSAPI_DYNAMIC_TARBALLS && env.CLIENT_URL_DYNAMIC_OL8 ? env.CLIENT_URL_DYNAMIC_OL8 : '',
                            pmm_client_dynamic_ol9: params.GSSAPI_DYNAMIC_TARBALLS && env.CLIENT_URL_DYNAMIC_OL9 ? env.CLIENT_URL_DYNAMIC_OL9 : ''
                          ]
                        ]
                        writeFile(file: 'body.json', text: JsonOutput.toJson(payload))
                        // Trigger a workflow on GH to run tests
                        sh '''
                            REPO=$(echo $CHANGE_URL | cut -d '/' -f 4-5)
                            curl -X POST \
                                -H "Accept: application/vnd.github.v3+json" \
                                -H "Authorization: token ${GITHUB_API_TOKEN}" \
                                "https://api.github.com/repos/${REPO}/actions/workflows/pmm-qa-fb-checks.yml/dispatches" \
                                -d @body.json
                        '''
                    }
                }
            }
        }
        stage('Test API') {
            steps {
                script {
                    unstash 'IMAGE'
                    unstash 'apiURL'
                    unstash 'apiBranch'
                    unstash 'apiCommitSha'
                    def IMAGE = sh(returnStdout: true, script: "cat results/docker/TAG").trim()
                    def API_TESTS_URL = sh(returnStdout: true, script: "cat apiURL").trim()
                    def API_TESTS_BRANCH = sh(returnStdout: true, script: "cat apiBranch").trim()
                    def GIT_COMMIT_HASH = sh(returnStdout: true, script: "cat apiCommitSha").trim()

                    apiTestJob = build job: 'pmm3-api-tests', propagate: false, parameters: [
                        string(name: 'DOCKER_VERSION', value: IMAGE),
                        string(name: 'GIT_URL', value: API_TESTS_URL),
                        string(name: 'GIT_BRANCH', value: API_TESTS_BRANCH),
                        string(name: 'GIT_COMMIT_HASH', value: GIT_COMMIT_HASH)
                    ]
                    env.API_TESTS_URL = apiTestJob.absoluteUrl
                    env.API_TESTS_RESULT = apiTestJob.result

                    if (!env.API_TESTS_RESULT.equals("SUCCESS")) {
                        error "API tests failed."
                    }
                }
            }
        }
    }
    post {
        success {
            script {
                if (params.CHANGE_URL) {
                    unstash 'IMAGE'
                    def IMAGE = sh(returnStdout: true, script: "cat results/docker/TAG").trim()
                    slackSend channel: '#pmm-notifications', color: '#00FF00', message: "[${JOB_NAME}]: build finished, image: ${IMAGE}, URL: ${BUILD_URL}"
                    if (env.API_TESTS_RESULT.equals("SUCCESS") && env.API_TESTS_URL) {
                      addComment("API tests have succeded: ${API_TESTS_URL}")
                    }
                }
            }
        }
        always {
            script {
                if (currentBuild.result != 'SUCCESS') {
                    if (!env.API_TESTS_RESULT.equals("SUCCESS") && env.API_TESTS_URL) {
                        addComment("API tests have failed: ${API_TESTS_URL}")
                    }
                    slackSend channel: '#pmm-notifications', color: '#FF0000', message: "[${JOB_NAME}]: build ${currentBuild.result}, URL: ${BUILD_URL}"
                }
            }
        }
    }
}
