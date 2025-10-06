def call() {
        sh """
            sudo yum remove ansible -y
            python3 -m venv virtenv --system-site-packages
            . virtenv/bin/activate
            python3 --version
            python3 -m pip install ansible
            python3 -m pip install ansible-core
            python3 -m pip install --upgrade pip
            python3 -m pip install --upgrade "setuptools<81"
            python3 -m pip install --upgrade setuptools-rust
            python3 -m pip install --upgrade molecule==3.3.0 molecule[ansible] molecule-ec2==0.3 pytest-testinfra pytest "ansible-lint>=5.1.1,<6.0.0" boto3 boto
            ansible-galaxy collection list
            ansible-galaxy collection install amazon.aws
            echo $PATH 
            pip list
            ansible --version && molecule --version
        """
}
