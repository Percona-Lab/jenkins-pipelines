package main

import (
	"context"
	"encoding/json"
	"fmt"
	"io"
	"net/url"
	"strings"
	"unicode"
)

type project struct {
	ID   string `json:"id"`
	Name string `json:"name"`
}

type projectResource struct {
	URN string `json:"urn"`
}

func (client *apiClient) listProjectClusterIDs(ctx context.Context, projectName string) (map[string]struct{}, error) {
	projects, err := client.listProjects(ctx)
	if err != nil {
		return nil, err
	}

	var projectID string
	for _, project := range projects {
		if normalizeProjectName(project.Name) == normalizeProjectName(projectName) {
			projectID = project.ID
			break
		}
	}
	if projectID == "" {
		return nil, fmt.Errorf("project %q not found", projectName)
	}

	resources, err := client.listProjectResources(ctx, projectID)
	if err != nil {
		return nil, err
	}

	const clusterURNPrefix = "do:kubernetes:"
	clusterIDs := make(map[string]struct{})
	for _, resource := range resources {
		if strings.HasPrefix(resource.URN, clusterURNPrefix) {
			clusterIDs[strings.TrimPrefix(resource.URN, clusterURNPrefix)] = struct{}{}
		}
	}
	return clusterIDs, nil
}

func (client *apiClient) listProjects(ctx context.Context) ([]project, error) {
	var resources []project
	err := client.listPages(ctx, "/v2/projects?per_page=200&page=1", func(body io.Reader) (string, error) {
		var page struct {
			Projects []project `json:"projects"`
			Links    pageLinks `json:"links"`
		}
		if err := json.NewDecoder(body).Decode(&page); err != nil {
			return "", err
		}
		resources = append(resources, page.Projects...)
		return page.Links.Pages.Next, nil
	})
	return resources, err
}

func (client *apiClient) listProjectResources(ctx context.Context, projectID string) ([]projectResource, error) {
	var resources []projectResource
	path := "/v2/projects/" + url.PathEscape(projectID) + "/resources?per_page=200&page=1"
	err := client.listPages(ctx, path, func(body io.Reader) (string, error) {
		var page struct {
			Resources []projectResource `json:"resources"`
			Links     pageLinks         `json:"links"`
		}
		if err := json.NewDecoder(body).Decode(&page); err != nil {
			return "", err
		}
		resources = append(resources, page.Resources...)
		return page.Links.Pages.Next, nil
	})
	return resources, err
}

func normalizeProjectName(name string) string {
	return strings.Map(func(r rune) rune {
		if unicode.IsLetter(r) || unicode.IsDigit(r) {
			return unicode.ToLower(r)
		}
		return -1
	}, name)
}
