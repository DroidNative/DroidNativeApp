/* Prints the low part of the address space (tests/device/x86/build.sh):
 * non-PIE x86 programs need their fixed load address free */
#include <stdio.h>
#include <stdlib.h>

int main(void)
{
    char line[512];
    FILE *f = fopen("/proc/self/maps", "r");

    while (f && fgets(line, sizeof(line), f))
        if (strtoull(line, NULL, 16) < 0x100000000ull)
            fputs(line, stdout);
    printf("main at %p\n", (void *) main);
    return 0;
}
