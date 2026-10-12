/*
 * libdn-appenv: LD_PRELOADed into applications whose per-app settings must
 * not (or only partly) reach the programs they start. DroidNative sets:
 *
 *   DN_APP_PID       pid of the application (the launch shell execs into it)
 *   DN_APP_CHILDREN  "unlisted": children keep the settings unless they are
 *                    in the application list; "none": no child keeps them
 *   DN_APP_VARS      variables the app's settings set (space separated)
 *   DN_GL_VARS       the same for dn-gl, previous values in DN_GL_ORIG_<name>
 *
 * Every exec* and posix_spawn* of another process (a different pid than
 * DN_APP_PID, or any spawn) is checked: a child that must not keep the
 * settings gets the environment restored, and a listed program gets its own
 * settings from /run/droidnative/apps.list (program, backend,
 * GDK_DPI_SCALE, QT_SCALE_FACTOR; "-" for unset), run through dn-gl.
 * Spawns inside glibc (system, popen) are not seen; the shell they start
 * applies the rule to the command instead, as it carries the preload too.
 *
 * Independent of these variables, Chromium based programs (Chromium,
 * Electron: icudtl.dat and a V8 snapshot beside an executable of at least
 * 32 MiB, which leaves out helpers such as crash reporters) get
 * --no-sandbox: their sandbox (setuid helper, user namespaces) cannot work
 * under PRoot, and they abort instead of running. The droidnative-tools
 * package lists the library in /etc/ld.so.preload for this, so every
 * program in the guest carries it. Flatpak apps have their own /etc: a
 * `flatpak run` of an app that ships such a program gets this library
 * from the guest (host-os, read-only) as LD_PRELOAD for that run.
 */
#define _GNU_SOURCE
#include <dirent.h>
#include <dlfcn.h>
#include <errno.h>
#include <limits.h>
#include <spawn.h>
#include <stdarg.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/stat.h>
#include <unistd.h>

#define SELF "/usr/lib/droidnative/libdn-appenv.so"
#define APP_LIST "/run/droidnative/apps.list"
#define DN_GL "/usr/bin/dn-gl"
#define MAX_ARGS 4096
// Browser binaries are 100 MiB and more; helpers beside them (crashpad,
// opera_crashreporter, autoupdate, sandbox) and wrapper scripts are small
// and reject switches they do not know
#define CHROMIUM_MIN_SIZE (32L << 20)
// Where a Flatpak app finds the guest's /usr with --filesystem=host-os
#define SELF_IN_FLATPAK "/run/host" SELF
#define FLATPAK_SEARCH_DEPTH 3

extern char **environ;

/* Lookup helpers */

static const char *env_get(char *const envp[], const char *name)
{
    size_t n = strlen(name);
    for (char *const *e = envp; e && *e; e++) {
        if (strncmp(*e, name, n) == 0 && (*e)[n] == '=')
            return *e + n + 1;
    }
    return NULL;
}

// Whole word of a space separated list
static int in_list(const char *list, const char *name, size_t len)
{
    if (!list)
        return 0;
    for (const char *p = list; *p;) {
        while (*p == ' ')
            p++;
        const char *end = p;
        while (*end && *end != ' ')
            end++;
        if ((size_t)(end - p) == len && strncmp(p, name, len) == 0)
            return 1;
        p = end;
    }
    return 0;
}

struct listed {
    char backend[32];
    char gdk[32];
    char qt[32];
};

static int lookup_listed(const char *prog, struct listed *out)
{
    FILE *f = fopen(APP_LIST, "re");
    if (!f)
        return 0;
    char line[512];
    int found = 0;
    while (!found && fgets(line, sizeof(line), f)) {
        char *fields[4] = { 0 };
        char *save = NULL;
        int i = 0;
        for (char *t = strtok_r(line, "\t\n", &save); t && i < 4; t = strtok_r(NULL, "\t\n", &save))
            fields[i++] = t;
        if (i == 4 && strcmp(fields[0], prog) == 0) {
            snprintf(out->backend, sizeof(out->backend), "%s", fields[1]);
            snprintf(out->gdk, sizeof(out->gdk), "%s", fields[2]);
            snprintf(out->qt, sizeof(out->qt), "%s", fields[3]);
            found = 1;
        }
    }
    fclose(f);
    return found;
}

