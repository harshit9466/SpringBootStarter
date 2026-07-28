# Reliable Object Storage for Observability Data
### A Zero-to-Deep Understanding Guide — MinIO vs OpenShift Data Foundation (ODF)

> **Who this is for**: Someone who needs to understand this end-to-end themselves — not hand it
> off to "the DevOps team" as a black box. Assumes zero prior knowledge of storage systems.
>
> **Why this document exists**: Our local `docker-compose` setup (Prometheus, Loki, Tempo) uses
> `filesystem` storage — data sits on one disk, on one machine. That's fine for learning/practice
> (this repo), but wrong for BiharOne's real production logs, which need to survive for 6+ months
> reliably. Public cloud object storage (AWS S3, Azure Blob, GCS) is ruled out for a government
> project due to data localization requirements. This document covers the two realistic on-prem
> alternatives: **MinIO** and **OpenShift Data Foundation (ODF)**.

---

## Table of Contents

1. [Part 0 — Foundations](#part-0--foundations)
2. [Part 1 — MinIO, Complete Deep Dive](#part-1--minio-complete-deep-dive)
3. [Part 2 — OpenShift Data Foundation (ODF), Complete Deep Dive](#part-2--openshift-data-foundation-odf-complete-deep-dive)
4. [Part 3 — MinIO vs ODF — Decision Framework](#part-3--minio-vs-odf--decision-framework)
5. [Part 4 — How This Connects to What We Already Built](#part-4--how-this-connects-to-what-we-already-built)
6. [Part 5 — Practical Action Items](#part-5--practical-action-items)

---

## Part 0 — Foundations

### 0.1 The Problem We're Solving, Restated

Loki (our log database) needs somewhere to physically write its data. Right now, in this
practice repo, that "somewhere" is a Docker named volume — which is really just a folder on one
laptop's disk. Three things can destroy that folder: a person running the wrong command
(`docker-compose down -v` — we did this ourselves earlier in this project), a Docker Desktop
reset, or the machine itself dying. For 6 months of real citizen-service logs, none of that is
acceptable.

### 0.2 Three Fundamentally Different Ways to Store Data

Before MinIO or ODF make any sense, you need to know these three models exist, because they
solve different problems:

**Block storage** — the most primitive. A raw, numbered sequence of storage blocks, like a stack
of blank index cards with numbers on them. The operating system decides what goes on which card.
This is what a normal hard disk, or a cloud "virtual disk" (AWS EBS, Azure Disk), looks like to
the computer using it. Fast, but only one machine can "own" it at a time in the simple case.

**File storage** — a layer on top of block storage that adds the concept of folders and
filenames (`/loki/chunks/abc123`). This is what your Windows/Linux filesystem gives you. Multiple
machines CAN share file storage over a network (NFS is the classic example), but it wasn't
designed for the internet-scale, massively-parallel access patterns of modern applications.

**Object storage** — a completely different model. There are no folders, no file paths in the
traditional sense. You store a "blob" of bytes (an object) under a flat key (like
`bucket-name/2026-07-28-logs.chunk`), and you talk to it over plain HTTP, not through the
operating system's file APIs. This is the model Amazon invented for S3 in 2006, and it turned out
to be exactly the right shape for storing huge amounts of write-once, rarely-modified data
(backups, logs, trace chunks, media files) with built-in redundancy.

> **Analogy**: Block/file storage is like a personal filing cabinet in your own office — fast to
> reach, but if your office burns down, it's gone. Object storage is like a professional
> records-storage warehouse (think Iron Mountain) — you hand them a labeled box (an object), they
> store it across multiple secure facilities automatically, and you can always ask for it back by
> its label, regardless of which specific facility it physically ended up in.

### 0.3 "S3" Is a Protocol, Not Just Amazon's Product — The Single Most Important Fact Here

This is the fact that unlocks everything else in this document.

When Amazon built S3, they also published the **API** — the exact set of HTTP requests and
responses a client uses to store and retrieve objects (`PUT /bucket/key`, `GET /bucket/key`,
etc.). That API became a de facto industry standard. Any software that speaks this exact same
HTTP API is called "**S3-compatible**" — regardless of who built it or where it runs.

**MinIO and ODF's NooBaa component are both S3-compatible object storage systems that you run
entirely on your own hardware.** Loki doesn't know or care that it isn't talking to Amazon — it
just sends the same S3 API requests to whatever URL you configure, and as long as something on
the other end speaks that protocol, it works.

> **Analogy**: "S3" is like "USB" — Amazon invented S3 the way one company invented the first USB
> port, but today any manufacturer can build a USB-compatible drive without paying that original
> company or sending your files through their servers. Ruling out "Amazon" doesn't rule out
> "USB-shaped things you own yourself."

### 0.4 What "Reliable" Actually Means — Replication and Erasure Coding

Two different (but related) techniques make storage survive hardware failure:

**Replication** — keep N full copies of every object on N different disks/servers. Simple, but
wasteful: 3x replication means you need 3x the disk space of your actual data.

**Erasure coding** — split each object into data fragments AND extra "parity" fragments (similar
math to how a Sudoku puzzle lets you reconstruct a missing number from the others), spread across
multiple disks. If some fragments are lost (a disk dies), the object can still be fully
reconstructed from the surviving fragments. This gives similar failure protection to replication
but uses far less extra disk space.

> **Analogy for erasure coding**: Imagine writing a message, then creating a few "checksum" notes
> that let you recover the original message even if some of the actual message pieces go missing
> — as long as you don't lose too many pieces at once, you can always reconstruct the whole thing.

Both MinIO and ODF use erasure coding internally — this is precisely what makes them "reliable"
in a way a single Docker volume never can be, because the data is never sitting on just one disk.

### 0.5 A Tempting But Wrong Option: "Just Use NFS, We Already Have It"

Many government/enterprise environments already have NFS (Network File System — a decades-old
protocol for mounting a remote folder over the network) available, making it a tempting
"already have it, why not use it" option. Two things to know before considering it:

**NFS by itself does not give you reliability.** NFS is a file-SHARING protocol, not a
redundancy mechanism. If the single NFS server behind it has one disk, that's still one disk —
NFS doesn't add erasure coding or replication on its own. Reliability only exists if whatever is
*behind* the NFS export already has it (a proper RAID array, a redundant NAS appliance).

**Loki specifically has real, documented stability problems on NFS** — this isn't theoretical.
Grafana Loki's own GitHub issue tracker has multiple confirmed reports:
- ["Can't run stably on NFS PV" (#9482)](https://github.com/grafana/loki/issues/9482)
- ["error creating index client while using nfs pvc" (#4720)](https://github.com/grafana/loki/issues/4720)
- ["Panic on corrupted boltdb-shipper-cache gzip file" (#5192)](https://github.com/grafana/loki/issues/5192)

The root cause: Loki's index component (`boltdb-shipper`/`tsdb`) depends on proper file-locking
semantics that NFS — especially older versions — doesn't reliably provide, leading to crashes
and index corruption under real load. This isn't unique to Loki; most modern "cloud native"
databases (Prometheus, Tempo, Elasticsearch) are built and tested against either genuinely local
disk or object storage, not network file-sharing protocols like NFS, for their actual data plane.

**Conclusion**: NFS is not a safe substitute for MinIO/ODF here, even though it may already exist
in BiharOne's infrastructure for other purposes (file shares, document storage, etc.).

### 0.6 Why Government/On-Prem Rules Out AWS, Not "Object Storage"

Public cloud object storage (real AWS S3, Azure Blob, GCS) means your data physically lives on
servers owned and operated by a private American/foreign company, in data centers whose exact
location and jurisdiction you don't fully control. For a government citizen-services platform,
that conflicts with data localization requirements — this is a completely valid, common
constraint, not something to work around.

MinIO and ODF give you the SAME storage model (object storage, S3 API, erasure coding) while
running 100% on hardware physically inside your own (or a government-empanelled) data center.
Nothing about the *technology* is cloud-dependent — only Amazon's specific *hosted service* is
being ruled out, and correctly so.

---

## Part 1 — MinIO, Complete Deep Dive

### 1.1 What MinIO Is, In One Sentence

MinIO is free, open-source software that turns a set of ordinary servers (or Kubernetes pods)
with attached disks into an S3-compatible object storage system — you install it on hardware you
already own or control, and nothing it stores ever leaves that hardware unless you explicitly
configure it to.

### 1.2 How MinIO Achieves Reliability — Erasure Coding in Practice

**Verified minimums** (from MinIO's own official documentation, not guessed):
- MinIO's distributed mode requires a **minimum of 4 drives** to enable erasure coding at all.
- MinIO organizes drives into "erasure sets" of **2 to 16 drives** each — the total drive count
  must be a multiple of one of those set sizes.
- MinIO officially **recommends a minimum of 4 server nodes** in a "Server Pool" for real
  production high availability — not just 4 drives on one machine, but 4 separate machines.
- With erasure coding active, the cluster can lose **up to roughly half its drives** (N/2) and
  still serve every object correctly, with zero data loss.

**Concretely, what "4 nodes, minimum" means for BiharOne**: this isn't a single server running
MinIO software — it's (at minimum) 4 separate machines (physical or virtual), each contributing
disks to one distributed MinIO cluster, so that losing any one or two of those machines doesn't
take down the storage or lose data.

### 1.3 MinIO's Architecture on Kubernetes/OKD

On a Kubernetes/OKD cluster, MinIO is deployed via the **MinIO Operator** — the standard,
officially supported way to run it. The Operator introduces a Kubernetes custom resource called a
**Tenant**. Conceptually:

```
MinIO Operator (installed once per cluster)
        │
        ▼
   Tenant (a custom resource you create — "give me a MinIO cluster")
        │
        ├── Defines: how many server pods, how many drives per pod,
        │            how much storage per drive, erasure coding settings
        │
        ▼
   Kubernetes creates: StatefulSet of MinIO pods, each backed by its
   own PersistentVolumeClaim (so each pod's drives are real, durable
   Kubernetes storage — typically backed by your cluster's block storage)
```

The Operator handles automatic TLS certificate management and multi-tenancy (multiple isolated
MinIO clusters on the same underlying Kubernetes cluster, if BiharOne ever needs to give
different services their own isolated storage).

**Important nuance**: MinIO pods still need SOMETHING to store their drives on — typically
Kubernetes PersistentVolumeClaims backed by whatever block storage the cluster already has
(local disks, a SAN, etc.). MinIO's erasure coding then spreads data across MULTIPLE such PVCs
on MULTIPLE nodes, so no single underlying disk failure loses data — this is what turns "several
unreliable individual disks" into "one reliable storage system."

### 1.4 How Loki Would Actually Connect to MinIO

Once a MinIO Tenant exists and a bucket is created inside it, Loki's config changes from:

```yaml
storage_config:
  filesystem:
    directory: /loki/chunks
```

to (conceptually — exact field names should be verified against the Loki version actually
deployed, same discipline as everywhere else in this project):

```yaml
storage_config:
  aws:                          # Loki calls the S3-compatible client config "aws" historically
    s3: http://minio-tenant-hl.minio-namespace.svc:9000
    bucketnames: loki-chunks
    access_key_id: <from a Kubernetes Secret>
    secret_access_key: <from a Kubernetes Secret>
    s3forcepathstyle: true      # required for non-AWS S3-compatible endpoints like MinIO
```

`s3forcepathstyle: true` is a genuinely important, easy-to-miss detail: AWS's real S3 uses one
URL style by default, while self-hosted S3-compatible systems (MinIO included) typically need the
older "path style" URL format — forgetting this is a common, confusing early mistake.

### 1.5 Operational Considerations

Running MinIO yourself means BiharOne's own team now owns:
- **Monitoring MinIO's own health** (disk usage, node availability) — ironically, you'd want to
  feed MinIO's own metrics into the SAME Prometheus/Grafana stack this project already builds.
- **Capacity planning** — deciding upfront how much raw disk to give the MinIO cluster, and
  expanding it before it fills up.
- **Upgrades** — MinIO releases updates; someone needs to apply them.
- **Access control** — MinIO has its own user/policy system (similar in spirit to AWS IAM) for
  controlling which applications can read/write which buckets.

### 1.6 MinIO — Pros and Cons

| Pros | Cons |
|---|---|
| Free, open-source (no licensing cost) | BiharOne's own team owns all operations |
| Battle-tested, widely used in exactly this "on-prem S3" role | Requires dedicated nodes/disks (minimum 4 for real HA) |
| True S3 API compatibility — works with Loki, Tempo, backups, anything | No built-in block/file storage — object storage only |
| Simple mental model — one focused tool | You are responsible for monitoring, upgrades, capacity |

---

## Part 2 — OpenShift Data Foundation (ODF), Complete Deep Dive

### 2.1 What ODF Is, In One Sentence

ODF is Red Hat's own storage product, deeply integrated into OpenShift/OKD, that gives a cluster
block storage, file storage, AND S3-compatible object storage all from one managed system built
on top of **Ceph** (a mature, widely-used open-source distributed storage engine) — but unlike
MinIO, this is a licensed Red Hat product, not free.

### 2.2 How ODF Differs From MinIO — It's a Platform, Not Just Object Storage

MinIO does ONE thing (object storage) and does it simply. ODF is broader: it provides all three
storage models from Part 0 (block, file, AND object) from a single underlying system, and it's
meant to be the general-purpose storage layer for an entire OpenShift cluster — not just for
logs. If BiharOne's OKD cluster needs persistent storage for databases, file shares, AND
S3-compatible buckets, ODF is designed to provide all of that from one place.

### 2.3 ODF's Architecture — Ceph Plus NooBaa (Verified, Not Guessed)

**Ceph** is the underlying storage engine — it's what actually manages the physical disks, the
replication/erasure coding, and provides the block and file storage interfaces.

**NooBaa**, specifically its **Multicloud Object Gateway (MCG)** component, is the piece that
provides the **S3-compatible object storage API** — this is the part that matters for Loki.
Per Red Hat's own architecture documentation:

- The `ocs-operator` (Red Hat's OpenShift Container Storage operator) creates the custom
  resources that configure both Ceph storage AND the Multicloud Object Gateway.
- NooBaa runs as a set of pods: a **core** pod, a **database** pod, and **endpoint** pods (these
  are what actually receive S3 API traffic — the endpoint pods automatically scale up or down
  based on how much S3 request load they're handling).
- A **BackingStore** defines WHERE data for a bucket physically lands (which underlying storage).
- A **BucketClass** defines the policy for how buckets using it behave, referencing a
  BackingStore.
- Applications request a bucket via an **Object Bucket Claim (OBC)** — a Kubernetes custom
  resource that works exactly like a PersistentVolumeClaim, except it hands you S3 credentials
  and a bucket name instead of a mounted filesystem path.

> **The OBC/PVC parallel is worth sitting with**: you already understand PVC — "I ask Kubernetes
> for storage, Kubernetes hands me something ready to use, backed by whatever the underlying
> storage system decided." OBC is the exact same pattern, just for an S3 bucket instead of a
> mounted disk. Loki (or any app) creates an OBC, and gets back a bucket name + access key +
> secret key it can use immediately, without knowing or caring what's physically underneath.

### 2.4 Licensing — The Real Cost Consideration

ODF is **not free** — it requires a Red Hat subscription/entitlement on top of (or bundled with)
an OpenShift subscription. For a government project, this becomes a genuine procurement
question: does BiharOne's existing Red Hat OpenShift agreement already include ODF, or would it
need to be purchased/negotiated separately? This is worth confirming with whoever manages
BiharOne's Red Hat licensing before assuming ODF is "free to turn on."

### 2.5 How Loki Would Connect to ODF

Conceptually similar to MinIO — Loki's `storage_config` points at an S3-style endpoint, except
the endpoint is NooBaa's S3 Route/Service instead of a MinIO Tenant's service:

```yaml
storage_config:
  aws:
    s3: http://s3.openshift-storage.svc:443    # NooBaa's internal S3 service, exact hostname
                                                 # depends on your ODF install namespace/version
    bucketnames: loki-chunks
    access_key_id: <from the Secret an OBC generates>
    secret_access_key: <from the Secret an OBC generates>
```

In practice, if Red Hat's own **Loki Operator** is used to run Loki (recommended over hand-rolling
Loki's config on OpenShift), it can be pointed directly at an ODF-backed object bucket during
setup, and much of this wiring is handled through the Operator's own configuration UI/CRD rather
than hand-written YAML.

### 2.6 ODF — Pros and Cons

| Pros | Cons |
|---|---|
| One system for block + file + object — not just logs | Requires paid Red Hat subscription/entitlement |
| Deeply integrated with OpenShift's own console/operators | More complex system overall (full Ceph underneath) |
| Backed by Red Hat support (matters for a government SLA) | Needs dedicated infrastructure nodes, similar to MinIO |
| Natively supported by Red Hat's own Loki Operator | Steeper operational learning curve than MinIO alone |

---

## Part 3 — MinIO vs ODF — Decision Framework

| Question | Leans MinIO | Leans ODF |
|---|---|---|
| Do you only need object storage (for Loki/Tempo/backups)? | ✅ | |
| Do you also need general block/file storage for databases etc.? | | ✅ |
| Is budget/licensing cost a hard constraint? | ✅ (free) | |
| Does BiharOne already have a Red Hat OpenShift + ODF subscription? | | ✅ (already paid for) |
| Do you want the absolute simplest possible system to operate? | ✅ | |
| Do you want official Red Hat support/SLA on the storage layer? | | ✅ |
| Is this OKD (the free/community distribution) rather than paid OpenShift? | ✅ (ODF licensing usually assumes paid OpenShift) | |

**A practical note on OKD specifically**: ODF is typically licensed and supported alongside paid
Red Hat OpenShift (OCP), not the free community OKD distribution this course has been discussing.
If BiharOne is running genuine OKD (not a paid OpenShift subscription), MinIO is very likely the
more realistic starting point — worth confirming which one BiharOne actually runs, since the
name similarity ("OKD" vs "OpenShift") hides a real licensing distinction.

---

## Part 4 — How This Connects to What We Already Built

### 4.1 Retention Sits on Top, Regardless of Which Storage Is Chosen

Whichever of MinIO or ODF gets chosen, the `retention_period` + `compactor.retention_enabled`
config from the earlier Loki discussion still applies exactly the same way — that config
controls "how long to keep before auto-deleting," while MinIO/ODF answers "will it actually
survive that long." Both pieces are needed together.

### 4.2 The Loki Config Change, Conceptually

Only the `storage_config` (and `object_store` value in `schema_config`) changes — from
`filesystem` to `s3` (used generically for any S3-compatible backend, including MinIO and
NooBaa). Everything else this project already built — the `loki-logback-appender` pushing logs
from the Spring Boot app, the Grafana Loki datasource, the labels/structured-metadata design —
stays exactly the same. The application and Grafana never need to know or care whether Loki's
data sits on `filesystem`, MinIO, or ODF underneath.

### 4.3 The Same Logic Applies to Tempo

Tempo's `storage.trace.backend` config has the exact same `local` vs `s3` choice as Loki's
`storage_config`. Once a MinIO/ODF bucket exists for logs, the same underlying object storage
system can hold trace data too — usually as a separate bucket, not mixed with Loki's.

---

## Part 5 — Practical Action Items

Concrete questions to bring to whoever manages BiharOne's actual OKD/OpenShift infrastructure:

```
□ Is this cluster genuine OKD (community, free) or licensed OpenShift (OCP)?
□ Does an existing Red Hat subscription already include ODF entitlement?
□ Is MinIO (or any other S3-compatible storage) already running anywhere in
  BiharOne's infrastructure for another purpose (backups, artifacts, etc.)?
□ How many spare nodes/disks are realistically available for a dedicated
  storage cluster (MinIO needs a minimum of 4 nodes for real HA)?
□ Is the "Red Hat OpenShift Logging" add-on (which bundles the Loki Operator)
  already available via OperatorHub on this cluster?
□ Who owns the decision and budget for a paid ODF entitlement, if that path
  is chosen over free MinIO?
```

---

## Sources

This document was written using verified information from:
- [MinIO distributed mode documentation](https://github.com/minio/minio/blob/master/docs/distributed/README.md)
- [MinIO Erasure Coding concepts](https://min.io/docs/minio/kubernetes/upstream/operations/concepts/erasure-coding.html)
- [Red Hat OpenShift Data Foundation architecture docs (v4.9–4.17)](https://docs.redhat.com/en/documentation/red_hat_openshift_data_foundation/4.17/html/planning_your_deployment/odf-architecture_rhodf)
- [Red Hat OpenShift Logging — LokiStack storage configuration](https://docs.redhat.com/en/documentation/red_hat_openshift_logging/6.3/html/configuring_logging/configuring-lokistack-storage)
- [Loki Operator — Object Storage requirements](https://loki-operator.dev/docs/object_storage.md/)

Exact YAML/CRD field names for MinIO Tenants and ODF NooBaa endpoints should be re-verified
against whatever specific OKD/OpenShift and Operator versions BiharOne's cluster actually runs
before implementing — this document teaches the concepts and verified architectural facts, not
a copy-paste deployment script for an environment this repo has no visibility into.

---

*Document maintained as reference material. Update if BiharOne's actual infrastructure decision
(MinIO vs ODF vs something else) is made, so this reflects the real chosen path.*
