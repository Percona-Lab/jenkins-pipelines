// Package instances removes Compute Engine instances whose configured lifetime
// has expired.
package instances

import (
	"context"
	"log"
	"math"
	"net/http"
	"os"
	"sort"
	"strconv"
	"strings"
	"time"

	"google.golang.org/api/compute/v1"
)

const (
	deleteClusterAfterHoursLabel = "delete-cluster-after-hours"
	deleteAfterHoursLabel        = "delete-after-hours"
	rancherLabel                 = "rancher"
	orphanFirewallGracePeriod    = time.Hour
)

var rancherFirewallSuffixes = []string{
	"allow-ssh",
	"allow-rancher",
	"allow-internal",
}

var createComputeService = func(ctx context.Context) (*compute.Service, error) {
	return compute.NewService(ctx)
}

type rancherInstance struct {
	instance *compute.Instance
	zone     string
}

// CleanInstances deletes instances whose delete-after-hours or legacy
// delete-cluster-after-hours lifetime has expired. Rancher instances are
// grouped by cluster prefix and removed only when every member is expired;
// their firewall rules are removed after every instance deletion request
// succeeds. Instances without a TTL label, with an invalid label, or with an
// invalid creation timestamp are preserved.
func CleanInstances(w http.ResponseWriter, _ *http.Request) {
	ctx := context.Background()
	computeService, err := createComputeService(ctx)
	if err != nil {
		log.Printf("Instance cleanup: create Compute Engine service: %v", err)
		http.Error(w, "Instance cleanup: cannot create Compute Engine service", http.StatusInternalServerError)
		return
	}

	project := os.Getenv("GCP_DEV_PROJECT")
	if project == "" {
		log.Printf("Instance cleanup: GCP_DEV_PROJECT is not set")
		http.Error(w, "Instance cleanup: GCP_DEV_PROJECT is not set", http.StatusInternalServerError)
		return
	}
	dryRun := strings.EqualFold(os.Getenv("DRY_RUN"), "true")
	if dryRun {
		log.Printf("Instance cleanup: DRY_RUN is enabled; no resources will be deleted")
	}

	now := time.Now()
	rancherClusters := make(map[string][]rancherInstance)
	instancePrefixes := make(map[string]bool)
	var genericInstances []rancherInstance
	request := computeService.Instances.AggregatedList(project)
	if err := request.Pages(ctx, func(page *compute.InstanceAggregatedList) error {
		for _, scopedList := range page.Items {
			for _, instance := range scopedList.Instances {
				if isGKENode(instance) {
					continue
				}
				member := rancherInstance{
					instance: instance,
					zone:     resourceName(instance.Zone),
				}
				prefix, hasRancherName := rancherClusterPrefix(instance.Name)
				if hasRancherName {
					instancePrefixes[prefix] = true
				}
				if isRancherInstance(instance) && hasRancherName {
					rancherClusters[prefix] = append(rancherClusters[prefix], member)
					continue
				}
				genericInstances = append(genericInstances, member)
			}
		}
		return nil
	}); err != nil {
		log.Printf("Instance cleanup: list instances: %v", err)
		http.Error(w, "Instance cleanup: cannot list instances", http.StatusInternalServerError)
		return
	}

	cleanupFailed := false
	sort.Slice(genericInstances, func(i, j int) bool {
		return genericInstances[i].instance.Name < genericInstances[j].instance.Name
	})
	for _, member := range genericInstances {
		if !instanceLifetimeExpired(member.instance, now) {
			continue
		}
		if member.zone == "" {
			log.Printf("Instance cleanup: preserving %s because its zone is missing", member.instance.Name)
			continue
		}
		if dryRun {
			log.Printf("Instance cleanup: DRY_RUN would delete instance %s in %s", member.instance.Name, member.zone)
			continue
		}
		if !deleteInstances(ctx, computeService, project, "generic", []rancherInstance{member}) {
			cleanupFailed = true
		}
	}

	prefixes := make([]string, 0, len(rancherClusters))
	for prefix := range rancherClusters {
		prefixes = append(prefixes, prefix)
	}
	sort.Strings(prefixes)

	for _, prefix := range prefixes {
		instances := rancherClusters[prefix]
		if !rancherClusterExpired(instances, now) {
			continue
		}

		if dryRun {
			for _, member := range instances {
				log.Printf("Instance cleanup: DRY_RUN would delete instance %s in %s", member.instance.Name, member.zone)
			}
			deleteRancherFirewalls(ctx, computeService, project, prefix, true)
			continue
		}

		if !deleteInstances(ctx, computeService, project, prefix, instances) {
			log.Printf("Instance cleanup: preserving firewalls for %s because at least one instance deletion failed", prefix)
			cleanupFailed = true
			continue
		}
		if !deleteRancherFirewalls(ctx, computeService, project, prefix, false) {
			cleanupFailed = true
		}
	}

	if !sweepOrphanedRancherFirewalls(ctx, computeService, project, instancePrefixes, now, dryRun) {
		cleanupFailed = true
	}
	if cleanupFailed {
		http.Error(w, "Instance cleanup completed with errors", http.StatusInternalServerError)
		return
	}
	w.WriteHeader(http.StatusOK)
}

