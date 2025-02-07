library changelog: false, identifier: 'lib@ENG-7_postgresql_rel', retriever: modernSCM([
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
        text(name: 'PACKAGES', defaultValue: '', description: 'put all pathes to all packages')
        string(name: 'repo_version', defaultValue: '', description: 'Repository Version i.e ppg-15.3 or ppg-15')
        choice(name: 'component', choices: ['release', 'testing', 'experimental', 'laboratory'], description: 'Component')
    }
    options {
        skipDefaultCheckout()
        disableConcurrentBuilds()
        buildDiscarder(logRotator(numToKeepStr: '10', artifactNumToKeepStr: '10'))
    }
    stages {
        stage('Prepare and Stash Files') {
            steps {
                slackNotify("#releases-ci", "#00FF00", "[${JOB_NAME}]: starting release for ${repo_version} for ${component} repo - [${BUILD_URL}]")
                cleanUpWS()
                script {
                    writeFile file: 'ppg_packages.txt', text: params.PACKAGES
                    def ppgReleaseContent = "PPG_REPO=${params.repo_version}\nCOMPONENT=${params.component}"
                    writeFile file: 'ppg_release.properties', text: ppgReleaseContent
                    stash name: 'files', includes: 'ppg_packages.txt, ppg_release.properties'
                }
            }
        }
        stage('Prepare FIles for Release') {
            steps {
                unstash 'files'
                script {
                    def remoteDir = '/tmp/ppg_release'
                    withCredentials([sshUserPrivateKey(credentialsId: 'repo.ci.percona.com', keyFileVariable: 'KEY_PATH', passphraseVariable: '', usernameVariable: 'USER')]) {
                        sh """
                            ssh -o StrictHostKeyChecking=no -i ${KEY_PATH} ${USER}@repo.ci.percona.com ' \
                                set -o errexit
                                set -o xtrace
                                mkdir -p ${remoteDir}
                                cd ${remoteDir}
                                rm -rf /tmp/ppg_release/*
                                echo \"${PACKAGES}\" >> ./ppg_packages.txt
                                wget https://raw.githubusercontent.com/Percona-Lab/jenkins-pipelines/ENG-7_postgresql_rel/scripts/prepare_pg_release.sh
                                bash -x ./prepare_pg_release.sh ${repo_version}
                            '
                        """
                    }
                }
            }
        }
        stage('Push to public repository') {
            steps {
                sync2ProdPPG(repo_version, component)
            }
        }
        stage('Push to WEB') {
            steps {
                sync2web(repo_version, component)
            }
        }       
    }
    post {
        success {
            slackNotify("#releases-ci", "#00FF00", "[${JOB_NAME}]: build has been finished successfully for ${repo_version} on ${component} repo - [${BUILD_URL}]")
            script {
                currentBuild.description = "Built on ${repo_version} for ${component} repo}"
            }
            deleteDir()
        }
        failure {
            slackNotify("#releases-ci", "#FF0000", "[${JOB_NAME}]: build failed for ${repo_version} on ${component} repo - [${BUILD_URL}]")
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
