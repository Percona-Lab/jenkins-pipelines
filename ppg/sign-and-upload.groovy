library changelog: false, identifier: 'lib@hetzner', retriever: modernSCM([
    $class: 'GitSCMSource',
    remote: 'https://github.com/Percona-Lab/jenkins-pipelines.git'
]) _

void cleanUpWS() {
    sh """
        sudo rm -rf ./*
    """
}

def AWS_STASH_PATH

pipeline {
    agent {
        label params.CLOUD == 'Hetzner' ? 'docker-x64-min' : 'docker'
    }
    parameters {
        choice(
             choices: [ 'Hetzner','AWS' ],
             description: 'Cloud infra for build',
             name: 'CLOUD' )
        string(
            defaultValue: '',
            description: 'Unsigned Github Artifact URL',
            name: 'ARTIFACT_URL')
        string(
            defaultValue: 'ppg-17.6',
            description: 'PPG repo name',
            name: 'PPG_REPO')
        choice(
            choices: 'laboratory\ntesting\nexperimental',
            description: 'Repo component to push packages to',
            name: 'COMPONENT')
    }
    options {
        skipDefaultCheckout()
        disableConcurrentBuilds()
        buildDiscarder(logRotator(numToKeepStr: '10', artifactNumToKeepStr: '10'))
    }
    stages {
        stage('Download artifact') {
            steps {
                withCredentials([string(credentialsId: 'github_token', variable: 'TOKEN')]) {
                    sh '''
                    echo "Downloading artifact..."
                    curl -L -f -o github-artifact.tar.gz "${ARTIFACT_URL}"
                    '''
                }
            }
        }
        stage('Extract artifact') {
            steps {
                sh '''
                tar -xzf github-artifact.tar.gz -C GITHUB_BUILDS
                AWS_STASH_PATH=$(find GITHUB_BUILDS -type d -name binary -exec dirname {} \\;)
                REPO_UPLOAD_PATH=UPLOAD/experimental/${AWS_STASH_PATH}
                echo ${REPO_UPLOAD_PATH} > uploadPath
                echo ${AWS_STASH_PATH} > awsUploadPath
                '''
                script {
                    AWS_STASH_PATH = sh(returnStdout: true, script: "cat awsUploadPath").trim()
                }
                stash includes: 'uploadPath', name: 'uploadPath'
                pushArtifactFolder(params.CLOUD, "source_tarball/", AWS_STASH_PATH)
                pushArtifactFolder(params.CLOUD, "srpm/", AWS_STASH_PATH)
                pushArtifactFolder(params.CLOUD, "source_deb/", AWS_STASH_PATH)
                uploadTarballfromAWS(params.CLOUD, "source_tarball/", AWS_STASH_PATH, 'source')
                uploadRPMfromAWS(params.CLOUD, "srpm/", AWS_STASH_PATH)
                uploadDEBfromAWS(params.CLOUD, "source_deb/", AWS_STASH_PATH)
                uploadRPMfromAWS(params.CLOUD, "rpm/", AWS_STASH_PATH)
                uploadDEBfromAWS(params.CLOUD, "deb/", AWS_STASH_PATH)
            }
        }

        stage('Sign packages') {
            steps {
                signRPM(params.CLOUD)
                signDEB(params.CLOUD)
            }
        }
        stage('Push to public repository') {
            steps {
                // sync packages
                sync2ProdAutoBuild(params.CLOUD, PPG_REPO, COMPONENT)
            }
        }

    }
    post {
        success {
            slackNotify("#releases-ci", "#00FF00", "[${JOB_NAME}]: build has been finished successfully for ${GIT_BRANCH} - [${BUILD_URL}]")
            script {
                currentBuild.description = "Built on ${GIT_BRANCH}"
            }
            deleteDir()
        }
        failure {
            slackNotify("#releases-ci", "#FF0000", "[${JOB_NAME}]: build failed for ${GIT_BRANCH} - [${BUILD_URL}]")
            deleteDir()
        }
        always {
            sh '''
                sudo rm -rf ./*
            '''
            deleteDir()
        }
    }
}
