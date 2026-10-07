// Package instances removes Compute Engine instances whose configured lifetime
// has expired.
package instances

import (
	"context"
	"log"
	"math"
	"net/http"
	"os"
	"strconv"
	"strings"
	"time"

	"google.golang.org/api/compute/v1"
)

const deleteAfterHoursLabel = "delete-cluster-after-hours"

var rancherFirewallSuffixes = []string{
	"allow-ssh",
	"allow-rancher",
	"allow-internal",
}

// CleanInstances deletes only instances that have a valid
// delete-cluster-after-hours label and are older than the configured lifetime.
// Before deleting an expired Rancher instance, it attempts to delete the three
// global firewall rules derived from the cluster prefix. Instances without the
// label, with an invalid label, or with an invalid creation timestamp are
// preserved.
func CleanInstances(http.ResponseWriter, *http.Request) {
	ctx := context.Background()
	computeService, err := compute.NewService(ctx)
	if err != nil {
		log.Printf("Instance cleanup: create Compute Engine service: %v", err)
		return
	}

	project := os.Getenv("GCP_DEV_PROJECT")
	if project == "" {
		log.Printf("Instance cleanup: GCP_DEV_PROJECT is not set")
		return
	}
	dryRun := strings.EqualFold(os.Getenv("DRY_RUN"), "true")
	if dryRun {
		log.Printf("Instance cleanup: DRY_RUN is enabled; no resources will be deleted")
	}

	deletedFirewallPrefixes := make(map[string]bool)
	request := computeService.Instances.AggregatedList(project)
	if err := request.Pages(ctx, func(page *compute.InstanceAggregatedList) error {
		for _, scopedList := range page.Items {
			for _, instance := range scopedList.Instances {
				if isGKENode(instance) {
					continue
				}
				prefix, isRancherInstance := rancherClusterPrefix(instance.Name)
				if !isRancherInstance {
					continue
				}
				if !instanceLifetimeExpired(instance, time.Now()) {
					continue
				}

				zone := resourceName(instance.Zone)
				if zone == "" {
					log.Printf("Instance cleanup: cannot determine zone for %s", instance.Name)
					continue
				}

				if !deletedFirewallPrefixes[prefix] {
					deleteRancherFirewalls(ctx, computeService, project, prefix, dryRun)
					deletedFirewallPrefixes[prefix] = true
				}
				if dryRun {
					log.Printf("Instance cleanup: DRY_RUN would delete instance %s in %s", instance.Name, zone)
					continue
				}

				operation, err := computeService.Instances.Delete(project, zone, instance.Name).Context(ctx).Do()
				if err != nil {
					log.Printf("Instance cleanup: cannot delete %s in %s: %v", instance.Name, zone, err)
					continue
				}

				log.Printf("Instance cleanup: deletion requested for %s in %s; operation=%s status=%s", instance.Name, zone, operation.Name, operation.Status)
			}
		}
		return nil
	}); err != nil {
		log.Printf("Instance cleanup: list instances: %v", err)
	}
}

// isGKENode explicitly excludes Google Kubernetes Engine nodes. The label is
// the primary signal; the standard gke- name prefix is a defensive fallback.
func isGKENode(instance *compute.Instance) bool {
	_, hasGKENodeLabel := instance.Labels["goog-gke-node"]
	return hasGKENodeLabel || strings.HasPrefix(instance.Name, "gke-")
}

func deleteRancherFirewalls(ctx context.Context, computeService *compute.Service, project, prefix string, dryRun bool) {
	for _, suffix := range rancherFirewallSuffixes {
		firewallName := prefix + "-" + suffix
		if dryRun {
			log.Printf("Instance cleanup: DRY_RUN would delete firewall %s", firewallName)
			continue
		}
		operation, err := computeService.Firewalls.Delete(project, firewallName).Context(ctx).Do()
		if err != nil {
			log.Printf("Instance cleanup: cannot delete firewall %s before instance deletion: %v", firewallName, err)
			continue
		}

		log.Printf("Instance cleanup: firewall deletion requested for %s; operation=%s status=%s", firewallName, operation.Name, operation.Status)
	}
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
	ttlValue, ok := instance.Labels[deleteAfterHoursLabel]
	if !ok {
		return false
	}

	ttlHours, err := strconv.ParseFloat(ttlValue, 64)
	if err != nil || math.IsNaN(ttlHours) || math.IsInf(ttlHours, 0) || ttlHours < 0 || ttlHours > float64(math.MaxInt64)/float64(time.Hour) {
		log.Printf("Instance cleanup: preserving %s because label %s=%q is invalid", instance.Name, deleteAfterHoursLabel, ttlValue)
		return false
	}

	createdAt, err := time.Parse(time.RFC3339, instance.CreationTimestamp)
	if err != nil {
		log.Printf("Instance cleanup: preserving %s because creation timestamp %q is invalid: %v", instance.Name, instance.CreationTimestamp, err)
		return false
	}

	return now.Sub(createdAt) > time.Duration(ttlHours*float64(time.Hour))
}

func resourceName(resourceURL string) string {
	resourceURL = strings.TrimSuffix(resourceURL, "/")
	if separator := strings.LastIndex(resourceURL, "/"); separator >= 0 {
		return resourceURL[separator+1:]
	}
	return resourceURL
}
