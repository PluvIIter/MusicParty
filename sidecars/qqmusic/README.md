# QQMusic native Web deployment

This directory deploys the native Web application shipped by [L1124/QQMusicApi](https://github.com/l-1124/QQMusicApi/tree/c046788dd1f11daacc74da487cbbf1bcd8ac6f33/web) at commit `c046788dd1f11daacc74da487cbbf1bcd8ac6f33`, with a small runtime authentication/cache policy in [the startup shim](<run_web.py>). The upstream source files are unchanged, but the runtime policy is deliberately different: every `AuthPolicy.NONE` business route becomes `OPTIONAL` with `cache=None`. Existing optional and strict authenticated routes are unchanged. Native routes, SDK operations, adapters, validation, response serialization and error handling are retained; this is not a rewritten gateway or Bearer-token bridge.

At this revision, upstream `NONE` routes ignore Cookie entirely and may use a public cache whose keys omit credentials. Sending a Java HTTP header alone is therefore insufficient. The shim lets the native Cookie parser and RequestEngine use each request's configured account for search, details, lyrics and other reads, and removes public caching/coalescing on those routes so accounts cannot share a cached result. Without configured credentials, optional reads remain anonymous; strict native routes still require credentials. There is no added local privacy filter: upstream data and permission failures pass through native response/error handling. This does not grant access that upstream denies.

## Local deployment

Use Python 3.13, install [the runtime requirements](<requirements.txt>), and export the complete pinned upstream tree into a new directory:

```sh
python3 -m venv .venv
.venv/bin/python -m pip install -r requirements.txt
git init upstream-checkout
git -C upstream-checkout fetch --depth=1 https://github.com/l-1124/QQMusicApi.git c046788dd1f11daacc74da487cbbf1bcd8ac6f33
mkdir -p upstream/source
git -C upstream-checkout archive FETCH_HEAD | tar -x -C upstream/source
export QQMUSIC_SERVER_HOST=127.0.0.1 QQMUSIC_SERVER_PORT=8000 QQMUSIC_SERVER_WORKERS=1
export QQMUSIC_CREDENTIAL_ENABLED=false QQMUSIC_LOGGING_LEVEL=WARNING
export QQMUSIC_CLIENT_DEVICE_PATH="$PWD/device.json"
export QQMUSIC_CREDENTIAL_STORE_PATH="$PWD/credentials.sqlite3"
PYTHON="$PWD/.venv/bin/python"
ENTRY="$PWD/run_web.py"
cd upstream/source
"$PYTHON" -I -B "$ENTRY"
```

The small requirements list installs the pinned SDK plus the upstream `web` dependency group: FastAPI, Griffe, pydantic-settings with TOML support, and Uvicorn. Python-dotenv is also pinned. Do not install the upstream Web requirements export: it includes development and documentation dependencies. Credential storage uses Python's standard-library SQLite, not aiosqlite.

Keep the source export clean: do not copy the upstream account/config examples to active configuration, and do not add `.env`. This deployment creates no account or login. The pinned application still initializes an empty SQLite credential store and invokes its startup pool health check even when `QQMUSIC_CREDENTIAL_ENABLED=false`; absent `web/accounts.toml`, there are no accounts to validate or refresh. Environment settings alone are not a substitute for keeping account seeds absent. Device and SQLite paths must be writable and outside the immutable source export.

## Windows bundle

The packaging script retains CPython 3.13.9 with its existing SHA256 pin, installed dependencies and dist-info/license metadata, plus the complete upstream Git archive including Web sources. The manifest declares `entryPoint=run_web.py` and `workingDirectory=upstream/source`. Start the bundled Python with `-I -B` and that root-level shim from that working directory. The shim defaults to its sibling `upstream/source`, inserts that absolute source path into `sys.path`, applies the route policy, then executes the original `web/run.py` using `runpy`; Uvicorn still uses the native `web.src.app:create_app` factory. `QQMUSIC_UPSTREAM_SOURCE` can select a clean pinned export for local development/tests; leave it unset in packaged deployment. The embedded Python search paths include `../upstream/source` relative to its `python` directory. No Web implementation is copied into a second server.

Set these native runtime environment variables:

| Variable | Bundled value / purpose |
| --- | --- |
| `QQMUSIC_SERVER_HOST` | `127.0.0.1` |
| `QQMUSIC_SERVER_PORT` | Launcher-selected free port |
| `QQMUSIC_SERVER_WORKERS` | `1` |
| `QQMUSIC_CLIENT_DEVICE_PATH` | Writable device-state path |
| `QQMUSIC_CREDENTIAL_STORE_PATH` | Writable SQLite-state path |
| `QQMUSIC_CREDENTIAL_ENABLED` | `false` |
| `QQMUSIC_LOGGING_LEVEL` | `WARNING` |

## Docker

```sh
docker build -t musicparty-qqmusic .
docker run --rm --network musicparty-internal musicparty-qqmusic
```

The image runs `python -I -B /opt/qqmusic/run_web.py` with the same native application and runtime policy as UID 10001, listens internally on port 8000, and stores writable state under `/home/qqmusic/data`. Do not publish this unauthenticated service to untrusted networks. Keep the backend and this service on a private network. The native upstream login and other routes remain present; network isolation, rather than a new private gateway, limits access.

## Readiness and offline tests

At this pinned revision, **there is no `/health` endpoint or uptime/status payload**. `GET /` returns `{"code":0,"msg":"ok","time":"..."}`. `GET /openapi.json` exposes the native `QQMusic API` schema; `/docs`, `/swagger`, and `/redoc` expose upstream documentation pages. Docker and launcher readiness must use the actual native root response, not the removed protocol-1 response. `/health` and `/v1/search` return native 404 responses.

[The smoke test](<smoke.py>) checks root readiness, OpenAPI title and routes, and the absence of old private endpoints. Run an existing service check with `python smoke.py`; use the full isolated startup check with:

```sh
.venv/bin/python smoke.py --source /absolute/path/to/upstream/source
QQMUSIC_UPSTREAM_SOURCE=/absolute/path/to/upstream/source .venv/bin/python -m pytest -q
```

The startup test runs the shim and real upstream entry point with an empty PATH, both clean and polluted Python environments, and a Python audit hook that rejects non-loopback socket access. It verifies startup without login and an empty credential database. Windows bundle tests additionally check the embedded runtime, source hashes and dependency/license metadata. Fixture tests use the real native app and RequestEngine with an offline CGI executor result and a transport that rejects network requests; they are not live QQ Music or playback verification.

## Licensing

See [the upstream notice](<UPSTREAM-NOTICE.md>). Docker retains the complete unmodified upstream source, license and commit under `/opt/qqmusic/upstream/`; Windows retains them under the bundle's `upstream/` directory. Retain those sources, original notices and installed dependency metadata when redistributing. A repository URL alone does not replace corresponding-source obligations.
