void triggerPlatformJob(String jobName, String branchName, String testList) {
    def buildParams = [
        string(name: 'GIT_BRANCH', value: branchName),
        text(name: 'TEST_LIST', value: testList ?: ''),
    ]

    def downstream = build(
        job: jobName,
        propagate: false,
        wait: true,
        parameters: buildParams,
    )

    echo "${jobName} result: ${downstream.result} (${downstream.absoluteUrl})"
    if (downstream.result != 'SUCCESS') {
        error("${jobName} finished with status ${downstream.result}: ${downstream.absoluteUrl}")
    }
}

List<String> getJobsForOperator(String operatorName) {
    operatorName = operatorName?.toLowerCase()
    def jobsByOperator = [
        pg   : ['pgo-aks-1', 'pgo-eks-1', 'pgo-gke-1', 'pgo-minikube-1', 'pgo-openshift-1'],
        ps   : ['pso-eks-1', 'pso-gke-1', 'pso-minikube-1', 'pso-openshift-1'],
        psmdb: ['psmdbo-aks-1', 'psmdbo-eks-1', 'psmdbo-gke-1', 'psmdbo-minikube-1', 'psmdbo-openshift-1'],
        pxc  : ['pxco-aks-1', 'pxco-eks-1', 'pxco-gke-1', 'pxco-minikube-1', 'pxco-openshift-1'],
    ]

    if (!jobsByOperator.containsKey(operatorName)) {
        error("Unsupported operator '${operatorName}'. Expected one of: pg, ps, psmdb, pxc")
    }

    return jobsByOperator[operatorName]
}

List<String> getSelectedPlatforms(Map allParams) {
    def selected = []
    if (allParams.RUN_AKS) {
        selected.add('aks')
    }
    if (allParams.RUN_EKS) {
        selected.add('eks')
    }
    if (allParams.RUN_GKE) {
        selected.add('gke')
    }
    if (allParams.RUN_MINIKUBE) {
        selected.add('minikube')
    }
    if (allParams.RUN_OPENSHIFT) {
        selected.add('openshift')
    }

    if (selected.isEmpty()) {
        error('Select at least one platform')
    }

    return selected
}

List<String> filterJobsByPlatforms(List<String> jobs, List<String> selectedPlatforms) {
    return jobs.findAll { jobName ->
        selectedPlatforms.any { platform ->
            jobName.contains("-${platform}-")
        }
    }
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
    parameters {
        choice(
            name: 'OPERATOR',
            choices: ['PG', 'PS', 'PSMDB', 'PXC'],
            description: 'Operator to test',
        )
        string(
            name: 'PR_BRANCH',
            defaultValue: 'main',
            description: 'PR branch/tag to test in operator repository',
        )
        text(
            name: 'TEST_LIST',
            defaultValue: '',
            description: 'List of tests to run separated by new line. If empty, the release test suite will run.',
        )
        booleanParam(
            name: 'RUN_AKS',
            defaultValue: true,
            description: 'Run AKS jobs',
        )
        booleanParam(
            name: 'RUN_EKS',
            defaultValue: true,
            description: 'Run EKS jobs',
        )
        booleanParam(
            name: 'RUN_GKE',
            defaultValue: true,
            description: 'Run GKE jobs',
        )
        booleanParam(
            name: 'RUN_MINIKUBE',
            defaultValue: true,
            description: 'Run Minikube jobs',
        )
        booleanParam(
            name: 'RUN_OPENSHIFT',
            defaultValue: true,
            description: 'Run OpenShift jobs',
        )
    }
    stages {
        stage('Run selected operator jobs') {
            steps {
                script {
                    def selectedPlatforms = getSelectedPlatforms(params)
                    def jobsToRun = filterJobsByPlatforms(getJobsForOperator(params.OPERATOR), selectedPlatforms)
                    if (jobsToRun.isEmpty()) {
                        error("No jobs matched for OPERATOR=${params.OPERATOR} and selected platforms=${selectedPlatforms.join(',')}")
                    }
                    currentBuild.description = "${params.OPERATOR}:${params.PR_BRANCH}:${selectedPlatforms.join('+')}"

                    def parallelStages = [:]
                    jobsToRun.each { jobName ->
                        parallelStages[jobName] = {
                            stage(jobName) {
                                triggerPlatformJob(jobName, params.PR_BRANCH, params.TEST_LIST)
                            }
                        }
                    }

                    parallel parallelStages
                }
            }
        }
    }
}
