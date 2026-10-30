// archiveSbomFiles.groovy — keep every platform's collected SBOM files as build
// artifacts, unpacked to sbom/<platform>/..., so they open straight from the
// build page (package-testing sbom_checks/export.py).
//
// Shared by the PXB and PS molecule jobs. Runs in the package-testing checkout,
// with the virtenv from installMoleculeBookwormMysql.
//
// Usage (in a stage post/always, after runSbomChecks):
//   archiveSbomFiles()

def call() {
    // Storing the files is diagnostics: a failure here marks the stage
    // unstable but must not fail a build whose checks passed.
    catchError(buildResult: 'SUCCESS', stageResult: 'UNSTABLE') {
        sh '''
            . virtenv/bin/activate
            python -m sbom_checks.export --fetched '*_sbom.zip' --out sbom
        '''
        archiveArtifacts artifacts: 'sbom/**', allowEmptyArchive: true
    }
}
