/*
 * Probes what a directory offers to Linux programs run by DroidNative:
 * executable mappings (directly, through an anonymous copy, through a
 * memfd), POSIX features (symlinks, hard links, FIFOs, sockets, modes,
 * case sensitivity, sparse files) and throughput. Used to compare the app's
 * private folder on the SD card with internal storage.
 *
 * Build (static, bionic, runs under PRoot when copied into the guest):
 *   $NDK/toolchains/llvm/prebuilt/linux-x86_64/bin/aarch64-linux-android29-clang \
 *       -O2 -static -o sd-probe tests/device/sd-probe.c
 * Usage: sd-probe <dir> [small files, default 2000] [MiB, default 256]
 * With tests/device/app-sh.sh -m the SD folder is at /mnt/sd.
 */
#define _GNU_SOURCE
#include <errno.h>
#include <fcntl.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/mman.h>
#include <sys/socket.h>
#include <sys/stat.h>
#include <sys/syscall.h>
#include <sys/un.h>
#include <time.h>
#include <unistd.h>

static char dir[512];
static int nfiles = 2000;
static size_t mib = 256;

static const char *p(const char *name)
{
    static char buf[4][600];
    static int i;
    i = (i + 1) % 4;
    snprintf(buf[i], sizeof buf[i], "%s/%s", dir, name);
    return buf[i];
}

static double now(void)
{
    struct timespec t;
    clock_gettime(CLOCK_MONOTONIC, &t);
    return t.tv_sec + t.tv_nsec / 1e9;
}

static void result(const char *what, int ok, int err)
{
    printf("%-28s %s", what, ok ? "yes" : "NO");
    if (!ok && err) printf(" (%s)", strerror(err));
    printf("\n");
}

/* Code */

// mov w0, #42; ret
static const uint32_t code[] = { 0x52800540, 0xd65f03c0 };

static int call(void *mem)
{
    __builtin___clear_cache((char *)mem, (char *)mem + sizeof code);
    return ((int (*)(void))mem)();
}

static void probe_exec(void)
{
    int fd = open(p("code.bin"), O_RDWR | O_CREAT | O_TRUNC, 0755);
    if (fd < 0 || write(fd, code, sizeof code) != sizeof code) {
        result("write code file", 0, errno);
        return;
    }
    void *m = mmap(NULL, 4096, PROT_READ | PROT_EXEC, MAP_PRIVATE, fd, 0);
    int err = errno;
    result("exec mmap of file", m != MAP_FAILED && call(m) == 42, err);
    if (m != MAP_FAILED) munmap(m, 4096);

    m = mmap(NULL, 4096, PROT_READ | PROT_WRITE, MAP_PRIVATE, fd, 0);
    err = m == MAP_FAILED ? errno : 0;
    int ok = 0;
    if (m != MAP_FAILED) {
        ok = mprotect(m, 4096, PROT_READ | PROT_EXEC) == 0;
        err = errno;
        if (ok) ok = call(m) == 42;
        munmap(m, 4096);
    }
    result("mprotect file map to exec", ok, err);

    m = mmap(NULL, 4096, PROT_READ | PROT_WRITE, MAP_PRIVATE | MAP_ANONYMOUS, -1, 0);
    pread(fd, m, sizeof code, 0);
    ok = mprotect(m, 4096, PROT_READ | PROT_EXEC) == 0;
    err = errno;
    result("anonymous copy to exec", ok && call(m) == 42, err);
    munmap(m, 4096);

    int mfd = syscall(SYS_memfd_create, "dn-probe", 0);
    ok = 0;
    err = errno;
    if (mfd >= 0 && write(mfd, code, sizeof code) == sizeof code) {
        m = mmap(NULL, 4096, PROT_READ | PROT_EXEC, MAP_PRIVATE, mfd, 0);
        err = errno;
        ok = m != MAP_FAILED && call(m) == 42;
        close(mfd);
    }
    result("memfd copy to exec", ok, err);
    close(fd);
    unlink(p("code.bin"));
}

/* POSIX features */

static void probe_posix(void)
{
    int fd = open(p("Target"), O_RDWR | O_CREAT | O_TRUNC, 0644);
    write(fd, "x", 1);
    close(fd);

    struct stat st;
    result("case sensitive names", stat(p("target"), &st) != 0, 0);
    result("symlink", symlink("Target", p("sym")) == 0, errno);
    result("hard link", link(p("Target"), p("hard")) == 0, errno);
    result("mkfifo", mkfifo(p("fifo"), 0600) == 0, errno);

    int s = socket(AF_UNIX, SOCK_STREAM, 0);
    struct sockaddr_un a = { .sun_family = AF_UNIX };
    snprintf(a.sun_path, sizeof a.sun_path, "%s", p("sock"));
    result("unix socket bind", bind(s, (struct sockaddr *)&a, sizeof a) == 0, errno);
    close(s);

    chmod(p("Target"), 0751);
    stat(p("Target"), &st);
    printf("%-28s %o (set 751)\n", "mode kept", st.st_mode & 07777);
    printf("%-28s %d:%d\n", "owner", st.st_uid, st.st_gid);

    fd = open(p("sparse"), O_RDWR | O_CREAT | O_TRUNC, 0644);
    int ok = ftruncate(fd, 1L << 30) == 0;
    int err = errno;
    fstat(fd, &st);
    printf("%-28s %s, %lld KiB allocated for 1 GiB\n", "sparse file", ok ? "created" : strerror(err),
        (long long)st.st_blocks / 2);
    close(fd);

    fd = open(p("shared"), O_RDWR | O_CREAT | O_TRUNC, 0644);
    ftruncate(fd, 4096);
    char *m = mmap(NULL, 4096, PROT_READ | PROT_WRITE, MAP_SHARED, fd, 0);
    ok = 0;
    if (m != MAP_FAILED) {
        memcpy(m, "shared-ok", 9);
        msync(m, 4096, MS_SYNC);
        char back[10] = { 0 };
        pread(fd, back, 9, 0);
        ok = memcmp(back, "shared-ok", 9) == 0;
        munmap(m, 4096);
    }
    result("shared writable mmap", ok, errno);
    close(fd);

    const char *names[] = { "Target", "target", "sym", "hard", "fifo", "sock", "sparse", "shared" };
    for (size_t i = 0; i < sizeof names / sizeof *names; i++) unlink(p(names[i]));
}