// isGKENode explicitly excludes Google Kubernetes Engine nodes. The label is
// the primary signal; the standard gke- name prefix is a defensive fallback.
func isGKENode(instance *compute.Instance) bool {
	_, hasGKENodeLabel := instance.Labels["goog-gke-node"]
	return hasGKENodeLabel || strings.HasPrefix(instance.Name, "gke-")
}

func isRancherInstance(instance *compute.Instance) bool {
	value, ok := instance.Labels[rancherLabel]
	return ok && (value == "" || strings.EqualFold(value, "true"))
}

func rancherClusterExpired(instances []rancherInstance, now time.Time) bool {
	if len(instances) == 0 {
		return false
	}
	for _, member := range instances {
		if member.zone == "" {
			log.Printf("Instance cleanup: preserving cluster because zone is missing for %s", member.instance.Name)
			return false
		}
		if !instanceLifetimeExpired(member.instance, now) {
			return false
		}
	}
	return true
}

func deleteInstances(ctx context.Context, computeService *compute.Service, project, group string, instances []rancherInstance) bool {
	allDeleted := true
	for _, member := range instances {
		operation, err := computeService.Instances.Delete(project, member.zone, member.instance.Name).Context(ctx).Do()
		if err != nil {
			log.Printf("Instance cleanup: cannot delete %s in %s: %v", member.instance.Name, member.zone, err)
			allDeleted = false
			continue
		}
		log.Printf("Instance cleanup: deletion requested for %s in %s; operation=%s status=%s", member.instance.Name, member.zone, operation.Name, operation.Status)
	}
	if !allDeleted {
		log.Printf("Instance cleanup: one or more deletion requests failed for group %s", group)
	}
	return allDeleted
}

func deleteRancherFirewalls(ctx context.Context, computeService *compute.Service, project, prefix string, dryRun bool) bool {
	allDeleted := true
	for _, suffix := range rancherFirewallSuffixes {
		firewallName := prefix + "-" + suffix
		if dryRun {
			log.Printf("Instance cleanup: DRY_RUN would delete firewall %s", firewallName)
			continue
		}
		operation, err := computeService.Firewalls.Delete(project, firewallName).Context(ctx).Do()
		if err != nil {
			log.Printf("Instance cleanup: cannot delete firewall %s after instance deletion: %v", firewallName, err)
			allDeleted = false
			continue
		}

		log.Printf("Instance cleanup: firewall deletion requested for %s; operation=%s status=%s", firewallName, operation.Name, operation.Status)
	}
	return allDeleted
}

