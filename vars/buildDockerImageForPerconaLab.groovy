def call(version) {
    sh """
        unset PSMDB_VERSION
        unset MAJOR_VERSION
        git clone https://github.com/percona/percona-docker.git
        cd percona-docker/percona-server-mongodb-${version}
        export PSMDB_VERSION=${version}
        export MAJOR_VERSION=$(echo "${PSMDB_VERSION}" | cut -d. -f1,2)
        docker build -t percona-server-mongodb:${MAJOR_VERSION} . -f Dockerfile
        docker login --username username
        docker tag percona-server-mongodb:${MAJOR_VERSION} perconalab/percona-server-mongodb:${MAJOR_VERSION}
        docker tag percona-server-mongodb:${MAJOR_VERSION} perconalab/percona-server-mongodb:${PSMDB_VERSION}
        docker push perconalab/percona-server-mongodb:${MAJOR_VERSION}
        docker push perconalab/percona-server-mongodb:${PSMDB_VERSION}
    """
}
