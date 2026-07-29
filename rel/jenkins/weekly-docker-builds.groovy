/* groovylint-disable DuplicateStringLiteral, GStringExpressionWithinString, LineLength */
library changelog: false, identifier: 'lib@hetzner', retriever: modernSCM([
    $class: 'GitSCMSource',
    remote: 'https://github.com/Percona-Lab/jenkins-pipelines.git'
]) _

pipeline {
    agent {
        label 'launcher-x64'
    }
    triggers {
        cron('H 6 * * 1')
    }
    parameters {
        choice(
            choices: 'perconalab\npercona',
            description: 'Organization on hub.docker.com',
            name: 'ORGANIZATION')
        choice(
            choices: '#releases-ci\n#releases',
            description: 'Channel for notifications',
            name: 'SLACKNOTIFY')
        string(
            defaultValue: 'hetzner-orchestrator-docker',
            description: 'Jenkins job name for Orchestrator Docker build',
            name: 'ORCHESTRATOR_JOB')
        string(
            defaultValue: 'hetzner-proxysql-docker',
            description: 'Jenkins job name for ProxySQL Docker build',
            name: 'PROXYSQL_JOB')
        string(
            defaultValue: '',
            description: 'ProxySQL version (e.g. 2.7.3). Leave empty to use job default.',
            name: 'PROXYSQL_VERSION')
        string(
            defaultValue: '',
            description: 'ProxySQL RPM release (e.g. 1.5). Leave empty to use job default.',
            name: 'PROXYSQL_RPM_RELEASE')
    }
    options {
        skipDefaultCheckout()
        disableConcurrentBuilds()
        buildDiscarder(logRotator(numToKeepStr: '10'))
        timestamps()
    }
    stages {
        stage('Weekly Docker Builds') {
            parallel {
                stage('Orchestrator') {
                    steps {
                        build job: params.ORCHESTRATOR_JOB,
                              parameters: [
                                  string(name: 'CLOUD',         value: 'Hetzner'),
                                  string(name: 'ORGANIZATION',  value: params.ORGANIZATION),
                                  string(name: 'COMPONENT',     value: 'testing'),
                                  string(name: 'SLACKNOTIFY',   value: params.SLACKNOTIFY),
                                  booleanParam(name: 'WEEKLY_UPDATE', value: true)
                              ],
                              wait: true,
                              propagate: true
                    }
                }
                stage('ProxySQL') {
                    steps {
                        script {
                            def proxysqlParams = [
                                string(name: 'CLOUD',        value: 'Hetzner'),
                                string(name: 'ORGANIZATION', value: params.ORGANIZATION),
                                string(name: 'COMPONENT',    value: 'testing'),
                                string(name: 'SLACKNOTIFY',  value: params.SLACKNOTIFY)
                            ]
                            if (params.PROXYSQL_VERSION) {
                                proxysqlParams += string(name: 'VERSION', value: params.PROXYSQL_VERSION)
                            }
                            if (params.PROXYSQL_RPM_RELEASE) {
                                proxysqlParams += string(name: 'RPM_RELEASE', value: params.PROXYSQL_RPM_RELEASE)
                            }
                            build job: params.PROXYSQL_JOB,
                                  parameters: proxysqlParams,
                                  wait: true,
                                  propagate: true
                        }
                    }
                }
            }
        }
    }
    post {
        success {
            slackNotify("${SLACKNOTIFY}", "#00FF00", "✅ ⏰ [${JOB_NAME}]: weekly docker builds finished successfully for ${ORGANIZATION} - [${BUILD_URL}]")
        }
        unstable {
            slackNotify("${SLACKNOTIFY}", "#FFFF00", "⚠️ ⏰ [${JOB_NAME}]: weekly docker builds finished with warnings for ${ORGANIZATION} - [${BUILD_URL}]")
        }
        failure {
            slackNotify("${SLACKNOTIFY}", "#FF0000", "❌ ⏰ [${JOB_NAME}]: weekly docker builds failed for ${ORGANIZATION} - [${BUILD_URL}]")
        }
        always {
            script {
                currentBuild.description = "Weekly docker builds for ${ORGANIZATION}"
            }
        }
    }
}
