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
        cron('0 15 * * 7')
    }
    stages {
        stage("Run parallel") {
            parallel {
                stage('Trigger psmdbo-gke-1 job 3 times') {
                    steps {
                        triggerJobMultiple("psmdbo-gke-1")
                    }
                }
                stage('Trigger psmdbo-eks-1 job 3 times') {
                    steps {
                        triggerJobMultiple("psmdbo-eks-1")
                    }
                }
                stage('Trigger pmsdbo-aks-1 job 3 times') {
                    steps {
                        triggerJobMultiple("psmdbo-aks-1")
                    }
                }
                stage('Trigger psmdbo-openshift-1 job 3 times') {
                    steps {
                        triggerJobMultiple("psmdbo-openshift-1")
                    }
                }
            }
        }
    }
    post {
        always {
            copyArtifacts(projectName: 'psmdbo-gke-1', selector: lastCompleted(), target: 'psmdbo-gke-1')
            copyArtifacts(projectName: 'psmdbo-eks-1', selector: lastCompleted(), target: 'psmdbo-eks-1')
            copyArtifacts(projectName: 'psmdbo-aks-1', selector: lastCompleted(), target: 'psmdb-aks-1')
            copyArtifacts(projectName: 'psmdbo-openshift-1', selector: lastCompleted(), target: 'psmdbo-openshift-1')
            archiveArtifacts '*/*.xml'
            step([$class: 'JUnitResultArchiver', testResults: '*/*.xml', healthScaleFactor: 1.0])
        }
    }
}
