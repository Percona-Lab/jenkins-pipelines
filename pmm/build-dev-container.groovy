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
    stages {
        stage('Clone repo') {
            steps {
                git poll: true,
                    branch: 'main',
                    url: "https://github.com/percona/pmm.git"
            }
        }
        stage('Build dev-container') {
            steps {
                withCredentials([usernamePassword(credentialsId: 'hub.docker.com', passwordVariable: 'PASS', usernameVariable: 'USER')]) {
                    sh """
                        docker login -u "${USER}" -p "${PASS}"
                    """
                }
                sh 'docker build -t ${DOCKER_IMAGE} -f devcontainer.Dockerfile .'
                sh 'docker push $DOCKER_IMAGE'
            }
        }
    }
}