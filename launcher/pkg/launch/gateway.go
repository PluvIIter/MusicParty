package launch

import (
	"context"
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"strings"
	"time"

	"launcher/pkg/process"
)

func inheritedQqVariables(base []string) []string {
	var names []string
	for _, entry := range base {
		key, _, _ := strings.Cut(entry, "=")
		if strings.HasPrefix(strings.ToUpper(key), "QQMUSIC_") {
			names = append(names, key)
		}
	}
	return names
}

var qqWebPaths = []string{"/song/{value}/detail", "/song/{mid}/url", "/recommend/get_guess_recommend", "/search/search_by_type"}

func gatewayProbe(ctx context.Context, client *http.Client, base string) error {
	localClient := *client
	localClient.Jar = nil
	localClient.CheckRedirect = func(*http.Request, []*http.Request) error { return http.ErrUseLastResponse }
	request, err := http.NewRequestWithContext(ctx, http.MethodGet, strings.TrimRight(base, "/")+"/", nil)
	if err != nil {
		return fmt.Errorf("invalid QQ Web API address")
	}
	response, err := localClient.Do(request)
	if err != nil {
		return fmt.Errorf("QQ Web API is unavailable")
	}
	var status struct {
		Code    *int   `json:"code"`
		Message string `json:"msg"`
	}
	err = json.NewDecoder(io.LimitReader(response.Body, 4096)).Decode(&status)
	response.Body.Close()
	if err != nil || response.StatusCode != 200 || status.Code == nil || *status.Code != 0 || status.Message != "ok" {
		return fmt.Errorf("QQ Web API status mismatch")
	}
	request, err = http.NewRequestWithContext(ctx, http.MethodGet, strings.TrimRight(base, "/")+"/openapi.json", nil)
	if err != nil {
		return fmt.Errorf("invalid QQ Web API address")
	}
	response, err = localClient.Do(request)
	if err != nil {
		return fmt.Errorf("QQ Web API identity check failed")
	}
	defer response.Body.Close()
	var schema struct {
		Info struct {
			Title string `json:"title"`
		} `json:"info"`
		Paths map[string]json.RawMessage `json:"paths"`
	}
	if json.NewDecoder(io.LimitReader(response.Body, 2*1024*1024)).Decode(&schema) != nil || response.StatusCode != 200 || schema.Info.Title != "QQMusic API" {
		return fmt.Errorf("QQ Web API identity mismatch")
	}
	for _, path := range qqWebPaths {
		if _, ok := schema.Paths[path]; !ok {
			return fmt.Errorf("QQ Web API required routes are unavailable")
		}
	}
	return nil
}

func waitGateway(ctx context.Context, client *http.Client, base string, handle *process.Handle) error {
	ticker := time.NewTicker(100 * time.Millisecond)
	defer ticker.Stop()
	for {
		if handle != nil {
			select {
			case <-handle.Done:
				return fmt.Errorf("QQ Web API process exited before becoming ready")
			default:
			}
		}
		if err := gatewayProbe(ctx, client, base); err == nil {
			if handle != nil {
				select {
				case <-handle.Done:
					return fmt.Errorf("QQ Web API process exited before becoming ready")
				default:
				}
			}
			if ctx.Err() != nil {
				return fmt.Errorf("QQ Web API readiness canceled")
			}
			return nil
		}
		select {
		case <-ctx.Done():
			return fmt.Errorf("QQ Web API readiness canceled or timed out")
		case <-ticker.C:
		}
	}
}
