# TunnelGuard

**TunnelGuard** is a security-focused Android TV and Google TV application designed to provide robust per-application VPN protection and enforce **fail-closed networking**.

With TunnelGuard, users can select specific applications (such as TiviMate, media players, or custom apps) that must *only* access the internet when a VPN connection is active. If the VPN path is disconnected or becomes unavailable, TunnelGuard instantly blocks those protected applications from accessing the internet, preventing any normal, unencrypted connection leaks. Other unprotected applications (such as YouTube or Netflix) can continue to access the internet normally.

[Requirements](#supported-android-versions) · [How It Works](#how-protection-works) · [Features](#features) · [VPN Providers](#vpn-provider-integration) · [Technical Details](#technical-details) · [Limitations](#known-limitations) · [License](#license)

## Supported Android Versions

- **Minimum SDK:** Android 5.0 / 5.1 (API Level 21)
- **Target SDK:** Android 14.0 (API Level 34)
- **Compatibility:** Optimized for Android TV & Google TV devices (Nvidia Shield, Chromecast with Google TV, Xiaomi Mi Box, Sony/TCL Smart TVs, etc.).

## How Protection Works

### One Active VPN at a Time

Android strictly permits **only one active `VpnService` at a time**.

- Android transfers the single VPN slot when another VPN starts. TunnelGuard releases its local
  blocking interface, but a separate foreground monitor continues observing the VPN transport.
- While the external VPN satisfies the selected policy, TunnelGuard reports upstream protection and
  does not compete for the slot. When it disconnects, TunnelGuard restores the local block only if
  Android still grants VPN consent. If consent was revoked, the dashboard and notification report
  the unprotected fault and opening TunnelGuard provides the user-initiated permission flow.
- **TunnelGuard does NOT fake or spoof third-party VPN control.** Instead, TunnelGuard implements a **Local Loopback Fail-Closed Firewall**.

### Fail-Closed Blocking

- **VPN active (or simulated connected):** TunnelGuard stays out of the way (`closeVpnInterface()`). This allows your protected apps to use the standard network path (e.g. routed through simulated/real gateways).
- **VPN disconnected:** TunnelGuard instantly activates its local `VpnService` interface. Using Android's official `addAllowedApplication(packageName)` API, Android routes all outgoing traffic of your selected (protected) apps *exclusively* into TunnelGuard's local TUN interface. Since TunnelGuard acts as a local packet sink (blackhole) and **does not forward packets**, all network traffic from the protected apps is instantly dropped (fail-closed block).
- While established, this provides system-level, non-root per-app blocking. Android's one-VPN
  handoff can still create an observational gap before TunnelGuard is allowed to restore the route;
  the app does not claim perfect leak detection or zero leakage during that transition.

## Features

### Protection Timeline

Open **System & Protection Diagnostics → Protection Timeline** for a remote-friendly, newest-first
history of meaningful VPN, fail-closed routing, profile, Emergency Lock, temporary override,
foreground-app, permission, boot, and recovery transitions. Events are emitted directly by the
components that own those state changes; the timeline never attempts to reconstruct state by parsing
the separate debug log. Filters cover VPN, protection, profiles, overrides, and warnings/errors, and
selecting an event shows its available structured context.

The timeline is stored only on the device in a dedicated preferences file and survives activity,
service, and process recreation. It retains at most 500 events and removes events older than 30 days.
History can be cleared independently, without changing profiles, protected apps, VPN configuration,
Emergency Lock, or overrides. JSON export uses a versioned schema and contains the structured event
fields shown in the app; it does not contain traffic payloads, URLs, browsing history, DNS queries,
credentials, VPN/Wi-Fi passwords, MAC addresses, or BSSIDs. Package names may be present because they
are required for per-app policy. Nothing is uploaded and no analytics or cloud logging is used.

When TunnelGuard observes a recovery start and completion, it may show their elapsed wall-clock
transition time in milliseconds. This is an observational diagnostic measurement only. It does not
prove zero leakage or uninterrupted enforcement during Android's one-VPN handoff interval.

### Temporary per-app allow overrides

From **Manage Protected Apps**, choose **Temporary Allow** for a protected app and explicitly confirm
5, 15, 30, or 60 minutes, or **Until app closes**. The app stays in its profile and every other
protected app keeps its normal policy. Active exceptions are shown in the app list, dashboard, ongoing
notification, and diagnostics, and can be cancelled immediately.

An override only stops TunnelGuard's local blocking for that package. It does **not** verify a VPN,
country, secure connection, or privacy, and the app may use the regular network while allowed.
Emergency Lock always suppresses exceptions; a timed exception continues counting and may resume only
if it remains unexpired when the lock is removed. Timers use persisted wall-clock and same-boot monotonic
deadlines plus an inexact wakeup alarm, so late alarms expire at reconciliation and clock changes cannot
extend access. As a conservative rule all overrides are cleared at reboot; **Until app closes** is
available only when foreground monitoring and its permissions are active. It must reach the foreground
within 30 seconds, has a four-hour safety limit, and is also cleared after process recovery or when
foreground monitoring observes that the app was left. Temporary runtime records are separate from configuration export/import and Android backup,
so they are never transferred to another device. Use overrides intentionally.

### Onboarding

- Explains what TunnelGuard does, "fail-closed" mechanics, the one active VPN constraint, and explicit user enablement.
- Remote-friendly layout with D-pad accessible "Get Started" control.
- Can be re-opened at any time from the settings screen.

### Dashboard & Security State

- Single source of truth state manager (`SecurityStateMachine.kt`) ensures impossible/conflicting states cannot be displayed.
- Unified home screen displaying VPN state, overall security state (`PROTECTED`, `BLOCKING`, `INACTIVE`, `CONNECTING`, `ERROR`), protected apps count, and traffic allow/block state.

### Diagnostics & Health Checks

- Fully structured system reports detailing VPN state, protection state, app counts, last transition time, boot status, Android version, device information, app version, and IPv4/IPv6 protection status.
- Clean, formatted events listing.
- Quick action buttons to Refresh, Copy to clipboard, Export/Share logs to local storage, and Clear logs.
- Includes a TV-friendly **Protection Health Check** for VPN permission and runtime state, fail-closed service/protocol coverage, DNS, profiles, monitor permissions, boot, country policy, updater readiness, and Android battery restrictions. Results describe only configuration and prerequisites Android exposes; they do not claim to prove complete security or the absence of every possible leak.

### Policy Tester

Open **System & Protection Diagnostics → Test an app's policy** to select any installed launcher
app with a D-pad and evaluate it against the active profile, per-app rules, provider and country
requirements, Auto-Connect, temporary overrides, Emergency Lock, and the currently observable
upstream VPN. The tester uses the same effective-policy and final-decision resolvers as live
monitoring. It never launches either app, changes configuration or protection, rebuilds the tunnel,
or writes a Protection Timeline event. Results can be copied or shared as readable text.

Android does not always expose the identity of an active third-party VPN, and offline country
lookup may be unavailable. The tester labels such details unknown rather than inventing them;
where policy requires verification, TunnelGuard applies the same conservative fail-closed result
as live enforcement.

## Per-profile VPN policy

Each protection profile can inherit the global VPN defaults or override whether VPN is required,
the preferred provider, exit country, Auto-Connect, and country validation. Existing and older
imported profiles inherit without copying global values. Policy precedence is **Emergency Lock**,
then a per-app country override, profile overrides, global defaults, and finally safe defaults.

An explicitly selected profile provider is never silently replaced when it is unavailable; a
VPN-required profile remains fail-closed. `ANY` disables the country restriction, while an unknown
country never satisfies required validation. Auto-Connect continues through the existing provider
adapter/coordinator and stale attempts are cancelled when the effective provider changes.

Android permits only one active `VpnService`. TunnelGuard can launch supported provider apps but
cannot control undocumented third-party APIs or silently change a provider's country; those actions
depend on the provider's documented Android capabilities.

### Configuration Backup & Restore

- Backup/Restore your entire protection configuration (profiles, protected packages, boot settings) via clean JSON.
- Does NOT export private keys or credentials.
- Validates imported package names using package syntax regex to filter out corrupt/malicious strings.
- Supports import from Clipboard or local backup file.

### IPv6 Protection with Fallback

- Attempts to establish IPv6 fail-closed routing (`::/0`) into the blackhole interface.
- Dynamically catches device limitations or OS-level IPv6 support errors and falls back to a secure IPv4-only fail-closed route.
- Accurately reports whether IPv6 is protected on the dashboard and diagnostics screens (never claims protection when unsupported).

### Start-on-Boot Reliability

- Receiver evaluates `VpnService.prepare` status on startup.
- Safe execution avoids boot crashes and loops.
- Persists and displays boot failure diagnostics (e.g. if permissions were revoked).

### Per-App VPN Country Requirements

- Settings → Per-app VPN countries lists protected apps and lets users select, change, or clear each app’s VPN exit-country requirement. Manage apps remains focused on selecting protected apps.
- TunnelGuard checks the active VPN's GeoIP country when that app is in the foreground and treats a missing or mismatched country as disconnected, preserving fail-closed warnings.
- Country assignments are included in configuration backup and restore.

### Automatic Profile Switching

Settings → **Automatic Profile Switching** can select an existing protection profile for any Wi-Fi,
a named Wi-Fi network, an unrecognized Wi-Fi network, Ethernet, or an upstream VPN state. Named
rules accept the currently connected network name or a manually entered SSID. **Unknown Wi-Fi** means
a usable SSID that does not match an enabled named-network rule; it never treats a hidden,
permission-restricted, blank, or Android `<unknown ssid>` value as an unrecognized network.

The feature is off by default. Rules retain stable IDs and target profile IDs, so renaming a custom
profile is safe. Rules may also use a local scheduled time, with every-day, weekday, weekend, or
explicit day selections and an optional end time. Ranges are start-inclusive and end-exclusive;
overnight ranges (for example, Monday 10 PM–6 AM) associate Tuesday's after-midnight portion with
Monday. A rule without an end is a transition at that local minute.

Network and schedule rules share one evaluator and priority list. Lower priority numbers win; ties are
resolved by stable rule ID—there is no hidden preference for schedules. This lets a named rule be
placed ahead of a generic Wi-Fi rule while keeping both available. Missing targets are disabled
rather than redirected.

<details>
<summary>Network changes, manual overrides, and boot behavior</summary>

Network changes are stabilized for 1.5 seconds. A manual profile selection remains active until the
next actual network-state change or scheduled transition (entering/leaving a range or another rule
becoming active); repeated callbacks while the same schedule and network state remain active do not
cancel it. Simulation Mode intentionally supplies the VPN-connected state to
automation. In normal mode TunnelGuard uses its existing upstream VPN detector, which excludes its
own fail-closed tunnel. At boot, available state is evaluated before starting protection; otherwise
the existing valid selection is retained (or an invalid selection falls back to the default), and the
service callback evaluates again when network information arrives. Schedule rules are evaluated
immediately at boot, including when boot occurs inside an active range. TunnelGuard uses the device's
current local timezone and reschedules after time/timezone changes. It requests an inexact, doze-aware
alarm only for the next boundary, so a transition may occur after its scheduled minute and no exact-alarm
permission is required. The pending boundary is persisted until evaluation, ensuring a delayed one-time
transition is applied once and then advanced rather than missed or replayed after a later app start.
Calendar-based boundary construction naturally follows daylight-saving days
that are shorter or longer than 24 hours. Profile changes use `ACTION_UPDATE`,
so the service immediately rebuilds the protected package routing while fail-closed remains authoritative.

Wi-Fi names are evaluated locally and are never sent to a server; TunnelGuard does not read BSSIDs,
MAC addresses, location coordinates, or retain a network history. Android may require Nearby Wi-Fi
Devices (Android 13+) and location permission before exposing an SSID; Location Services may also
need to be enabled. Permissions are requested only after selecting the clearly labelled control. If permission is denied,
or a TV manufacturer does not expose the name, named and Unknown Wi-Fi rules are skipped while
transport-level Wi-Fi, Ethernet, and VPN rules continue to work. Availability therefore varies by
Android TV/Google TV device and OS version.

</details>

## VPN Provider Integration

TunnelGuard resolves the configured VPN application through a small provider-adapter registry. Any
installed, launchable VPN app remains supported by the generic adapter. Known providers are labelled
**Standard** when only their normal Android launcher activity is verified; no undocumented connect,
disconnect, deep-link, or country-selection API is assumed. Capabilities are compiled into TunnelGuard
and cannot be supplied by an imported configuration.

<details>
<summary>Provider validation and connection checks</summary>

An adapter only makes a safe, package-scoped request to open a provider. TunnelGuard validates that
the resolved launcher belongs to the selected package and resolves it fresh after app updates. A
successful launch is **not** treated as a VPN connection. Android network detection and country
verification remain authoritative, and fail-closed traffic blocking continues until the active upstream
VPN independently satisfies the effective policy. If launching is unavailable, TunnelGuard retains the
block and presents manual recovery.

</details>

## Technical Details

### Fail-Closed Mechanism

Android's `VpnService.Builder` has the `addAllowedApplication` parameter. When our local VPN interface is established:

```kotlin
val builder = Builder()
    .setSession("TunnelGuardFailClosedTunnel")
    .addAddress("10.0.0.1", 24)
    .addRoute("0.0.0.0", 0) // Intercept all IPv4 traffic
```

By adding only the package names of selected apps to the builder, Android routes their packets into our `ParcelFileDescriptor`. Since we do not forward them, their traffic is completely sunk, achieving the fail-closed network block. Unselected apps continue using normal interfaces.

## Known Limitations

### VPN Coexistence & Country Requirements

Because Android only allows one VPN app, TunnelGuard's fail-closed interface cannot run at the same
time as a standard on-device VPN app like Proton VPN. During that interval policy enforcement depends
on the external VPN: TunnelGuard can observe and report it, but cannot blackhole protected-app traffic.
After the external VPN disconnects there can be an unprotected interval while Android reports the
transition and local blocking is restored. If Android revoked TunnelGuard's VPN consent, local blocking
cannot resume until the user opens TunnelGuard and approves the system VPN dialog again.

### Device / emulator VPN handoff verification

1. Select at least one protected app, approve Android's VPN dialog, and enable protection; verify the
   dashboard says **Blocking** and the ongoing notification is present.
2. Connect a second VPN application; verify TunnelGuard changes to **Protected** (or a policy-conflict
   warning), its monitoring notification remains present, and diagnostics show no local tunnel.
3. Disconnect the second VPN without changing Wi-Fi or Ethernet. Verify TunnelGuard restores
   **Blocking** when consent remains available.
4. Repeat after revoking TunnelGuard under Android's VPN settings. Verify TunnelGuard reports an
   unprotected permission fault and does not repeatedly open consent or reclaim the VPN slot. Open
   TunnelGuard, use the protection control to approve consent, and verify blocking returns.
5. During step 3, turn protection off. Verify both ongoing notifications disappear and delayed network
   callbacks do not restart either service.

Per-app country assignments are routing requirements, not simultaneous VPN tunnels: the installed upstream VPN must connect to the assigned country, and only one country can be active at a time.

### System App Exceptions

Certain system-level apps or Google Play Services may bypass VPN interfaces if specifically exempted by Android OS configurations.

### IPv6 on Unsupported Devices

On legacy devices or custom ROMs where the kernel doesn't support local VPN IPv6 routes, IPv6 traffic is unprotected. Ensure IPv6 is disabled in your router/modem or TV settings if your hardware falls back to IPv4-only.

## License

This project is licensed under the [MIT License](LICENSE).
