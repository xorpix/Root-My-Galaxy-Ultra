# Backend refresh review — 10 November 2026

KowSU rewrote its advertised history between the bundled 6ecdd69224fe6769e6c8715a4ef2b91602b23362 and 21787ce135c988bae4244a44cfcb5a542aa3347f. The old object remains fetchable by its exact SHA. Endpoint comparison shows identical userspace/ksud and UAPI trees; the version count rises from 2740 to 2750. The kernel delta includes upstream file-reference balancing, BTF-resolved SELinux inode layout, policy generation/type tracking and less hot-path logging.

The scoped history bridge accepts only this old pin and the reviewed replacement or a proven descendant. Other rewrites still stop the refresh. The compatibility patch was rebased against the new endpoint. Three conflicts were resolved: initialize the symbol resolver once before Samsung KDP setup; run both the new SELinux inode initializer and Samsung DEFEX initializer with Samsung-aware credential/error cleanup; retain the Samsung no-text-patch guard alongside upstream stop_machine error reporting; retain the Samsung header in tp_marker without restoring removed logging.

The new patch requires a complete matched driver/daemon rebuild. Old binaries and manifests remain the baseline until that rebuild is staged. Native and automation checks are required; no new phone result is claimed.

The expired November 9 schedule exception was removed. The existing validated backend publication workflow can now also be started by a main-branch release-note commit whose first line explicitly names the computed next version as “Refresh and release VERSION”. Ordinary note edits do not publish. Default-branch, exact-source, tag, matched-payload and signing gates remain in place.
