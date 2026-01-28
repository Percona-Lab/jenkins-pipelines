library changelog: false, identifier: 'v3lib@master', retriever: modernSCM(
  scm: [$class: 'GitSCMSource', remote: 'https://github.com/Percona-Lab/jenkins-pipelines.git'],
  libraryPath: 'pmm/v3/'
)

pipeline {
    agent {
        label 'master'
    }
    parameters {
        string(
            description: 'Publish packages to repositories: testing for RC, experimental for 3-dev-latest',
            name: 'DESTINATION'
        )
        string(
            description: 'Upload path for the packages',
            name: 'UPLOAD_PATH'
        )
    }
    options {
        buildDiscarder(logRotator(numToKeepStr: '30'))
        skipDefaultCheckout()
        disableConcurrentBuilds()
        parallelsAlwaysFailFast()
    }
    stages {
        stage('Push to public repository') {
            steps {
                // Upload packages to the repo defined in `DESTINATION`
                sync2ProdPMMClientRepo(params.DESTINATION, params.UPLOAD_PATH, 'pmm3-client')
                withCredentials([sshUserPrivateKey(credentialsId: 'repo.ci.percona.com', keyFileVariable: 'KEY_PATH', usernameVariable: 'USER')]) {
                    script {
                        sh """
                            ssh -o StrictHostKeyChecking=no -o UserKnownHostsFile=/dev/null -i \${KEY_PATH} \${USER}@repo.ci.percona.com "
                                scp -P 2222 -o ConnectTimeout=1 -o StrictHostKeyChecking=no ${params.UPLOAD_PATH}/binary/tarball/*.tar.gz jenkins@jenkins-deploy.jenkins-deploy.web.r.int.percona.com:/data/downloads/TESTING/pmm/
                            "
                        """
                    }  
                }
            }
        }
    }
    post {
        success {
            script {
                slackSend botUser: true, channel: '#pmm-notifications', color: '#00FF00', message: "[${JOB_NAME}]: packages pushed to ${params.DESTINATION} repo - ${BUILD_URL}"
            }
        }
        failure {
            script {
                echo "Pipeline failed"
                slackSend botUser: true, channel: '#pmm-notifications', color: '#FF0000', message: "[${JOB_NAME}]: build ${currentBuild.result} - ${BUILD_URL}"
            }
        }
    }
}
