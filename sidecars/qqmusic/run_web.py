from dataclasses import replace
import os
from pathlib import Path
import runpy
import sys


source = Path(
    os.environ.get("QQMUSIC_UPSTREAM_SOURCE") or Path(__file__).resolve().parent / "upstream/source"
).resolve(strict=True)
sys.path.insert(0, str(source))

from web.src import app as native
from web.src.routing.route_types import AuthPolicy


# Public cache keys omit credentials; cookie-aware routes must not share those entries.
native.ROUTES = tuple(
    replace(route, auth=AuthPolicy.OPTIONAL, cache=None) if route.auth is AuthPolicy.NONE else route
    for route in native.ROUTES
)

if __name__ == "__main__":
    runpy.run_path(str(source / "web/run.py"), run_name="__main__")
