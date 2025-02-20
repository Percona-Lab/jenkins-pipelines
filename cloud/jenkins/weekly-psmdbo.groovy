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
        cron('0 15 * * 7')
    }
    stages {
        stage("Run parallel") {
            parallel {
                stage('Trigger psmdbo-gke job 3 times') {
                    steps {
                        triggerJobMultiple("psmdbo-gke")
                    }
                }
                stage('Trigger psmdbo-eks job 3 times') {
                    steps {
                        triggerJobMultiple("psmdbo-eks")
                    }
                }
                stage('Trigger pmsdbo-aks job 3 times') {
                    steps {
                        triggerJobMultiple("psmdbo-aks")
                    }
                }
                stage('Trigger psmdbo-os job 3 times') {
                    steps {
                        triggerJobMultiple("psmdbo-os")
                    }
                }
            }
        }
    }
    post {
        always {
            copyArtifacts(projectName: 'psmdbo-gke', selector: lastCompleted(), target: 'psmdbo-gke')
            copyArtifacts(projectName: 'psmdbo-eks', selector: lastCompleted(), target: 'psmdbo-eks')
            copyArtifacts(projectName: 'psmdbo-aks', selector: lastCompleted(), target: 'psmdb-aks')
            copyArtifacts(projectName: 'psmdbo-os', selector: lastCompleted(), target: 'psmdbo-os')
            archiveArtifacts '*/*.xml'
            step([$class: 'JUnitResultArchiver', testResults: '*/*.xml', healthScaleFactor: 1.0])
        }
    }
}
