void triggerJobMultiple(String jobName) {
    int maxAttempts = 2
    int attempt = 0

    while (attempt < maxAttempts) {
        def result = build job: jobName, propagate: false, wait: true

        if (result.result == 'SUCCESS') {
            echo "Job ${jobName} succeeded on attempt ${attempt + 1}."
            return
        }

        echo "Job ${jobName} finished with status ${result.result} on attempt ${attempt + 1}."
        attempt++

        if (attempt < maxAttempts) {
            echo "Retrying job ${jobName}..."
        }
    }

    error "Job ${jobName} failed after ${maxAttempts} attempts."
}

pipeline {
    agent {
        label 'docker-x64-min'
    }
    options {
        skipDefaultCheckout()
        disableConcurrentBuilds()
        buildDiscarder(logRotator(numToKeepStr: '10', artifactNumToKeepStr: '10'))
    }
    triggers {
        cron('0 8 * * 6')
    }
    stages {
        stage("Run parallel") {
            parallel {
                stage('Trigger pxco-gke-1 job 2 times') {
                    steps {
                        triggerJobMultiple("pxco-gke-1")
                    }
                }
                stage('Trigger pxco-eks-1 job 2 times') {
                    steps {
                        triggerJobMultiple("pxco-eks-1")
                    }
                }
                stage('Trigger pxco-aks-1 job 2 times') {
                    steps {
                        triggerJobMultiple("pxco-aks-1")
                    }
                }
                stage('Trigger pxco-openshift-1 job 2 times') {
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
