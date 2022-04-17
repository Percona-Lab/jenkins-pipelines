import groovy.transform.SourceURI
import java.nio.file.Path
import java.nio.file.Paths

class ScriptSourceUri {
    @SourceURI
    static URI uri
}

def call() {
    Path resourceLocation = Paths.get(ScriptSourceUri.uri)
    return resourceLocation.getParent().getParent().toString()
}
