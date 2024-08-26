def call() {
    sh '''
        ##SYSREL=$(cat /etc/system-release | tr -dc '0-9.'|awk -F'.' {'print $1'})
        sudo dnf config-manager --add-repo=https://download.docker.com/linux/centos/docker-ce.repo
        sudo dnf install -y docker-ce docker-ce-cli containerd.io
        sudo yum -y install git curl
        sudo usermod -aG docker `id -u -n`
        sudo mkdir -p /etc/docker
        echo '{"experimental": true}' | sudo tee /etc/docker/daemon.json
        sudo systemctl status docker || sudo systemctl start docker
        sudo docker system prune --all --force
    '''
}
