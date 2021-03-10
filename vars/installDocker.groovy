def call() {
    sh '''
        sudo amazon-linux-extras install epel -y
        sudo yum -y install git curl docker
        sudo usermod -aG docker `id -u -n`
        sudo mkdir -p /etc/docker
        echo '{"experimental": true}' | sudo tee /etc/docker/daemon.json
        sudo service docker status || sudo service docker start
        sudo docker system prune --all --force
    '''
}
