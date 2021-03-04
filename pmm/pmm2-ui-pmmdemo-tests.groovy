library changelog: false, identifier: 'lib@master', retriever: modernSCM([
    $class: 'GitSCMSource',
    remote: 'https://github.com/Percona-Lab/jenkins-pipelines.git'
]) _

void uploadAllureArtifacts() {
    withCredentials([sshUserPrivateKey(credentialsId: 'aws-jenkins', keyFileVariable: 'KEY_PATH', passphraseVariable: '', usernameVariable: 'USER')]) {
        sh """
            scp -r -i "${KEY_PATH}" -o ConnectTimeout=1 -o StrictHostKeyChecking=no \
                pmm-app/tests/output/allure aws-jenkins@${MONITORING_HOST}:/home/aws-jenkins/allure-reports
        """
    }
}
pipeline {
    agent {
        label 'large-amazon'
    }
    environment {
        REMOTE_AWS_MYSQL_USER=credentials('pmm-dev-mysql-remote-user')
        REMOTE_AWS_MYSQL_PASSWORD=credentials('pmm-dev-remote-password')
        REMOTE_AWS_MYSQL57_HOST=credentials('pmm-dev-mysql57-remote-host')
        REMOTE_MYSQL_HOST=credentials('mysql-remote-host')
        REMOTE_MYSQL_USER=credentials('mysql-remote-user')
        REMOTE_MYSQL_PASSWORD=credentials('mysql-remote-password')
        REMOTE_MONGODB_HOST=credentials('qa-remote-mongodb-host')
        REMOTE_MONGODB_USER=credentials('qa-remote-mongodb-user')
        REMOTE_MONGODB_PASSWORD=credentials('qa-remote-mongodb-password')
        REMOTE_POSTGRESQL_HOST=credentials('qa-remote-pgsql-host')
        REMOTE_POSTGRESQL_USER=credentials('qa-remote-pgsql-user')
        REMOTE_POSTGRESSQL_PASSWORD=credentials('qa-remote-pgsql-password')
        REMOTE_PROXYSQL_HOST=credentials('qa-remote-proxysql-host')
        REMOTE_PROXYSQL_USER=credentials('qa-remote-proxysql-user')
        REMOTE_PROXYSQL_PASSWORD=credentials('qa-remote-proxysql-password')
        INFLUXDB_ADMIN_USER=credentials('influxdb-admin-user')
        INFLUXDB_ADMIN_PASSWORD=credentials('influxdb-admin-password')
        INFLUXDB_USER=credentials('influxdb-user')
        INFLUXDB_USER_PASSWORD=credentials('influxdb-user-password')
        MONITORING_HOST=credentials('monitoring-host')
        PMM_UI_URL='https://pmmdemo.percona.com/'
    }
    parameters {
        string(
            defaultValue: 'PMM-2.0',
            description: 'Tag/Branch for grafana-dashboard repository',
            name: 'GIT_BRANCH')
        string(
            defaultValue: '',
            description: 'Commit hash for the branch',
            name: 'GIT_COMMIT_HASH')
    }
    options {
        skipDefaultCheckout()
    }
    triggers { cron('0 0 * * *') }
    stages {
        stage('Prepare') {
            steps {
                // clean up workspace and fetch pmm-qa repository
                deleteDir()
                git poll: false, branch: GIT_BRANCH, url: 'https://github.com/percona/grafana-dashboards.git'

                slackSend botUser: true, channel: '#pmm-ci', color: '#FFFF00', message: "[${JOB_NAME}]: build started - ${BUILD_URL}"
                installDocker()
                sh '''
                    sudo yum -y update --security
                    sudo yum -y install jq svn
                    sudo usermod -aG docker ec2-user
                    sudo service docker start
                    sudo curl -L https://github.com/docker/compose/releases/download/1.21.0/docker-compose-`uname -s`-`uname -m` | sudo tee /usr/local/bin/docker-compose > /dev/null
                    sudo chmod +x /usr/local/bin/docker-compose
                    sudo ln -s /usr/local/bin/docker-compose /usr/bin/docker-compose
                    sudo docker-compose --version
                '''
            }
        }
        stage('Checkout Commit') {
             when {
                expression { env.GIT_COMMIT_HASH.length()>0 }
            }
            steps {
                sh 'git checkout ' + env.GIT_COMMIT_HASH
            }
        }
        stage('Setup Node') {
            steps {
                sh """
                    curl --silent --location https://rpm.nodesource.com/setup_14.x | sudo bash -
                    sudo yum -y install nodejs

                    pushd pmm-app/
                    npm install
                    node -v
                    npm -v
                    sudo yum install -y gettext
                    envsubst < env.list > env.generated.list
                    popd
                """
            }
        }
        stage('Run UI Tests') {
            options {
                timeout(time: 10, unit: "MINUTES")
            }
            steps {
                withCredentials([[$class: 'AmazonWebServicesCredentialsBinding', accessKeyVariable: 'AWS_ACCESS_KEY_ID', credentialsId: 'PMM_AWS_DEV', secretKeyVariable: 'AWS_SECRET_ACCESS_KEY']]) {
                    sh """
                        pushd pmm-app/
                        sed -i 's+http://localhost/+${PMM_UI_URL}/+g' pr.codecept.js
                        export PWD=\$(pwd);
                        sudo docker run --env AWS_SECRET_ACCESS_KEY=${AWS_SECRET_ACCESS_KEY} --env AWS_ACCESS_KEY_ID=${AWS_ACCESS_KEY_ID} --env-file env.generated.list --net=host -v \$PWD:/tests -v \$PWD/node_modules:/node_modules  codeception/codeceptjs:2.6.1 codeceptjs run-multiple parallel --debug --steps --reporter mocha-multi -c pr.codecept.js --grep '@pmm-demo'
                        popd
                    """
                }
            }
        }
    }
    post {
        always {
            // stop staging
            sh '''
                sudo chmod 777 -R pmm-app/tests/output
                ./pmm-app/node_modules/.bin/mochawesome-merge pmm-app/tests/output/parallel_chunk*/*.json > pmm-app/tests/output/combine_results.json
                ./pmm-app/node_modules/.bin/marge pmm-app/tests/output/combine_results.json --reportDir pmm-app/tests/output/ --inline --cdn --charts
            '''
            script {
                if (currentBuild.result == null || currentBuild.result == 'SUCCESS') {
                    junit 'pmm-app/tests/output/parallel_chunk*/*.xml'
                    publishHTML([allowMissing: false, alwaysLinkToLastBuild: false, keepAll: false, reportDir: 'pmm-app/tests/output/', reportFiles: 'combine_results.html', reportName: 'HTML Report', reportTitles: ''])
                    slackSend botUser: true, channel: '#pmm-ci', color: '#00FF00', message: "[${JOB_NAME}]: build finished - ${BUILD_URL}  & View Tests Run Report - http://${MONITORING_HOST}:9093/latest-report/"
                    archiveArtifacts artifacts: 'pmm-app/tests/output/combine_results.html'
                } else {
                    junit 'pmm-app/tests/output/parallel_chunk*/*.xml'
                    publishHTML([allowMissing: false, alwaysLinkToLastBuild: false, keepAll: false, reportDir: 'pmm-app/tests/output/', reportFiles: 'combine_results.html', reportName: 'HTML Report', reportTitles: ''])
                    slackSend botUser: true, channel: '#pmm-ci', color: '#FF0000', message: "[${JOB_NAME}]: build ${currentBuild.result} - ${BUILD_URL} & View Tests Run Report - http://${MONITORING_HOST}:9093/latest-report/"
                    archiveArtifacts artifacts: 'pmm-app/tests/output/combine_results.html'
                    archiveArtifacts artifacts: 'pmm-app/tests/output/parallel_chunk*/*.png'
                }
            }
            sh '''
                sudo rm -r pmm-app/node_modules/
                sudo rm -r pmm-app/tests/output
            '''
            deleteDir()
        }
    }
}