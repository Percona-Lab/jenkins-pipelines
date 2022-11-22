Jenkins.instance.getItemByFullName(env.JOB_NAME).description = '''
Build dev container for dev-latest
'''
pipeline {
    agent {
        label 'agent-amd64'
    }
    environment {
        DOCKER_IMAGE='perconalab/pmm-server:dev-container'
    }
    triggers {
        upstream upstreamProjects: 'pmm2-server-autobuild', threshold: hudson.model.Result.SUCCESS
    }
    options {
        buildDiscarder(logRotator(numToKeepStr: '30'))
        skipDefaultCheckout()
    }    
    stages {
        stage('Clone repo') {
            steps {
                git changelog: false,
                    poll: false,
                    branch: 'main',
                    url: "https://github.com/percona/pmm.git"
            }
        }
        stage('Build dev-container') {
            steps {
                withCredentials([usernamePassword(credentialsId: 'hub.docker.com', passwordVariable: 'PASS', usernameVariable: 'USER')]) {
                    sh '''
                        echo "${PASS}" | docker login -u "${USER}" --password-stdin
                    '''
                }
                sh 'docker build -t $DOCKER_IMAGE -f devcontainer.Dockerfile .'
                sh 'docker push $DOCKER_IMAGE'
            }
        }
    }
}
