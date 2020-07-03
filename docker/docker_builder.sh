#!/usr/bin/env bash

shell_quote_string() {
  echo "$1" | sed -e 's,\([^a-zA-Z0-9/_.=-]\),\\\1,g'
}

usage () {
    cat <<EOF
Usage: $0 [OPTIONS]
    The following options may be given :
        --builddir=DIR      Absolute path to the dir where all actions will be performed
        --get_sources       Source will be downloaded from github
        --product           Product to build
        --build_container   Build container
        --export_container  Export built container
        --release_container Push built container to repository( default = 0)
        --dockerfile        Dockerfile( default = Dockerfile)
        --cleanup           Cleanup docker images( default = 0)
        --version           Set version tag for container
        --update_major      If set will update :MAJOR tag( default = 0)
        --organization      Set organization for release
        --install_deps      Install build dependencies(root previlages are required)
        --branch            Branch for build
        --repo              Repo for build
        --help) usage ;;
Example $0 --builddir=/tmp/docker --get_sources=1 --cleanup=1 --build_container=1 --release_container=1 --organization=percona --product=percona-server --version=5.7.13
EOF
        exit 1
}

append_arg_to_args () {
  args="$args "$(shell_quote_string "$1")
}

 parse_arguments() {
    pick_args=
    if test "$1" = PICK-ARGS-FROM-ARGV
    then
        pick_args=1
        shift
    fi

    for arg do
        val=$(echo "$arg" | sed -e 's;^--[^=]*=;;')
        case "$arg" in
            # these get passed explicitly to mysqld
            --builddir=*) WORKDIR="$val" ;;
            --get_sources=*) SOURCE="$val" ;;
            --product=*) PRODUCT="$val" ;;
            --build_container=*) BUILD_CONTAINER="$val" ;;
            --export_container=*) EXPORT_CONTAINER="$val" ;;
            --release_container=*) RELEASE_CONTAINER="$val" ;;
            --dockerfile=*) DOCKERFILE="$val" ;;
            --cleanup=*) CLEANUP="$val" ;;
            --version=*) VERSION="$val" ;;
            --update_major=*) UPDATE_MAJOR="$val" ;;
            --organization=*) ORG="$val" ;;
            --install_deps=*) INSTALL="$val" ;;
            --branch=*) BRANCH="$val" ;;
            --repo=*) REPO="$val" ;;
            --help) usage ;;
            *)
              if test -n "$pick_args"
              then
                  append_arg_to_args "$arg"
              fi
              ;;
        esac
    done
}

check_workdir(){
    if [ "$WORKDIR" == "$CURDIR" ]; then
        echo >&2 "Current directory cannot be used for building!"
        exit 1
    else
        if ! test -d "$WORKDIR"; then
            echo >&2 "$WORKDIR is not a directory."
            exit 1
        fi
    fi
    return
}

get_system(){
    if [ -f /etc/redhat-release ]; then
        export RHEL=$(rpm --eval %rhel)
        export ARCH=$(echo $(uname -m) | sed -e 's:i686:i386:g')
        export OS_NAME="el$RHEL"
        export OS="rpm"
    else
        export ARCH=$(uname -m)
        export OS_NAME="$(lsb_release -sc)"
        export OS_ID="$(lsb_release -is | awk '{print tolower($0)}')"
        export OS="deb"
    fi
    return
}

install_deps(){
    if [ $INSTALL == 0 ]; then
        echo "Docker will not be installed"
        return
    fi
    if [ ! $( id -u ) -eq 0 ]
    then
        echo "It is not possible to instal dependencies. Please run as root"
        exit 1
    fi
    if [ "$OS" == "rpm" ]; then
        yum install -y yum-utils
        yum-config-manager --add-repo https://download.docker.com/linux/centos/docker-ce.repo
        yum install -y docker-ce docker-ce-cli containerd.io
    elif [ "$OS" == "deb" ]; then
        apt-get install -y \
            apt-transport-https \
            ca-certificates \
            curl \
            gnupg-agent \
            software-properties-common

        curl -fsSL https://download.docker.com/linux/${OS_ID}/gpg | apt-key add -
        add-apt-repository "deb [arch=amd64] https://download.docker.com/linux/${OS_ID} ${OS_NAME} stable"
        apt-get update
        apt-get install -y docker-ce docker-ce-cli containerd.io     
    else
        echo "Unsupported OS"
        exit 1    
    fi
}

