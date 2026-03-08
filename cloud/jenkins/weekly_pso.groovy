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
        cron('0 8 * * 0')
    }
    stages {
        stage("Run parallel") {
            parallel {
                stage('Trigger pso-gke-1 job 2 times') {
                    steps {
                        triggerJobMultiple("pso-gke-1")
                    }
                }
                stage('Trigger pso-eks-1 job 2 times') {
                    steps {
                        triggerJobMultiple("pso-eks-1")
                    }
                }
                stage('Trigger pso-openshift-1 job 2 times') {
                    steps {
                        triggerJobMultiple("pso-openshift-1")
                    }
                }
            }
        }
    }
    post {
        always {
            copyArtifacts(projectName: 'pso-gke-1', selector: lastCompleted(), target: 'pso-gke-1')
            copyArtifacts(projectName: 'pso-eks-1', selector: lastCompleted(), target: 'pso-eks-1')
            copyArtifacts(projectName: 'pso-openshift-1', selector: lastCompleted(), target: 'pso-openshift-1')
            archiveArtifacts '*/*.xml'
            step([$class: 'JUnitResultArchiver', testResults: '*/*.xml', healthScaleFactor: 1.0])
        }
    }
}
