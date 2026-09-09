# Fix: Proxy alert listener hardening + spoofing analysis (#2868)

**Issue:** [#2868](https://github.com/GrimAnticheat/Grim/issues/2868)
**Affected platform:** Bukkit / Paper backends behind BungeeCord (or Velocity via the BungeeCord channel)
**Branch:** `fix/2868-proxy-alert-hardening`

## Two distinct problems in the report

1. **Crash on malformed input (fixed here).** Malformed messages throw out of the PacketEvents listener because part of the payload is parsed before/around the existing exception handler.
2. **Spoofing (design gap, documented — not fully fixed).** A client connected directly to the backend can send a `GRIMAC` message on the `BungeeCord` plugin channel and display arbitrary MiniMessage (including hover/click) to staff, because the receiver cannot verify the message came from the proxy.

---

## Part 1 — Listener hardening (fixed)

### Root cause

`ProxyAlertMessenger.onPacketReceive` read the payload like this:

```java
ByteArrayDataInput in = ByteStreams.newDataInput(wrapper.getData());
if (!in.readUTF().equals("GRIMAC")) return;      // can throw IllegalStateException on truncated data
byte[] messageBytes = new byte[in.readShort()];  // negative length -> NegativeArraySizeException
in.readFully(messageBytes);                       // can throw IllegalStateException
try {
    alert = new DataInputStream(...).readUTF();    // only THIS was guarded
} catch (IOException exception) { ... }
```

The channel is attacker-reachable (any client can send plugin messages on `BungeeCord`). Guava's `ByteArrayDataInput` throws `IllegalStateException` on short reads, and `new byte[negativeShort]` throws `NegativeArraySizeException`. Those calls sat **outside** the `try`, so a malformed payload propagated out of the listener:

```
PacketEvents caught an unhandled exception while calling your listener.
```

### The fix

Wrap the **entire** parse in one `try/catch`, reject negative lengths, and drop malformed payloads silently:

```java
final Component message;
try {
    ByteArrayDataInput in = ByteStreams.newDataInput(wrapper.getData());
    if (!in.readUTF().equals("GRIMAC")) return;
    int length = in.readShort();
    if (length < 0) return;                 // malformed / spoofed payload
    byte[] messageBytes = new byte[length];
    in.readFully(messageBytes);
    String alert = new DataInputStream(new ByteArrayInputStream(messageBytes)).readUTF();
    message = MessageUtil.miniMessage(alert);
} catch (Exception exception) {
    return;                                  // untrusted input — never throw out of the listener
}
GrimAPI.INSTANCE.getAlertManager().sendAlert(message, null);
```

File: `common/src/main/java/ac/grim/grimac/events/packets/ProxyAlertMessenger.java`

### Reproduce

1. Paper backend behind BungeeCord with `alerts.proxy.receive: true` and a staff member with alert listeners.
2. From a client connected **directly** to the backend, send a `BungeeCord`-channel plugin message whose body is `GRIMAC` followed by a **truncated** or **negative-length** blob.
3. Before: `PacketEvents caught an unhandled exception while calling your listener`.
4. After: the malformed packet is dropped, no exception.

---

## Part 2 — Spoofing (design gap, NOT fully fixed)

### Why it can't be fixed by hardening alone

BungeeCord/Velocity forward alerts by having the **proxy** send a normal plugin message to the backend. From the backend's point of view a forwarded alert and a directly-injected one are byte-for-byte identical — there is no field that proves proxy origin. So any client that can reach the backend directly on the `BungeeCord` channel can display arbitrary staff alerts. Hardening (Part 1) stops crashes but a *well-formed* spoofed message still renders.

### Recommended mitigations (need a maintainer decision)

1. **Deployment (works today, no code):** firewall backends so only the proxy can reach them, and never expose backend ports publicly. This is the canonical proxy-hardening practice and already defeats the attack. Worth documenting in Grim's config comments.
2. **Optional shared secret (code change, recommended):** add `alerts.proxy.secret`. The sender prepends an HMAC (or the secret itself) to the forwarded payload; the receiver rejects messages whose secret doesn't match. When the secret is blank, behave exactly as today (backwards compatible). This is a real fix but:
   - it changes the forwarded payload format, so **all** backends must run a version that understands the new field (mixed-version fleets need the empty-secret fallback), and
   - it belongs to a maintainer, since it touches config schema + both send and receive paths.

This writeup deliberately ships only the safe hardening (Part 1). Part 2 is documented with a concrete recommendation rather than force-landing a wire-format/config change unilaterally.

## Verification

- Bukkit module compiles cleanly; the built jar in this folder contains the hardened `ProxyAlertMessenger`.
- The parse is now fully enclosed in try/catch and rejects negative lengths.
