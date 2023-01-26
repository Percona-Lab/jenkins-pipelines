def call(version = 'default') {
    switch(version) {
        case ~/6\.0\.*/: return ['centos-7', 'debian-10', 'debian-11', 'ubuntu-focal', 'ubuntu-bionic', 'rhel8', 'rhel9', 'ubuntu-jammy']
        default:         return ['centos-7', 'debian-10', 'debian-11', 'ubuntu-focal', 'ubuntu-bionic', 'rhel8', 'ubuntu-jammy']
    }
}
