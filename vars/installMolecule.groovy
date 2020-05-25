def call() {
        sh """
            sudo yum install -y gcc python3-pip python3-devel libselinux-python openssl-devel
            sudo mkdir -p /usr/local/lib64/python3.7/site-packages
            sudo rsync -aHv /usr/lib64/python2.7/site-packages/*selinux* /usr/local/lib64/python3.7/site-packages/
            pip3 install --user molecule==2.22 pytest molecule-ec2 ansible wheel boto boto3 paramiko selinux
        """
}
