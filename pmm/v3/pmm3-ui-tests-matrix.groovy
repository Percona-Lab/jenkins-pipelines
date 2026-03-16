library changelog: false, identifier: 'lib@master', retriever: modernSCM([
    $class: 'GitSCMSource',
    remote: 'https://github.com/Percona-Lab/jenkins-pipelines.git'
]) _
void runUITestsJob(String GIT_BRANCH, GIT_COMMIT_HASH, DOCKER_VERSION, CLIENT_VERSION, TAG, MYSQL_IMAGE, POSTGRES_IMAGE, MONGO_IMAGE, PROXYSQL_IMAGE, PMM_QA_GIT_BRANCH, CLIENTS) {
    runUITestsJob = build job: 'pmm3-ui-tests', parameters: [
        string(name: 'GIT_BRANCH', value: GIT_BRANCH),
        string(name: 'GIT_COMMIT_HASH', value: GIT_COMMIT_HASH),
        string(name: 'DOCKER_VERSION', value: DOCKER_VERSION),
        string(name: 'CLIENT_VERSION', value: CLIENT_VERSION),
        string(name: 'TAG', value: TAG),
        string(name: 'MYSQL_IMAGE', value: MYSQL_IMAGE),
        string(name: 'POSTGRES_IMAGE', value: POSTGRES_IMAGE),
        string(name: 'MONGO_IMAGE', value: MONGO_IMAGE),
        string(name: 'PROXYSQL_IMAGE', value: PROXYSQL_IMAGE),
        string(name: 'PMM_QA_GIT_BRANCH', value: PMM_QA_GIT_BRANCH),
        string(name: 'CLIENTS', value: CLIENTS)
    ]
}

pipeline {
    agent {
        label 'agent-amd64-ol9'
    }
    parameters {
        string(
            defaultValue: 'main',
            description: 'Tag/Branch for pmm-ui-tests repository',
            name: 'GIT_BRANCH')
        string(
            defaultValue: '',
            description: 'Commit hash for the branch',
            name: 'GIT_COMMIT_HASH')
        string(
            defaultValue: 'perconalab/pmm-server:3-dev-latest',
            description: 'PMM Server docker container version (image-name:version-tag)',
            name: 'DOCKER_VERSION')
        string(
            defaultValue: '3-dev-latest',
            description: 'PMM Client version',
            name: 'CLIENT_VERSION')
        string(
            defaultValue: 'percona:5.7',
            description: 'Percona Server Docker Container Image',
            name: 'MYSQL_IMAGE')
        string(
            defaultValue: 'perconalab/percona-distribution-postgresql:16.0',
            description: 'Postgresql Docker Container Image',
            name: 'POSTGRES_IMAGE')
        string(
            defaultValue: 'percona/percona-server-mongodb:4.4',
            description: 'Percona Server MongoDb Docker Container Image',
            name: 'MONGO_IMAGE')
        string(
            defaultValue: 'proxysql/proxysql:2.3.0',
            description: 'ProxySQL Docker Container Image',
            name: 'PROXYSQL_IMAGE')
        string(
            defaultValue: 'main',
            description: 'Tag/Branch for qa-integration repository',
            name: 'PMM_QA_GIT_BRANCH')
    }
    options {
        skipDefaultCheckout()
    }
    triggers {
        upstream upstreamProjects: 'pmm3-server-autobuild', threshold: hudson.model.Result.SUCCESS
    }
    stages{
        stage('UI tests Matrix') {
            parallel {
                stage('@ia'){
                    steps {
                        script {
                            runUITestsJob(GIT_BRANCH, GIT_COMMIT_HASH, DOCKER_VERSION, CLIENT_VERSION, '@ia', MYSQL_IMAGE, POSTGRES_IMAGE, MONGO_IMAGE, PROXYSQL_IMAGE, PMM_QA_GIT_BRANCH, '');
                        }
                    }
                }
                stage('@instances'){
                    steps {
                        script {
                            runUITestsJob(GIT_BRANCH, GIT_COMMIT_HASH, DOCKER_VERSION, CLIENT_VERSION, '@instances', MYSQL_IMAGE, POSTGRES_IMAGE, MONGO_IMAGE, PROXYSQL_IMAGE, PMM_QA_GIT_BRANCH, '--database haproxy --database external --database psmdb');
                        }
                    }
                }
                stage('@gcp'){
                    steps {
                        script {
                            runUITestsJob(GIT_BRANCH, GIT_COMMIT_HASH, DOCKER_VERSION, CLIENT_VERSION, '@gcp', MYSQL_IMAGE, POSTGRES_IMAGE, MONGO_IMAGE, PROXYSQL_IMAGE, PMM_QA_GIT_BRANCH, '');
                        }
                    }
                }
            }
        }
    }
    post {
        always {
            script {
                if (currentBuild.result == null || currentBuild.result == 'SUCCESS') {
                    slackSend channel: '#pmm-notifications', color: '#00FF00', message: "[${JOB_NAME}]: build finished - ${BUILD_URL} "
                } else {
                    slackSend channel: '#pmm-notifications', color: '#FF0000', message: "[${JOB_NAME}]: build ${currentBuild.result} - ${BUILD_URL}"
                }
            }
            deleteDir()
        }
    }
}
