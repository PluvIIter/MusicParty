# QQMusic native Web packaging and integration tests

This directory packages and tests the native Web application from [L1124/QQMusicApi](https://github.com/l-1124/QQMusicApi/tree/c046788dd1f11daacc74da487cbbf1bcd8ac6f33/web), pinned at `c046788dd1f11daacc74da487cbbf1bcd8ac6f33`. It does not modify upstream source, authentication, caching, routing, responses or startup behavior. There is no production wrapper or runtime monkeypatch.

Upstream `AuthPolicy.NONE` routes ignore request Cookies and retain their public shared cache. `OPTIONAL` routes use valid caller Cookies when supplied; strict routes retain upstream credential requirements. Native account pools and configuration remain available for independent deployments. Forwarding a Cookie does not make an anonymous route authenticated or bypass upstream permissions.

## Independent native deployment

Use the upstream entry point and configuration directly:

```sh
git clone https://github.com/l-1124/QQMusicApi.git qqmusic-upstream
cd qqmusic-upstream
git checkout c046788dd1f11daacc74da487cbbf1bcd8ac6f33
uv sync --group web --frozen
uv run python web/run.py
```

See [upstream Web documentation](https://github.com/l-1124/QQMusicApi/tree/c046788dd1f11daacc74da487cbbf1bcd8ac6f33/web) for account seeds, credential pools, caching and server settings. This integration adds no restrictions on configuring them. Keep the service on a trusted network: native login and other upstream routes remain exposed.

### Docker

Build the upstream Dockerfile with the pinned repository as its build context:

```sh
docker build -f web/Dockerfile -t musicparty-qqmusic \
  'https://github.com/l-1124/QQMusicApi.git#c046788dd1f11daacc74da487cbbf1bcd8ac6f33'
docker run --rm -p 127.0.0.1:8080:8080 musicparty-qqmusic
```

The native image runs `python web/run.py` and defaults to port **8080**. There is no locally maintained Dockerfile or alternate server implementation. Check an existing native container with `python smoke.py --port 8080` from this directory.

## Windows bundle

[The packaging script](<../../scripts/package-qqmusic.ps1>) retains CPython 3.13.9 with its pinned SHA256, installed dependencies and dist-info/license metadata, and the complete upstream Git archive, including Web source. [Runtime requirements](<requirements.txt>) cover the pinned SDK and native Web runtime dependencies; they are used for the embedded Windows runtime, not to replace upstream Docker dependencies.

The bundle manifest declares `entryPoint=upstream/source/web/run.py` and `workingDirectory=upstream/source`. Launch bundled Python with `-I -B` and the absolute native entry-point path from that working directory. The embedded Python search paths include `../upstream/source` relative to its `python` directory. No source override environment variable is needed.

| Native environment variable | Bundled integration value |
| --- | --- |
| `QQMUSIC_SERVER_HOST` | `127.0.0.1` |
| `QQMUSIC_SERVER_PORT` | Launcher-selected free loopback port |
| `QQMUSIC_SERVER_WORKERS` | `1` |
| `QQMUSIC_CLIENT_DEVICE_PATH` | Writable device-state path |
| `QQMUSIC_CREDENTIAL_STORE_PATH` | Writable SQLite-state path |
| `QQMUSIC_CREDENTIAL_ENABLED` | `false` for the clean bundled deployment |
| `QQMUSIC_LOGGING_LEVEL` | `WARNING` |

The packaged source export has no active `.env`, `web/accounts.toml` or `web/config.toml`. This makes offline startup reproducible without account refresh traffic; it is a packaging/test fixture, not an upstream business-policy change or a restriction on independent deployments. Native startup still initializes the SQLite credential store and performs its normal pool health check.

## Readiness and offline tests

At the pinned revision, `GET /` returns `{"code":0,"msg":"ok","time":"..."}` and `GET /openapi.json` exposes the `QQMusic API` schema. There is no `/health` endpoint or private protocol-1 uptime payload. `/health` and `/v1/search` return native 404 responses. OpenAPI authentication declarations vary by native route; anonymous routes are not required to declare optional Cookie security.

[The smoke test](<smoke.py>) checks root readiness, schema title, native route presence and the absence of old private endpoints. With an existing Python 3.13 fixture environment and a clean pinned upstream export:

```sh
.venv/bin/python smoke.py --source /absolute/path/to/upstream/source
QQMUSIC_UPSTREAM_SOURCE=/absolute/path/to/upstream/source .venv/bin/python -m pytest -q
```

`QQMUSIC_UPSTREAM_SOURCE` selects source for fixture tests only, not production startup. The smoke test invokes that export's original `web/run.py` with empty PATH and `-I -B`, in both clean and polluted Python environments. Its test-only audit hook rejects non-loopback socket access, and it checks the empty fixture credential database. [Windows bundle tests](<../../scripts/test-qqmusic-bundle.py>) also verify the embedded runtime, source inventory/hashes and dependency/license metadata.

Fixture tests instantiate the real native app and RequestEngine, substituting only offline executors/transport. They verify that `NONE` routes ignore Cookies and keep shared caching/ETag behavior, while `OPTIONAL` routes use valid Cookies, reject malformed ones and retain native error handling. These checks do not verify live QQ Music availability or playback.

## Licensing

See [the upstream notice](<UPSTREAM-NOTICE.md>). The Windows bundle retains the complete source export, license, revision and source hash inventory under `upstream/`. Retain corresponding source, original notices and installed dependency metadata when redistributing. For independently built upstream images, follow the upstream license and corresponding-source obligations; a repository URL alone does not replace them.
