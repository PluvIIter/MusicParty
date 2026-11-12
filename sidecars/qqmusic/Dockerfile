FROM python:3.13.9-slim AS build

RUN apt-get update && apt-get install -y --no-install-recommends git ca-certificates
WORKDIR /build
COPY requirements.txt .
RUN pip install --no-cache-dir --no-compile -r requirements.txt && \
    git init checkout && \
    git -C checkout fetch --depth=1 https://github.com/l-1124/QQMusicApi.git c046788dd1f11daacc74da487cbbf1bcd8ac6f33 && \
    test "$(git -C checkout rev-parse FETCH_HEAD)" = c046788dd1f11daacc74da487cbbf1bcd8ac6f33 && \
    mkdir -p /opt/qqmusic/upstream/source && \
    git -C checkout archive --format=tar --output=/build/upstream.tar FETCH_HEAD && \
    tar -xf /build/upstream.tar -C /opt/qqmusic/upstream/source
RUN test -f /opt/qqmusic/upstream/source/web/run.py && \
    test ! -e /opt/qqmusic/upstream/source/.env && \
    test ! -e /opt/qqmusic/upstream/source/web/accounts.toml && \
    test ! -e /opt/qqmusic/upstream/source/web/config.toml && \
    cp /opt/qqmusic/upstream/source/LICENSE /opt/qqmusic/upstream/LICENSE && \
    printf '%s\n' c046788dd1f11daacc74da487cbbf1bcd8ac6f33 > /opt/qqmusic/upstream/COMMIT
RUN python -I -c 'import hashlib, json; from pathlib import Path; root = Path("/opt/qqmusic/upstream"); source = root / "source"; files = {p.relative_to(source).as_posix(): hashlib.sha256(p.read_bytes()).hexdigest() for p in sorted(source.rglob("*")) if p.is_file()}; (root / "source-manifest.json").write_text(json.dumps({"commit": (root / "COMMIT").read_text().strip(), "files": files}, indent=2) + "\n", encoding="utf-8")'

FROM python:3.13.9-slim
COPY --from=build /usr/local/lib/python3.13/site-packages /usr/local/lib/python3.13/site-packages
COPY --from=build /opt/qqmusic/upstream /opt/qqmusic/upstream
COPY README.md UPSTREAM-NOTICE.md requirements.txt run_web.py /opt/qqmusic/
RUN useradd --system --uid 10001 --create-home qqmusic && \
    mkdir -p /home/qqmusic/data && chown 10001:10001 /home/qqmusic/data
WORKDIR /opt/qqmusic/upstream/source
USER 10001
ENV HOME=/home/qqmusic \
    QQMUSIC_SERVER_HOST=0.0.0.0 QQMUSIC_SERVER_PORT=8000 QQMUSIC_SERVER_WORKERS=1 \
    QQMUSIC_CREDENTIAL_ENABLED=false QQMUSIC_LOGGING_LEVEL=WARNING \
    QQMUSIC_CLIENT_DEVICE_PATH=/home/qqmusic/data/device.json \
    QQMUSIC_CREDENTIAL_STORE_PATH=/home/qqmusic/data/credentials.sqlite3 \
    PYTHONDONTWRITEBYTECODE=1 PYTHONUNBUFFERED=1
EXPOSE 8000
HEALTHCHECK --interval=30s --timeout=3s CMD python -I -c "import json, urllib.request; r = json.load(urllib.request.urlopen('http://127.0.0.1:8000/', timeout=2)); assert r['code'] == 0 and r['msg'] == 'ok' and r['time']" || exit 1
CMD ["python", "-I", "-B", "/opt/qqmusic/run_web.py"]
