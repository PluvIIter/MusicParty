package config

import (
	"encoding/json"
	"fmt"
	"io"
	"strconv"
	"strings"
)

const qqCookieMaximum = 8192

// validateQqCredential accepts both credential shapes:
//   - a raw Cookie header ("musicid=...; musickey=..."), the same way the NetEase source is configured;
//   - the legacy native JSON object kept for existing launcher profiles.
//
// Both are forwarded to the upstream Web service as the Cookie header, so the
// validation only rejects values that cannot be a header value.
func validateQqCredential(value string) error {
	trimmed := strings.TrimSpace(value)
	if trimmed == "" {
		return nil
	}
	if strings.HasPrefix(trimmed, "{") {
		return validateQqCredentialJSON(trimmed)
	}
	return validateQqCredentialCookie(trimmed)
}

func validateQqCredentialCookie(value string) error {
	invalid := fmt.Errorf("QQ Cookie must be a Cookie header without control characters")
	if len(value) > qqCookieMaximum {
		return invalid
	}
	if strings.IndexFunc(value, func(r rune) bool { return r < 32 || r == 127 }) >= 0 {
		return invalid
	}
	return nil
}

func validateQqCredentialJSON(value string) error {
	invalid := fmt.Errorf("QQ Credential must use the native Web API cookie fields")
	if len(value) > 32768 {
		return invalid
	}
	decoder := json.NewDecoder(strings.NewReader(value))
	opening, err := decoder.Token()
	if err != nil || opening != json.Delim('{') {
		return invalid
	}
	credential := make(map[string]json.RawMessage)
	for decoder.More() {
		token, err := decoder.Token()
		key, ok := token.(string)
		if err != nil || !ok {
			return invalid
		}
		if _, exists := credential[key]; exists {
			return invalid
		}
		var raw json.RawMessage
		if decoder.Decode(&raw) != nil {
			return invalid
		}
		credential[key] = raw
	}
	if closing, err := decoder.Token(); err != nil || closing != json.Delim('}') {
		return invalid
	}
	if decoder.Decode(new(any)) != io.EOF {
		return invalid
	}
	if len(credential) == 0 {
		return nil
	}
	for key := range credential {
		switch key {
		case "musicid", "musickey", "str_musicid", "openid", "refresh_token", "access_token", "expired_at", "unionid", "refresh_key":
		default:
			return invalid
		}
	}
	id := string(credential["musicid"])
	if strings.HasPrefix(id, `"`) {
		if json.Unmarshal(credential["musicid"], &id) != nil {
			return invalid
		}
	}
	if len(id) == 0 || len(id) > 20 || id[0] == '0' || strings.IndexFunc(id, func(r rune) bool { return r < '0' || r > '9' }) >= 0 {
		return invalid
	}
	if number, err := strconv.ParseInt(id, 10, 64); err != nil || number <= 0 {
		return invalid
	}
	if !qqCredentialText(credential["musickey"], 4096) {
		return invalid
	}
	for _, key := range []string{"str_musicid", "openid", "refresh_token", "access_token", "unionid", "refresh_key"} {
		if raw, exists := credential[key]; exists {
			var text string
			if json.Unmarshal(raw, &text) != nil || string(raw) == "null" || len(text) > 4096 || strings.IndexFunc(text, func(r rune) bool { return r < 32 || r > 126 }) >= 0 {
				return invalid
			}
		}
	}
	if raw, exists := credential["expired_at"]; exists {
		expires := string(raw)
		if strings.HasPrefix(expires, `"`) {
			if json.Unmarshal(raw, &expires) != nil {
				return invalid
			}
		}
		if len(expires) == 0 || len(expires) > 18 || strings.IndexFunc(expires, func(r rune) bool { return r < '0' || r > '9' }) >= 0 {
			return invalid
		}
	}
	return nil
}

func qqCredentialText(raw json.RawMessage, maximum int) bool {
	var value string
	return json.Unmarshal(raw, &value) == nil && strings.TrimSpace(value) != "" && len(value) <= maximum && strings.IndexFunc(value, func(r rune) bool { return r < 32 || r > 126 }) < 0
}
