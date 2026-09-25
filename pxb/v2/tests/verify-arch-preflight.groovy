// Execute each real Jenkinsfile only up to its Declarative pipeline boundary.
// No pipeline closure, worker allocation, shell step or credential binding runs.
class PipelineBoundary extends RuntimeException {}
class RejectedArch extends RuntimeException {
    RejectedArch(String message) { super(message) }
}

def files = new File('pxb/v2/jenkins').listFiles().findAll { it.name.endsWith('.groovy') }.sort { it.name }
files << new File('pxc/jenkins/prepare-pxc-build-docker.groovy')
int failures = 0
int cases = 0
files.each { file ->
    def valid = file.name.contains('-2.4-') ? ['x86_64'] : ['x86_64', 'aarch64']
    ['Hetzner', 'AWS'].each { cloud ->
        [null, '', 'arm64', 'invalid', 'x86_64', 'aarch64'].each { arch ->
            boolean accepted = false
            def bindings = new Binding([
                params: [ARCH: arch, CLOUD: cloud],
                error: { message -> throw new RejectedArch(message.toString()) },
                pipeline: { Closure ignored -> throw new PipelineBoundary() },
            ])
            try {
                new GroovyShell(bindings).evaluate(file)
                throw new AssertionError("${file.name} did not reach a pipeline or reject ARCH")
            } catch (PipelineBoundary ignored) {
                accepted = true
            } catch (RejectedArch rejected) {
                assert rejected.message.contains('ARCH') : rejected.message
            }
            cases++
            if (accepted != (arch in valid)) {
                failures++
                println "FAIL ${file.name}: ARCH=${arch}, CLOUD=${cloud}, accepted=${accepted} before pipeline boundary"
            }
        }
    }
}
println "ARCH preflight: ${cases - failures}/${cases} cases passed across ${files.size()} actual Jenkinsfiles"
assert failures == 0 : "${failures} invalid-ARCH or valid-ARCH behaviors failed"
