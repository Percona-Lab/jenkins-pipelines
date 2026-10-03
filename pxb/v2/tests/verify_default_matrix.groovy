import groovy.json.JsonOutput

// Evaluate the real JJB output, including both product-test targets.
File root = new File('.').canonicalFile
File rendered = java.nio.file.Files.createTempDirectory('pxb-default-matrix-').toFile()
def legacyPlatforms = [
    '8.0': ['centos:8', 'oraclelinux:9', 'ubuntu:focal', 'ubuntu:jammy',
            'ubuntu:noble', 'debian:bullseye', 'debian:bookworm', 'asan'],
    '8.1': ['centos:8', 'oraclelinux:9', 'ubuntu:focal', 'ubuntu:jammy',
            'ubuntu:noble', 'debian:bullseye', 'debian:bookworm', 'asan'],
    '9.x': ['oraclelinux:9', 'ubuntu:jammy', 'ubuntu:noble',
            'debian:bookworm', 'debian:trixie', 'asan'],
]
def additions = [
    '8.0': [['oraclelinux:9', 'aarch64']],
    '8.1': [['oraclelinux:9', 'aarch64']],
    '9.x': [['amazonlinux:2023', 'aarch64'], ['amazonlinux:2023', 'x86_64']],
]

List<Map> combinations(Map axes) {
    List<Map> rows = [[:]]
    axes.each { name, values ->
        rows = rows.collectMany { row -> values.collect { value -> row + [(name): value] } }
    }
    rows
}

Set<String> rowKeys(List<Map> rows) {
    rows.collect { row -> row.sort().collect { key, value -> "${key}=${value}" }.join(',') }.toSet()
}

Set<String> selected(List<Map> rows, String expression) {
    def script = new GroovyShell().parse(expression ?: 'true')
    rowKeys(rows.findAll { row ->
        script.binding = new Binding(row)
        def result = script.run()
        assert result instanceof Boolean: "Filter did not return a boolean: ${expression}"
        result
    })
}

try {
    def process = new ProcessBuilder('jenkins-jobs', 'test',
        new File(root, 'pxb/v2/jenkins').path, '--config-xml', '-o', rendered.path)
        .directory(root).redirectErrorStream(true).start()
    StringBuffer output = new StringBuffer()
    process.consumeProcessOutput(output, output)
    process.waitForOrKill(45000)
    assert process.exitValue() == 0: "JJB rendering failed:\n${output}"
    assert rendered.listFiles().count { new File(it, 'config.xml').isFile() } == 27

    List<String> failures = []
    List<Map> results = []
    legacyPlatforms.each { family, platforms ->
        List<Map> compileRows = combinations([
            DOCKER_OS: platforms, ARCH: ['x86_64'], CMAKE_BUILD_TYPE: ['RelWithDebInfo', 'Debug'],
        ])
        compileRows += additions[family].collect { os, arch ->
            [DOCKER_OS: os, ARCH: arch, CMAKE_BUILD_TYPE: 'RelWithDebInfo']
        }
        def targets = family == '9.x' ? ['innodb9x', 'xtradb9x'] : ['innodb80', 'xtradb80']
        ['compile', 'test'].each { stage ->
            String name = "percona-xtrabackup-${family}-${stage}-param"
            def job = new XmlParser(false, false).parse(new File(rendered, "${name}/config.xml"))
            Map axes = job.axes[0].children().collectEntries { axis ->
                [(axis.'name'.text()): axis.values.string.collect { it.text() }]
            }
            Set<String> names = ['DOCKER_OS', 'ARCH', 'CMAKE_BUILD_TYPE'].toSet()
            if (stage == 'test') names.add('XTRABACKUP_TARGET')
            assert axes.keySet() == names: "Unexpected axes in ${name}: ${axes.keySet()}"
            assert axes.ARCH == ['x86_64', 'aarch64']
            assert axes.CMAKE_BUILD_TYPE == ['RelWithDebInfo', 'Debug']
            assert axes.DOCKER_OS.toSet() == (platforms + (family == '9.x' ? ['amazonlinux:2023'] : [])).toSet()
            if (stage == 'test') assert axes.XTRABACKUP_TARGET == targets

            List<Map> expectedRows = stage == 'compile' ? compileRows : compileRows.collectMany { row ->
                targets.collect { target -> row + [XTRABACKUP_TARGET: target] }
            }
            Set<String> expected = rowKeys(expectedRows)
            List<Map> candidates = combinations(axes)
            String expression = job.combinationFilter.text()
            Set<String> actual = selected(candidates, expression)
            results.add([job: name, candidates: candidates.size(), selected: actual.size(), expected: expected.size()])
            if (actual != expected) {
                failures.add("${name}: missing=${expected - actual}, extra=${actual - expected}")
            }
            // These common regressions must not satisfy the exact-set contract.
            assert selected(candidates, 'true') != expected
            assert selected(candidates, "(${expression ?: 'true'}) || ARCH == 'aarch64'") != expected
            assert selected(candidates, "(${expression ?: 'true'}) && CMAKE_BUILD_TYPE == 'RelWithDebInfo'") != expected
        }
    }
    println JsonOutput.prettyPrint(JsonOutput.toJson([status: failures ? 'FAIL' : 'PASS', jobs: results]))
    assert failures.isEmpty(): failures.join('\n')
} finally {
    assert rendered.deleteDir(): "Could not remove this test's temporary render directory: ${rendered}"
}
