# MeshLine Privacy Policy

**Last updated: 15 August 2026**

MeshLine is an offline, phone-to-phone emergency messaging app. This policy
describes exactly what the app does with your information.

Publish this document at a public URL and enter that URL in the Play Console
listing. Play requires a reachable privacy policy for any app that requests
location.

---

## The short version

MeshLine has no servers, no accounts, and no analytics. Nothing you do in the app
is sent to us, because there is nowhere for it to be sent. Your messages travel
directly between phones over Bluetooth.

---

## What MeshLine collects

**We collect nothing.** The developer operates no backend service and receives no
data from the app whatsoever. There is no telemetry, no crash reporting SDK, no
advertising identifier, and no third-party analytics library in the build.

## What stays on your device

| Data | Purpose | Leaves your device? |
|---|---|---|
| Mesh identity key | Signs your messages so others can verify they are genuinely from you | Only the public half, inside messages you send |
| Messages you send and receive | Displayed in the app | Only as you direct (see below) |
| Session and group keys | Encrypt your private chats and groups | No — only ciphertext produced with them |
| Which contacts you have verified | Shows whether an identity has been checked in person | No |
| Resource pins | Shown on your Pins screen | Only pins you choose to create |
| Approximate positions of received pins | Shown relative to you | No |

All of this is encrypted on your device with keys held in the Android Keystore,
and excluded from cloud backup and device transfer. Your message history is
stored encrypted and capped at the most recent 2000 messages.

You can destroy all of it at any time with **Wipe all secure data** on the Radar
screen. That deletes every session key, group key, and stored message. It cannot
recall anything already sent, and it does not hide that MeshLine is installed.
Your mesh identity is deliberately kept, so that contacts who verified you still
recognise your device.

## What is transmitted, and when

MeshLine transmits only when you take an action, or when your device relays
traffic for the mesh:

- **When you send an SOS**: your message text and, if a position fix is
  available, your coordinates are broadcast to every nearby device running
  MeshLine. **A public SOS is not encrypted.** This is deliberate — it is what
  allows a stranger in range to read your distress call and help you. Anyone
  within Bluetooth range, running any compatible software, can read it.
- **When you send a chat message**: the text is end-to-end encrypted (Noise-XX)
  and can only be read by the recipient you selected. Devices that relay it
  cannot read it.
- **When you send a group message**: the text is end-to-end encrypted to that
  group's members only. Devices that relay it cannot read it, and cannot tell
  which group it belongs to — the packet is addressed to a value derived from
  the group key rather than to the group's name. What a nearby observer *can*
  see is that some group is active and which devices are transmitting for it.
  Message contents, the group's name, and its member list stay private.
- **When you create a resource pin**: the label, the pin type, and the
  coordinates of the pin are broadcast unencrypted so others can find the
  resource.
- **When your device relays**: MeshLine passes other people's packets onward to
  extend the network's reach. Relayed content is not stored beyond what is needed
  to forward it and to avoid forwarding the same packet twice.

## Location

MeshLine requests precise location for two purposes only:

1. To attach coordinates to an SOS or resource pin **that you choose to send**.
2. On Android 11 and earlier, because the operating system requires the location
   permission for Bluetooth scanning. MeshLine does not use scan results to
   determine your location, which is why the app declares `neverForLocation` on
   its Bluetooth scan permission.

Your location is never collected, logged, or transmitted in the background. If
you never send an SOS or place a pin, your location never leaves your device.

## Permissions

| Permission | Why |
|---|---|
| Nearby devices (Bluetooth scan, advertise, connect) | Discover nearby phones and exchange mesh packets |
| Precise location | Attach coordinates to an SOS or pin you send; required for Bluetooth scanning below Android 12 |
| Notifications | Show that the mesh relay is running |
| Foreground service (connected device) | Keep relaying while the app is in the background |

## Children

MeshLine is not directed at children and collects no data from anyone.

## Data deletion

All MeshLine data lives on your device. Uninstalling the app, or clearing its
storage from Android Settings, permanently deletes your mesh identity, your
messages, and your pins. There is no server-side copy to request deletion of.

## Changes

Material changes to this policy will be published at this URL with an updated
date, and noted in the app's release notes.

## Contact

Questions about this policy: **aldrinjose007@gmail.com**
