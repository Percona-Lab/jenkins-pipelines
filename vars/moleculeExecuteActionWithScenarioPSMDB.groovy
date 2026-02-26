def call(moleculeDir, action, scenario) {
    writeFile file: 'molecule_ami_env.sh', text: libraryResource('psmdb/molecule_ami_env.sh')
    sh """
        . virtenv/bin/activate
        source molecule_ami_env.sh
        cd ${moleculeDir}
        molecule ${action} -s ${scenario}
    """
}