clean_docker() {
    if [ "${CLEANUP}" == 0 ]; then
        echo "Docker images/containers will not be removed"
        return
    fi

    docker container prune -f
    docker image prune -af
}

get_sources(){
    if [ "${SOURCE}" = 0 ]; then
        echo "Sources will not be downloaded"
        return 0
    fi
    cd ${WORKDIR}
    git clone "${REPO}"
    retval=$?
    if [ ${retval} != 0 ]; then
        echo "There were some issues during repo cloning from github. Please retry one more time"
        exit 1
    fi

    cd percona-docker
    if [ ! -z "${BRANCH}" ]; then
        git reset --hard
        git clean -xdf
        git checkout "${BRANCH}"
    fi
}

build_container() {
    if [ "${BUILD_CONTAINER}" == 0 ]; then
        echo "Docker image will not be built"
        return
    fi

    cd ${WORKDIR}/percona-docker
    if [[ -d "${PRODUCT}-$(echo ${VERSION} | awk -F"." 'BEGIN { OFS = "." }{ print $1, $2}')" ]]; then 
        cd "${PRODUCT}-$(echo ${VERSION} | awk -F"." 'BEGIN { OFS = "." }{ print $1, $2}')"
    else
        if ! cd "${PRODUCT}"; then
            exit 1
        fi
    fi

    docker build --no-cache -t ${ORG}/${PRODUCT}:${VERSION} -f ${DOCKERFILE} .
    if [ $? -eq 0 ]; then
        if [ "${UPDATE_MAJOR}" == 1 ]; then
            docker tag ${ORG}/${PRODUCT}:${VERSION} ${ORG}/${PRODUCT}:$(echo ${VERSION} | awk -F"." 'BEGIN { OFS = "." }{ print $1, $2}')
        fi
    fi
}

release_container() {
    if [ "${RELEASE_CONTAINER}" == 0 ]; then
        echo "Docker image will not be released"
        return
    fi

    # Form a list of images to push
    IMAGES_PUSH="${ORG}/${PRODUCT}:${VERSION}"
    if [ "${UPDATE_MAJOR}" == 1 ]; then
        IMAGES_PUSH+=" ${ORG}/${PRODUCT}:$(echo ${VERSION} | awk -F"." 'BEGIN { OFS = "." }{ print $1, $2}')"
    fi

    for image in $IMAGES_PUSH; do
        docker push $image
    done
}

export_container() {
    if [ "${EXPORT_CONTAINER}" == 0 ]; then
        echo "Docker image will not be exported"
        return
    fi

    cd ${WORKDIR}
    docker image save ${ORG}/${PRODUCT}:${VERSION} | gzip -c > ${ORG}-${PRODUCT}-${VERSION}.tar.gz
    if [ $? -eq 0 ]; then
        mkdir $CURDIR/tarball 
        mkdir $WORKDIR/tarball
        cp ${ORG}-${PRODUCT}-${VERSION}.tar.gz $CURDIR/tarball/
        cp ${ORG}-${PRODUCT}-${VERSION}.tar.gz $WORKDIR/tarball/
        rm ${ORG}-${PRODUCT}-${VERSION}.tar.gz
    fi
}

CURDIR=$(pwd)
args=
WORKDIR=
SOURCE=0
PRODUCT=
BUILD_CONTAINER=0
EXPORT_CONTAINER=0
RELEASE_CONTAINER=0
DOCKERFILE=Dockerfile
CLEANUP=0
VERSION=
UPDATE_MAJOR=0
ORG=
INSTALL=0
BRANCH=
REPO="https://github.com/percona/percona-docker.git"
parse_arguments PICK-ARGS-FROM-ARGV "$@"

check_workdir
get_system
install_deps
get_sources
clean_docker
build_container
release_container
export_container
