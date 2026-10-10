// runSbomChecks.groovy — run package-testing's SBOM checks on this agent, over
// the collections the molecule targets fetched back (*_sbom.zip, written by
// package-testing tasks/check_sbom.yml).
//
// Shared by the PXB and PS molecule jobs so the two cannot drift apart. Runs in
// the package-testing checkout, with the virtenv from installMoleculeBookwormMysql.
//
// Usage (in a stage post/always, before the junit step for sbom-junit.xml):
//   runSbomChecks(product: 'pxb')
//   runSbomChecks(product: 'ps', testFile: 'pytest-tests/test_ps_sbom.py')
//
// Settings default to the job environment, where the jobs set them from their
// parameters: SBOM_EXTERNAL_TOOLS, SBOM_VULN_MODE, SBOM_EXPECTED_PLATFORMS.
// SBOM_CHECK_MODE and SBOM_LICENSE_STRICT reach pytest through the environment.

def call(Map args = [:]) {
    def product = args.product
    if (!product) {
        error('runSbomChecks: product is required (pxb or ps)')
    }
    def testFile = args.get('testFile', "pytest-tests/test_${product}_sbom.py")
    def externalTools = args.get('externalTools', env.SBOM_EXTERNAL_TOOLS).toString() == 'true'
    def vulnMode = args.get('vulnMode', env.SBOM_VULN_MODE ?: 'warn').toString()
    def expectedPlatforms = args.get('expectedPlatforms', env.SBOM_EXPECTED_PLATFORMS ?: '').toString()

    // Tools are installed once, on this agent -- not on every target, where they
    // needed per-AMI workarounds and a 1.4 GB trivy database each. A failed
    // install is caught so the checks below still run and report the missing
    // tool per platform, instead of aborting before any junit exists.
    //
    // One catchError per tool: with a shared one, a failed trivy install skipped
    // the cyclonedx download too, and every platform then failed for both.
    if (externalTools) {
        if (vulnMode != 'off') {
            catchError(buildResult: 'FAILURE', stageResult: 'FAILURE') {
                // 'binary', not the default auto-detect: on a Debian agent that
                // picks the APT path, which sets up a package repo via
                // lsb_release and fails on a minimal image.
                installTrivy(method: 'binary')
                sh 'trivy --version'
            }
        }
        catchError(buildResult: 'FAILURE', stageResult: 'FAILURE') {
            // rm first, and no "|| true": curl -f does not truncate on an HTTP
            // error, so a failed download must not leave an older binary behind.
            // --version proves it starts; the agent may lack ICU, hence the
            // invariant-globalization flag the python wrapper also sets.
            sh '''
                set -e
                ARCH=$(uname -m)
                if [ "$ARCH" = "aarch64" ]; then
                    CDX_ASSET=cyclonedx-linux-arm64
                else
                    CDX_ASSET=cyclonedx-linux-x64
                fi
                rm -rf sbom-tools
                mkdir sbom-tools
                curl -fsSL -o sbom-tools/cyclonedx \
                    https://github.com/CycloneDX/cyclonedx-cli/releases/latest/download/${CDX_ASSET}
                chmod +x sbom-tools/cyclonedx
                DOTNET_SYSTEM_GLOBALIZATION_INVARIANT=1 sbom-tools/cyclonedx --version
            '''
        }
    }

    // A failing check fails the build, but the caller's junit step still
    // publishes. The test file binds the product; SBOM_FETCHED keeps only the
    // collections whose manifest names it.
    catchError(buildResult: 'FAILURE', stageResult: 'FAILURE') {
        sh """
            . virtenv/bin/activate
            export SBOM_FETCHED='*_sbom.zip'
            export SBOM_EXPECTED_PLATFORMS='${expectedPlatforms}'
            export CYCLONEDX_BIN="\$PWD/sbom-tools/cyclonedx"
            export TRIVY_BIN="\$(command -v trivy || echo trivy)"
            python -m pytest -v -p no:cacheprovider ${testFile} --junitxml=sbom-junit.xml
        """
    }
}
