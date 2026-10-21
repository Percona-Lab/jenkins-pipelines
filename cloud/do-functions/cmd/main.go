package main

import (
	"context"
	"fmt"
	"log"
	"net/http"
	"os"
	"strconv"
	"strings"
	"time"
)

const (
	defaultAPIURL     = "https://api.digitalocean.com"
	skipDeletionTag   = "skip-cluster-deletion"
	orphanGracePeriod = time.Hour
)

type Event struct{}

type Response struct {
	Body       Result `json:"body"`
	StatusCode string `json:"statusCode"`
}

type Result struct {
	Clusters        ResourceSummary `json:"clusters"`
	LoadBalancers   ResourceSummary `json:"loadBalancers"`
	Volumes         ResourceSummary `json:"volumes"`
	VolumeSnapshots ResourceSummary `json:"volumeSnapshots"`
	Errors          []string        `json:"errors,omitempty"`
	DryRun          bool            `json:"dryRun"`
}

type ResourceSummary struct {
	Scanned    int      `json:"scanned"`
	Candidates []string `json:"candidates"`
	Deleted    []string `json:"deleted"`
	Skipped    []string `json:"skipped"`
}

func Main(ctx context.Context, _ Event) Response {
	token := os.Getenv("DIGITALOCEAN_ACCESS_TOKEN")
	if token == "" {
		token = os.Getenv("DIGITALOCEAN_TOKEN")
	}
	if token == "" {
		return errorResponse("DIGITALOCEAN_ACCESS_TOKEN or DIGITALOCEAN_TOKEN is not set")
	}

	dryRun, err := strconv.ParseBool(envOrDefault("DRY_RUN", "false"))
	if err != nil {
		return errorResponse("DRY_RUN must be true or false")
	}

	client := &apiClient{
		baseURL:    strings.TrimRight(envOrDefault("DIGITALOCEAN_API_URL", defaultAPIURL), "/"),
		token:      token,
		httpClient: http.DefaultClient,
	}
	result := cleanup(ctx, client, time.Now().UTC(), dryRun)

	statusCode := "200"
	if len(result.Errors) > 0 {
		statusCode = "500"
	}
	return Response{Body: result, StatusCode: statusCode}
}

func cleanup(ctx context.Context, client *apiClient, now time.Time, dryRun bool) Result {
	result := Result{DryRun: dryRun}
	projectClusterIDs, err := client.listProjectClusterIDs(ctx, defaultCleanupProject)
	if err != nil {
		result.Errors = append(result.Errors, fmt.Sprintf("list clusters from project %s: %v", defaultCleanupProject, err))
	}

	clusters, err := client.listClusters(ctx)
	if err != nil {
		result.Errors = append(result.Errors, fmt.Sprintf("list clusters: %v", err))
	} else {
		result.Clusters = cleanClusters(ctx, client, clusters, projectClusterIDs, now, dryRun, &result.Errors)
	}

	loadBalancers, err := client.listLoadBalancers(ctx)
	if err != nil {
		result.Errors = append(result.Errors, fmt.Sprintf("list load balancers: %v", err))
	} else {
		result.LoadBalancers = cleanLoadBalancers(ctx, client, loadBalancers, now, dryRun, &result.Errors)
	}

	volumes, err := client.listVolumes(ctx)
	if err != nil {
		result.Errors = append(result.Errors, fmt.Sprintf("list volumes: %v", err))
	} else {
		result.Volumes = cleanVolumes(ctx, client, volumes, now, dryRun, &result.Errors)

		snapshots, err := client.listVolumeSnapshots(ctx)
		if err != nil {
			result.Errors = append(result.Errors, fmt.Sprintf("list volume snapshots: %v", err))
		} else {
			result.VolumeSnapshots = cleanVolumeSnapshots(ctx, client, snapshots, volumes, now, dryRun, &result.Errors)
		}
	}

	for _, err := range result.Errors {
		log.Print(err)
	}
	return result
}

func deleteCandidate(ctx context.Context, resourceType, name, reason string, deleteFn func(context.Context, string) error, id string, dryRun bool, cleanupErrors *[]string) bool {
	if dryRun {
		log.Printf("[DRY-RUN] Would delete %s %s: %s", resourceType, name, reason)
		return false
	}

	log.Printf("Deleting %s %s: %s", resourceType, name, reason)
	if err := deleteFn(ctx, id); err != nil {
		*cleanupErrors = append(*cleanupErrors, fmt.Sprintf("delete %s %s: %v", resourceType, name, err))
		return false
	}
	return true
}

func hasTag(tags []string, expected string) bool {
	for _, tag := range tags {
		if tag == expected {
			return true
		}
	}
	return false
}

func olderThan(createdAt string, now time.Time, minimumAge time.Duration) bool {
	created, err := time.Parse(time.RFC3339, createdAt)
	return err == nil && now.Sub(created) >= minimumAge
}

func errorResponse(message string) Response {
	return Response{Body: Result{Errors: []string{message}}, StatusCode: "500"}
}

func envOrDefault(name, fallback string) string {
	if value := os.Getenv(name); value != "" {
		return value
	}
	return fallback
}