static const char *base_name(const char *path)
{
    const char *s = strrchr(path, '/');
    return s ? s + 1 : path;
}

/* Environment rewriting */

struct plan {
    char **envp;        // NULL: unchanged
    char **argv;        // NULL: unchanged, else dn-gl prefixed or --no-sandbox added
    const char *path;   // NULL: unchanged
};

static void plan_free(struct plan *p)
{
    if (p->envp) {
        for (char **e = p->envp; *e; e++)
            free(*e);
        free(p->envp);
    }
    free(p->argv);
}

static int push(char ***arr, size_t *n, size_t *cap, char *s)
{
    if (*n + 2 > *cap) {
        size_t c = *cap ? *cap * 2 : 64;
        char **a = realloc(*arr, c * sizeof(char *));
        if (!a)
            return -1;
        *arr = a;
        *cap = c;
    }
    (*arr)[(*n)++] = s;
    (*arr)[*n] = NULL;
    return 0;
}

// LD_PRELOAD without this library; NULL if nothing remains
static char *preload_without_self(const char *value)
{
    char *out = calloc(1, strlen(value) + 1);
    if (!out)
        return NULL;
    const char *p = value;
    while (*p) {
        while (*p == ' ' || *p == ':')
            p++;
        const char *end = p;
        while (*end && *end != ' ' && *end != ':')
            end++;
        if (end > p && !((size_t)(end - p) == strlen(SELF) && strncmp(p, SELF, end - p) == 0)) {
            if (*out)
                strcat(out, " ");
            strncat(out, p, end - p);
        }
        p = end;
    }
    if (!*out) {
        free(out);
        return NULL;
    }
    return out;
}

static char *make_var(const char *name, const char *value)
{
    size_t n = strlen(name) + strlen(value) + 2;
    char *s = malloc(n);
    if (s)
        snprintf(s, n, "%s=%s", name, value);
    return s;
}

/*
 * Decides what a new program started from this process gets. child: the
 * program runs in another process than the application (fork or spawn).
 */
static void make_plan(struct plan *plan, char *const argv[], char *const envp[], int child)
{
    plan->envp = NULL;
    plan->argv = NULL;
    plan->path = NULL;
    const char *policy = env_get(envp, "DN_APP_CHILDREN");
    if (!policy || !child || !argv || !argv[0])
        return;

    struct listed own;
    int listed = lookup_listed(base_name(argv[0]), &own);
    if (strcmp(policy, "unlisted") == 0 && !listed)
        return;     // keeps the app's settings, and the rule for its children

    const char *app_vars = env_get(envp, "DN_APP_VARS");
    const char *gl_vars = env_get(envp, "DN_GL_VARS");
    char **out = NULL;
    size_t n = 0, cap = 0;
    for (char *const *e = envp; *e; e++) {
        const char *eq = strchr(*e, '=');
        if (!eq)
            continue;
        size_t len = (size_t)(eq - *e);
        if (in_list(app_vars, *e, len) || in_list(gl_vars, *e, len))
            continue;
        if (strncmp(*e, "DN_APP_", 7) == 0 || strncmp(*e, "DN_GL_", 6) == 0)
            continue;
        if (len == 10 && strncmp(*e, "LD_PRELOAD", 10) == 0) {
            char *rest = preload_without_self(eq + 1);
            if (rest) {
                push(&out, &n, &cap, make_var("LD_PRELOAD", rest));
                free(rest);
            }
            continue;
        }
        push(&out, &n, &cap, strdup(*e));
    }
    // Values dn-gl replaced come back
    for (char *const *e = envp; *e; e++) {
        if (strncmp(*e, "DN_GL_ORIG_", 11) == 0) {
            const char *eq = strchr(*e, '=');
            if (!eq)
                continue;
            char name[256];
            snprintf(name, sizeof(name), "%.*s", (int)(eq - *e - 11), *e + 11);
            push(&out, &n, &cap, make_var(name, eq + 1));
        }
    }
    if (listed) {
        if (strcmp(own.gdk, "-") != 0)
            push(&out, &n, &cap, make_var("GDK_DPI_SCALE", own.gdk));
        if (strcmp(own.qt, "-") != 0)
            push(&out, &n, &cap, make_var("QT_SCALE_FACTOR", own.qt));
        if (strcmp(own.backend, "auto") != 0) {
            size_t argc = 0;
            while (argv[argc] && argc < MAX_ARGS)
                argc++;
            char **a = calloc(argc + 3, sizeof(char *));
            if (a) {
                a[0] = DN_GL;
                a[1] = strdup(own.backend);
                memcpy(a + 2, argv, argc * sizeof(char *));
                plan->argv = a;
                plan->path = DN_GL;
            }
        }
    }
    plan->envp = out;
}

