/*
 * jdspswitch - write the JamesDSP global on/off parameter into the engine's
 * state file.
 *
 * The framework re-sends EFFECT_CMD_ENABLE for every new output session, so the
 * off switch cannot be expressed with AudioEffect.setEnabled().  Instead the
 * value travels the normal parameter path (SET_PARAM cmd 400) and is persisted
 * here, exactly like every other setting; the engine replays it on the next
 * session (and immediately, because it watches this file).
 *
 * usage: jdspswitch <on|off> [state-file]
 */
#define _GNU_SOURCE
#include <errno.h>
#include <fcntl.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/stat.h>
#include <unistd.h>

#define DEFAULT_PATH "/data/vendor/audio/jamesdsp_state.bin"
#define HDR 16u
#define TRL 4u
#define CMD_SET_PARAM 5u
#define MASTER_CMD 400

static unsigned get_u32(const unsigned char *p)
{
    return (unsigned)p[0] | ((unsigned)p[1] << 8) | ((unsigned)p[2] << 16) | ((unsigned)p[3] << 24);
}

static void put_u32(unsigned char *p, unsigned v)
{
    p[0] = (unsigned char)(v & 0xFF);
    p[1] = (unsigned char)((v >> 8) & 0xFF);
    p[2] = (unsigned char)((v >> 16) & 0xFF);
    p[3] = (unsigned char)((v >> 24) & 0xFF);
}

static unsigned crc32_of(const unsigned char *buf, size_t len)
{
    unsigned c = 0xFFFFFFFFu;
    size_t i;
    int k;
    for (i = 0; i < len; i++) {
        c ^= buf[i];
        for (k = 0; k < 8; k++)
            c = (c & 1u) ? (0xEDB88320u ^ (c >> 1)) : (c >> 1);
    }
    return c ^ 0xFFFFFFFFu;
}

int main(int argc, char **argv)
{
    const char *path = DEFAULT_PATH;
    unsigned char *in = NULL, *out = NULL;
    size_t in_len = 0, cap, off, out_off;
    unsigned count = 0, kept = 0, i;
    int on, fd;
    struct stat st;

    if (argc < 2) {
        fprintf(stderr, "usage: %s <on|off> [state-file]\n", argv[0]);
        return 2;
    }
    on = strcmp(argv[1], "off") != 0;
    if (argc > 2)
        path = argv[2];

    fd = open(path, O_RDONLY | O_CLOEXEC);
    if (fd >= 0) {
        if (fstat(fd, &st) == 0 && st.st_size > 0) {
            in = malloc((size_t)st.st_size);
            if (in != NULL) {
                ssize_t n = read(fd, in, (size_t)st.st_size);
                if (n > 0)
                    in_len = (size_t)n;
            }
        }
        close(fd);
    }

    cap = in_len + 64;
    out = malloc(cap);

    memcpy(out, "JDSP", 4);
    put_u32(out + 4, 1u);   /* version */
    put_u32(out + 8, 0u);   /* flags   */
    out_off = HDR;

    /* copy every record except an older master-switch record */
    if (in != NULL && in_len >= HDR + TRL && memcmp(in, "JDSP", 4) == 0) {
        count = get_u32(in + 12);
        off = HDR;
        for (i = 0; i < count; i++) {
            unsigned cmd, size;
            if (off + 8 > in_len)
                break;
            cmd = get_u32(in + off);
            size = get_u32(in + off + 4);
            if (off + 8 + size > in_len)
                break;
            if (cmd == CMD_SET_PARAM && size >= 16u && get_u32(in + off + 8 + 12) == MASTER_CMD) {
                off += 8 + size;
                continue;
            }
            memcpy(out + out_off, in + off, 8 + size);
            out_off += 8 + size;
            kept++;
            off += 8 + size;
        }
    }

    /* append the new master-switch record: status, psize, vsize, cmd, value */
    put_u32(out + out_off, CMD_SET_PARAM);
    put_u32(out + out_off + 4, 18u);
    {
        unsigned char *r = out + out_off + 8;
        put_u32(r, 0u);                       /* status */
        put_u32(r + 4, 4u);                   /* psize  */
        put_u32(r + 8, 2u);                   /* vsize  */
        put_u32(r + 12, MASTER_CMD);          /* cmd    */
        r[16] = (unsigned char)(on ? 1 : 0);
        r[17] = 0;
    }
    out_off += 8 + 18;
    kept++;

    put_u32(out + 12, kept);
    put_u32(out + out_off, crc32_of(out, out_off));
    out_off += TRL;

    {
        char tmp[512];
        snprintf(tmp, sizeof(tmp), "%s.tmp.%d", path, (int)getpid());
        fd = open(tmp, O_WRONLY | O_CREAT | O_TRUNC | O_CLOEXEC, 0660);
        if (fd < 0) {
            fprintf(stderr, "cannot create %s: %s\n", tmp, strerror(errno));
            return 1;
        }
        if (write(fd, out, out_off) != (ssize_t)out_off) {
            fprintf(stderr, "short write: %s\n", strerror(errno));
            close(fd);
            unlink(tmp);
            return 1;
        }
        close(fd);
        chown(tmp, 1041, 1005);   /* audioserver:audio, the engine's uid/gid */
        chmod(tmp, 0660);
        if (rename(tmp, path) != 0) {
            fprintf(stderr, "rename failed: %s\n", strerror(errno));
            unlink(tmp);
            return 1;
        }
    }

    printf("master=%d records=%u bytes=%zu %s\n", on, kept, out_off, path);
    free(in);
    free(out);
    return 0;
}
