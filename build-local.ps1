# Music Party Local Build Script
# Requirements: Node.js, Java 21, Go, Wails CLI, Python 3.13, pkg (npm install -g pkg)

echo "--- 1. Building Frontend ---"
cd music-party-web
npm install
npm run build
cd ..

echo "--- 2. Building Java Backend ---"
# Copy frontend dist to static
if (Test-Path "src/main/resources/static") { rm -r src/main/resources/static }
mkdir -p src/main/resources/static
cp -r music-party-web/dist/* src/main/resources/static/
mvn clean package -DskipTests

echo "--- 3. Building Netease API ---"
# Assuming api-enhanced is at E:\Develop\Code\api-enhanced as per context
cd E:\Develop\Code\api-enhanced
npm install
npx pkg . -t node18-win-x64 -o $PSScriptRoot/launcher/bin/netease-api.exe
cd $PSScriptRoot

echo "--- 4. Preparing Launcher Assets ---"
if (-Not (Test-Path "launcher/bin")) { mkdir launcher/bin }
cp target/music-party-*.jar launcher/bin/server.jar

echo "--- 5. Packaging QQ Music Gateway ---"
& "$PSScriptRoot/scripts/package-qqmusic.ps1" -OutputDir "$PSScriptRoot/launcher/bin/qqmusic"
if ($LASTEXITCODE -ne 0) { throw "QQ gateway packaging failed" }

echo "--- 6. Packaging Cloudflare Tunnel ---"
& "$PSScriptRoot/scripts/package-cloudflared.ps1" -OutputDir "$PSScriptRoot/launcher/bin"
if ($LASTEXITCODE -ne 0) { throw "Cloudflare Tunnel packaging failed" }

echo "--- 7. Building Wails Launcher ---"
cd launcher
wails build -platform windows/amd64
cd ..

echo "DONE! Your EXE is at: launcher/build/bin/MusicParty.exe"
