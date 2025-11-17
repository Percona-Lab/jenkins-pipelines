pipeline_timeout = 10

pipeline {
    parameters {
        choice(
            choices: 'pxb-24\npxb-80\npxb-84-lts\npxb-9x-innovation',
            description: 'Name of the repository',
            name: 'repo_name'
        )
        choice(
            choices: 'release\ntesting\nexperimental',
            description: 'Type of repository',
            name: 'repo_type'
        )
        choice(
            choices: 'ps\nms',
            description: 'Version of repository',
            name: 'server')
        string(
            defaultValue: 'https://github.com/Percona-QA/percona-qa.git',
            description: 'Package Testing Repository URL',
            name: 'PACKAGE_TESTING_REPO_URL',
            trim: true)
        string(
            defaultValue: 'master',
            description: 'Package Testing Repository Branch',
            name: 'PACKAGE_TESTING_REPO_BRANCH',
            trim: true)
        
    }
    agent {
        label 'docker'
    }
    options {
        skipDefaultCheckout()
        skipStagesAfterUnstable()
        buildDiscarder(logRotator(numToKeepStr: '10', artifactNumToKeepStr: '10'))
    }
    stages {
        stage('Testing') {
                steps {
                    git branch: '${PACKAGE_TESTING_REPO_BRANCH}', url: '${PACKAGE_TESTING_REPO_URL}'

                    sh """ cd backup_tests/
                        chmod +x docker_backup_tests.sh
                        ./docker_backup_tests.sh ${repo_name} ${repo_type} ${server} 9.1 """
                }
        }
    }
    post {
        always {
            sh '''
                cat /mnt/jenkins/workspace/pxb-docker-tests/backup_tests/backup_log
                echo Finish: \$(date -u "+%s")
            '''
        }
    }
}

