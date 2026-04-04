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
        cron('0 15 * * 0')
    }
    stages {
        stage("Run parallel") {
            parallel {
                stage('Trigger pgo-gke-1 job 2 times') {
                    steps {
                        triggerJobMultiple("pgo-gke-1")
                    }
                }
                stage('Trigger pgo-eks-1 job 2 times') {
                    steps {
                        triggerJobMultiple("pgo-eks-1")
                    }
                }
                stage('Trigger pgo-aks-1 job 2 times') {
                    steps {
                        triggerJobMultiple("pgo-aks-1")
                    }
                }
                stage('Trigger pgo-openshift-1 job 2 times') {
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
