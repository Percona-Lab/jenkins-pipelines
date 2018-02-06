def call() {
    sh '''
        sudo yum -y install git epel-release
        sudo yum -y install awscli
        curl -fsSL get.docker.com -o get-docker.sh
        sh get-docker.sh
        sudo usermod -aG docker `id -u -n`
        sudo mkdir -p /etc/docker
        echo '{"experimental": true}' | sudo tee /etc/docker/daemon.json
        sudo service docker status || sudo service docker start
    '''
}
