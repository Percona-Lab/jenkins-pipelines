def call(moleculeDir, action, scenario, varName, varValue) {
    writeFile file: 'molecule_ami_env.sh', text: libraryResource('psmdb/molecule_ami_env.sh')
    sh """
        . virtenv/bin/activate
        source molecule_ami_env.sh
        cd ${moleculeDir}
        ${varName}=${varValue} molecule ${action} -s ${scenario}
    """
}
