/* Map Termux's hard-coded prefix onto this app's own prefix.
 *
 * Termux's binaries are relocatable for libraries (DT_RUNPATH=$ORIGIN) but not for data paths:
 * dpkg has "/data/data/com.termux/files/usr/etc/dpkg/dpkg.cfg.d" compiled in and aborts on
 * startup -- even for --help -- with EACCES, because that directory belongs to another app.
 * No dpkg option changes it, and the string cannot be patched in place because the two prefixes
 * differ in length.
 *
 * So the rewrite happens below them: every path-taking libc entry point that mentions the Termux
 * prefix is redirected to this app's prefix before the kernel sees it. With this preloaded, dpkg
 * runs normally, which is what lets apt-get install work end to end.
 *
 * Set TERMUX_PATH_PREFIX to the replacement prefix; without it the shim is inert.
 */
#define _GNU_SOURCE
#include <dlfcn.h>
#include <limits.h>
#include <stdarg.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/stat.h>
#include <dirent.h>
#include <fcntl.h>
#include <unistd.h>

#define TERMUX_PREFIX "/data/data/com.termux/files/usr"
#define TERMUX_PREFIX_LEN (sizeof (TERMUX_PREFIX) - 1)

static char g_prefix[PATH_MAX];
static int g_ready;

static void
init_prefix (void)
{
  if (g_ready) return;
  g_ready = 1;
  const char *p = getenv ("TERMUX_PATH_PREFIX");
  if (p && p[0]) snprintf (g_prefix, sizeof g_prefix, "%s", p);
}

/* Rewrite into buf when path is under the Termux prefix; otherwise return path unchanged. */
static const char *
rewrite (const char *path, char *buf, size_t bufsz)
{
  init_prefix ();
  if (!g_prefix[0] || !path) return path;
  if (strncmp (path, TERMUX_PREFIX, TERMUX_PREFIX_LEN) != 0) return path;
  /* Only rewrite at a path boundary: /data/data/com.termux/files/usr/bin, not .../usrx. */
  if (path[TERMUX_PREFIX_LEN] != '/' && path[TERMUX_PREFIX_LEN] != '\0') return path;
  int n = snprintf (buf, bufsz, "%s%s", g_prefix, path + TERMUX_PREFIX_LEN);
  if (n < 0 || (size_t) n >= bufsz) return path;
  return buf;
}

#define REAL(name) \
  static __typeof__ (name) *real_##name; \
  if (!real_##name) real_##name = (__typeof__ (name) *) dlsym (RTLD_NEXT, #name)

int open (const char *path, int flags, ...)
{
  char buf[PATH_MAX];
  const char *p = rewrite (path, buf, sizeof buf);
  REAL (open);
  if (flags & O_CREAT) {
    va_list ap; va_start (ap, flags);
    mode_t mode = va_arg (ap, mode_t);
    va_end (ap);
    return real_open (p, flags, mode);
  }
  return real_open (p, flags);
}

int openat (int dirfd, const char *path, int flags, ...)
{
  char buf[PATH_MAX];
  const char *p = rewrite (path, buf, sizeof buf);
  REAL (openat);
  if (flags & O_CREAT) {
    va_list ap; va_start (ap, flags);
    mode_t mode = va_arg (ap, mode_t);
    va_end (ap);
    return real_openat (dirfd, p, flags, mode);
  }
  return real_openat (dirfd, p, flags);
}

FILE *fopen (const char *path, const char *mode)
{
  char buf[PATH_MAX];
  REAL (fopen);
  return real_fopen (rewrite (path, buf, sizeof buf), mode);
}

DIR *opendir (const char *path)
{
  char buf[PATH_MAX];
  REAL (opendir);
  return real_opendir (rewrite (path, buf, sizeof buf));
}

int stat (const char *path, struct stat *st)
{
  char buf[PATH_MAX];
  REAL (stat);
  return real_stat (rewrite (path, buf, sizeof buf), st);
}

int lstat (const char *path, struct stat *st)
{
  char buf[PATH_MAX];
  REAL (lstat);
  return real_lstat (rewrite (path, buf, sizeof buf), st);
}

int access (const char *path, int mode)
{
  char buf[PATH_MAX];
  REAL (access);
  return real_access (rewrite (path, buf, sizeof buf), mode);
}

int mkdir (const char *path, mode_t mode)
{
  char buf[PATH_MAX];
  REAL (mkdir);
  return real_mkdir (rewrite (path, buf, sizeof buf), mode);
}

int unlink (const char *path)
{
  char buf[PATH_MAX];
  REAL (unlink);
  return real_unlink (rewrite (path, buf, sizeof buf));
}

int rename (const char *oldp, const char *newp)
{
  char b1[PATH_MAX], b2[PATH_MAX];
  REAL (rename);
  return real_rename (rewrite (oldp, b1, sizeof b1), rewrite (newp, b2, sizeof b2));
}

int symlink (const char *target, const char *linkpath)
{
  char b1[PATH_MAX], b2[PATH_MAX];
  REAL (symlink);
  return real_symlink (rewrite (target, b1, sizeof b1), rewrite (linkpath, b2, sizeof b2));
}

ssize_t readlink (const char *path, char *buf, size_t bufsz)
{
  char b1[PATH_MAX];
  REAL (readlink);
  return real_readlink (rewrite (path, b1, sizeof b1), buf, bufsz);
}

/* dpkg scans its configuration directory with scandir, and libc's scandir calls its own
 * internal __opendir rather than going through the PLT, so interposing opendir alone does not
 * reach it. The same goes for the *at family and statx. These wrappers cover the rest. */
int
scandir (const char *path, struct dirent ***namelist,
         int (*filter) (const struct dirent *),
         int (*compare) (const struct dirent **, const struct dirent **))
{
  char buf[PATH_MAX];
  REAL (scandir);
  return real_scandir (rewrite (path, buf, sizeof buf), namelist, filter, compare);
}

int
fstatat (int dirfd, const char *path, struct stat *st, int flags)
{
  char buf[PATH_MAX];
  REAL (fstatat);
  return real_fstatat (dirfd, rewrite (path, buf, sizeof buf), st, flags);
}

int
faccessat (int dirfd, const char *path, int mode, int flags)
{
  char buf[PATH_MAX];
  REAL (faccessat);
  return real_faccessat (dirfd, rewrite (path, buf, sizeof buf), mode, flags);
}

int
__open_2 (const char *path, int flags)
{
  char buf[PATH_MAX];
  static int (*real) (const char *, int);
  if (!real) real = (int (*) (const char *, int)) dlsym (RTLD_NEXT, "__open_2");
  if (!real) return -1;
  return real (rewrite (path, buf, sizeof buf), flags);
}
