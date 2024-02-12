def call(String version = 'default', String newVersion = null) {
    def switchValues = [
        (~/(p.mdb-)?6(\.)?0.*/): ['centos-7', 'debian-10', 'debian-11', 'ubuntu-focal', 'rhel8', 'rhel9', 'ubuntu-jammy', 'rhel8-arm', 'rhel9-arm', 'ubuntu-focal-arm', 'ubuntu-jammy-arm' ],
        (~/(p.mdb-)?7(\.)?0.*/): ['centos-7', 'debian-11', 'debian-12', 'ubuntu-focal', 'rhel8', 'rhel9', 'ubuntu-jammy', 'rhel8-arm', 'rhel9-arm', 'ubuntu-focal-arm', 'ubuntu-jammy-arm' ],
        'default': ['centos-7', 'debian-10', 'debian-11', 'ubuntu-focal', 'rhel8', 'ubuntu-jammy']
    ]

    def versionValues = switchValues.find { key, value -> version ==~ key }?.value ?: switchValues['default']
    def newVersionValues = switchValues.find { key, value -> newVersion ==~ key }?.value ?: versionValues

    return versionValues.intersect(newVersionValues)
}
