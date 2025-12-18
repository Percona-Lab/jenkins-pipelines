@Library('jenkins-pipelines@feature/pmm-ha-rosa') _

def openshiftRosa

/**
 * Lists ROSA HCP clusters.
 *
 * This pipeline lists all ROSA clusters with their status, version, and age.
 * Replaces the IPI-based openshift_cluster_list.groovy.
 */
pipeline {
    agent { label 'agent-amd64-ol9' }

    parameters {
        choice(
            name: 'AWS_REGION',
            choices: ['us-east-2'],
            description: 'AWS region'
        )
        choice(
            name: 'OUTPUT_FORMAT',
            choices: ['table', 'json'],
            description: 'Output format'
        )
        string(
            name: 'CLUSTER_PREFIX',
            defaultValue: '',
            description: 'Filter clusters by name prefix (optional)'
        )
    }

    options {
        buildDiscarder(logRotator(numToKeepStr: '50'))
        timeout(time: 10, unit: 'MINUTES')
        timestamps()
    }

    stages {
        stage('Checkout & Load') {
            steps {
                checkout scm
                script {
                    openshiftRosa = load 'pmm/v3/vars/openshiftRosa.groovy'
                }
            }
        }

        stage('Install CLI Tools') {
            steps {
                script {
                    openshiftRosa.installRosaCli()
                }
            }
        }

        stage('Login to ROSA') {
            steps {
                withCredentials([
                    aws(
                        credentialsId: 'jenkins-openshift-aws',
                        accessKeyVariable: 'AWS_ACCESS_KEY_ID',
                        secretKeyVariable: 'AWS_SECRET_ACCESS_KEY'
                    ),
                    string(credentialsId: 'REDHAT_OFFLINE_TOKEN', variable: 'ROSA_TOKEN')
                ]) {
                    script {
                        openshiftRosa.login([
                            token: env.ROSA_TOKEN,
                            region: params.AWS_REGION
                        ])
                    }
                }
            }
        }

        stage('List Clusters') {
            steps {
                withCredentials([
                    aws(
                        credentialsId: 'jenkins-openshift-aws',
                        accessKeyVariable: 'AWS_ACCESS_KEY_ID',
                        secretKeyVariable: 'AWS_SECRET_ACCESS_KEY'
                    )
                ]) {
                    script {
                        def listConfig = [region: params.AWS_REGION]
                        if (params.CLUSTER_PREFIX?.trim()) {
                            listConfig.prefix = params.CLUSTER_PREFIX.trim()
                        }

                        def clusters = openshiftRosa.listClusters(listConfig)

                        if (params.OUTPUT_FORMAT == 'json') {
                            echo new groovy.json.JsonBuilder(clusters).toPrettyString()
                        } else {
                            def title = params.CLUSTER_PREFIX?.trim() ?
                                "ROSA CLUSTERS (prefix: ${params.CLUSTER_PREFIX})" :
                                'ROSA CLUSTERS'
                            echo openshiftRosa.formatClustersSummary(clusters, title)
                        }

                        currentBuild.description = "${clusters.size()} clusters | ${params.AWS_REGION}"
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
