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

func gatewayProbe(ctx context.Context, client *http.Client, base, token string) error {
	request, err := http.NewRequestWithContext(ctx, http.MethodGet, strings.TrimRight(base, "/")+"/health", nil)
	if err != nil {
		return fmt.Errorf("invalid QQ gateway address")
	}
	response, err := client.Do(request)
	if err != nil {
		return fmt.Errorf("QQ gateway is unavailable")
	}
	var health struct {
		ProtocolVersion int `json:"protocolVersion"`
	}
	err = json.NewDecoder(io.LimitReader(response.Body, 4096)).Decode(&health)
	response.Body.Close()
	if err != nil || response.StatusCode != 200 || health.ProtocolVersion != 1 {
		return fmt.Errorf("QQ gateway protocol mismatch")
	}
	request, err = http.NewRequestWithContext(ctx, http.MethodPost, strings.TrimRight(base, "/")+"/v1/launcher-identity-probe", strings.NewReader(`{"params":{}}`))
	if err != nil {
		return fmt.Errorf("invalid QQ gateway address")
	}
	request.Header.Set("Authorization", "Bearer "+token)
	request.Header.Set("Content-Type", "application/json")
	response, err = client.Do(request)
	if err != nil {
		return fmt.Errorf("QQ gateway identity check failed")
	}
	defer response.Body.Close()
	var body struct {
		Error struct {
			Code string `json:"code"`
		} `json:"error"`
	}
	if json.NewDecoder(io.LimitReader(response.Body, 4096)).Decode(&body) != nil || response.StatusCode != 404 || body.Error.Code != "CAPABILITY_UNAVAILABLE" {
		return fmt.Errorf("QQ gateway authentication or identity check failed")
	}
	return nil
}

func waitGateway(ctx context.Context, client *http.Client, base, token string, handle *process.Handle) error {
	ticker := time.NewTicker(100 * time.Millisecond)
	defer ticker.Stop()
	for {
		if handle != nil {
			select {
			case <-handle.Done:
				return fmt.Errorf("QQ gateway process exited before becoming ready")
			default:
			}
		}
		if err := gatewayProbe(ctx, client, base, token); err == nil {
			if handle != nil {
				select {
				case <-handle.Done:
					return fmt.Errorf("QQ gateway process exited before becoming ready")
				default:
				}
			}
			return nil
		}
		select {
		case <-ctx.Done():
			return fmt.Errorf("QQ gateway readiness canceled or timed out")
		case <-ticker.C:
		}
	}
}