/* Chromium sandbox */

// Executable a name resolves to, as execvp would find it
static int find_program(const char *file, char *out, size_t size)
{
    if (strchr(file, '/'))
        return snprintf(out, size, "%s", file) < (int)size;
    const char *path = getenv("PATH");
    if (!path)
        path = "/usr/local/bin:/usr/bin:/bin";
    for (const char *p = path; *p;) {
        const char *end = strchr(p, ':');
        size_t len = end ? (size_t)(end - p) : strlen(p);
        if (len && snprintf(out, size, "%.*s/%s", (int)len, p, file) < (int)size &&
            access(out, X_OK) == 0)
            return 1;
        p += len + (end ? 1 : 0);
    }
    return 0;
}

static int exists_beside(char *dir_end, char *buf, size_t size, const char *name)
{
    size_t used = (size_t)(dir_end - buf);
    if (used + strlen(name) + 1 > size)
        return 0;
    strcpy(dir_end, name);
    return access(buf, F_OK) == 0;
}

// Chromium and Electron ship ICU data and a V8 snapshot beside the binary
static int is_chromium(const char *file)
{
    char found[PATH_MAX], real[PATH_MAX];
    struct stat st;
    if (!find_program(file, found, sizeof(found)) || !realpath(found, real) ||
        stat(real, &st) != 0 || st.st_size < CHROMIUM_MIN_SIZE)
        return 0;
    char *slash = strrchr(real, '/');
    if (!slash)
        return 0;
    char *dir_end = slash + 1;
    return exists_beside(dir_end, real, sizeof(real), "icudtl.dat") &&
           (exists_beside(dir_end, real, sizeof(real), "v8_context_snapshot.bin") ||
            exists_beside(dir_end, real, sizeof(real), "snapshot_blob.bin"));
}

/* Flatpak */

// A Chromium or Electron binary somewhere in a Flatpak app's files
static int dir_has_chromium(char *path, size_t size, int depth)
{
    size_t len = strlen(path);
    DIR *d = opendir(path);
    if (!d)
        return 0;
    int found = 0;
    struct dirent *e;
    // Beside icudtl.dat: check the big executables of that directory
    while (!found && (e = readdir(d))) {
        if (strcmp(e->d_name, "icudtl.dat") != 0)
            continue;
        DIR *again = opendir(path);
        struct dirent *f;
        while (again && !found && (f = readdir(again))) {
            if (f->d_name[0] == '.' || len + strlen(f->d_name) + 2 > size)
                continue;
            snprintf(path + len, size - len, "/%s", f->d_name);
            struct stat st;
            found = stat(path, &st) == 0 && S_ISREG(st.st_mode) && (st.st_mode & 0111) &&
                    is_chromium(path);
            path[len] = '\0';
        }
        if (again)
            closedir(again);
    }
    rewinddir(d);
    while (!found && depth > 0 && (e = readdir(d))) {
        // Data trees are large and hold no browsers
        if (e->d_name[0] == '.' || strcmp(e->d_name, "share") == 0 ||
            strcmp(e->d_name, "include") == 0 || strcmp(e->d_name, "debug") == 0 ||
            len + strlen(e->d_name) + 2 > size)
            continue;
        if (e->d_type != DT_DIR && e->d_type != DT_UNKNOWN)
            continue;
        snprintf(path + len, size - len, "/%s", e->d_name);
        found = dir_has_chromium(path, size, depth - 1);
        path[len] = '\0';
    }
    closedir(d);
    return found;
}

