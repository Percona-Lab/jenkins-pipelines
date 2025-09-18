def call(String name, String arguments='', String python='python3') {
    final pythonContent = libraryResource("pmm/${name}.py")
    writeFile(file: "${name}.py", text: pythonContent)
    sh("${python} ${name}.py ${arguments}")
}
