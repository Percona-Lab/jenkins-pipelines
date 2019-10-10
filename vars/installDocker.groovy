def call() {
    sh '''
        sudo yum -y install git curl epel-release
        sudo yum -y install python36
        curl -fsSL https://bootstrap.pypa.io/3.3/get-pip.py -o get-pip.py
        sudo python3 get-pip.py
        sudo env PATH=/usr/local/bin:${PATH} pip install awscli==1.15.19
        curl -fsSL get.docker.com -o get-docker.sh
        env -i sh get-docker.sh || sudo yum -y install docker
        sudo usermod -aG docker `id -u -n`
        sudo mkdir -p /etc/docker
        echo '{"experimental": true}' | sudo tee /etc/docker/daemon.json
        sudo service docker status || sudo service docker start
        sudo docker system prune --all --force
    '''
}
