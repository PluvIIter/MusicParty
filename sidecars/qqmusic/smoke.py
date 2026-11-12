import json
import time
import urllib.error
import urllib.request


def verify():
    base = "http://127.0.0.1:8000"
    for attempt in range(30):
        try:
            with urllib.request.urlopen(base + "/health", timeout=2) as response:
                assert json.load(response) == {"protocolVersion": 1}
            break
        except (OSError, urllib.error.URLError):
            if attempt == 29:
                raise
            time.sleep(1)
    for token, expected in [(None, 401), ("wrong-token", 401), ("ci-private-gateway-token-123", 404)]:
        headers = {"Content-Type": "application/json"}
        if token:
            headers["Authorization"] = "Bearer " + token
        request = urllib.request.Request(base + "/v1/not-supported", data=b'{"params":{}}', headers=headers)
        try:
            urllib.request.urlopen(request, timeout=5)
            raise AssertionError("unexpected successful gateway response")
        except urllib.error.HTTPError as error:
            assert error.code == expected
            body = error.read().decode()
            assert "ci-private-gateway-token-123" not in body


if __name__ == "__main__":
    verify()
