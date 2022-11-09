package clusters

import (
        "log"
        "fmt"
        "net/http"
        "context"
        "os"
        "time"
        "strconv"
        "math"

        "google.golang.org/api/container/v1"
)

func CleanClusters(w http.ResponseWriter, r *http.Request) {

    ctx := context.Background()

    containerService, err := container.NewService(ctx)
    if err != nil {
        log.Fatal("Error: create containerService", err)
    }

    project := os.Getenv("GCP_DEV_PROJECT")

    uri := fmt.Sprintf("projects/%s/locations/-", project)
    currentTime := time.Now()

    clusters, err := containerService.Projects.Locations.Clusters.List(uri).Context(ctx).Do()
    if err != nil {
        log.Printf("Getting clusters failed with error: %v", err)
    }

    for _, cluster := range clusters.Clusters {

        creationTime, err := time.Parse(time.RFC3339, cluster.CreateTime)
        if err != nil {
            log.Printf("Error getting cluster creation time : %v", err)
            continue
        }

        deleteClusterAfterHours, ok := cluster.ResourceLabels["delete-cluster-after-hours"]
        if !ok {
            continue
        }

        deleteClusterAfterHoursInt, err := strconv.ParseInt(deleteClusterAfterHours, 10, 64)
        if err != nil {
            log.Printf("Parse label value: %v", err)
            continue
        }

        if int64(math.Round(currentTime.Sub(creationTime).Hours())) > deleteClusterAfterHoursInt {

            clusterUri := fmt.Sprintf("projects/%s/locations/%s/clusters/%s", project, cluster.Location, cluster.Name)

            resp, err := containerService.Projects.Locations.Clusters.Delete(clusterUri).Context(ctx).Do()
            if err != nil {
                log.Printf("Cluster deletion error: %v", err)
                continue
            }
            log.Printf("Cluster: %s was deleted with status %s\n", cluster.Name, resp.Status)
        }
    }
}
