# Upstream dependency notice

This integration uses QQMusicApi / qqmusic-api-python by Luren and its upstream contributors:

- Repository: https://github.com/l-1124/QQMusicApi
- Exact source revision: c046788dd1f11daacc74da487cbbf1bcd8ac6f33
- Upstream declared license: GNU General Public License version 3 or later (GPL-3.0-or-later)
- Upstream copyright and license notices remain in the installed package and complete source distribution.

The native Web source and runtime behavior are not patched or replaced. Authentication, shared caching, account pools, routes, operations, adapters, responses and errors follow the pinned upstream implementation. Production startup executes the original `web/run.py` directly.

The Windows launcher bundle retains the complete upstream Git archive, LICENSE, commit and source-file hash inventory under `bin/qqmusic/upstream/`, together with installed dependency dist-info/licenses and CPython's LICENSE/SBOM. The build script verifies CPython 3.13.9 against the pinned official SHA256 and checks runtime isolation from a system Python installation. Keep those files when redistributing or repackaging the launcher and satisfy the upstream GPL corresponding-source and notice obligations.

Docker deployments build the upstream `web/Dockerfile` directly from the pinned upstream Git context. This repository maintains no alternate image implementation. Preserve required notices and provide corresponding source when redistributing images; a repository URL alone does not replace those obligations.

Upstream has no affiliation with MusicParty. API availability, authentication and licensing do not grant rights to redistribute music, bypass DRM, access private collections or use someone else's account credentials. The bundled offline fixture has no account seeds; independent deployments may use native upstream account configuration. Keep the native service away from untrusted networks.
