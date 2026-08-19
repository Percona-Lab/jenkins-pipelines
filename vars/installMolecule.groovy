def call() {
        sh """
            sudo yum install -y gcc python3-pip python3-devel libselinux-python3
            sudo yum remove ansible -y
            python3 -m venv virtenv
            . virtenv/bin/activate
            python3 --version
            python3 -m pip install --upgrade pip
            python -m pip install \
                "molecule==26.6.0" \
                "molecule-plugins[ec2]==26.7.15" \
                "ansible==9.13.0" \
                "ansible-core==2.16.19" \
                "ansible-lint==26.6.0" \
                "PyYAML==6.0.3" \
                "pytest==9.1.1" \
                "pytest-testinfra==10.2.2" \
                "boto3==1.43.53" \
                "botocore==1.43.53"
        """
}
