void build(String IMAGE_PREFIX){
    sh """
        cd ./source/
        docker build --no-cache --squash -t perconalab/percona-xtradb-cluster-operator:master-${IMAGE_PREFIX} images/${IMAGE_PREFIX}-image
    """
}
void pushImage(String IMAGE_PREFIX){
     withCredentials([usernamePassword(credentialsId: 'hub.docker.com', passwordVariable: 'PASS', usernameVariable: 'USER')]) {
        sh """
            IMAGE_PREFIX=${IMAGE_PREFIX}
            sg docker -c "
                docker login -u '${USER}' -p '${PASS}'
                docker push perconalab/percona-xtradb-cluster-operator:master-${IMAGE_PREFIX}
            "
        """
    }
}
pipeline {
    parameters {
        string(
            defaultValue: 'master',
            description: 'Tag/Branch for Percona-Lab/percona-openshift repository',
            name: 'GIT_BRANCH')
        string(
            defaultValue: 'https://github.com/Percona-Lab/percona-openshift',
            description: 'Percona-Lab/percona-openshift repository',
            name: 'GIT_REPO')
    }
    agent {
         label 'docker' 
    }
    options {
        skipDefaultCheckout()
        disableConcurrentBuilds()
    }

    stages {
        stage('Prepare') {
            steps {
                git branch: 'cloud-215', url: 'https://github.com/hors/jenkins-pipelines'
                sh '''
                    # sudo is needed for better node recovery after compilation failure
                    # if building failed on compilation stage directory will have files owned by docker user
                    sudo git reset --hard
                    sudo git clean -xdf
                    sudo rm -rf source
                    ./cloud/local/checkout
                '''
                stash includes: "source/**", name: "sourceFILES"
            }
        }

        stage('Build docker images') {
            steps {
                unstash "sourceFILES"
                build('pxc')
                build('proxysql')
                build('backup')

                echo 'Push PXC docker images to docker hub'
                pushImage('pxc')
                pushImage('proxysql')
                pushImage('backup')
            }
        }
    }

    post {
        always {
            deleteDir()
        }
    }
}
