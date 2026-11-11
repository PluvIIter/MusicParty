const http = require('node:http');
const fs = require('node:fs');
const path = require('node:path');
const { chromium } = require('playwright');

function tone(seconds = 180) {
    const rate = 16000,
        bytes = rate * seconds * 2,
        data = Buffer.alloc(44 + bytes);
    data.write('RIFF', 0);
    data.writeUInt32LE(36 + bytes, 4);
    data.write('WAVEfmt ', 8);
    data.writeUInt32LE(16, 16);
    data.writeUInt16LE(1, 20);
    data.writeUInt16LE(1, 22);
    data.writeUInt32LE(rate, 24);
    data.writeUInt32LE(rate * 2, 28);
    data.writeUInt16LE(2, 32);
    data.writeUInt16LE(16, 34);
    data.write('data', 36);
    data.writeUInt32LE(bytes, 40);
    for (let i = 0; i < rate * seconds; i++)
        data.writeInt16LE(Math.round(5000 * Math.sin((2 * Math.PI * 440 * i) / rate)), 44 + i * 2);
    return data;
}

async function serve() {
    const root = path.resolve(__dirname, '../dist');
    const server = http.createServer((req, res) => {
        const pathname = decodeURIComponent(new URL(req.url, 'http://localhost').pathname);
        const file = path.resolve(root, '.' + (pathname === '/' ? '/index.html' : pathname));
        if (!file.startsWith(root + path.sep)) {
            res.writeHead(403);
            res.end();
            return;
        }
        fs.readFile(file, (error, data) => {
            if (error) {
                res.writeHead(404);
                res.end();
                return;
            }
            const types = {
                '.html': 'text/html',
                '.js': 'text/javascript',
                '.css': 'text/css',
                '.svg': 'image/svg+xml',
                '.json': 'application/json',
                '.webmanifest': 'application/manifest+json',
                '.png': 'image/png',
            };
            res.writeHead(200, {
                'Content-Type': types[path.extname(file)] || 'application/octet-stream',
                'Cache-Control': 'no-store',
            });
            res.end(data);
        });
    });
    await new Promise((resolve) => server.listen(0, '127.0.0.1', resolve));
    return {
        origin: 'http://127.0.0.1:' + server.address().port,
        close: () =>
            new Promise((resolve) => {
                server.closeAllConnections();
                server.close(resolve);
            }),
    };
}

function launchOptions() {
    return {
        headless: true,
        ...(process.env.PLAYWRIGHT_EXECUTABLE_PATH ? { executablePath: process.env.PLAYWRIGHT_EXECUTABLE_PATH } : {}),
        args: ['--disable-gpu', '--autoplay-policy=no-user-gesture-required'],
    };
}
module.exports = { serve, tone, chromium, launchOptions };
