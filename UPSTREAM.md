# Android derivative provenance

Creator Android is an independent Android derivative of [YouRecApp](https://github.com/antirez/YouRecApp) by Salvatore Sanfilippo (antirez), initially based on commit `faf1815e82e1f2d5f03034a583363d3c3af464a2`.

Upstream source and Git history are retained. The original MIT license and dependency notices remain in LICENSE and THIRD_PARTY_NOTICES.md. YouRec name/icon and Apple-specific assets are not the branding or assets of the Android app. This fork is not an official Android release by antirez.

Android source is under `android/`. Project persistence, immutable original media, capture-ledger semantics, editing concepts and test cases are adapted from the upstream Swift implementation and specification. Android camera, playback, export and UI integrations are new implementations; optional upstream C/C++ components will be tracked individually if incorporated.

See `android/docs/PLAN.md` for product scope and `android/docs/STATUS.md` for implementation and validation status.
