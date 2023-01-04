library changelog: false, identifier: "lib@master", retriever: modernSCM([
    $class: 'GitSCMSource',
    remote: 'https://github.com/Percona-Lab/jenkins-pipelines.git'
])

pipeline {
    agent {
        label 'docker'
    }
    environment {
        PATH = '/usr/local/bin:/usr/bin:/usr/local/sbin:/usr/sbin:/home/ec2-user/.local/bin'
    }
    parameters {
        string(name: 'PSMDB_VERSION', defaultValue: 'latest', description: 'PSMDB version')
        string(name: 'PBM_VERSION', defaultValue: 'latest', description: 'PBM version')
        string(name: 'PMM_VERSION', defaultValue: 'latest', description: 'PMM2 agent version')
        string(name: 'PMM_IMAGE', defaultValue: 'perconalab/pmm-server:dev-latest', description: 'PMM server docker image')
    }
    options {
          disableConcurrentBuilds()
    }
    stages {
        stage ('Trigger integration tests') {
            steps {
                withCredentials([string(credentialsId: 'GITHUB_API_TOKEN', variable: 'GITHUB_API_TOKEN')]) {
                    sh """
                        curl -i -v -X POST \
                             -H "Accept: application/vnd.github.v3+json" \
                             -H "Authorization: token ${GITHUB_API_TOKEN}" \
                             "https://api.github.com/repos/Percona-Lab/qa-integration/actions/workflows/PMM_PSMDB_PBM.yml/dispatches" \
                             -d '{"ref":"main","inputs":{"psmdb_version":"${params.PSMDB_VERSION}","pbm_version":"${params.PBM_VERSION}","pmm_version":"${params.PMM_VERSION}","pmm_image":"${params.PMM_IMAGE}"}}'
                    """
                }
            }
        }
    }
} 
