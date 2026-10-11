package app.droidnative.env

import android.os.Build

/** Maps the device ABI to the Void Linux architecture and rootfs tarball. */
enum class VoidArch(val voidName: String, val abi: String) {
    AARCH64("aarch64", "arm64-v8a"),
    ARMV7L("armv7l", "armeabi-v7a"),
    X86_64("x86_64", "x86_64"),
    I686("i686", "x86");

    /** Rootfs tarball name, glibc flavour (Flatpak and most binaries need glibc). */
    fun rootfsName(date: String = ROOTFS_DATE) = "void-$voidName-ROOTFS-$date.tar.xz"

    fun rootfsUrl(date: String = ROOTFS_DATE) = "$ROOTFS_BASE_URL/${rootfsName(date)}"

    /** Turnip for KGSL is only built for ARM (see linux-side/srcpkgs). */
    val hasTurnipPackage get() = this == AARCH64

    /** Flathub ships no 32-bit ARM builds. */
    val supportsFlatpak get() = this == AARCH64 || this == X86_64

    companion object {
        const val ROOTFS_BASE_URL = "https://repo-default.voidlinux.org/live/current"
        const val ROOTFS_DATE = "20250202"
        const val SHA256_URL = "$ROOTFS_BASE_URL/sha256sum.txt"

        /** Primary ABI the app process runs as. */
        fun current(): VoidArch {
            val abi = Build.SUPPORTED_ABIS.firstOrNull() ?: "arm64-v8a"
            return entries.firstOrNull { it.abi == abi } ?: AARCH64
        }
    }
}
