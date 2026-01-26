#!/bin/bash
#
# TAK Server ADS-B Plugin Build Script
# Copyright (c) 2025 Adeptus Cyber Solutions, LLC
#
# Builds the plugin JAR and creates a self-extracting installer script
#
# Usage:
#   ./build.sh                  # Auto-select if only one JAR in libs/
#   ./build.sh 5.4              # Build for TAK Server 5.4
#   ./build.sh 5.3              # Build for TAK Server 5.3
#

set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$SCRIPT_DIR"

# Colors for output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m' # No Color

# Parse TAK version argument
TAK_VERSION="$1"

echo "=========================================="
echo "TAK Server ADS-B Plugin Build"
echo "Adeptus Cyber Solutions, LLC"
echo "=========================================="
echo

# Check for TAK Server plugin SDK in libs/
if ! ls libs/takserver-plugins-*.jar 1> /dev/null 2>&1; then
    echo -e "${RED}ERROR: No TAK Server plugin JARs found in libs/ directory${NC}"
    echo
    echo "Please copy the TAK Server Plugin SDK JAR to the libs/ folder:"
    echo "  cp /opt/tak/lib/takserver-plugins-*-all.jar libs/"
    echo
    exit 1
fi

echo -e "${GREEN}Available TAK Server Plugin SDKs:${NC}"
ls -1 libs/takserver-plugins-*.jar | while read jar; do
    echo "  - $(basename "$jar")"
done
echo

# Check if version selection is needed
JAR_COUNT=$(ls -1 libs/takserver-plugins-*.jar 2>/dev/null | wc -l | tr -d ' ')

if [ "$JAR_COUNT" -gt 1 ] && [ -z "$TAK_VERSION" ]; then
    echo -e "${YELLOW}Multiple TAK Server versions found. Please specify which to use:${NC}"
    echo
    echo "  ./build.sh 5.4    # For TAK Server 5.4.x"
    echo "  ./build.sh 5.3    # For TAK Server 5.3.x"
    echo
    exit 1
fi

# Build Gradle command
GRADLE_ARGS="clean shadowJar"
if [ -n "$TAK_VERSION" ]; then
    echo -e "Building for TAK Server version: ${GREEN}${TAK_VERSION}${NC}"
    GRADLE_ARGS="$GRADLE_ARGS -PtakVersion=$TAK_VERSION"
fi
echo

# Clean and build
echo "Building plugin..."
./gradlew $GRADLE_ARGS

