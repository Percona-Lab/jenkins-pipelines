package orphanedResources

import (
	"context"
	"fmt"
	"log"
	"net/http"
	"os"
	"strings"

	"google.golang.org/api/compute/v1"
)

func CleanOrphanedResources(w http.ResponseWriter, r *http.Request) {

	ctx := context.Background()
	computeService, err := compute.NewService(ctx)
	if err != nil {
		log.Fatal("Error: create service", err)
	}

	project := os.Getenv("GCP_DEV_PROJECT")
	targetPoolAggregatedList, err := computeService.TargetPools.AggregatedList(project).Do()

	if err != nil {
		log.Fatal("Error: get targetPoolAggregatedList", err)
	}

	toDelete := false
	for _, targetPoolList := range targetPoolAggregatedList.Items {
		for _, targetPoolItem := range targetPoolList.TargetPools {
			region := strings.Split(targetPoolItem.Region, "/")[8]

			if len(targetPoolItem.Instances) > 0 {
				instanceName := strings.Split(targetPoolItem.Instances[0], "/")[10]
				zone := strings.Split(targetPoolItem.Instances[0], "/")[8]
				_, err := computeService.Instances.Get(project, zone, instanceName).Context(ctx).Do()
				if err != nil && strings.Contains(err.Error(), "404") {
					toDelete = true
				}
			}

			if len(targetPoolItem.Instances) == 0 || toDelete == true {

				// Firewall-rule deleting
				firewallRuleId := fmt.Sprintf("k8s-fw-%s", targetPoolItem.Name)

				respFirewall, err := computeService.Firewalls.Delete(project, firewallRuleId).Context(ctx).Do()
				if err != nil {
					log.Printf("We can't delete firewallRuleId: %v", err)
				} else {
					log.Printf("firewall-rule deleted: k8s-fw- %s was deleted with status %s\n", targetPoolItem.Name, respFirewall.Status)
				}

				// Forwarding-rule deleting
				respFWRule, err := computeService.ForwardingRules.Delete(project, region, targetPoolItem.Name).Context(ctx).Do()
				if err != nil {
					log.Printf("We can't delete Forwarding-rule: %v", err)
				} else {
					log.Printf("forwarding-rule deleted: %s was deleted with status %s\n", targetPoolItem.Name, respFWRule.Status)
				}

				// Address deleting
				respAdd, err := computeService.Addresses.Delete(project, region, targetPoolItem.Name).Context(ctx).Do()
				if err != nil {
					log.Printf("We can't delete Address: %v", err)
				} else {
					log.Printf("address deleted: %s was deleted with status %s\n", targetPoolItem.Name, respAdd.Status)
				}

				// Target pool deleting
				respTP, err := computeService.TargetPools.Delete(project, region, targetPoolItem.Name).Context(ctx).Do()
				if err != nil {
					log.Printf("We can't delete target pool: %v", err)
				} else {
					log.Printf("Target-pool deleted: %s was deleted with status %s\n", targetPoolItem.Name, respTP.Status)
				}
			}
		}
	}
}
