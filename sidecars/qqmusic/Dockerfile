FROM python:3.13-slim

RUN apt-get update && apt-get install -y --no-install-recommends git ca-certificates && rm -rf /var/lib/apt/lists/*
WORKDIR /app
COPY requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt && \
    mkdir -p /usr/share/licenses/qqmusic-api-python && \
    git clone https://github.com/l-1124/QQMusicApi.git /usr/share/licenses/qqmusic-api-python/source && \
    git -C /usr/share/licenses/qqmusic-api-python/source checkout c046788dd1f11daacc74da487cbbf1bcd8ac6f33 && \
    cp /usr/share/licenses/qqmusic-api-python/source/LICENSE /usr/share/licenses/qqmusic-api-python/LICENSE && \
    useradd --system --uid 10001 --create-home gateway
COPY app.py gateway.py ./
COPY README.md UPSTREAM-NOTICE.md ./
USER 10001
ENV QQMUSIC_BIND=0.0.0.0 QQMUSIC_PORT=8000 QQMUSIC_DEVICE_PATH=/tmp/qqmusic-device.json PYTHONDONTWRITEBYTECODE=1 PYTHONUNBUFFERED=1
EXPOSE 8000
HEALTHCHECK --interval=30s --timeout=3s CMD python -c "import urllib.request; urllib.request.urlopen('http://127.0.0.1:8000/health', timeout=2)" || exit 1
CMD ["python", "app.py"]
