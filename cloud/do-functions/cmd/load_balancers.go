package main

import (
	"context"
	"encoding/json"
	"io"
	"net/url"
)

type loadBalancer struct {
	ID         string `json:"id"`
	Name       string `json:"name"`
	Type       string `json:"type"`
	Tag        string `json:"tag"`
	DropletIDs []int  `json:"droplet_ids"`
	CreatedAt  string `json:"created_at"`
}

func cleanLoadBalancers(ctx context.Context, client *apiClient, loadBalancers []loadBalancer, projectResourceURNs map[string]struct{}, dryRun bool, cleanupErrors *[]string) ResourceSummary {
	summary := ResourceSummary{Scanned: len(loadBalancers)}
	for _, loadBalancer := range loadBalancers {
		if !isCleanupResource([]string{loadBalancer.Tag}, "do:loadbalancer:"+loadBalancer.ID, projectResourceURNs) || loadBalancer.Type == "GLOBAL" || len(loadBalancer.DropletIDs) > 0 || loadBalancer.Tag != "" {
			summary.Skipped = append(summary.Skipped, loadBalancer.Name)
			continue
		}

		summary.Candidates = append(summary.Candidates, loadBalancer.Name)
		if deleteCandidate(ctx, "load balancer", loadBalancer.Name, "no backend Droplets", client.deleteLoadBalancer, loadBalancer.ID, dryRun, cleanupErrors) {
			summary.Deleted = append(summary.Deleted, loadBalancer.Name)
		}
	}
	return summary
}

func (client *apiClient) listLoadBalancers(ctx context.Context) ([]loadBalancer, error) {
	var resources []loadBalancer
	err := client.listPages(ctx, "/v2/load_balancers?per_page=200&page=1", func(body io.Reader) (string, error) {
		var page struct {
			LoadBalancers []loadBalancer `json:"load_balancers"`
			Links         pageLinks      `json:"links"`
		}
		if err := json.NewDecoder(body).Decode(&page); err != nil {
			return "", err
		}
		resources = append(resources, page.LoadBalancers...)
		return page.Links.Pages.Next, nil
	})
	return resources, err
}

func (client *apiClient) deleteLoadBalancer(ctx context.Context, id string) error {
	return client.delete(ctx, "/v2/load_balancers/"+url.PathEscape(id))
}
