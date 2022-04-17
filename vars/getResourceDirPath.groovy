def call() {
    final pythonContent = libraryResource('pmm/test.py')
    writeFile(file: 'test.py', text: pythonContent)
    sh('python3 ./test.py')
}
