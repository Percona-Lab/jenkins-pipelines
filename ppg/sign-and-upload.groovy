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
            description: 'Github draft-release name of the artifact',
            name: 'RELEASE_NAME')
        choice(
             choices: [ 'RPM','DEB','BOTH' ],
             description: 'Distro to be uploaded',
             name: 'DISTRO' )
        string(
            defaultValue: 'ppg-17.6',
            description: 'PPG repo name',
            name: 'PPG_REPO')
        string(
            defaultValue: 'percona/postgres-packaging',
            description: 'packaging repo name',
            name: 'PACKAGING_REPO')
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
                    echo "Install jq"
                    if ! command -v jq > /dev/null; then
                        sudo apt-get update
                        sudo apt-get install -y jq
                    fi
                    echo "Searching release: $RELEASE_NAME"
                    RELEASE_ID=$(curl -s \
                        -H "Authorization: Bearer $TOKEN" \
                        https://api.github.com/repos/$PACKAGING_REPO/releases?per_page=100 \
                        | jq --arg name "$RELEASE_NAME" '.[] | select(.name==$name) | .id')

                    if [ -z "$RELEASE_ID" ] || [ "$RELEASE_ID" = "null" ]; then
                        echo "Release not found: $RELEASE_NAME"
                        exit 1
                    fi
                    
                    ASSET_ID=$(curl -s \
                        -H "Authorization: Bearer $TOKEN" \
                        https://api.github.com/repos/$PACKAGING_REPO/releases/$RELEASE_ID \
                        | jq '.assets[] | select(.name | endswith(".tar.gz")) | .id')

                    if [ -z "$ASSET_ID" ] || [ "$ASSET_ID" = "null" ]; then
                        echo "Asset not found in release"
                        exit 1
                    fi
                    
                    echo "Downloading artifact $RELEASE_NAME..."
                    curl -L \
                        -H "Accept: application/octet-stream" \
                        -H "Authorization: Bearer $TOKEN" \
                        https://api.github.com/repos/$PACKAGING_REPO/releases/assets/$ASSET_ID \
                        -o github-artifact.tar.gz
                    '''
                }
            }
        }
        stage('Extract artifact') {
            steps {
                sh '''
                mkdir -p GITHUB_BUILDS
                tar -xzf github-artifact.tar.gz -C GITHUB_BUILDS
                AWS_STASH_PATH=$(find GITHUB_BUILDS -type d -name source_tarball -exec dirname {} \\; | head -1)
                REPO_UPLOAD_PATH=UPLOAD/experimental/${AWS_STASH_PATH}
                echo ${REPO_UPLOAD_PATH} > uploadPath
                echo ${AWS_STASH_PATH} > awsUploadPath
                '''
                script {
                    AWS_STASH_PATH = sh(returnStdout: true, script: "cat awsUploadPath").trim()
                }
                stash includes: 'uploadPath', name: 'uploadPath'
                dir("${AWS_STASH_PATH}") {
                    pushArtifactFolder(params.CLOUD, "source_tarball/", AWS_STASH_PATH)
                    pushArtifactFolder(params.CLOUD, "srpm/", AWS_STASH_PATH)
                    pushArtifactFolder(params.CLOUD, "source_deb/", AWS_STASH_PATH)
                    pushArtifactFolder(params.CLOUD, "rpm/", AWS_STASH_PATH)
                    pushArtifactFolder(params.CLOUD, "deb/", AWS_STASH_PATH)
                
                    uploadTarballfromAWS(params.CLOUD, "source_tarball/", AWS_STASH_PATH, 'source')
                    uploadRPMfromAWS(params.CLOUD, "srpm/", AWS_STASH_PATH)
                    uploadSourceDebfromAWS(params.CLOUD, "source_deb/", AWS_STASH_PATH)
                
                    uploadRPMfromAWS(params.CLOUD, "rpm/", AWS_STASH_PATH)
                    uploadDEBfromAWS(params.CLOUD, "deb/", AWS_STASH_PATH)
                }
            }
        }

        stage('Sign packages') {
            steps {
                script {
                    if (params.DISTRO == 'RPM' || params.DISTRO == 'BOTH') {
                        signRPM(params.CLOUD)
                    }

                    if (params.DISTRO == 'DEB' || params.DISTRO == 'BOTH') {
                        signDEB(params.CLOUD)
                    }
                }
            }
        }
        stage('Push to public repository') {
            steps {
                // sync packages
                sync2ProdAutoBuildPG(params.CLOUD, PPG_REPO, COMPONENT)
            }
        }

    }
    post {
        success {
            slackNotify("#releases-ci", "#00FF00", "[${JOB_NAME}]: build has been finished successfully for ${RELEASE_NAME} (${PPG_REPO}) - [${BUILD_URL}]")
            script {
                currentBuild.description = "Built for ${RELEASE_NAME} (${PPG_REPO})"
            }
            deleteDir()
        }
        failure {
            slackNotify("#releases-ci", "#FF0000", "[${JOB_NAME}]: build failed for ${RELEASE_NAME} (${PPG_REPO}) - [${BUILD_URL}]")
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