// Installed files of Flatpak app [id], user installation first
static int flatpak_app_has_chromium(const char *id)
{
    if (!id[0] || strchr(id, '/') || id[0] == '.')
        return 0;
    char path[PATH_MAX];
    const char *home = getenv("HOME");
    if (home && snprintf(path, sizeof(path), "%s/.local/share/flatpak/app/%s/current/active/files",
                         home, id) < (int)sizeof(path) &&
        access(path, F_OK) == 0)
        return dir_has_chromium(path, sizeof(path), FLATPAK_SEARCH_DEPTH);
    if (snprintf(path, sizeof(path), "/var/lib/flatpak/app/%s/current/active/files", id) < (int)sizeof(path) &&
        access(path, F_OK) == 0)
        return dir_has_chromium(path, sizeof(path), FLATPAK_SEARCH_DEPTH);
    return 0;
}

// `flatpak run [options] APP`: lets a Chromium based app load this library
static void add_flatpak_preload(struct plan *plan, const char *file, char *const argv[])
{
    if (plan->argv || !file || !argv || !argv[0] || !argv[1] ||
        strcmp(base_name(file), "flatpak") != 0 || strcmp(argv[1], "run") != 0)
        return;
    size_t argc = 0;
    const char *app = NULL;
    for (; argv[argc] && argc < MAX_ARGS; argc++) {
        if (argc >= 2 && !app && argv[argc][0] != '-')
            app = argv[argc];
        if (strncmp(argv[argc], "--env=LD_PRELOAD=", 17) == 0)
            return;     // the caller's own choice
    }
    if (!app || !flatpak_app_has_chromium(app))
        return;
    char **a = calloc(argc + 3, sizeof(char *));
    if (!a)
        return;
    a[0] = argv[0];
    a[1] = argv[1];
    a[2] = "--filesystem=host-os:ro";
    a[3] = "--env=LD_PRELOAD=" SELF_IN_FLATPAK;
    memcpy(a + 4, argv + 2, (argc - 2) * sizeof(char *));
    plan->argv = a;
}

// Adds --no-sandbox for a Chromium browser process; its own children
// (--type=renderer, ...) get the switch from it
static void add_no_sandbox(struct plan *plan, const char *file, char *const argv[])
{
    if (plan->argv || !file || !argv || !argv[0])
        return;
    size_t argc = 0;
    for (; argv[argc] && argc < MAX_ARGS; argc++) {
        if (strcmp(argv[argc], "--no-sandbox") == 0 || strncmp(argv[argc], "--type=", 7) == 0)
            return;
    }
    if (!is_chromium(file))
        return;
    char **a = calloc(argc + 2, sizeof(char *));
    if (!a)
        return;
    a[0] = argv[0];
    a[1] = "--no-sandbox";
    memcpy(a + 2, argv + 1, (argc - 1) * sizeof(char *));
    plan->argv = a;
}

// Shells keep exported variables in their own tables, not in environ:
// the environment handed to exec is the reliable source
static int is_child(char *const envp[])
{
    const char *p = env_get(envp, "DN_APP_PID");
    if (!p)
        p = getenv("DN_APP_PID");
    return !p || atoi(p) != getpid();
}

/* Interposed functions */

typedef int (*execve_fn)(const char *, char *const[], char *const[]);
typedef int (*execvpe_fn)(const char *, char *const[], char *const[]);
typedef int (*spawn_fn)(pid_t *, const char *, const posix_spawn_file_actions_t *,
                        const posix_spawnattr_t *, char *const[], char *const[]);

