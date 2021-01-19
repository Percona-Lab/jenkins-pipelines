pipeline {
    agent {
        label 'micro-amazon'
    }
    parameters {
        string(
            defaultValue: 'publish',
            description: 'Tag/Branch for build',
            name: 'BRANCH_NAME')
        choice(
            defaultValue: 'test.percona.com',
            choices: ['test.percona.com', 'percona.com'],
            description: 'Publish to test or production server',
            name: 'PUBLISH_TARGET')
    }
    options {
        buildDiscarder(logRotator(daysToKeepStr: '14', numToKeepStr: '5'))
    }
    stages {
        stage('Checkout') {
            steps {
                sh """
                    sudo chmod 777 -R ./
                """
                git poll: false, branch: BRANCH_NAME, url: 'https://github.com/percona/pmm-doc.git'
                stash name: "html-files", includes: "**"
            }
        }
        stage('Doc Publish'){
            agent {
                label 'vbox-01.ci.percona.com'
            }
            steps{
                unstash "html-files"
                withCredentials([sshUserPrivateKey(credentialsId: 'jenkins-deploy', keyFileVariable: 'KEY_PATH', passphraseVariable: '', usernameVariable: 'USER')]) {
                    sh '''
                        echo BRANCH=${BRANCH_NAME}
                        DEST_HOST='docs-rsync-endpoint.int.percona.com'
                        rsync --delete-before -avzr -O -e "ssh -o StrictHostKeyChecking=no -p2222 -i \${KEY_PATH}" ./* \${USER}@\${DEST_HOST}:/data/websites_data/\${PUBLISH_TARGET}/doc/percona-monitoring-and-management/
                    '''
                }
            }
        }
    }
    post {
        always {
            deleteDir()
        }
    }
}
