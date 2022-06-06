pipeline_timeout = 10

pipeline {
    parameters {
        string(
            defaultValue: 'https://github.com/percona/proxysql-admin-tool',
            description: 'URL to ProxySQL-admin-tool repository',
            name: 'PAT_REPO',
            trim: true)
        string(
            defaultValue: 'v2',
            description: 'Tag/Branch for ProxySQL-admin-tool repository',
            name: 'PAT_TAG',
            trim: true)
       choice(
            choices: 'centos:7\ncentos:8\nubuntu:xenial\nubuntu:bionic\nubuntu:focal\ndebian:stretch\ndebian:buster',
            description: 'OS version for compilation',
            name: 'DOCKER_OS')
    }
    agent {
        label 'micro-amazon'
    }
    options {
        skipDefaultCheckout()
        skipStagesAfterUnstable()
        timeout(time: 6, unit: 'DAYS')
        buildDiscarder(logRotator(numToKeepStr: '200', artifactNumToKeepStr: '200'))
    }
    stages {
        stage('Test proxysql-admin-tool') {
                agent { label 'docker' }
                steps {
                    git branch: 'PSQLADM-361-Create-a-Jenkins-job-to-build-proxysql-admin-and-run-test-suites', url: 'https://github.com/adivinho/jenkins-pipelines'
                    echo 'Test proxysql-admin-tool'
                    withCredentials([[$class: 'AmazonWebServicesCredentialsBinding', accessKeyVariable: 'AWS_ACCESS_KEY_ID', credentialsId: 'c42456e5-c28d-4962-b32c-b75d161bff27', secretKeyVariable: 'AWS_SECRET_ACCESS_KEY']]) {
                        sh '''
                            aws ecr-public get-login-password --region us-east-1 | docker login -u AWS --password-stdin public.ecr.aws/e7j3v3n0
                            sg docker -c "
                                if [ \$(docker ps -q | wc -l) -ne 0 ]; then
                                    docker ps -q | xargs docker stop --time 1 || :
                                fi
                                ./proxysql/run-test-proxysql-admin-tool ${DOCKER_OS} ${PAT_REPO} ${PAT_TAG}
                            "
                        '''
                    }
                    step([$class: 'JUnitResultArchiver', testResults: 'proxysql/results/*.xml', healthScaleFactor: 1.0])
                    archiveArtifacts 'proxysql/results/*.output,proxysql/results/*.xml,proxysql/results/qa_proxysql_admin_ipv*_logs.tar.gz'
                }
        }
    }
    post {
        always {
            sh '''
                echo Finish: \$(date -u "+%s")
            '''
        }
    }
}