int execve(const char *path, char *const argv[], char *const envp[])
{
    static execve_fn real;
    if (!real)
        real = (execve_fn)dlsym(RTLD_NEXT, "execve");
    struct plan p;
    make_plan(&p, argv, envp, is_child(envp));
    add_no_sandbox(&p, path, argv);
    add_flatpak_preload(&p, path, argv);
    int r = real(p.path ? p.path : path, p.argv ? p.argv : argv, p.envp ? p.envp : envp);
    int err = errno;
    plan_free(&p);
    errno = err;
    return r;
}

int execv(const char *path, char *const argv[])
{
    return execve(path, argv, environ);
}

int execvpe(const char *file, char *const argv[], char *const envp[])
{
    static execvpe_fn real;
    if (!real)
        real = (execvpe_fn)dlsym(RTLD_NEXT, "execvpe");
    struct plan p;
    make_plan(&p, argv, envp, is_child(envp));
    add_no_sandbox(&p, file, argv);
    add_flatpak_preload(&p, file, argv);
    int r = real(p.path ? p.path : file, p.argv ? p.argv : argv, p.envp ? p.envp : envp);
    int err = errno;
    plan_free(&p);
    errno = err;
    return r;
}

int execvp(const char *file, char *const argv[])
{
    return execvpe(file, argv, environ);
}

// execl family: collect the variadic arguments, then the vector forms
#define COLLECT_ARGS(first, ap, argv, extra)                         \
    do {                                                             \
        size_t i_ = 0;                                               \
        (argv)[i_++] = (char *)(first);                              \
        while (i_ < MAX_ARGS - 1 && ((argv)[i_] = va_arg(ap, char *)) != NULL) \
            i_++;                                                    \
        (argv)[i_] = NULL;                                           \
        extra;                                                       \
    } while (0)

int execl(const char *path, const char *arg, ...)
{
    char *argv[MAX_ARGS];
    va_list ap;
    va_start(ap, arg);
    COLLECT_ARGS(arg, ap, argv, (void)0);
    va_end(ap);
    return execve(path, argv, environ);
}

int execlp(const char *file, const char *arg, ...)
{
    char *argv[MAX_ARGS];
    va_list ap;
    va_start(ap, arg);
    COLLECT_ARGS(arg, ap, argv, (void)0);
    va_end(ap);
    return execvpe(file, argv, environ);
}

int execle(const char *path, const char *arg, ...)
{
    char *argv[MAX_ARGS];
    char **envp;
    va_list ap;
    va_start(ap, arg);
    COLLECT_ARGS(arg, ap, argv, envp = va_arg(ap, char **));
    va_end(ap);
    return execve(path, argv, envp);
}

static int spawn(const char *name, pid_t *pid, const char *path,
                 const posix_spawn_file_actions_t *fa, const posix_spawnattr_t *attr,
                 char *const argv[], char *const envp[])
{
    spawn_fn real = (spawn_fn)dlsym(RTLD_NEXT, name);
    struct plan p;
    make_plan(&p, argv, envp ? envp : environ, 1);
    add_no_sandbox(&p, path, argv);
    add_flatpak_preload(&p, path, argv);
    int r = real(pid, p.path ? p.path : path, fa, attr, p.argv ? p.argv : argv,
                 p.envp ? p.envp : envp);
    plan_free(&p);
    return r;
}

int posix_spawn(pid_t *pid, const char *path, const posix_spawn_file_actions_t *fa,
                const posix_spawnattr_t *attr, char *const argv[], char *const envp[])
{
    return spawn("posix_spawn", pid, path, fa, attr, argv, envp);
}

int posix_spawnp(pid_t *pid, const char *file, const posix_spawn_file_actions_t *fa,
                 const posix_spawnattr_t *attr, char *const argv[], char *const envp[])
{
    return spawn("posix_spawnp", pid, file, fa, attr, argv, envp);
}
