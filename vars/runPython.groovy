def call(String name, String arguments='') {
    final pythonContent = libraryResource("pmm/${name}.py")
    writeFile(file: "${name}.py", text: pythonContent)
    sh("python3 ${name}.py ${arguments}")
}
