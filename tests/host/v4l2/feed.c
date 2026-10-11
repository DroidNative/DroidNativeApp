/*
 * Stand-in for the Android side of the camera passthrough (CameraBridge.kt):
 * serves one fake camera in <dir> for PRoot's dn_v4l2 extension and feeds
 * test frames while a guest streams. Frame n is filled with the byte n & 0xff.
 *
 * Usage: feed <dir> [seconds]     (creates <dir>/video0 and video0.sock)
 */
#define _GNU_SOURCE
#include <errno.h>
#include <fcntl.h>
#include <poll.h>
#include <signal.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>
#include <unistd.h>
#include <sys/mman.h>
#include <sys/socket.h>
#include <sys/stat.h>
#include <sys/un.h>

#include "dn_v4l2_proto.h"

#define N_SLOTS 4
#define SLOT_SIZE (1280 * 720 * 2)
#define SLOT_OFFSET 4096

static uint64_t now_us(void)
{
    struct timespec ts;
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return (uint64_t)ts.tv_sec * 1000000 + ts.tv_nsec / 1000;
}

int main(int argc, char **argv)
{
    if (argc < 2) {
        fprintf(stderr, "usage: %s <dir> [seconds]\n", argv[0]);
        return 2;
    }
    int seconds = argc > 2 ? atoi(argv[2]) : 60;
    char fifo[512], sock_path[512];
    snprintf(fifo, sizeof(fifo), "%s/video0", argv[1]);
    snprintf(sock_path, sizeof(sock_path), "%s/video0.sock", argv[1]);
    signal(SIGPIPE, SIG_IGN);

    /* Frame memory */
    size_t shm_size = SLOT_OFFSET + (size_t)N_SLOTS * SLOT_SIZE;
    int shm_fd = memfd_create("dn-v4l2", MFD_CLOEXEC);
    if (shm_fd < 0 || ftruncate(shm_fd, shm_size) < 0) {
        perror("memfd");
        return 1;
    }
    uint8_t *shm = mmap(NULL, shm_size, PROT_READ | PROT_WRITE, MAP_SHARED, shm_fd, 0);
    struct dn_v4l2_shm *header = (struct dn_v4l2_shm *)shm;

    /* Token FIFO: keep a write end only, so readers never block in open
     * and POLLERR tells when the last one is gone */
    unlink(fifo);
    if (mkfifo(fifo, 0660) < 0) {
        perror("mkfifo");
        return 1;
    }
    int tmp = open(fifo, O_RDWR | O_NONBLOCK);
    int token_fd = open(fifo, O_WRONLY | O_NONBLOCK);
    close(tmp);

    /* Control socket */
    struct sockaddr_un addr = { .sun_family = AF_UNIX };
    strncpy(addr.sun_path, sock_path, sizeof(addr.sun_path) - 1);
    unlink(sock_path);
    int lsock = socket(AF_UNIX, SOCK_STREAM, 0);
    if (bind(lsock, (struct sockaddr *)&addr, sizeof(addr)) < 0 || listen(lsock, 4) < 0) {
        perror("bind");
        return 1;
    }

    struct dn_v4l2_hello hello = {
        .magic = DN_V4L2_HELLO_MAGIC, .version = 1, .shm_size = shm_size,
        .n_slots = N_SLOTS, .slot_size = SLOT_SIZE, .slot_offset = SLOT_OFFSET,
        .n_sizes = 3, .sizes = { { 1280, 720 }, { 640, 480 }, { 320, 240 } },
        .name = "Test camera",
    };

    int client = -1, streaming = 0;
    uint32_t produced = 0, frame_bytes = 0;
    uint64_t deadline = now_us() + (uint64_t)seconds * 1000000;
    printf("feed: ready\n");
    fflush(stdout);

    while (now_us() < deadline) {
        struct pollfd fds[2] = { { lsock, POLLIN, 0 }, { client, POLLIN, 0 } };
        poll(fds, 2, streaming ? 33 : 200);

        if (fds[0].revents & POLLIN) {
            if (client >= 0)
                close(client);
            client = accept(lsock, NULL, NULL);
            char control[CMSG_SPACE(sizeof(int))] = { 0 };
            struct iovec iov = { &hello, sizeof(hello) };
            struct msghdr msg = { .msg_iov = &iov, .msg_iovlen = 1,
                                  .msg_control = control, .msg_controllen = sizeof(control) };
            struct cmsghdr *cmsg = CMSG_FIRSTHDR(&msg);
            cmsg->cmsg_level = SOL_SOCKET;
            cmsg->cmsg_type = SCM_RIGHTS;
            cmsg->cmsg_len = CMSG_LEN(sizeof(int));
            memcpy(CMSG_DATA(cmsg), &shm_fd, sizeof(int));
            sendmsg(client, &msg, 0);
            streaming = 0;
            printf("feed: client connected\n");
        }
        if (client >= 0 && (fds[1].revents & (POLLIN | POLLHUP))) {
            struct dn_v4l2_cmd cmd;
            ssize_t n = recv(client, &cmd, sizeof(cmd), 0);
            if (n != sizeof(cmd)) {
                close(client);
                client = -1;
                streaming = 0;
                printf("feed: client gone\n");
            } else if (cmd.cmd == DN_V4L2_CMD_START) {
                streaming = 1;
                produced = 0;
                frame_bytes = cmd.pixelformat == 0x56595559 /* YUYV */
                    ? cmd.width * cmd.height * 2 : cmd.width * cmd.height * 3 / 2;
                printf("feed: start %ux%u %.4s\n", cmd.width, cmd.height, (char *)&cmd.pixelformat);
            } else {
                streaming = 0;
                printf("feed: stop after %u frames\n", produced);
            }
            fflush(stdout);
        }
        if (!streaming)
            continue;

        /* Reader closed the device without STREAMOFF */
        struct pollfd w = { token_fd, 0, 0 };
        poll(&w, 1, 0);
        if (w.revents & POLLERR) {
            streaming = 0;
            printf("feed: no reader left, stop after %u frames\n", produced);
            fflush(stdout);
            continue;
        }
        /* At most two frames wait for the guest; a third would overwrite
         * a slot it may still be copying */
        if (produced - header->consumed >= 2)
            continue;
        uint32_t slot = produced % N_SLOTS;
        memset(shm + SLOT_OFFSET + (size_t)slot * SLOT_SIZE, produced & 0xff, frame_bytes);
        struct dn_v4l2_token token = { DN_V4L2_TOKEN_MAGIC, slot, frame_bytes, produced, now_us() };
        if (write(token_fd, &token, sizeof(token)) == sizeof(token))
            produced++;
    }
    unlink(fifo);
    unlink(sock_path);
    return 0;
}
