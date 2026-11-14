package config

import (
	"fmt"
	"strings"
)

const qqCookieMaximum = 8192

// validateQqCredential keeps the QQ credential a plain Cookie header, the same
// shape the NetEase source uses. The upstream Web layer reads musicid/musickey
// from it and ignores anything else, so this only rejects values that cannot be
// forwarded as a header.
func validateQqCredential(value string) error {
	trimmed := strings.TrimSpace(value)
	if trimmed == "" {
		return nil
	}
	return validateQqCookie(trimmed)
}

func validateQqCookie(value string) error {
	if strings.HasPrefix(value, "{") {
		return fmt.Errorf("QQ Cookie must be a Cookie header; the legacy JSON credential is no longer supported")
	}
	invalid := fmt.Errorf("QQ Cookie must be a Cookie header without control characters")
	if len(value) > qqCookieMaximum {
		return invalid
	}
	if strings.IndexFunc(value, func(r rune) bool { return r < 32 || r == 127 }) >= 0 {
		return invalid
	}
	return nil
}
