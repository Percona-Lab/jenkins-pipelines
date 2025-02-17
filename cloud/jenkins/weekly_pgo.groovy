void triggerJobMultiple(String jobName) {
    for (int i = 1; i <= 3; i++) {
        build job: "$jobName", propagate: false, wait: true
    }
}

pipeline {
    agent any
    options {
        skipDefaultCheckout()
        disableConcurrentBuilds()
        buildDiscarder(logRotator(numToKeepStr: '10', artifactNumToKeepStr: '10'))
    }
    triggers {
        cron('0 15 * * 0')
    }
    stages {
        stage("Run parallel") {
            parallel {
                stage('Trigger pgo-gke job 3 times') {
                    steps {
                        triggerJobMultiple("pgo-gke")
                    }
                }
                stage('Trigger pgo-eks job 3 times') {
                    steps {
                        triggerJobMultiple("pgo-eks")
                    }
                }
                stage('Trigger pgo-aks job 3 times') {
                    steps {
                        triggerJobMultiple("pgo-aks")
                    }
                }
                stage('Trigger pgo-os job 3 times') {
                    steps {
                        triggerJobMultiple("pgo-os")
                    }
                }
            }
        }
    }
    post {
        always {
            copyArtifacts(projectName: 'pgo-gke', selector: lastCompleted(), target: 'pgo-gke')
            copyArtifacts(projectName: 'pgo-eks', selector: lastCompleted(), target: 'pgo-eks')
            copyArtifacts(projectName: 'pgo-aks', selector: lastCompleted(), target: 'pgo-aks')
            copyArtifacts(projectName: 'pgo-os', selector: lastCompleted(), target: 'pgo-os')
            archiveArtifacts '*/*.xml'
            step([$class: 'JUnitResultArchiver', testResults: '*/*.xml', healthScaleFactor: 1.0])
        }
    }
}