# Find the built JAR
JAR_FILE=$(ls build/libs/*-all.jar 2>/dev/null | head -1)

if [ -z "$JAR_FILE" ]; then
    echo -e "${RED}ERROR: Build failed - no JAR file found${NC}"
    exit 1
fi

JAR_NAME=$(basename "$JAR_FILE")
echo -e "${GREEN}Built: ${JAR_NAME}${NC}"
echo

# Get version from build.gradle
VERSION=$(grep "^version" build.gradle | sed "s/version = '\(.*\)'/\1/")
PACKAGE_NAME="takserver-adsb-plugin-${VERSION}"

# Create dist directory
DIST_DIR="build/dist"
rm -rf "$DIST_DIR"
mkdir -p "$DIST_DIR/${PACKAGE_NAME}"

# Copy files for distribution
cp "$JAR_FILE" "$DIST_DIR/${PACKAGE_NAME}/"
cp tak.server.plugins.AdsbPlugin.yaml "$DIST_DIR/${PACKAGE_NAME}/"

# Create the tar.gz payload
echo "Creating payload..."
cd "$DIST_DIR"
tar -czf payload.tar.gz "$PACKAGE_NAME"
cd "$SCRIPT_DIR"

# Create the self-extracting installer script
INSTALLER="build/${PACKAGE_NAME}-installer.sh"

cat > "$INSTALLER" << 'INSTALLER_HEADER'
#!/bin/bash
#
# TAK Server ADS-B Plugin - Self-Extracting Installer
# Copyright (c) 2025 Adeptus Cyber Solutions, LLC
#
# This script contains an embedded tar.gz payload that will be extracted
# and installed to your TAK Server.
#

set -e

# Colors for output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m' # No Color

TAK_HOME="${TAK_HOME:-/opt/tak}"

echo "=========================================="
echo "TAK Server ADS-B Plugin Installer"
echo "Adeptus Cyber Solutions, LLC"
echo "=========================================="
echo

# Check if running as root or with sudo
if [ "$EUID" -ne 0 ]; then
    echo -e "${RED}ERROR: Please run as root or with sudo${NC}"
    echo "  sudo $0"
    exit 1
fi

# Check TAK Server directory exists
if [ ! -d "$TAK_HOME" ]; then
    echo -e "${RED}ERROR: TAK Server directory not found at $TAK_HOME${NC}"
    echo "Set TAK_HOME environment variable if installed elsewhere:"
    echo "  sudo TAK_HOME=/path/to/tak $0"
    exit 1
fi

echo "TAK Server Home: $TAK_HOME"
echo

# Create temp directory for extraction
TEMP_DIR=$(mktemp -d)
trap "rm -rf $TEMP_DIR" EXIT

echo "Extracting payload..."

# Find the line where the payload starts and extract
ARCHIVE_START=$(awk '/^__PAYLOAD_BELOW__$/{print NR + 1; exit 0; }' "$0")
tail -n +${ARCHIVE_START} "$0" | tar -xzf - -C "$TEMP_DIR"

# Find the extracted directory
EXTRACT_DIR=$(ls -d "$TEMP_DIR"/takserver-adsb-plugin-* 2>/dev/null | head -1)

if [ -z "$EXTRACT_DIR" ] || [ ! -d "$EXTRACT_DIR" ]; then
    echo -e "${RED}ERROR: Failed to extract payload${NC}"
    exit 1
fi

echo -e "${GREEN}Extracted successfully${NC}"
echo

# Copy JAR to lib directory
echo "Installing plugin JAR to $TAK_HOME/lib/..."
cp "$EXTRACT_DIR"/*.jar "$TAK_HOME/lib/"
echo -e "${GREEN}Done${NC}"

# Copy config file if it doesn't exist (don't overwrite existing config)
CONFIG_DIR="$TAK_HOME/conf/plugins"
CONFIG_FILE="$CONFIG_DIR/tak.server.plugins.AdsbPlugin.yaml"

mkdir -p "$CONFIG_DIR"

if [ -f "$CONFIG_FILE" ]; then
    echo -e "${YELLOW}Config file already exists, not overwriting: $CONFIG_FILE${NC}"
    echo "New config saved as: ${CONFIG_FILE}.new"
    cp "$EXTRACT_DIR/tak.server.plugins.AdsbPlugin.yaml" "${CONFIG_FILE}.new"
else
    echo "Installing config file to $CONFIG_FILE..."
    cp "$EXTRACT_DIR/tak.server.plugins.AdsbPlugin.yaml" "$CONFIG_FILE"
    echo -e "${GREEN}Done${NC}"
fi

chown tak:tak "$TAK_HOME/lib/"*.jar
chown -R tak:tak "$CONFIG_DIR"  

echo
echo -e "${GREEN}=========================================="
echo "Installation Complete!"
echo "==========================================${NC}"
echo
echo "Next steps:"
echo
echo "  1. Edit the config file to set your location:"
echo -e "     ${YELLOW}sudo nano $CONFIG_FILE${NC}"
echo
echo "  2. Restart TAK Server:"
echo -e "     ${YELLOW}sudo systemctl restart takserver${NC}"
echo
echo "  3. Check logs for plugin startup:"
echo -e "     ${YELLOW}journalctl -u takserver -f | grep -i adsb${NC}"
echo

exit 0

__PAYLOAD_BELOW__
INSTALLER_HEADER

# Append the tar.gz payload to the installer script
cat "$DIST_DIR/payload.tar.gz" >> "$INSTALLER"

chmod +x "$INSTALLER"

# Clean up
rm -rf "$DIST_DIR"

echo
echo -e "${GREEN}=========================================="
echo "Build Complete!"
echo "==========================================${NC}"
echo
echo "Self-extracting installer: $INSTALLER"
echo "Size: $(du -h "$INSTALLER" | cut -f1)"
echo
echo "To install on TAK Server:"
echo "  1. Copy the installer to the TAK Server:"
echo "     scp $INSTALLER user@takserver:~/"
echo
echo "  2. Run the installer:"
echo "     sudo ./${PACKAGE_NAME}-installer.sh"
echo
