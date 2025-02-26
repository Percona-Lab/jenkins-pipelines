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
                stage('Trigger pxco-gke-1 job 3 times') {
                    steps {
                        triggerJobMultiple("pxco-gke-1")
                    }
                }
                stage('Trigger pxco-eks-1 job 3 times') {
                    steps {
                        triggerJobMultiple("pxco-eks-1")
                    }
                }
                stage('Trigger pxco-aks-1 job 3 times') {
                    steps {
                        triggerJobMultiple("pxco-aks-1")
                    }
                }
                stage('Trigger pxco-openshift-1 job 3 times') {
                    steps {
                        triggerJobMultiple("pxco-openshift-1")
                    }
                }
            }
        }
    }
    post {
        always {
            copyArtifacts(projectName: 'pxco-gke-1', selector: lastCompleted(), target: 'pxco-gke-1')
            copyArtifacts(projectName: 'pxco-eks-1', selector: lastCompleted(), target: 'pxco-eks-1')
            copyArtifacts(projectName: 'pxco-aks-1', selector: lastCompleted(), target: 'pxco-aks-1')
            copyArtifacts(projectName: 'pxco-openshift-1', selector: lastCompleted(), target: 'pxco-openshift-1')
            archiveArtifacts '*/*.xml'
            step([$class: 'JUnitResultArchiver', testResults: '*/*.xml', healthScaleFactor: 1.0])
        }
    }
}
