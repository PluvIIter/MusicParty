import asyncio
import hmac
import json
import logging
import os
from contextlib import asynccontextmanager

from fastapi import FastAPI, Request
from fastapi.responses import JSONResponse
from qqmusic_api.core.exceptions import CredentialExpiredError, CredentialInvalidError, RatelimitedError

from gateway import Gateway, GatewayError


MAX_BODY = 16384
MAX_RESPONSE = 2 * 1024 * 1024


def error(code, status):
    return JSONResponse({"error": {"code": code, "message": code}}, status_code=status, headers={"Cache-Control": "no-store"})


def unique_object(pairs):
    result = {}
    for key, value in pairs:
        if key in result:
            raise ValueError("duplicate key")
        result[key] = value
    return result


def create_app(gateway=None, token=None, timeout=20.0):
    secret = token if token is not None else os.environ.get("QQMUSIC_GATEWAY_TOKEN", "")
    if len(secret) < 16 or len(secret) > 512 or any(ord(c) < 33 or ord(c) > 126 for c in secret):
        raise RuntimeError("QQMUSIC_GATEWAY_TOKEN must be a nonempty private token of at least 16 characters")
    service = gateway or Gateway(device_path=os.environ.get("QQMUSIC_DEVICE_PATH", "/tmp/qqmusic-gateway-device.json"))
    slots = asyncio.Semaphore(8)

    @asynccontextmanager
    async def lifespan(application):
        yield
        await service.close()

    application = FastAPI(lifespan=lifespan, docs_url=None, redoc_url=None, openapi_url=None)

    @application.middleware("http")
    async def boundary(request, call_next):
        if request.url.path == "/health" and request.method == "GET":
            return await call_next(request)
        authorization = request.headers.getlist("authorization")
        if len(authorization) != 1 or not hmac.compare_digest(authorization[0].encode(), ("Bearer " + secret).encode()):
            return error("UNAUTHORIZED", 401)
        if request.url.query:
            return error("INVALID_INPUT", 400)
        if request.method != "POST":
            return error("METHOD_NOT_ALLOWED", 405)
        if request.headers.get("content-type", "").split(";", 1)[0].strip().lower() != "application/json":
            return error("INVALID_INPUT", 415)
        try:
            content_length = request.headers.get("content-length")
            if content_length is not None and (not content_length.isdecimal() or int(content_length) > MAX_BODY):
                return error("BODY_TOO_LARGE", 413)
            async with asyncio.timeout(5):
                payload = bytearray()
                async for chunk in request.stream():
                    payload.extend(chunk)
                    if len(payload) > MAX_BODY:
                        return error("BODY_TOO_LARGE", 413)
            request._body = bytes(payload)
        except TimeoutError:
            return error("REQUEST_TIMEOUT", 408)
        return await call_next(request)

    @application.get("/health")
    async def health():
        return JSONResponse({"protocolVersion": 1}, headers={"Cache-Control": "no-store"})

    @application.post("/v1/{operation}")
    async def operation(operation: str, request: Request):
        try:
            body = json.loads(await request.body(), object_pairs_hook=unique_object,
                              parse_constant=lambda value: (_ for _ in ()).throw(ValueError()))
            if not isinstance(body, dict) or set(body) - {"params", "credential"} or "params" not in body:
                return error("INVALID_INPUT", 400)
            async with asyncio.timeout(timeout):
                async with slots:
                    result = await service.execute(operation, body["params"], body.get("credential"))
            encoded = json.dumps({"data": result}, ensure_ascii=False, allow_nan=False).encode()
            if len(encoded) > MAX_RESPONSE:
                return error("UPSTREAM_RESPONSE_TOO_LARGE", 502)
            return JSONResponse({"data": result}, headers={"Cache-Control": "no-store"})
        except GatewayError as failure:
            return error(failure.code, failure.status)
        except (CredentialExpiredError, CredentialInvalidError):
            return error("SOURCE_AUTH_REQUIRED", 401)
        except RatelimitedError:
            return error("SOURCE_RATE_LIMITED", 429)
        except TimeoutError:
            return error("UPSTREAM_TIMEOUT", 504)
        except (ValueError, UnicodeError, TypeError):
            return error("INVALID_INPUT", 400)
        except Exception:
            return error("UPSTREAM_UNAVAILABLE", 502)

    return application


def run():
    import uvicorn

    logging.getLogger("qqmusic_api").setLevel(logging.CRITICAL)
    uvicorn.run(create_app(), host=os.environ.get("QQMUSIC_BIND", "127.0.0.1"),
                port=int(os.environ.get("QQMUSIC_PORT", "8000")), access_log=False, log_level="warning")


if __name__ == "__main__":
    run()
