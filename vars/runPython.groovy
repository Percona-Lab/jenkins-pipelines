def call(String name) {
    final pythonContent = libraryResource("pmm/${name}.py")
    writeFile(file: "${name}.py", text: pythonContent)
    sh("python3 ${name}.py")
}
