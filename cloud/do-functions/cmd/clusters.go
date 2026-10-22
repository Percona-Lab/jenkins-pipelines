package main

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"log"
	"net/url"
	"strconv"
	"strings"
	"time"
)

const (
	ttlTagPrefix          = "delete-cluster-after-hours:"
	defaultCleanupTag     = "cloud-team"
	defaultCleanupProject = "cloud-team"
)

type cluster struct {
	ID        string   `json:"id"`
	Name      string   `json:"name"`
	Tags      []string `json:"tags"`
	CreatedAt string   `json:"created_at"`
}

func cleanClusters(ctx context.Context, client *apiClient, clusters []cluster, projectResourceURNs map[string]struct{}, now time.Time, dryRun bool, cleanupErrors *[]string) ResourceSummary {
	summary := ResourceSummary{Scanned: len(clusters)}
	for _, cluster := range clusters {
		inCleanupScope := isCleanupResource(cluster.Tags, "do:kubernetes:"+cluster.ID, projectResourceURNs)
		shouldDelete, reason, err := shouldDeleteCluster(cluster, inCleanupScope, now)
		if err != nil {
			*cleanupErrors = append(*cleanupErrors, fmt.Sprintf("cluster %s: %v", cluster.Name, err))
			continue
		}
		if !shouldDelete {
			log.Printf("Keeping cluster %s: %s", cluster.Name, reason)
			summary.Skipped = append(summary.Skipped, cluster.Name)
			continue
		}

		summary.Candidates = append(summary.Candidates, cluster.Name)
		if deleteCandidate(ctx, "cluster", cluster.Name, reason, client.deleteClusterDangerously, cluster.ID, dryRun, cleanupErrors) {
			summary.Deleted = append(summary.Deleted, cluster.Name)
		}
	}
	return summary
}

func shouldDeleteCluster(cluster cluster, inCleanupScope bool, now time.Time) (bool, string, error) {
	if hasTag(cluster.Tags, skipDeletionTag) {
		return false, fmt.Sprintf("tag %s is present", skipDeletionTag), nil
	}
	if !inCleanupScope {
		return false, fmt.Sprintf("does not match %s tag or %s project", defaultCleanupTag, defaultCleanupProject), nil
	}

	ttl, found, err := ttlFromTags(cluster.Tags)
	if err != nil {
		return false, "", err
	}
	if !found {
		return true, fmt.Sprintf("missing %s tag and matches %s tag or %s project", strings.TrimSuffix(ttlTagPrefix, ":"), defaultCleanupTag, defaultCleanupProject), nil
	}

	createdAt, err := time.Parse(time.RFC3339, cluster.CreatedAt)
	if err != nil {
		return false, "", fmt.Errorf("parse created_at %q: %w", cluster.CreatedAt, err)
	}
	age := now.Sub(createdAt)
	if age < 0 {
		return false, "", errors.New("created_at is in the future")
	}
	if age >= ttl {
		return true, fmt.Sprintf("age %.1f hours reached TTL %.1f hours", age.Hours(), ttl.Hours()), nil
	}
	return false, fmt.Sprintf("age %.1f hours is below TTL %.1f hours", age.Hours(), ttl.Hours()), nil
}

func ttlFromTags(tags []string) (time.Duration, bool, error) {
	for _, tag := range tags {
		if !strings.HasPrefix(tag, ttlTagPrefix) {
			continue
		}
		rawHours := strings.TrimSpace(strings.TrimPrefix(tag, ttlTagPrefix))
		hours, err := strconv.ParseFloat(rawHours, 64)
		if err != nil || hours <= 0 {
			return 0, true, fmt.Errorf("invalid tag %q: TTL must be a positive number of hours", tag)
		}
		return time.Duration(hours * float64(time.Hour)), true, nil
	}
	return 0, false, nil
}

func (client *apiClient) listClusters(ctx context.Context) ([]cluster, error) {
	var resources []cluster
	err := client.listPages(ctx, "/v2/kubernetes/clusters?per_page=200&page=1", func(body io.Reader) (string, error) {
		var page struct {
			Clusters []cluster `json:"kubernetes_clusters"`
			Links    pageLinks `json:"links"`
		}
		if err := json.NewDecoder(body).Decode(&page); err != nil {
			return "", err
		}
		resources = append(resources, page.Clusters...)
		return page.Links.Pages.Next, nil
	})
	return resources, err
}

func (client *apiClient) deleteClusterDangerously(ctx context.Context, id string) error {
	return client.delete(ctx, "/v2/kubernetes/clusters/"+url.PathEscape(id)+"/destroy_with_associated_resources/dangerous")
}
