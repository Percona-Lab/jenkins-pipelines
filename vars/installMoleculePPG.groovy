def call() {
        sh """
            sudo dnf module install -y python38
            sudo alternatives --set python3 /usr/bin/python3.8
            sudo dnf install -y gcc python38-pip python38-devel libselinux-python3 python3-libselinux
            sudo yum remove ansible -y
            python3 -m venv virtenv --system-site-packages
            . virtenv/bin/activate
            python3 --version
            python3 -m pip install --upgrade pip
            python3 -m pip install --upgrade "setuptools<81"
            python3 -m pip install --upgrade setuptools-rust
            python3 -m pip install --upgrade molecule==3.3.0 molecule[ansible] molecule-ec2==0.3 pytest-testinfra pytest "ansible-lint>=5.1.1,<6.0.0" boto3 boto selinux packaging
            sudo cp -r  /usr/lib64/python3.6/site-packages/selinux /usr/lib64/python3.8/site-packages
            echo $PATH 
            pip list
            ansible --version && molecule --version
        """
}
