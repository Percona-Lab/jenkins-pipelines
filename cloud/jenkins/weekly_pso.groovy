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
        cron('0 8 * * 0')
    }
    stages {
        stage("Run parallel") {
            parallel {
                stage('Trigger pso-gke job 3 times') {
                    steps {
                        triggerJobMultiple("pso-gke-1")
                    }
                }
                stage('Trigger pso-eks job 3 times') {
                    steps {
                        triggerJobMultiple("pso-eks-1")
                    }
                }
                stage('Trigger pso-os job 3 times') {
                    steps {
                        triggerJobMultiple("pso-openshift-1")
                    }
                }
            }
        }
    }
    post {
        always {
            copyArtifacts(projectName: 'pso-gke', selector: lastCompleted(), target: 'pso-gke-1')
            copyArtifacts(projectName: 'pso-eks', selector: lastCompleted(), target: 'pso-eks-1')
            copyArtifacts(projectName: 'pso-os', selector: lastCompleted(), target: 'pso-openshift-1')
            archiveArtifacts '*/*.xml'
            step([$class: 'JUnitResultArchiver', testResults: '*/*.xml', healthScaleFactor: 1.0])
        }
    }
}
