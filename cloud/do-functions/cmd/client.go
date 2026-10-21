package main

import (
	"context"
	"fmt"
	"io"
	"net/http"
	"strings"
)

type pageLinks struct {
	Pages struct {
		Next string `json:"next"`
	} `json:"pages"`
}

type apiClient struct {
	baseURL    string
	token      string
	httpClient *http.Client
}

func (client *apiClient) listPages(ctx context.Context, path string, decode func(io.Reader) (string, error)) error {
	next := client.baseURL + path
	for next != "" {
		request, err := client.request(ctx, http.MethodGet, next)
		if err != nil {
			return err
		}
		resp, err := client.httpClient.Do(request)
		if err != nil {
			return err
		}
		if resp.StatusCode != http.StatusOK {
			err := responseError(resp)
			resp.Body.Close()
			return err
		}
		next, err = decode(resp.Body)
		resp.Body.Close()
		if err != nil {
			return err
		}
	}
	return nil
}

func (client *apiClient) delete(ctx context.Context, path string) error {
	request, err := client.request(ctx, http.MethodDelete, client.baseURL+path)
	if err != nil {
		return err
	}
	resp, err := client.httpClient.Do(request)
	if err != nil {
		return err
	}
	defer resp.Body.Close()
	if resp.StatusCode != http.StatusNoContent {
		return responseError(resp)
	}
	return nil
}

func (client *apiClient) request(ctx context.Context, method, endpoint string) (*http.Request, error) {
	request, err := http.NewRequestWithContext(ctx, method, endpoint, nil)
	if err != nil {
		return nil, err
	}
	request.Header.Set("Authorization", "Bearer "+client.token)
	request.Header.Set("Accept", "application/json")
	return request, nil
}

func responseError(resp *http.Response) error {
	body, _ := io.ReadAll(io.LimitReader(resp.Body, 4096))
	return fmt.Errorf("DigitalOcean API returned %s: %s", resp.Status, strings.TrimSpace(string(body)))
}