// sweepOrphanedRancherFirewalls provides a retry path when all instance
// deletion requests succeeded but a firewall deletion failed. The grace period
// prevents deleting rules while create_rancher.py is between firewall and
// instance creation.
func sweepOrphanedRancherFirewalls(ctx context.Context, computeService *compute.Service, project string, instancePrefixes map[string]bool, now time.Time, dryRun bool) bool {
	sweepSucceeded := true
	request := computeService.Firewalls.List(project)
	if err := request.Pages(ctx, func(page *compute.FirewallList) error {
		for _, firewall := range page.Items {
			prefix, ok := rancherFirewallPrefix(firewall.Name)
			if !ok || !containsString(firewall.TargetTags, prefix) {
				continue
			}
			if instancePrefixes[prefix] {
				continue
			}

			createdAt, err := time.Parse(time.RFC3339, firewall.CreationTimestamp)
			if err != nil || now.Sub(createdAt) <= orphanFirewallGracePeriod {
				continue
			}
			if dryRun {
				log.Printf("Instance cleanup: DRY_RUN would delete orphaned firewall %s", firewall.Name)
				continue
			}

			operation, err := computeService.Firewalls.Delete(project, firewall.Name).Context(ctx).Do()
			if err != nil {
				log.Printf("Instance cleanup: cannot delete orphaned firewall %s: %v", firewall.Name, err)
				sweepSucceeded = false
				continue
			}
			log.Printf("Instance cleanup: orphaned firewall deletion requested for %s; operation=%s status=%s", firewall.Name, operation.Name, operation.Status)
		}
		return nil
	}); err != nil {
		log.Printf("Instance cleanup: list firewall rules for orphan sweep: %v", err)
		return false
	}
	return sweepSucceeded
}

func rancherFirewallPrefix(firewallName string) (string, bool) {
	for _, suffix := range rancherFirewallSuffixes {
		fullSuffix := "-" + suffix
		if prefix := strings.TrimSuffix(firewallName, fullSuffix); prefix != firewallName && prefix != "" {
			return prefix, true
		}
	}
	return "", false
}

func containsString(values []string, expected string) bool {
	for _, value := range values {
		if value == expected {
			return true
		}
	}
	return false
}

func rancherClusterPrefix(instanceName string) (string, bool) {
	if prefix := strings.TrimSuffix(instanceName, "-server"); prefix != instanceName && prefix != "" {
		return prefix, true
	}

	const workerMarker = "-worker-"
	markerIndex := strings.LastIndex(instanceName, workerMarker)
	if markerIndex <= 0 {
		return "", false
	}

	workerNumber := instanceName[markerIndex+len(workerMarker):]
	if workerNumber == "" {
		return "", false
	}
	if _, err := strconv.ParseUint(workerNumber, 10, 64); err != nil {
		return "", false
	}

	return instanceName[:markerIndex], true
}

func instanceLifetimeExpired(instance *compute.Instance, now time.Time) bool {
	ttlValue, ttlLabel, ok := instanceTTL(instance)
	if !ok {
		return false
	}

	ttlHours, err := strconv.ParseFloat(ttlValue, 64)
	if err != nil || math.IsNaN(ttlHours) || math.IsInf(ttlHours, 0) || ttlHours < 0 || ttlHours > float64(math.MaxInt64)/float64(time.Hour) {
		log.Printf("Instance cleanup: preserving %s because label %s=%q is invalid", instance.Name, ttlLabel, ttlValue)
		return false
	}

	createdAt, err := time.Parse(time.RFC3339, instance.CreationTimestamp)
	if err != nil {
		log.Printf("Instance cleanup: preserving %s because creation timestamp %q is invalid: %v", instance.Name, instance.CreationTimestamp, err)
		return false
	}

	return now.Sub(createdAt) > time.Duration(ttlHours*float64(time.Hour))
}

func instanceTTL(instance *compute.Instance) (value, label string, ok bool) {
	if value, ok := instance.Labels[deleteAfterHoursLabel]; ok {
		return value, deleteAfterHoursLabel, true
	}
	if value, ok := instance.Labels[deleteClusterAfterHoursLabel]; ok {
		return value, deleteClusterAfterHoursLabel, true
	}
	return "", "", false
}

func resourceName(resourceURL string) string {
	resourceURL = strings.TrimSuffix(resourceURL, "/")
	if separator := strings.LastIndex(resourceURL, "/"); separator >= 0 {
		return resourceURL[separator+1:]
	}
	return resourceURL
}
