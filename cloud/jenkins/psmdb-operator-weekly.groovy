viod triggerJob(jobName) {
    for (int i = 1; i <= 3; i++) {
        build job: jobName, propagate: false, wait: true
    }
}

def jobs = ['psmdbo-gke', 'psmdbo-eks', 'psmdbo-aks', 'psmdbo-os']

pipeline {
    options {
        skipDefaultCheckout()
        disableConcurrentBuilds()
        buildDiscarder(logRotator(numToKeepStr: '10', artifactNumToKeepStr: '10'))
        timestamps ()
    }
    triggers {
        cron('0 15 * * 6')
    }
    stages {
        stage("Run parallel") {
            parallel {
                stage('Trigger psmdbo-gke job 3 times') {
                    jobs.each { job -> triggerJob(job) }
                }
            }
        }
    }
    post {
        always {
            jobs.each { job -> copyArtifacts(projectName: job, selector: lastCompleted(), target: job) }
            archiveArtifacts '*/*.xml'
            step([$class: 'JUnitResultArchiver', testResults: '*/*.xml', healthScaleFactor: 1.0])
        }
    }
}
