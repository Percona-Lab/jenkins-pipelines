def call() {
        sh """
            sudo yum install -y gcc python3-pip python3-devel libselinux-python3
            sudo yum remove ansible -y
            python3 -m venv virtenv
            . virtenv/bin/activate
            python3 --version
            python3 -m pip install --upgrade setuptools
            python3 -m pip install --upgrade molecule==3.3.0 testinfra pytest molecule-ec2==0.3 molecule[ansible]
        """
}
