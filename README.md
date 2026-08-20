# TunnelGuard

**TunnelGuard** is a security-focused Android TV and Google TV application designed to provide robust per-application VPN protection and enforce **fail-closed networking**.

With TunnelGuard, users can select specific applications (such as TiviMate, media players, or custom apps) that must *only* access the internet when a VPN connection is active. If the VPN path is disconnected or becomes unavailable, TunnelGuard instantly blocks those protected applications from accessing the internet, preventing any normal, unencrypted connection leaks. Other unprotected applications (such as YouTube or Netflix) can continue to access the internet normally.

---

## Supported Android Versions

* **Minimum SDK:** Android 5.0 / 5.1 (API Level 21)
* **Target SDK:** Android 14.0 (API Level 34)
* **Compatibility:** Optimized for Android TV & Google TV devices (Nvidia Shield, Chromecast with Google TV, Xiaomi Mi Box, Sony/TCL Smart TVs, etc.).

---

## Key Android Platform & VPN Limitations (Crucial Security Notice)

Before running or developing TunnelGuard, it is vital to understand Android's networking security boundaries:

1. **The Single Active VPN Constraint:**
   Android strictly permits **only one active `VpnService` at a time**.
   * If an external VPN app (like Proton VPN, NordVPN, or ExpressVPN) is running and active, starting TunnelGuard's protection will instantly terminate the external VPN's connection.
   * If TunnelGuard's protection is running, starting an external VPN app will instantly terminate TunnelGuard.
   * **TunnelGuard does NOT fake or spoof third-party VPN control.** Instead, TunnelGuard implements a **Local Loopback Fail-Closed Firewall**.
2. **How TunnelGuard's Fail-Closed Protection Works:**
   * **When the Upstream VPN is Active (or Simulated Connected):** TunnelGuard stays out of the way (`closeVpnInterface,()`). This allows your protected apps to use the standard network path (e.g. routed through simulated/real gateways).
   * **When the Upstream VPN fails / disconnects:** TunnelGuard instantly activates its local `VpnService` interface. Using Android's official `addAllowedApplication(packageName)` API, Android routes all outgoing traffic of your selected (protected) apps *exclusively* into TunnelGuard's local TUN interface. Since TunnelGuard acts as a local packet sink (blackhole) and **does not forward packets**, all network traffic from the protected apps is instantly dropped (fail-closed block).
   * This design achieves 100% reliable, system-level, non-root per-app internet blocking.

---

## Features & Enhancements

1. **First-Run Onboarding Screen:**
   * Explains what TunnelGuard does, "fail-closed" mechanics, the one active VPN constraint, and explicit user enablement.
   * Remote-friendly layout with D-pad accessible "Get Started" control.
   * Can be re-opened at any time from the settings screen.
2. **Deterministic State Machine & Dashboard:**
   * Single source of truth state manager (`SecurityStateMachine.kt`) ensures impossible/conflicting states cannot be displayed.
   * Unified home screen displaying VPN state, overall security state (`PROTECTED`, `BLOCKING`, `INACTIVE`, `CONNECTING`, `ERROR`), protected apps count, and traffic allow/block state.
3. **Dedicated Diagnostics Screen:**
   * Fully structured system reports detailing VPN state, protection state, app counts, last transition time, boot status, Android version, device information, app version, and IPv4/IPv6 protection status.
   * Clean, formatted events listing.
   * Quick action buttons to Refresh, Copy to clipboard, Export/Share logs to local storage, and Clear logs.
4. **Configuration Import & Export (Backup/Restore):**
   * Backup/Restore your entire protection configuration (profiles, protected packages, boot settings) via clean JSON.
   * Does NOT export private keys or credentials.
   * Validates imported package names using package syntax regex to filter out corrupt/malicious strings.
   * Supports import from Clipboard or local backup file.
5. **IPv6 Protection with Fallback:**
   * Attempts to establish IPv6 fail-closed routing (`::/0`) into the blackhole interface.
   * Dynamically catches device limitations or OS-level IPv6 support errors and falls back to a secure IPv4-only fail-closed route.
   * Accurately reports whether IPv6 is protected on the dashboard and diagnostics screens (never claims protection when unsupported).
6. **Start-on-Boot Reliability:**
   * Receiver evaluates `VpnService.prepare` status on startup.
   * Safe execution avoids boot crashes and loops.
   * Persists and displays boot failure diagnostics (e.g. if permissions were revoked).

---

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

---

## Known Limitations & Security Considerations

1. **System-level restriction:** Because Android only allows one VPN app, TunnelGuard's fail-closed interface cannot run at the same time as a standard on-device VPN app like Proton VPN. It is designed to act as the firewall wrapper itself, or be used in environments where the VPN is configured or simulated via state simulation tools.
2. **System Apps Bypass:** Certain system-level apps or Google Play Services may bypass VPN interfaces if specifically exempted by Android OS configurations.
3. **IPv6 Leaks on Unsupported Hardware:** On legacy devices or custom ROMs where the kernel doesn't support local VPN IPv6 routes, IPv6 traffic is unprotected. Ensure IPv6 is disabled in your router/modem or TV settings if your hardware falls back to IPv4-only.

---

## License

This project is licensed under the MIT License - see the `LICENSE` file for details.
