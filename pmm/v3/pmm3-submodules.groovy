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

                    if [ -s ci.yml ]; then
                        source /home/ec2-user/venv/bin/activate
                        python3 ci.py
                        cat .git-sources
                        . ./.git-sources
                        echo $pmm_commit > apiCommitSha
                        echo $pmm_branch > apiBranch
                        echo $pmm_url > apiURL
                        echo $pmm_qa_branch > pmmQABranch
                        echo $pmm_qa_commit > pmmQACommitSha
                        echo $pmm_ui_tests_branch > pmmUITestBranch
                        echo $pmm_ui_tests_commit > pmmUITestsCommitSha
                    else
                        # Define variables
                        pmm_commit=$(git submodule status | grep 'sources/pmm/src' | awk -F ' ' '{print $1}')
                        echo $pmm_commit > apiCommitSha
                        pmm_branch=$(git config -f .gitmodules submodule.pmm.branch)
                        echo $pmm_branch > apiBranch
                        pmm_url=$(git config -f .gitmodules submodule.pmm.url)
                        echo $pmm_url > apiURL
                        pmm_qa_branch=$(git config -f .gitmodules submodule.pmm-qa.branch)
                        echo $pmm_qa_branch > pmmQABranch
                        pmm_qa_commit=$(git submodule status | grep 'pmm-qa' | awk -F ' ' '{print $1}')
                        echo $pmm_qa_commit > pmmQACommitSha
                        pmm_ui_tests_branch=$(git config -f .gitmodules submodule.pmm-ui-tests.branch)
                        echo $pmm_ui_tests_branch > pmmUITestBranch
                        pmm_ui_tests_commit=$(git submodule status | grep 'pmm-ui-tests' | awk -F ' ' '{print $1}')
                        echo $pmm_ui_tests_commit > pmmUITestsCommitSha
                    fi
                    fb_commit_sha=$(git rev-parse HEAD)
                    echo $fb_commit_sha > fbCommitSha
                '''
                }
                script {
                    env.PMM_VERSION = sh(returnStdout: true, script: "cat VERSION").trim()
                    env.FB_COMMIT = sh(returnStdout: true, script: "cat fbCommitSha").trim()
                }
                stash includes: 'apiBranch', name: 'apiBranch'
                stash includes: 'apiURL', name: 'apiURL'
                stash includes: 'pmmQABranch', name: 'pmmQABranch'
                stash includes: 'apiCommitSha', name: 'apiCommitSha'
                stash includes: 'pmmQACommitSha', name: 'pmmQACommitSha'
                stash includes: 'pmmUITestBranch', name: 'pmmUITestBranch'
                stash includes: 'pmmUITestsCommitSha', name: 'pmmUITestsCommitSha'
                stash includes: 'fbCommitSha', name: 'fbCommitSha'
                slackSend channel: '#pmm-ci', color: '#0000FF', message: "[${JOB_NAME}]: v3 build started, URL: ${BUILD_URL}"
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
                            s3://pmm-build-cache/PR-BUILDS/pmm-client/pmm-client-${BRANCH_NAME}-${FB_COMMIT:0:7}.tar.gz
                    '''
                }
                script {
                    sh (script: 'echo "https://s3.us-east-2.amazonaws.com/pmm-build-cache/PR-BUILDS/pmm-client/pmm-client-${BRANCH_NAME}-${FB_COMMIT:0:7}.tar.gz" | tee CLIENT_URL')
                    env.CLIENT_URL = sh (script: "cat CLIENT_URL", returnStdout: true).trim()
                }
            }
        }
        stage('Build client source rpm') {
            steps {
                withCredentials([[$class: 'AmazonWebServicesCredentialsBinding', accessKeyVariable: 'AWS_ACCESS_KEY_ID', credentialsId: 'AMI/OVF', secretKeyVariable: 'AWS_SECRET_ACCESS_KEY']]) {
                    sh '''
                        set -o errexit
                        aws ecr-public get-login-password --region us-east-1 | docker login -u AWS --password-stdin public.ecr.aws/e7j3v3n0

                        ${PATH_TO_SCRIPTS}/build-client-srpm public.ecr.aws/e7j3v3n0/rpmbuild:3
                    '''
                }
            }
        }
        stage('Build client binary rpm') {
            steps {
                withCredentials([[$class: 'AmazonWebServicesCredentialsBinding', accessKeyVariable: 'AWS_ACCESS_KEY_ID', credentialsId: 'AMI/OVF', secretKeyVariable: 'AWS_SECRET_ACCESS_KEY']]) {
                    sh '''
                        set -o errexit

                        aws ecr-public get-login-password --region us-east-1 | docker login -u AWS --password-stdin public.ecr.aws/e7j3v3n0

                        ${PATH_TO_SCRIPTS}/build-client-rpm public.ecr.aws/e7j3v3n0/rpmbuild:3

                        mkdir -p tmp/pmm-server/RPMS/
                        cp results/rpm/pmm-client-*.rpm tmp/pmm-server/RPMS/
                    '''
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
                        export DOCKER_CLIENT_TAG=perconalab/pmm-client-fb:${BRANCH_NAME}-${FB_COMMIT:0:7}
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
                        export RPMBUILD_DOCKER_IMAGE=public.ecr.aws/e7j3v3n0/rpmbuild:3
                        export RPMBUILD_DIST="el9"

                        ${PATH_TO_SCRIPTS}/build-server-rpm-all
                    '''
                }
            }
        }
        stage('Build server docker') {
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
                        export DOCKER_TAG=perconalab/pmm-server-fb:${BRANCH_NAME}-${FB_COMMIT:0:7}

                        export RPMBUILD_DOCKER_IMAGE=public.ecr.aws/e7j3v3n0/rpmbuild:3
                        export RPMBUILD_DIST=el9
                        export DOCKERFILE=Dockerfile.el9

                        ${PATH_TO_SCRIPTS}/build-server-docker
                    '''
                }
                stash includes: 'results/docker/TAG', name: 'IMAGE'
                archiveArtifacts 'results/docker/TAG'
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

                        def payload = [
                          body: "Server docker: ${IMAGE}\nClient docker: ${CLIENT_IMAGE}\nClient tarball: ${CLIENT_URL}\nStaging instance: ${STAGING_URL}?DOCKER_VERSION=${IMAGE}&CLIENT_VERSION=${CLIENT_URL}"
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

                        payload = [
                          ref: "${PMM_BRANCH}",
                          inputs: [
                            server_image: "${IMAGE}", client_image: "${CLIENT_IMAGE}", sha: "${FB_COMMIT_HASH}"
                          ]
                        ]
                        writeFile(file: 'body.json', text: JsonOutput.toJson(payload))
                        // Trigger a workflow on GH to run some test there as well, pass server and client images as parameters
                        sh '''
                            REPO=$(echo $CHANGE_URL | cut -d '/' -f 4-5)
                            curl -X POST \
                                -H "Accept: application/vnd.github.v3+json" \
                                -H "Authorization: token ${GITHUB_API_TOKEN}" \
                                "https://api.github.com/repos/${REPO}/actions/workflows/jenkins-dispatch.yml/dispatches" \
                                -d @body.json
                        '''

                        payload = [
                          ref: "${PMM_BRANCH}",
                          inputs: [
                            client_tar_url: "${CLIENT_URL}", sha: "${FB_COMMIT_HASH}"
                          ]
                        ]
                        writeFile(file: 'body.json', text: JsonOutput.toJson(payload))
                        // Trigger a workflow on GH to run PMM binary cli tests
                        sh '''
                            REPO=$(echo $CHANGE_URL | cut -d '/' -f 4-5)
                            curl -X POST \
                                -H "Accept: application/vnd.github.v3+json" \
                                -H "Authorization: token ${GITHUB_API_TOKEN}" \
                                "https://api.github.com/repos/${REPO}/actions/workflows/pmm-cli.yml/dispatches" \
                                -d @body.json
                        '''

                        def PMM_QA_GIT_BRANCH = sh(returnStdout: true, script: "cat pmmQABranch").trim()
                        payload = [
                          ref: "${PMM_BRANCH}",
                          inputs: [
                            server_image: "${IMAGE}", client_image: "${CLIENT_IMAGE}", sha: "${FB_COMMIT_HASH}",
                            pmm_qa_branch: "${PMM_QA_GIT_BRANCH}", client_version: "${CLIENT_URL}"
                          ]
                        ]
                        writeFile(file: 'body.json', text: JsonOutput.toJson(payload))
                        // Trigger a workflow on GH to run testsuite tests
                        sh '''
                            REPO=$(echo $CHANGE_URL | cut -d '/' -f 4-5)
                            curl -X POST \
                                -H "Accept: application/vnd.github.v3+json" \
                                -H "Authorization: token ${GITHUB_API_TOKEN}" \
                                "https://api.github.com/repos/${REPO}/actions/workflows/pmm-testsuite.yml/dispatches" \
                                -d @body.json
                        '''

                        def PMM_UI_TESTS_GIT_BRANCH = sh(returnStdout: true, script: "cat pmmUITestBranch").trim()
                        payload = [
                          ref: "${PMM_BRANCH}",
                          inputs: [
                            server_image: "${IMAGE}", client_image: "${CLIENT_IMAGE}", sha: "${FB_COMMIT_HASH}",
                            pmm_qa_branch: "${PMM_QA_GIT_BRANCH}", pmm_ui_branch: "${PMM_UI_TESTS_GIT_BRANCH}",
                            client_version: "${CLIENT_URL}"
                          ]
                        ]
                        writeFile(file: 'body.json', text: JsonOutput.toJson(payload))
                        // Trigger a workflow on GH to run ui tests
                        sh '''
                            REPO=$(echo $CHANGE_URL | cut -d '/' -f 4-5)
                            curl -X POST \
                                -H "Accept: application/vnd.github.v3+json" \
                                -H "Authorization: token ${GITHUB_API_TOKEN}" \
                                "https://api.github.com/repos/${REPO}/actions/workflows/pmm-ui-tests-fb.yml/dispatches" \
                                -d @body.json
                        '''

                        payload = [
                          ref: "${PMM_BRANCH}",
                          inputs: [
                            server_image: "${IMAGE}", client_image: "${CLIENT_IMAGE}", sha: "${FB_COMMIT_HASH}",
                          ]
                        ]
                        writeFile(file: 'body.json', text: JsonOutput.toJson(payload))
                        // Trigger a workflow on GH to run trivy for vulnerability scan
                        sh '''
                            REPO=$(echo $CHANGE_URL | cut -d '/' -f 4-5)
                            curl -X POST \
                                -H "Accept: application/vnd.github.v3+json" \
                                -H "Authorization: token ${GITHUB_API_TOKEN}" \
                                "https://api.github.com/repos/${REPO}/actions/workflows/trivy_scan.yml/dispatches" \
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
                    slackSend channel: '#pmm-ci', color: '#00FF00', message: "[${JOB_NAME}]: build finished, image: ${IMAGE}, URL: ${BUILD_URL}"
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
                    slackSend channel: '#pmm-ci', color: '#FF0000', message: "[${JOB_NAME}]: build ${currentBuild.result}, URL: ${BUILD_URL}"
                }
            }
        }
    }
}
