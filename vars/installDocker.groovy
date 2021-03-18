def call() {
    sh '''
        SYSREL=$(cat /etc/system-release | tr -dc '0-9.'|awk -F'.' {'print $1'})
        if [[ $SYSREL -eq 2 ]]; then
            sudo amazon-linux-extras install epel -y
            sudo yum install -y docker
        elif [[ $SYSREL -eq 7 ]]; then
            sudo yum install -y epel-release 
            sudo yum install -y python3 python3-pip
            sudo pip3 install awscli
            curl -fsSL get.docker.com -o get-docker.sh
            env -i sh get-docker.sh || sudo yum -y install docker
        fi
        sudo yum -y install git curl
        sudo usermod -aG docker `id -u -n`
        sudo mkdir -p /etc/docker
        echo '{"experimental": true}' | sudo tee /etc/docker/daemon.json
        sudo service docker status || sudo service docker start
        sudo docker system prune --all --force
    '''
}
