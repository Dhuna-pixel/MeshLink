# MeshLink 2.0

> **Infrastructure-Independent Multi-Hop Device-to-Device Messaging**
>
> An undergraduate engineering project exploring application-level multi-hop routing across nearby Android and iOS devices without relying on internet infrastructure.

---

## 📌 Overview

MeshLink is a mobile prototype that demonstrates **infrastructure-independent, multi-hop communication** between nearby devices.

Instead of requiring messages to travel through a centralized server, cellular network, Wi-Fi access point, or internet connection, MeshLink allows participating devices to act as **source, relay, or destination nodes**.

A message can therefore travel through intermediate devices:

```text
┌─────────┐       ┌─────────┐       ┌─────────┐
│ Node A  │ ────► │ Node B  │ ────► │ Node C  │
│ Source  │       │ Relay   │       │  Dest.  │
└─────────┘       └─────────┘       └─────────┘
