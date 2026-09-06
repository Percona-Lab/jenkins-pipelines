import org.codehaus.groovy.ast.CodeVisitorSupport
import org.codehaus.groovy.ast.builder.AstBuilder
import org.codehaus.groovy.ast.expr.MethodCallExpression
import org.codehaus.groovy.ast.expr.VariableExpression
import org.codehaus.groovy.ast.stmt.BlockStatement
import org.codehaus.groovy.control.CompilePhase

// Inspect the real pipeline's nested stage closures without executing them.
class PxcSourceContract extends CodeVisitorSupport {
    int scmCheckouts = 0
    List<Integer> gitLines = []

    @Override
    void visitMethodCallExpression(MethodCallExpression call) {
        if (call.methodAsString == 'checkout') {
            def args = call.arguments.expressions
            if (args.size() == 1 && args[0] instanceof VariableExpression && args[0].name == 'scm') {
                scmCheckouts++
            }
        }
        if (call.methodAsString == 'git') gitLines << call.lineNumber
        super.visitMethodCallExpression(call)
    }
}

def file = new File(args ? args[0] : 'pxc/jenkins/prepare-pxc-build-docker.groovy')
def visitor = new PxcSourceContract()
new AstBuilder().buildFromString(CompilePhase.CONVERSION, false, file.text)
    .findAll { it instanceof BlockStatement }.each { it.visit(visitor) }
assert visitor.scmCheckouts == 1 : "Expected one checkout scm, found ${visitor.scmCheckouts}"
assert visitor.gitLines.empty : "Git steps bypass selected SCM at lines ${visitor.gitLines}"
println 'PXC preparation: selected SCM checkout verified in actual pipeline source'
