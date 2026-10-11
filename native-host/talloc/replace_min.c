/* The one libreplace symbol talloc.c needs on bionic */
#include <string.h>

void *rep_memset_explicit(void *block, int c, size_t size)
{
    void *ptr = memset(block, c, size);
    __asm__ volatile("" : : "r"(block) : "memory"); // keep the store
    return ptr;
}
