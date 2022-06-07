pipeline {
    agent {
        label 'docker-farm'
    }
    parameters {
        string(
            defaultValue: 'publish',
            description: 'Tag/Branch to publish',
            name: 'BRANCH_NAME')
        choice(
            choices: ['percona.com', 'new.percona.com'],
            description: 'Publish to production or staging server',
            name: 'PUBLISH_TARGET')
    }
    options {
        buildDiscarder(logRotator(daysToKeepStr: '14', numToKeepStr: '5'))
    }
    stages {
        stage('Checkout') {
            steps {
                git poll: false, branch: BRANCH_NAME, url: 'https://github.com/percona/pmm-doc.git'
                stash name: "html-files", includes: "1.x/**,2.x/**,versions.json,index.html"
            }
        }
        stage('Publish Docs'){
            agent {
                label 'vbox-01.ci.percona.com'
            }
            steps{
                deleteDir()
                unstash "html-files"
                withCredentials([sshUserPrivateKey(credentialsId: 'jenkins-deploy', keyFileVariable: 'KEY_PATH', passphraseVariable: '', usernameVariable: 'USER')]) {
                    sh '''
                        echo BRANCH=${BRANCH_NAME}
                        ls -l
                        DEST_HOST='docs-rsync-endpoint.int.percona.com'
                        rsync --delete -vv -azr -O -e "ssh -o StrictHostKeyChecking=no -p2222 -i \${KEY_PATH}" ./ \${USER}@\${DEST_HOST}:/data/websites_data/\${PUBLISH_TARGET}/doc/percona-monitoring-and-management/
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
