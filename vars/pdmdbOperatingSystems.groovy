def call(String version = 'default', String newVersion = null, String gatedBuild = 'false') {
    Boolean isGatedBuild = gatedBuild.toLowerCase() == 'true'

    def switchValues = [
        (~/(p.mdb-)?4(\.)?0.*/):  ['centos-7', 'debian-10', 'debian-11', 'ubuntu-focal', 'rhel8', 'ubuntu-jammy'],
        (~/(p.mdb-)?5(\.)?0.*/):  ['debian-11', 'ubuntu-focal', 'rhel8', 'ubuntu-jammy','ubuntu-noble'],
        (~/(p.mdb-)?6(\.)?0.*/): ['debian-11', 'ubuntu-focal', 'rhel8', 'rhel9', 'ubuntu-jammy', 'ubuntu-noble', 'rhel8-arm', 'rhel9-arm', 'ubuntu-focal-arm', 'ubuntu-jammy-arm','ubuntu-noble-arm', 'al2023', 'al2023-arm' ],
        (~/(p.mdb-)?7(\.)?0.*/): ['debian-11', 'debian-12', 'ubuntu-focal', 'rhel8', 'rhel9', 'ubuntu-jammy', 'ubuntu-noble', 'rhel8-arm', 'rhel9-arm', 'ubuntu-focal-arm', 'ubuntu-jammy-arm', 'ubuntu-noble-arm', 'al2023', 'al2023-arm' ],
        (~/(p.mdb-)?8(\.)?0.*/): ['debian-12', 'rhel8', 'rhel8-arm', 'rhel9', 'rhel9-arm', 'ubuntu-focal','ubuntu-focal-arm', 'ubuntu-jammy', 'ubuntu-jammy-arm', 'ubuntu-noble', 'ubuntu-noble-arm', 'al2023', 'al2023-arm'],
        'default': ['centos-7', 'debian-10', 'debian-11', 'ubuntu-focal', 'rhel8', 'ubuntu-jammy']
    ]

    def versionValues = switchValues.find { key, value -> version ==~ key }?.value ?: switchValues['default']
    def newVersionValues = switchValues.find { key, value -> newVersion ==~ key }?.value ?: versionValues

    return versionValues.intersect(newVersionValues)
}