/* Random access inside one big file (what an image file needs) */

static void probe_random(void)
{
    const size_t size = 64 << 20;
    char *buf = aligned_alloc(4096, 1 << 20);
    memset(buf, 0x33, 1 << 20);
    int fd = open(p("image"), O_RDWR | O_CREAT | O_TRUNC, 0644);
    for (size_t off = 0; off < size; off += 1 << 20) pwrite(fd, buf, 1 << 20, off);
    fsync(fd);
    close(fd);

    // O_DIRECT bypasses the page cache; without it, drop the file's cache
    fd = open(p("image"), O_RDWR | O_DIRECT);
    int direct = fd >= 0;
    if (!direct) {
        fd = open(p("image"), O_RDWR);
        posix_fadvise(fd, 0, 0, POSIX_FADV_DONTNEED);
    }
    srand(1);
    const int n = 300;
    double t = now();
    for (int i = 0; i < n; i++) pread(fd, buf, 4096, (off_t)(rand() % (size / 4096)) * 4096);
    double r = now() - t;
    t = now();
    for (int i = 0; i < n; i++) pwrite(fd, buf, 4096, (off_t)(rand() % (size / 4096)) * 4096);
    double w = now() - t;
    t = now();
    for (int i = 0; i < 30; i++) {
        pwrite(fd, buf, 4096, (off_t)(rand() % (size / 4096)) * 4096);
        fdatasync(fd);
    }
    double ws = now() - t;
    t = now();
    for (int i = 0; i < 64; i++) pread(fd, buf, 1 << 20, (off_t)(rand() % 64) << 20);
    double big = now() - t;
    close(fd);
    unlink(p("image"));
    printf("%-28s %s: read %.0f/s, write %.0f/s, write+fdatasync %.0f/s, 1 MiB reads %.0f MB/s\n",
        "random 4 KiB in 64 MiB file", direct ? "O_DIRECT" : "cache dropped", n / r, n / w, 30 / ws, 64 / big);
    free(buf);
}

/* Throughput */

static void probe_speed(void)
{
    const size_t block = 1 << 20, total = mib;
    char *buf = malloc(block);
    memset(buf, 0x5a, block);
    int fd = open(p("big"), O_RDWR | O_CREAT | O_TRUNC, 0644);
    double t = now();
    for (size_t i = 0; i < total; i++) write(fd, buf, block);
    fsync(fd);
    double w = now() - t;
    lseek(fd, 0, SEEK_SET);
    t = now();
    while (read(fd, buf, block) > 0) {}
    double r = now() - t;
    close(fd);
    unlink(p("big"));
    printf("%-28s write %.0f MB/s, read %.0f MB/s (%zu MB, read may be cached)\n", "sequential",
        total / w, total / r, total);

    mkdir(p("many"), 0755);
    char name[64];
    t = now();
    for (int i = 0; i < nfiles; i++) {
        snprintf(name, sizeof name, "many/f%d", i);
        fd = open(p(name), O_WRONLY | O_CREAT | O_TRUNC, 0644);
        write(fd, buf, 4096);
        close(fd);
    }
    double c = now() - t;
    struct stat st;
    t = now();
    for (int i = 0; i < nfiles; i++) {
        snprintf(name, sizeof name, "many/f%d", i);
        stat(p(name), &st);
    }
    double s = now() - t;
    t = now();
    for (int i = 0; i < nfiles; i++) {
        snprintf(name, sizeof name, "many/f%d", i);
        unlink(p(name));
    }
    double u = now() - t;
    rmdir(p("many"));
    printf("%-28s create %.0f/s, stat %.0f/s, unlink %.0f/s (%d x 4 KiB)\n", "small files",
        nfiles / c, nfiles / s, nfiles / u, nfiles);
    free(buf);
}

int main(int argc, char **argv)
{
    if (argc < 2) {
        fprintf(stderr, "usage: %s <dir> [small files] [MiB]\n", argv[0]);
        return 2;
    }
    if (argc > 2) nfiles = atoi(argv[2]);
    if (argc > 3) mib = (size_t)atoi(argv[3]);
    setvbuf(stdout, NULL, _IOLBF, 0);
    snprintf(dir, sizeof dir, "%s", argv[1]);
    printf("== %s\n", dir);
    probe_exec();
    probe_posix();
    probe_random();
    probe_speed();
    return 0;
}
