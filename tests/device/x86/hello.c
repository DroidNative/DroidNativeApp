/* x86 test program for the box64/box86 check (tests/device/x86/build.sh) */
#include <stdio.h>
#include <sys/utsname.h>

int main(int argc, char **argv)
{
    struct utsname u;

    uname(&u);
    printf("hello from x86 (%d-bit) machine=%s args=%d\n", (int) (sizeof(void *) * 8), u.machine, argc - 1);
    return 0;
}
