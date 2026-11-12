# Upstream dependency notice

This deployment uses QQMusicApi / qqmusic-api-python by Luren and its upstream contributors:

- Repository: https://github.com/l-1124/QQMusicApi
- Exact source revision: c046788dd1f11daacc74da487cbbf1bcd8ac6f33
- Upstream declared license: GNU General Public License version 3 or later (GPL-3.0-or-later)
- Upstream copyright and license notices remain in the installed package and complete source distribution.

The native Web source files are not patched or replaced. The separate [startup shim](<run_web.py>) changes the in-memory route policy only: `AuthPolicy.NONE` becomes `OPTIONAL` and those routes' public caches are disabled. Existing authenticated route policies remain unchanged; native routes, operations, adapters, responses and errors are still used. Runtime authentication/cache semantics therefore differ from the pinned upstream defaults. Build outputs include a complete Git archive of the pinned upstream tree, including Web source and original license notices. The Docker image retains that source under `/opt/qqmusic/upstream/source`, the original LICENSE under `/opt/qqmusic/upstream/LICENSE`, and the revision under `/opt/qqmusic/upstream/COMMIT`. Keep them when redistributing the image and satisfy the upstream GPL corresponding-source and notice obligations.

The Windows launcher bundle retains the same complete source export, LICENSE, commit and source-file hash inventory under `bin/qqmusic/upstream/`, together with installed dependency dist-info/licenses and CPython's LICENSE/SBOM. The build script verifies CPython 3.13.9 against the pinned official SHA256 and checks runtime isolation from a system Python installation. Keep those files when redistributing or repackaging the launcher.

Upstream has no affiliation with MusicParty. API availability, authentication and licensing do not grant rights to redistribute music, bypass DRM, access private collections or use someone else's account credentials. This deployment starts without account seeds and disables default pooled credentials; upstream's native API surface remains intact. Do not expose it to untrusted networks.
