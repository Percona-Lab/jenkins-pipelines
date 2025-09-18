// groovylint-disable-next-line UnusedVariable, VariableName
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
                        def clusters = openshiftCluster.list([
                            region: env.OPENSHIFT_AWS_REGION,
                            format: params.OUTPUT_FORMAT ?: 'table',
                            accessKey: AWS_ACCESS_KEY_ID,
                            secretKey: AWS_SECRET_ACCESS_KEY
                        ])

                        // Set build description - optimized for Blue Ocean
                        def clusterCount = clusters.size()
                        def clustersText = clusterCount == 1 ? "1 cluster" : "${clusterCount} clusters"
                        currentBuild.description = "${clustersText} | ${env.OPENSHIFT_AWS_REGION} | ${params.OUTPUT_FORMAT ?: 'table'}"

                        if (params.OUTPUT_FORMAT == 'json') {
                            // For JSON output, just print the raw JSON
                            def json = new groovy.json.JsonBuilder(clusters)
                            echo json.toPrettyString()
                        } else {
                            // Use the shared formatting function for table output
                            def clusterSummary = openshiftTools.formatClustersSummary(
                                clusters,
                                "OPENSHIFT CLUSTERS IN ${env.OPENSHIFT_AWS_REGION}"
                            )
                            echo clusterSummary
                        }
                    }
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
