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
 */
#define _GNU_SOURCE
#include <dlfcn.h>
#include <errno.h>
#include <limits.h>
#include <spawn.h>
#include <stdarg.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>

#define SELF "/usr/lib/droidnative/libdn-appenv.so"
#define APP_LIST "/run/droidnative/apps.list"
#define DN_GL "/usr/bin/dn-gl"
#define MAX_ARGS 4096

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
    char **argv;        // NULL: unchanged, else dn-gl prefixed
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
            }
        }
    }
    plan->envp = out;
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
    int r = real(p.argv ? DN_GL : path, p.argv ? p.argv : argv, p.envp ? p.envp : envp);
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
    int r = real(p.argv ? DN_GL : file, p.argv ? p.argv : argv, p.envp ? p.envp : envp);
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
    int r = real(pid, p.argv ? DN_GL : path, fa, attr, p.argv ? p.argv : argv,
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
