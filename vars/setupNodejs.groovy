def call() {
    sh """
        sudo yum remove -y nodejs npm nodesource-release
        sudo wget https://rpm.nodesource.com/pub_14.x/el/7/x86_64/nodejs-14.18.1-1nodesource.x86_64.rpm
        sudo yum clean all
        sudo yum install -y nodejs-14.18.1-1nodesource.x86_64.rpm
        npm ci
        node -v
        npm -v
    """
}
