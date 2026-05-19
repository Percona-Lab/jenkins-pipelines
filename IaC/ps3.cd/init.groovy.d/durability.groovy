/**
 * Enforce MAX_SURVIVABILITY as the global pipeline durability default.
 *
 * Closes the failure class where an abrupt JVM stop (kill -9, AWS spot
 * interrupt) loses in-flight Workflow build state. Under MAX_SURVIVABILITY
 * the pipeline persists FlowNode state to disk on every step, so a build
 * resumes at the last step boundary on restart.
 *
 * PS-11173 Phase 2 (ps3 canary resilience plan).
 *
 * Idempotent: only writes if the current hint differs.
 */
import org.jenkinsci.plugins.workflow.flow.FlowDurabilityHint
import jenkins.model.Jenkins

def descCls = Class.forName('org.jenkinsci.plugins.workflow.flow.GlobalDefaultFlowDurabilityLevel$DescriptorImpl')
def descriptors = Jenkins.instance.getExtensionList(descCls)
if (descriptors.isEmpty()) {
    println "durability.groovy: GlobalDefaultFlowDurabilityLevel descriptor missing (workflow-api plugin not installed?)"
    return
}

def d = descriptors[0]
def current = d.durabilityHint
def target = FlowDurabilityHint.MAX_SURVIVABILITY

if (current != target) {
    d.setDurabilityHint(target)
    d.save()
    println "durability.groovy: GlobalDefaultFlowDurabilityLevel ${current} -> ${target}"
} else {
    println "durability.groovy: GlobalDefaultFlowDurabilityLevel already ${target}"
}
