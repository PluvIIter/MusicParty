# Upstream dependency notice

This gateway uses QQMusicApi / qqmusic-api-python by Luren and its upstream contributors:

- Repository: https://github.com/l-1124/QQMusicApi
- Exact source revision: c046788dd1f11daacc74da487cbbf1bcd8ac6f33
- Upstream declared license: GNU General Public License version 3 or later (GPL-3.0-or-later)
- Upstream copyright and license notices remain in the installed package and complete source distribution.

No upstream implementation source is vendored into this directory. The pinned dependency is installed separately. The Dockerfile also retains a checkout at the exact revision, including the original LICENSE and notices, under `/usr/share/licenses/qqmusic-api-python/source`, and copies the original license to `/usr/share/licenses/qqmusic-api-python/LICENSE`. That checkout is supplied with the image for corresponding-source access. Keep it and the notices when redistributing the image. For distributions outside that image, provide the applicable corresponding source and license according to the upstream GPL terms; a bare package binary without the necessary source/notice provision is not the intended distribution.

Upstream has no affiliation with this application. Its API availability, authentication requirements, and licensing do not grant rights to redistribute music, bypass DRM, access private collections, or use someone else's account credentials. The gateway deliberately does not implement encrypted media, credential pools, QR login, automatic account refresh, or private collection access.
