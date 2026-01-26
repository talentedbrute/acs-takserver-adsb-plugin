# TAK Server ADS-B Plugin

A TAK Server plugin that injects real-time ADS-B aircraft data from the [airplanes.live](https://airplanes.live) API into TAK Server as CoT (Cursor on Target) messages.

Developed by [Adeptus Cyber Solutions, LLC](https://adeptuscyber.com)

## Features

- Polls airplanes.live API at configurable intervals
- Displays aircraft within configurable radius (up to 250nm)
- Differentiates civilian vs military aircraft with distinct CoT types
- Includes aircraft metadata in CoT remarks (registration, type, operator, squawk, altitude)
- Configurable via YAML file

## CoT Types

| Aircraft Type | CoT Type | Description |
|--------------|----------|-------------|
| Civilian | `a-f-A-C-F` | Friendly Air Civilian Fixed-wing |
| Military | `a-f-A-M-F` | Friendly Air Military Fixed-wing |

## Requirements

- TAK Server 5.x
- Java 11+ (builds with Java 11 for TAK Server compatibility)
- TAK Server Plugin SDK JAR (from tak.gov or your TAK Server installation)

## Building

### Prerequisites

1. Download or copy the TAK Server Plugin SDK JAR to the `libs/` folder:
   - From tak.gov: Download `takserver-plugins-X.X-release-XX-all.jar`
   - From TAK Server: Copy from `/opt/tak/lib/takserver-plugins-*-all.jar`

   ```bash
   cp /opt/tak/lib/takserver-plugins-*-all.jar libs/
   ```

### Build

```bash
# If you have one SDK JAR in libs/
./build.sh

# If you have multiple SDK versions, specify which one to use
./build.sh 5.4          # Build for TAK Server 5.4.x
./build.sh 5.3          # Build for TAK Server 5.3.x
```

This will:
1. Compile the plugin against the specified TAK Server SDK
2. Create a self-extracting installer: `build/takserver-adsb-plugin-1.0.0-installer.sh`

Alternatively, build just the JAR:
```bash
./gradlew clean shadowJar -PtakVersion=5.4
```

## Installation

### Option 1: Self-Extracting Installer (Recommended)

1. Copy the installer script to your TAK Server:
   ```bash
   scp build/takserver-adsb-plugin-1.0.0-installer.sh user@takserver:~/
   ```

2. Run the installer:
   ```bash
   sudo ./takserver-adsb-plugin-1.0.0-installer.sh
   ```

3. Edit the configuration file:
   ```bash
   sudo nano /opt/tak/conf/plugins/tak.server.plugins.AdsbPlugin.yaml
   ```

4. Restart TAK Server:
   ```bash
   sudo systemctl restart takserver
   ```

### Option 2: Manual Installation

1. Copy the plugin JAR to your TAK Server:
   ```bash
   cp build/libs/takserver-adsb-plugin-*-all.jar /opt/tak/lib/
   ```

2. Copy and customize the configuration file:
   ```bash
   cp tak.server.plugins.AdsbPlugin.yaml /opt/tak/conf/plugins/
   ```

3. Edit the configuration file to set your desired center point and options:
   ```bash
   sudo nano /opt/tak/conf/plugins/tak.server.plugins.AdsbPlugin.yaml
   ```

4. Restart TAK Server:
   ```bash
   sudo systemctl restart takserver
   ```

## Configuration

Edit `/opt/tak/conf/plugins/tak.server.plugins.AdsbPlugin.yaml`:

```yaml
# Polling interval in milliseconds (min: 5000)
interval: 5000

# Center point coordinates
latitude: 34.0007
longitude: -81.0348

# Query radius in nautical miles (max: 250)
radius: 250

# Seconds until CoT marker becomes stale
staleTimeSec: 30

# Optional: TAK groups to publish to
# groups:
#   - "__ANON__"
```

### Configuration Options

| Option | Default | Description |
|--------|---------|-------------|
| `interval` | 5000 | Polling interval in ms (minimum 5000) |
| `latitude` | 34.0007 | Center point latitude |
| `longitude` | -81.0348 | Center point longitude |
| `radius` | 250 | Query radius in nautical miles (max 250) |
| `staleTimeSec` | 30 | Seconds until marker becomes stale |
| `groups` | (all) | List of TAK groups to publish to |

## API Rate Limits

The airplanes.live API has the following limits:
- **1 request per second** (enforced by minimum 5000ms interval)
- **500 requests per day** on the free tier

At the default 5-second interval, you'll use approximately 17,280 requests per day. Consider:
- Increasing the interval for long-term monitoring
- Using a paid API tier for production deployments

## Verification

1. Check TAK Server logs for plugin startup:
   ```bash
   journalctl -u takserver -f | grep -i adsb
   ```

2. Look for messages like:
   ```
   ADS-B Plugin Configuration:
     Interval: 5000 ms
     Center: (34.0007, -81.0348)
     Radius: 250 nm
   Starting ADS-B Plugin - polling airplanes.live API
   Sent 42 aircraft (3 military) to TAK Server
   ```

3. Open ATAK or WinTAK and verify aircraft icons appear within the configured radius.

## Downloads

Pre-built installers are available on the [GitHub Releases](../../releases) page. Choose the installer matching your TAK Server version:

- `takserver-adsb-plugin-X.X.X-tak5.4-installer.sh` - For TAK Server 5.4.x
- `takserver-adsb-plugin-X.X.X-tak5.5-installer.sh` - For TAK Server 5.5.x
- `takserver-adsb-plugin-X.X.X-tak5.6-installer.sh` - For TAK Server 5.6.x

## CI/CD

This project uses GitHub Actions for automated builds and releases.

### Automated Releases

When code is merged to `main`, the release workflow automatically:

1. Increments the patch version in `build.gradle` (e.g., 1.0.0 → 1.0.1)
2. Commits the version bump with `[skip ci]` to avoid build loops
3. Builds the plugin for TAK Server 5.4, 5.5, and 5.6
4. Creates self-extracting installer scripts for each version
5. Publishes a GitHub Release with all installer artifacts

### Manual Releases

You can also trigger a release manually:

1. Go to **Actions** → **Build and Release**
2. Click **Run workflow**
3. Optionally check "Skip version bump" to release with the current version

### CI Validation

Pull requests and feature branches trigger validation that:
- Checks Java syntax
- Validates YAML configuration

### SDK JARs for GitHub Actions

The build requires TAK Server SDK JARs. You have two options:

**Option A: Commit SDK JARs to repository**
```bash
# Copy SDK JARs to libs/ folder
cp takserver-plugins-5.4-*.jar libs/
cp takserver-plugins-5.5-*.jar libs/
cp takserver-plugins-5.6-*.jar libs/

# Update .gitignore to track them (uncomment the exclude line)
# Then commit
git add libs/*.jar
git commit -m "Add TAK Server SDK JARs"
```

**Option B: Configure download URL (requires secrets)**

Set up a `TAK_SDK_BASE_URL` secret pointing to where SDK JARs can be downloaded, then uncomment the curl commands in `.github/workflows/release.yml`.

## Proxy Support

The plugin automatically detects HTTP proxy settings from environment variables:

- `HTTPS_PROXY` / `https_proxy`
- `HTTP_PROXY` / `http_proxy`

Example:
```bash
export HTTPS_PROXY=http://proxy.example.com:8080
```

The proxy configuration is logged at startup when detected.

## License

MIT License - See [LICENSE](LICENSE) file for details.

Copyright (c) 2025 Adeptus Cyber Solutions, LLC

## Credits

- Developed by [Adeptus Cyber Solutions, LLC](https://adeptuscyber.com)
- ADS-B data provided by [airplanes.live](https://airplanes.live)
- Built using the TAK Server Plugin SDK
