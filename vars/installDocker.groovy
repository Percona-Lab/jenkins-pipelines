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
        sudo sed -i.bak -e 's/nofile=1024:4096/nofile=900000:900000/; s/DAEMON_MAXFILES=.*/DAEMON_MAXFILES=990000/' /etc/sysconfig/docker
        sudo sed -i.bak -e 's^ExecStart=.*^ExecStart=/usr/bin/dockerd --data-root=/mnt/docker --default-ulimit nofile=900000:900000^' /usr/lib/systemd/system/docker.service
        sudo systemctl daemon-reload
        sudo service docker status || sudo service docker start
        sudo docker system prune --all --force
    '''
}
