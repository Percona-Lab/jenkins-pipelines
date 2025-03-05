void triggerJobMultiple(String jobName) {
    for (int i = 1; i <= 3; i++) {
        build job: "$jobName", propagate: false, wait: true
    }
}

pipeline {
    agent {
        label 'docker'
    }
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
                stage('Trigger pgo-gke-1 job 3 times') {
                    steps {
                        triggerJobMultiple("pgo-gke-1")
                    }
                }
                stage('Trigger pgo-eks-1 job 3 times') {
                    steps {
                        triggerJobMultiple("pgo-eks-1")
                    }
                }
                stage('Trigger pgo-aks-1 job 3 times') {
                    steps {
                        triggerJobMultiple("pgo-aks-1")
                    }
                }
                stage('Trigger pgo-openshift-1 job 3 times') {
                    steps {
                        triggerJobMultiple("pgo-openshift-1")
                    }
                }
            }
        }
    }
    post {
        always {
            copyArtifacts(projectName: 'pgo-gke-1', selector: lastCompleted(), target: 'pgo-gke-1')
            copyArtifacts(projectName: 'pgo-eks-1', selector: lastCompleted(), target: 'pgo-eks-1')
            copyArtifacts(projectName: 'pgo-aks-1', selector: lastCompleted(), target: 'pgo-aks-1')
            copyArtifacts(projectName: 'pgo-openshift-1', selector: lastCompleted(), target: 'pgo-openshift-1')
            archiveArtifacts '*/*.xml'
            step([$class: 'JUnitResultArchiver', testResults: '*/*.xml', healthScaleFactor: 1.0])
        }
    }
}
