@Library('jenkins-pipelines') _

pipeline {
    agent {
        label 'agent-amd64-ol9'
    }

    environment {
        OPENSHIFT_AWS_REGION = "${params.AWS_REGION ?: 'us-east-2'}"
    }

    options {
        skipDefaultCheckout()
        buildDiscarder(logRotator(numToKeepStr: '50', daysToKeepStr: '7'))
        timestamps()
    }

    stages {
        stage('List OpenShift Clusters') {
            steps {
                withCredentials([
                    aws(
                        credentialsId: 'jenkins-openshift-aws',
                        accessKeyVariable: 'AWS_ACCESS_KEY_ID',
                        secretKeyVariable: 'AWS_SECRET_ACCESS_KEY'
                    )
                ]) {
                    script {
                        openshiftCluster.list([
                            region: env.OPENSHIFT_AWS_REGION,
                            format: params.OUTPUT_FORMAT ?: 'table'
                        ])
                    }
                }
            }
        }
    }

    post {
        always {
            cleanWs()
        }
    }
}
