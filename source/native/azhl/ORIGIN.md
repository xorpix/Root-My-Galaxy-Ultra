# AZHL adapter provenance

Kernel primitives and offset tables derive from the user's Root-S26U-FIXED4.zip, SHA256 105e6b18fbae073559b157c6f8c59208bd6b95dac861450e60165ce4dd3adad6, source/ghostlock-azhl/exploit/src. Original LICENSE and NOTICE are retained.

The app-protocol helper derives from rushiranpise/Root-My-Galaxy-Payloads src/su_daemon.c at 7f1490bf0b65e1cfd1ef9f2f2fb0485dc4186dae. Local changes implement explicit backend/version handoff, shell-only authentication, exact AZHL identity, one attempt per boot, pre-load module disabling and live selected-driver verification.

The uploaded native su_daemon.c is retained as reference and is not built. Build with build.sh and NDK r28c. Build success does not verify actual phone kernel symbols or compatibility.
